package jp.yuta.woodangle;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 木材角度判別：カメラで木材の傾きを測り、PLCの撮影要求に応えて結果を返す。
 * 通信はSLMPモニタと同じく、スマホが「SLMP対応機器」として待ち受け、
 * R04CPUのシンプルCPU通信で要求の書込み・結果の読出しをしてもらう。
 */
public class MainActivity extends Activity implements CameraSource.Callback, Judge.Listener {

    private static final int COLOR_OK = 0xFF2E7D32;
    private static final int COLOR_NG = 0xFFC62828;
    private static final int COLOR_GRAY = 0xFF757575;
    private static final int COLOR_WARN = 0xFFE65100;
    private static final int REQ_CAMERA = 1, REQ_SETTINGS = 2;
    private static final ArrayDeque<String> history = new ArrayDeque<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private CameraSource camera;
    private volatile AngleAnalyzer.Params params;
    private volatile AngleAnalyzer.Result lastLive;
    private volatile long lastShown;
    private boolean torchOn;
    private String cameraError;
    private boolean askedPermission, resumed;
    private final Runnable retryCamera = this::openCamera;

    private FrameView frameView;
    private TextView angleText, resultText, liveText, statusText, ipText;
    private Button startBtn, roiBtn, torchBtn;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            main.postDelayed(this, 300);
        }
    };

    // ------------------------------------------------------------ 画面

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);

        frameView = new FrameView(this);
        frameView.setRoiListener((l, t, r, b) -> {
            prefs.saveRoi(l, t, r, b);
            applySettings();
            frameView.setRoiEdit(false);
            roiBtn.setText("範囲設定");
            Toast.makeText(this, "判別範囲を保存しました", Toast.LENGTH_SHORT).show();
        });
        root.addView(frameView, new LinearLayout.LayoutParams(0, -1, 3f));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(10), dp(6), dp(10), dp(6));

        angleText = new TextView(this);
        angleText.setTextSize(40);
        angleText.setTypeface(Typeface.DEFAULT_BOLD);
        angleText.setGravity(Gravity.CENTER_HORIZONTAL);
        angleText.setText("--.-°");
        side.addView(angleText);

        resultText = new TextView(this);
        resultText.setTextSize(14);
        resultText.setGravity(Gravity.CENTER_HORIZONTAL);
        resultText.setText("PLCからの撮影要求を待っています");
        side.addView(resultText);

        liveText = new TextView(this);
        liveText.setTextSize(13);
        liveText.setTextColor(COLOR_GRAY);
        liveText.setPadding(0, dp(6), 0, dp(4));
        side.addView(liveText);

        LinearLayout r1 = row();
        startBtn = btn(r1, "待受開始", v -> {
            if (Core.isRunning()) stopServer();
            else startServer();
        });
        btn(r1, "テスト判別", v -> {
            if (!Core.judge.startManual(SystemClock.elapsedRealtime())) {
                Toast.makeText(this, "PLCの要求を処理中です", Toast.LENGTH_SHORT).show();
            }
        });
        side.addView(r1);

        LinearLayout r2 = row();
        roiBtn = btn(r2, "範囲設定", v -> {
            boolean on = !frameView.isRoiEdit();
            frameView.setRoiEdit(on);
            roiBtn.setText(on ? "範囲設定をやめる" : "範囲設定");
        });
        btn(r2, "基準にする", v -> setZero());
        side.addView(r2);

        LinearLayout r3 = row();
        torchBtn = btn(r3, "ライト", v -> {
            torchOn = !torchOn;
            prefs.saveTorch(torchOn);
            if (camera != null) camera.setTorch(torchOn);
            updateTorchBtn();
        });
        btn(r3, "⚙ 設定", v -> startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS));
        btn(r3, "ログ", v -> showLog());
        side.addView(r3);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(0, dp(6), 0, dp(2));
        side.addView(statusText);

        ipText = new TextView(this);
        ipText.setTextSize(12);
        ipText.setTextColor(COLOR_GRAY);
        side.addView(ipText);

        ScrollView sv = new ScrollView(this);
        sv.addView(side);
        root.addView(sv, new LinearLayout.LayoutParams(0, -1, 2f));
        setContentView(root);

        torchOn = prefs.torch();
        updateTorchBtn();
        applySettings();
        Core.judge.setListener(this);
        Judge.Outcome last = Core.judge.lastOutcome();
        if (last != null) showOutcome(last);
        if (savedInstanceState == null && prefs.autoStart() && !Core.isRunning()) startServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(refresh);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else if (!askedPermission) {
            askedPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(refresh);
        main.removeCallbacks(retryCamera);
        if (camera != null) camera.close();
        camera = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Core.judge.setListener(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            cameraError = "カメラの使用が許可されていません（スマホの設定→アプリ→権限）";
            frameView.setNote(cameraError);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS && resultCode == RESULT_OK) {
            applySettings();
            final int port = prefs.port();
            final String ip = prefs.plcIp();
            new Thread(() -> {
                try {
                    Core.restartIfChanged(port, ip);
                } catch (Exception e) {
                    main.post(() -> Toast.makeText(this, "待受を開き直せません: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "slmp-restart").start();
        }
    }

    private void openCamera() {
        if (camera != null || !resumed) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cameraError = null;
        int rot;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: rot = 90; break;
            case Surface.ROTATION_180: rot = 180; break;
            case Surface.ROTATION_270: rot = 270; break;
            default: rot = 0;
        }
        camera = new CameraSource(this, rot, this);
        camera.open(torchOn);
    }

    private void applySettings() {
        params = prefs.analyzerParams();
        Core.judge.setConfig(prefs.judgeConfig());
        frameView.setOffset(prefs.offset());
        ipText.setText("このスマホのIP: " + NetUtil.phoneIps()
                + "\n要求 " + prefs.requestDevice() + "（PLC→スマホ）"
                + "\n結果 " + prefs.resultDevice() + "〜" + lastDevice(prefs.resultDevice()) + "（スマホ→PLC）"
                + "\n" + modeName(prefs.mode()) + "・平均" + prefs.average() + "枚");
    }

    private static String modeName(int m) {
        switch (m) {
            case AngleAnalyzer.MODE_SLAB: return "形と向き";
            case AngleAnalyzer.MODE_EDGE: return "角度（エッジ）";
            default: return "角度（輪郭）";
        }
    }

    private boolean slabMode() {
        return prefs.mode() == AngleAnalyzer.MODE_SLAB;
    }

    private static String lastDevice(String head) {
        try {
            Device d = Device.parse(head);
            return Device.name(d.code, d.number + Judge.WORDS - 1);
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------ カメラ画像（カメラのスレッド）

    @Override
    public void onFrame(byte[] gray, int w, int h, long atMs) {
        AngleAnalyzer.Params p = params;
        if (p == null) return;
        AngleAnalyzer.Result r = AngleAnalyzer.analyze(gray, w, h, p);
        lastLive = r;
        Core.judge.offerFrame(r, atMs, SystemClock.elapsedRealtime());
        long now = SystemClock.elapsedRealtime();
        if (now - lastShown < 80) return;
        lastShown = now;
        main.post(() -> {
            frameView.setFrame(gray, w, h, r);
            showLive(r);
        });
    }

    @Override
    public void onCameraError(String message) {
        main.post(() -> {
            cameraError = message;
            frameView.setNote(message);
            addHistory("カメラ: " + message + "（3秒後に開き直します）");
            if (camera != null) camera.close();
            camera = null;
            main.removeCallbacks(retryCamera);
            if (resumed) main.postDelayed(retryCamera, 3000);
        });
    }

    private void showLive(AngleAnalyzer.Result r) {
        if (r.ok) {
            double a = AngleAnalyzer.normalize(r.angleDeg - prefs.offset());
            String dir = r.shape == AngleAnalyzer.SHAPE_UNKNOWN ? ""
                    : AngleAnalyzer.describe(r.shape, r.direction == AngleAnalyzer.DIR_NONE ? AngleAnalyzer.DIR_NONE
                    : AngleAnalyzer.directionOf(r.flatNormalDeg - prefs.offset())) + "  ";
            liveText.setText(String.format(Locale.ROOT, "ライブ %s%+.1f°  信頼度 %.0f%s", dir, a, r.confidence,
                    r.message.isEmpty() ? "" : "\n" + r.message));
        } else {
            liveText.setText("ライブ 判別できません：" + r.message);
        }
    }

    // ------------------------------------------------------------ 判別結果

    @Override
    public void onOutcome(Judge.Outcome o) {
        main.post(() -> {
            showOutcome(o);
            addHistory(String.format(Locale.ROOT, "%s %s %+.1f° 信頼度%.0f %dms %s",
                    o.manual ? "テスト" : "要求" + o.requestNo, o.codeText(), o.angleDeg, o.confidence, o.elapsedMs, o.message));
        });
    }

    private void showOutcome(Judge.Outcome o) {
        boolean ok = o.code == Judge.RES_OK;
        if (slabMode()) {
            boolean down = ok && o.direction == AngleAnalyzer.DIR_DOWN;
            angleText.setText(!ok ? "NG" : (down ? "OK" : "向き違い"));
            angleText.setTextColor(down ? COLOR_OK : COLOR_NG);
        } else {
            angleText.setText(o.code == Judge.RES_CAMERA ? "--.-°" : String.format(Locale.ROOT, "%+.1f°", o.angleDeg));
            angleText.setTextColor(ok ? COLOR_OK : COLOR_NG);
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(o.atMillis));
        resultText.setText((slabMode() && ok ? AngleAnalyzer.describe(o.shape, o.direction) + "\n" : "")
                + (o.manual ? "テスト判別" : "要求番号 " + o.requestNo) + "  " + o.codeText()
                + "  " + time + (ok ? String.format(Locale.ROOT, "\n角度 %+.1f°  ", o.angleDeg) : "\n") + "信頼度 " + Math.round(o.confidence) + "  処理 " + o.elapsedMs + "ms"
                + (o.message.isEmpty() ? "" : "\n" + o.message));
        resultText.setTextColor(ok ? 0xFF212121 : COLOR_NG);
    }

    private void setZero() {
        AngleAnalyzer.Result r = lastLive;
        if (r == null || !r.ok) {
            Toast.makeText(this, "今は角度を測れていません", Toast.LENGTH_SHORT).show();
            return;
        }
        double raw = r.angleDeg;
        new AlertDialog.Builder(this)
                .setTitle("基準角度")
                .setMessage(String.format(Locale.ROOT,
                        "今の向き（測定値 %+.1f°）を 0° にします。\n基準の木材をまっすぐ置いた状態で押してください。", raw))
                .setPositiveButton("0°にする", (d, w) -> {
                    prefs.saveOffset((float) raw);
                    applySettings();
                    addHistory(String.format(Locale.ROOT, "基準角度を %+.1f° に設定", raw));
                })
                .setNeutralButton("基準を解除", (d, w) -> {
                    prefs.saveOffset(0f);
                    applySettings();
                    addHistory("基準角度を解除");
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------ 通信

    private void startServer() {
        final int port = prefs.port();
        final String ip = prefs.plcIp();
        startBtn.setEnabled(false);
        new Thread(() -> {
            String err = null;
            try {
                Core.start(port, ip);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String error = err;
            main.post(() -> {
                startBtn.setEnabled(true);
                if (error != null) {
                    Toast.makeText(this, "待受を開始できません（ポート " + port + "）: " + error, Toast.LENGTH_LONG).show();
                }
                updateStatus();
            });
        }, "slmp-start").start();
    }

    private void stopServer() {
        new AlertDialog.Builder(this)
                .setMessage("待受を停止しますか？\nPLCの撮影要求に応えられなくなります。")
                .setPositiveButton("停止", (d, w) -> {
                    Core.stop();
                    updateStatus();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void updateStatus() {
        SlmpServer s = Core.server();
        startBtn.setText(s != null ? "待受停止" : "待受開始");
        String st;
        int color;
        if (s == null) {
            st = "停止中（PLCと通信しません）";
            color = COLOR_GRAY;
        } else if (Core.isFresh(3000)) {
            st = "PLC通信中  要求 " + Core.judge.requestValue();
            color = COLOR_OK;
        } else if (s.clientCount() > 0) {
            st = "PLC接続あり・要求なし";
            color = COLOR_WARN;
        } else {
            st = "待受中（ポート " + prefs.port() + "）PLCからの接続待ち";
            color = COLOR_WARN;
        }
        int pend = Core.judge.pending();
        if (pend > 0) st += "\n判別中…（要求 " + pend + "）";
        else if (pend < 0) st += "\nテスト判別中…";
        if (cameraError != null) {
            st += "\nカメラ: " + cameraError;
            color = COLOR_NG;
        }
        statusText.setText(st);
        statusText.setTextColor(color);
    }

    // ------------------------------------------------------------ ログ

    private static void addHistory(String s) {
        synchronized (history) {
            history.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + s);
            while (history.size() > 100) history.pollFirst();
        }
    }

    private void showLog() {
        List<String> lines = new ArrayList<>();
        lines.add("■ 判別");
        synchronized (history) {
            List<String> h = new ArrayList<>(history);
            for (int i = h.size() - 1; i >= 0; i--) lines.add(h.get(i));
        }
        SlmpServer s = Core.server();
        if (s != null) {
            lines.add("");
            lines.add("■ 通信");
            List<String> l = s.logLines();
            for (int i = l.size() - 1; i >= 0; i--) lines.add(l.get(i));
        }
        TextView tv = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        tv.setText(sb);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("ログ（新しい順）").setView(sv).setPositiveButton("閉じる", null).show();
    }

    // ------------------------------------------------------------ 部品

    private void updateTorchBtn() {
        torchBtn.setText(torchOn ? "ライト ON" : "ライト OFF");
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private Button btn(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
