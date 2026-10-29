import jp.yuta.woodangle.AngleAnalyzer;
import jp.yuta.woodangle.DeviceMemory;
import jp.yuta.woodangle.Judge;
import jp.yuta.woodangle.SlmpServer;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Random;

/**
 * 判別処理とPLC受け渡しの試験（Android不要）。
 *   javac -d out app/src/main/java/jp/yuta/woodangle/{AngleAnalyzer,Judge,Device,DeviceMemory,SlmpServer}.java ci/LogicTest.java
 *   java -cp out LogicTest
 */
public class LogicTest {
    static int fails = 0;
    static final int W = 320, H = 240;

    public static void main(String[] a) throws Exception {
        Random rnd = new Random(1);
        // 1. 輪郭：明るい木材・暗い背景、いろいろな角度
        double worst = 0;
        for (int deg = -89; deg <= 90; deg += 7) {
            byte[] img = bar(deg, 200, 50, 0.5, 0.5, 200, 60, 8, rnd);
            AngleAnalyzer.Result r = AngleAnalyzer.analyze(img, W, H, cp());
            double e = diff(r.angleDeg, deg);
            worst = Math.max(worst, e);
            check(r.ok && e < 1.0, "輪郭 明るい木 " + deg + "° → " + fmt(r.angleDeg) + " 信頼度" + fmt(r.confidence));
        }
        System.out.println("輪郭 明るい木 最大誤差 " + fmt(worst) + "°");

        // 2. 輪郭：暗い木材・明るい背景（自動判定）、中心ずれ
        for (int deg : new int[]{-60, -15, 0, 30, 75}) {
            byte[] img = bar(deg, 180, 40, 0.4, 0.6, 50, 210, 10, rnd);
            AngleAnalyzer.Result r = AngleAnalyzer.analyze(img, W, H, cp());
            check(r.ok && diff(r.angleDeg, deg) < 1.0 && Math.abs(r.cx - 0.4) < 0.02 && Math.abs(r.cy - 0.6) < 0.02,
                    "輪郭 暗い木 " + deg + "° → " + fmt(r.angleDeg) + " 中心(" + fmt(r.cx) + "," + fmt(r.cy) + ")");
        }

        // 3. エッジ：画面からはみ出す太い木材
        for (int deg : new int[]{-70, -20, 0, 10, 45, 88}) {
            byte[] img = bar(deg, 2000, 90, 0.5, 0.5, 190, 70, 8, rnd);
            AngleAnalyzer.Params p = cp();
            p.mode = AngleAnalyzer.MODE_EDGE;
            AngleAnalyzer.Result r = AngleAnalyzer.analyze(img, W, H, p);
            check(r.ok && diff(r.angleDeg, deg) < 1.5, "エッジ はみ出し " + deg + "° → " + fmt(r.angleDeg) + " 信頼度" + fmt(r.confidence));
        }

        // 4. NG：何もない画面
        byte[] blank = new byte[W * H];
        for (int i = 0; i < blank.length; i++) blank[i] = (byte) (100 + rnd.nextInt(5));
        check(!AngleAnalyzer.analyze(blank, W, H, cp()).ok, "輪郭 無地はNG");
        AngleAnalyzer.Params pe = cp();
        pe.mode = AngleAnalyzer.MODE_EDGE;
        check(!AngleAnalyzer.analyze(blank, W, H, pe).ok, "エッジ 無地はNG");

        // 5. 正方形は信頼度が低い
        byte[] sq = bar(20, 80, 80, 0.5, 0.5, 200, 60, 5, rnd);
        AngleAnalyzer.Result rs = AngleAnalyzer.analyze(sq, W, H, cp());
        check(rs.confidence < 15, "正方形の信頼度が低い " + fmt(rs.confidence));

        // 6. ±90°付近の平均（-89と+89の平均は90付近になること）
        DeviceMemory mem0 = new DeviceMemory();
        Judge j0 = new Judge(mem0);
        Judge.Config c0 = new Judge.Config();
        c0.waitMs = 0;
        c0.average = 2;
        j0.setConfig(c0);
        j0.startManual(0);
        j0.offerFrame(res(-89, 80), 1, 1);
        Judge.Outcome o0 = j0.offerFrame(res(89, 80), 2, 2);
        check(o0 != null && Math.abs(Math.abs(o0.angleDeg) - 90) < 0.6, "±90°付近の平均 → " + (o0 == null ? "null" : fmt(o0.angleDeg)));

        // 7. 背板の木口：平らな面の向き（上下左右）、いろいろな形・傾き
        int[][] shapes = {{60, 60}, {150, 32}, {90, 40}, {200, 22}};   // 半径, 高さ（半円・薄い背板・中くらい・とても薄い）
        String[] dn = {"", "下", "上", "左", "右"};
        int[] dirAng = {0, 270, 90, 180, 0};
        int slabFail = 0, slabN = 0;
        double minConf = 999;
        for (int[] sh : shapes) {
            for (int d = 1; d <= 4; d++) {
                for (int tilt : new int[]{-30, -10, 0, 15, 35}) {
                    byte[] img = slabImg(dirAng[d] + tilt, sh[0], sh[1], 0.5, 0.5, 200, 50, 10, rnd);
                    AngleAnalyzer.Result r = AngleAnalyzer.analyze(img, W, H, new AngleAnalyzer.Params());
                    slabN++;
                    boolean ok = r.ok && r.direction == d;
                    if (r.ok) minConf = Math.min(minConf, r.confidence);
                    if (!ok) {
                        slabFail++;
                        System.out.println("NG!! 背板 R" + sh[0] + " h" + sh[1] + " " + dn[d] + " 傾き" + tilt + " → "
                                + (r.ok ? AngleAnalyzer.dirName(r.direction) : r.message) + " 信頼度" + fmt(r.confidence));
                    }
                }
            }
        }
        check(slabFail == 0, "背板の向き " + (slabN - slabFail) + "/" + slabN + " 正解  最低信頼度 " + fmt(minConf));
        // 暗い背景に樹皮（暗い縁）つき
        for (int d = 1; d <= 4; d++) {
            byte[] img = slabImg(dirAng[d] + 8, 110, 45, 0.45, 0.55, 190, 40, 12, rnd, true);
            AngleAnalyzer.Result r = AngleAnalyzer.analyze(img, W, H, new AngleAnalyzer.Params());
            check(r.ok && r.direction == d, "樹皮つき " + dn[d] + " → " + (r.ok ? AngleAnalyzer.dirName(r.direction) : r.message) + " 信頼度" + fmt(r.confidence));
        }
        // 長方形（両面とも平ら）は信頼度が低い
        byte[] rect = bar(5, 160, 50, 0.5, 0.5, 200, 50, 8, rnd);
        AngleAnalyzer.Result rr = AngleAnalyzer.analyze(rect, W, H, new AngleAnalyzer.Params());
        check(rr.confidence < 30, "長方形は向きの信頼度が低い " + fmt(rr.confidence));
        // 判別範囲からはみ出す → NG
        byte[] big = slabImg(270, 250, 120, 0.5, 0.5, 200, 50, 5, rnd);
        check(!AngleAnalyzer.analyze(big, W, H, new AngleAnalyzer.Params()).ok, "はみ出した木口はNG");

        // 8. PLCとの受け渡し（シンプルCPU通信の代わりにTCPで書込み・読出し）
        handshake();

        System.out.println(fails == 0 ? "すべてOK" : ("失敗 " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void handshake() throws Exception {
        DeviceMemory mem = new DeviceMemory();
        SlmpServer srv = new SlmpServer(mem, 50123);
        srv.start();
        Judge j = new Judge(mem);
        Judge.Config c = new Judge.Config();
        c.waitMs = 100;
        c.average = 3;
        c.offsetDeg = 5;
        j.setConfig(c);
        long t0 = System.nanoTime();
        try (Socket s = new Socket("127.0.0.1", 50123)) {
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            // 要求 1 を書く（PLCの書込み）
            call(out, in, write(0, 0xA8, new short[]{1}));
            // スマホ側：監視とカメラ画像（要求前の画像は使わないこと）
            long now = ms(t0);
            j.poll(now);
            check(j.pending() == 1, "要求1を検出");
            j.offerFrame(res(40, 90), now + 50, now + 50);      // 待ち時間内 → 使わない
            j.offerFrame(res(30, 90), now + 120, now + 120);
            j.offerFrame(res(32, 90), now + 150, now + 150);
            short[] mid = toShorts(call(out, in, read(100, 0xA8, 9)), 9);
            check(mid[0] == 0, "途中では完了番号0");
            j.offerFrame(res(31, 90), now + 180, now + 180);
            short[] d = toShorts(call(out, in, read(100, 0xA8, 9)), 9);
            check(d[0] == 1 && d[1] == 1 && Math.abs(d[3] - 260) <= 2 && d[6] == 90 && d[2] == 1,
                    "結果 完了" + d[0] + " 結果" + d[1] + " 向き" + d[2] + " 角度" + d[3] + "(期待260=31-5) 信頼度" + d[6] + " 時間" + d[8]);
            // 同じ番号のままなら再判別しない
            j.poll(ms(t0));
            check(j.pending() == 0, "同じ番号は再判別しない");
            // 要求 2：画像が来ない → カメラ異常
            call(out, in, write(0, 0xA8, new short[]{2}));
            long n2 = ms(t0);
            j.poll(n2);
            j.poll(n2 + c.waitMs + c.cameraTimeoutMs + 1);
            d = toShorts(call(out, in, read(100, 0xA8, 9)), 9);
            check(d[0] == 2 && d[1] == 3, "画像なし→カメラ異常 完了" + d[0] + " 結果" + d[1]);
            // 要求 0 → 完了番号も0
            call(out, in, write(0, 0xA8, new short[]{0}));
            j.poll(ms(t0));
            d = toShorts(call(out, in, read(100, 0xA8, 9)), 9);
            check(d[0] == 0 && d[1] == 0, "要求0で完了番号0");
            // 要求 3：NG画像だけ → NG
            call(out, in, write(0, 0xA8, new short[]{3}));
            long n3 = ms(t0);
            j.poll(n3);
            AngleAnalyzer.Result ng = new AngleAnalyzer.Result();
            ng.message = "木材が見つかりません";
            for (int i = 0; i < 3; i++) j.offerFrame(ng, n3 + 200 + i, n3 + 200 + i);
            d = toShorts(call(out, in, read(100, 0xA8, 9)), 9);
            check(d[0] == 3 && d[1] == 2, "木材なし→NG 完了" + d[0] + " 結果" + d[1]);
            // ハートビート
            j.tickHeartbeat();
            j.tickHeartbeat();
            d = toShorts(call(out, in, read(100, 0xA8, 9)), 9);
            check(d[7] == 2, "ハートビート " + d[7]);
        } finally {
            srv.stop();
        }
    }

    static AngleAnalyzer.Params cp() {
        AngleAnalyzer.Params p = new AngleAnalyzer.Params();
        p.mode = AngleAnalyzer.MODE_CONTOUR;
        return p;
    }

    static AngleAnalyzer.Result res(double ang, double conf) {
        AngleAnalyzer.Result r = new AngleAnalyzer.Result();
        r.ok = true;
        r.angleDeg = ang;
        r.confidence = conf;
        r.cx = 0.5;
        r.cy = 0.5;
        r.direction = AngleAnalyzer.DIR_DOWN;
        r.flatNormalDeg = 270;
        return r;
    }

    // ---- 合成画像：角度deg、長さlen、太さthick の棒 ----
    static byte[] bar(double deg, double len, double thick, double cx, double cy, int fgv, int bgv, int noise, Random rnd) {
        byte[] img = new byte[W * H];
        double t = Math.toRadians(deg);
        double ux = Math.cos(t), uy = -Math.sin(t);   // 画面座標（y下向き）
        double px = cx * W, py = cy * H;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double dx = x + 0.5 - px, dy = y + 0.5 - py;
                double along = dx * ux + dy * uy, across = -dx * uy + dy * ux;
                boolean in = Math.abs(along) <= len / 2 && Math.abs(across) <= thick / 2;
                int v = (in ? fgv : bgv) + (int) (rnd.nextGaussian() * noise);
                img[y * W + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        return img;
    }

    static byte[] slabImg(double normalDeg, double R, double h, double cx, double cy, int fgv, int bgv, int noise, Random rnd) {
        return slabImg(normalDeg, R, h, cx, cy, fgv, bgv, noise, rnd, false);
    }

    /** 背板の木口（円の一部を弦で切った形）。normalDeg は平らな面の外向きの向き（右0°・反時計回り） */
    static byte[] slabImg(double normalDeg, double R, double h, double cx, double cy, int fgv, int bgv, int noise, Random rnd, boolean bark) {
        byte[] img = new byte[W * H];
        double t = Math.toRadians(normalDeg);
        double nx = Math.cos(t), ny = -Math.sin(t);       // 平らな面の外向き（画面座標）
        double px = cx * W, py = cy * H;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double dx = x + 0.5 - px, dy = y + 0.5 - py;
                double v = -(dx * nx + dy * ny);            // 丸い側が＋
                double u = dx * -ny + dy * nx;
                double dist = Math.sqrt(u * u + (v - (h - R)) * (v - (h - R)));
                boolean in = v >= -h / 2 + h / 2 && dist <= R;   // 弦は v=0
                int val = bgv;
                if (in) val = (bark && dist > R - 5) ? 95 : fgv;
                val += (int) (rnd.nextGaussian() * noise);
                img[y * W + x] = (byte) Math.max(0, Math.min(255, val));
            }
        }
        return img;
    }

    // ---- SLMP 3E（Q互換）フレーム ----
    static byte[] frame(int cmd, int sub, byte[] data) {
        int len = 6 + data.length;
        byte[] f = new byte[9 + len];
        byte[] h = {0x50, 0, 0, (byte) 0xFF, (byte) 0xFF, 0x03, 0, (byte) len, (byte) (len >> 8), 0x10, 0,
                (byte) cmd, (byte) (cmd >> 8), (byte) sub, (byte) (sub >> 8)};
        System.arraycopy(h, 0, f, 0, h.length);
        System.arraycopy(data, 0, f, h.length, data.length);
        return f;
    }

    static byte[] head(int no, int code, int n, int extra) {
        byte[] b = new byte[6 + extra];
        b[0] = (byte) no;
        b[1] = (byte) (no >> 8);
        b[2] = (byte) (no >> 16);
        b[3] = (byte) code;
        b[4] = (byte) n;
        b[5] = (byte) (n >> 8);
        return b;
    }

    static byte[] write(int no, int code, short[] v) {
        byte[] b = head(no, code, v.length, v.length * 2);
        for (int i = 0; i < v.length; i++) {
            b[6 + i * 2] = (byte) v[i];
            b[7 + i * 2] = (byte) (v[i] >> 8);
        }
        return frame(0x1401, 0, b);
    }

    static byte[] read(int no, int code, int n) {
        return frame(0x0401, 0, head(no, code, n, 0));
    }

    static byte[] call(OutputStream out, DataInputStream in, byte[] req) throws Exception {
        out.write(req);
        out.flush();
        byte[] h = new byte[9];
        in.readFully(h);
        int len = (h[7] & 0xFF) | ((h[8] & 0xFF) << 8);
        byte[] b = new byte[len];
        in.readFully(b);
        int end = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
        if (end != 0) throw new RuntimeException("終了コード " + Integer.toHexString(end));
        byte[] d = new byte[len - 2];
        System.arraycopy(b, 2, d, 0, d.length);
        return d;
    }

    static short[] toShorts(byte[] b, int n) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) ((b[i * 2] & 0xFF) | ((b[i * 2 + 1] & 0xFF) << 8));
        return s;
    }

    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static double diff(double a, double b) {
        double d = Math.abs(AngleAnalyzer.normalize(a - b));
        return Math.min(d, 180 - d);
    }

    static String fmt(double v) {
        return String.format("%.2f", v);
    }

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "OK   " : "NG!! ") + msg);
        if (!ok) fails++;
    }
}
