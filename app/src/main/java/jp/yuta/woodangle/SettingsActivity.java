package jp.yuta.woodangle;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** 設定画面：通信（PLCのIP・ポート・デバイス）と判別の条件 */
public class SettingsActivity extends Activity {

    private static final int COLOR_NOTE = 0xFF757575;
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
    private static final int NUM = InputType.TYPE_CLASS_NUMBER;
    private static final int DEC = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;

    private Prefs prefs;
    private EditText plcIp, port, reqDev, resDev, average, waitMs, minConf, minArea, minContrast, offset;
    private EditText roiL, roiT, roiR, roiB;
    private RadioGroup mode, polarity;
    private CheckBox autoStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("設定");
        prefs = new Prefs(this);

        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), dp(8), dp(16), dp(16));

        section(f, "通信（シンプルCPU通信）");
        TextView phone = new TextView(this);
        phone.setText("このスマホのIP: " + NetUtil.phoneIps()
                + "\nPLCのシンプルCPU通信設定で、交信先IPにこのアドレスを入れてください。");
        phone.setTextSize(13);
        f.addView(phone);
        plcIp = field(f, "PLCのIPアドレス", "例: 192.168.3.39", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                "入力すると、このIPからの接続だけ受け付けます。空欄なら制限しません。");
        plcIp.setText(prefs.plcIp());
        port = field(f, "待受ポート番号", "例: 5000", NUM, "シンプルCPU通信設定の交信先ポート番号と同じにします（1024〜65535）。");
        port.setText(String.valueOf(prefs.port()));
        reqDev = field(f, "要求デバイス（PLC → スマホ）", "例: D0", TEXT,
                "PLCが撮影要求番号（1〜32767）を書込むワードデバイス。番号が変わるたびに1回判別します。");
        reqDev.setText(prefs.requestDevice());
        resDev = field(f, "結果デバイスの先頭（スマホ → PLC）", "例: D100", TEXT,
                "ここから8ワード：完了番号・結果・角度(0.1°)・中心X・中心Y・信頼度・ハートビート・処理時間。");
        resDev.setText(prefs.resultDevice());
        autoStart = new CheckBox(this);
        autoStart.setText("アプリ起動時に自動で待受を開始");
        autoStart.setChecked(prefs.autoStart());
        f.addView(autoStart);

        section(f, "判別のしかた");
        mode = radios(f, new String[]{"輪郭（木材全体が映る）", "エッジ（木材が画面からはみ出す・木目）"}, prefs.mode());
        note(f, "輪郭：背景との明るさの差で木材を切り出し、長手方向を測ります。\nエッジ：画面内の縁・木目の向きの平均を測ります。");
        label(f, "木材の明るさ（輪郭モード）");
        polarity = radios(f, new String[]{"自動", "木材が背景より明るい", "木材が背景より暗い"}, prefs.polarity());
        minArea = field(f, "最小面積（%）", "例: 2", NUM, "判別範囲に対して、これより小さいかたまりは木材とみなしません。");
        minArea.setText(String.valueOf(prefs.minArea()));
        minContrast = field(f, "最小コントラスト", "例: 20", NUM, "木材と背景の明るさの差（0〜255）がこれ未満ならNG。");
        minContrast.setText(String.valueOf(prefs.minContrast()));

        section(f, "判別範囲（画面に対する%）");
        note(f, "メイン画面の「範囲設定」で、指でなぞって決めることもできます。");
        LinearLayout r = new LinearLayout(this);
        roiL = small(r, "左", Math.round(prefs.roiL() * 100));
        roiT = small(r, "上", Math.round(prefs.roiT() * 100));
        roiR = small(r, "右", Math.round(prefs.roiR() * 100));
        roiB = small(r, "下", Math.round(prefs.roiB() * 100));
        f.addView(r);

        section(f, "判定");
        average = field(f, "平均する枚数", "例: 3", NUM, "要求ごとに、この枚数の画像の平均角度を返します（1〜10）。1枚約33ms。");
        average.setText(String.valueOf(prefs.average()));
        waitMs = field(f, "要求後の待ち時間（ms）", "例: 150", NUM,
                "要求を受けてからこの時間より後の画像を使います。古い画像や停止直後の揺れをよけるため（0〜2000）。");
        waitMs.setText(String.valueOf(prefs.waitMs()));
        minConf = field(f, "最小信頼度", "例: 30", NUM, "平均の信頼度がこれ未満なら結果NG（0〜100）。");
        minConf.setText(String.valueOf(prefs.minConfidence()));
        offset = field(f, "基準角度（°）", "例: 0", DEC, "測った角度からこの値を引いて返します。メイン画面の「基準にする」でも設定できます。");
        offset.setText(String.format(Locale.ROOT, "%.1f", prefs.offset()));

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);
        Button cancel = new Button(this);
        cancel.setText("キャンセル");
        cancel.setOnClickListener(v -> finish());
        btns.addView(cancel);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        btns.addView(save);
        f.addView(btns);

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        setContentView(sv);
    }

    private void save() {
        String ip = plcIp.getText().toString().trim();
        if (!ip.isEmpty() && !NetUtil.isIpv4(ip)) {
            err(plcIp, "IPアドレスの形式が正しくありません（例: 192.168.3.39）");
            return;
        }
        Integer p = intIn(port, 1024, 65535);
        if (p == null) return;
        String req = wordDevice(reqDev);
        if (req == null) return;
        String res = wordDevice(resDev);
        if (res == null) return;
        Device rq = Device.parse(req), rs = Device.parse(res);
        if (rq.code == rs.code && rq.number >= rs.number && rq.number < rs.number + Judge.WORDS) {
            err(reqDev, "結果デバイスの8ワードと重なっています");
            return;
        }
        Integer area = intIn(minArea, 0, 90), con = intIn(minContrast, 1, 255);
        if (area == null || con == null) return;
        Integer l = intIn(roiL, 0, 100), t = intIn(roiT, 0, 100), rr = intIn(roiR, 0, 100), b = intIn(roiB, 0, 100);
        if (l == null || t == null || rr == null || b == null) return;
        if (rr - l < 5 || b - t < 5) {
            err(roiR, "右は左より、下は上より5以上大きくしてください");
            return;
        }
        Integer avg = intIn(average, 1, 10), wait = intIn(waitMs, 0, 2000), conf = intIn(minConf, 0, 100);
        if (avg == null || wait == null || conf == null) return;
        float off;
        try {
            off = Float.parseFloat(offset.getText().toString().trim());
            if (off < -90 || off > 90) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            err(offset, "-90〜90で入力してください");
            return;
        }
        SharedPreferences.Editor e = prefs.edit();
        e.putString("plcIp", ip).putInt("port", p).putString("reqDev", req).putString("resDev", res)
                .putBoolean("autoStart", autoStart.isChecked())
                .putInt("mode", checkedIndex(mode)).putInt("polarity", checkedIndex(polarity))
                .putInt("minArea", area).putInt("minContrast", con)
                .putInt("average", avg).putInt("waitMs", wait).putInt("minConf", conf).putFloat("offset", off)
                .putFloat("roiL", l / 100f).putFloat("roiT", t / 100f).putFloat("roiR", rr / 100f).putFloat("roiB", b / 100f)
                .apply();
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    /** ワードデバイス（D/W/R/ZR等）だけ受け付ける */
    private String wordDevice(EditText e) {
        try {
            Device d = Device.parse(e.getText().toString());
            if (d.bit) {
                err(e, "ワードデバイス（D・W・Rなど）を指定してください");
                return null;
            }
            return d.toString();
        } catch (IllegalArgumentException ex) {
            err(e, ex.getMessage());
            return null;
        }
    }

    private Integer intIn(EditText e, int lo, int hi) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            if (v < lo || v > hi) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException ex) {
            err(e, lo + "〜" + hi + "で入力してください");
            return null;
        }
    }

    private void err(EditText e, String msg) {
        e.setError(msg);
        e.requestFocus();
    }

    // ---- 部品 ----

    private RadioGroup radios(LinearLayout f, String[] labels, int checked) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setId(1000 + i);
            g.addView(b);
        }
        g.check(1000 + Math.max(0, Math.min(labels.length - 1, checked)));
        f.addView(g);
        return g;
    }

    private static int checkedIndex(RadioGroup g) {
        return Math.max(0, g.getCheckedRadioButtonId() - 1000);
    }

    private EditText small(LinearLayout r, String label, int value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(dp(4), 0, dp(2), 0);
        r.addView(l);
        EditText e = new EditText(this);
        e.setInputType(NUM);
        e.setSingleLine(true);
        e.setText(String.valueOf(value));
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        return e;
    }

    private void section(LinearLayout f, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF1565C0);
        t.setPadding(0, dp(18), 0, dp(2));
        f.addView(t);
    }

    private void label(LinearLayout f, String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(15);
        l.setPadding(0, dp(8), 0, 0);
        f.addView(l);
    }

    private void note(LinearLayout f, String text) {
        TextView n = new TextView(this);
        n.setText(text);
        n.setTextSize(12);
        n.setTextColor(COLOR_NOTE);
        f.addView(n);
    }

    private EditText field(LinearLayout f, String label, String hint, int inputType, String note) {
        label(f, label);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        f.addView(e);
        note(f, note);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
