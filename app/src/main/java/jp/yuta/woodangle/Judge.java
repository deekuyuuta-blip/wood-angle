package jp.yuta.woodangle;

import java.util.Locale;

/**
 * PLCとの受け渡し（Android非依存・純Java）。
 *
 * PLC → スマホ（PLCが書込む）
 *   要求デバイス（例 D0）: 撮影要求番号。PLCが判別させたいとき 1〜32767 の新しい番号を書く。
 *                          0 にすると完了番号・結果も 0 に戻る。
 * スマホ → PLC（PLCが読出す）  結果デバイスの先頭（例 D100）から9ワード
 *   +0 完了番号   判別が終わると要求番号と同じ値になる（PLCはこれが要求番号と一致したら結果を使う）
 *   +1 結果       1=判別できた  2=NG（木材なし・信頼度不足）  3=カメラ異常（時間内に画像が来ない）
 *   +2 向き       背板の平らな面の向き 1=下（OK） 2=上 3=左 4=右 0=不明
 *   +3 角度       0.1°単位の符号付き（例 123 = +12.3°）。-900〜+900
 *   +4 中心X      画面幅に対する 0〜1000
 *   +5 中心Y      画面高さに対する 0〜1000
 *   +6 信頼度     0〜100
 *   +7 ハートビート 1秒ごとに+1
 *   +8 処理時間   要求を受けてから結果を書くまで [ms]
 *
 * 結果の9ワードは1回でまとめて書くので、PLCが途中の値を読むことはない。
 * スレッド：poll() は監視スレッド、offerFrame() はカメラのスレッドから呼ばれる。
 */
public final class Judge {

    public static final int WORDS = 9;
    private static final int HB = 7;
    public static final int RES_NONE = 0, RES_OK = 1, RES_NG = 2, RES_CAMERA = 3;

    /** 判別の設定 */
    public static final class Config {
        public Device request = Device.parse("D0");
        public Device result = Device.parse("D100");
        /** 要求を受けてから、この時間より後に届いた画像を使う（ブレ・古い画像よけ）[ms] */
        public int waitMs = 150;
        /** 何枚の画像の平均にするか */
        public int average = 3;
        /** これ未満の信頼度は NG */
        public int minConfidence = 30;
        /** 基準角度（ゼロ合わせ）[°]。結果 = 測った角度 − これ */
        public double offsetDeg = 0;
        /** この時間内に画像がそろわなければカメラ異常 [ms] */
        public int cameraTimeoutMs = 1000;
    }

    /** 最終結果（画面表示用） */
    public static final class Outcome {
        public int requestNo;
        public int code;
        public double angleDeg;
        public double cx, cy;
        public double confidence;
        /** 背板の平らな面の向き（AngleAnalyzer.DIR_*） */
        public int direction;
        public int elapsedMs;
        public String message = "";
        public boolean manual;
        public long atMillis;

        public String codeText() {
            switch (code) {
                case RES_OK: return "判別OK";
                case RES_NG: return "NG";
                case RES_CAMERA: return "カメラ異常";
                default: return "-";
            }
        }
    }

    public interface Listener {
        void onOutcome(Outcome o);
    }

    private final DeviceMemory mem;
    private Config cfg = new Config();
    private Listener listener;

    private static final int MANUAL = -1;
    private int handled;        // 最後に完了した要求番号
    private int pending;        // 処理中の要求番号（0=なし、-1=画面のテスト判別）
    private long pendingSince;
    private int okCount, ngCount;
    private double sumCos, sumSin, sumCx, sumCy, sumConf;
    private final int[] dirVotes = new int[5];
    private final double[] dirConf = new double[5];
    private String lastNg = "";
    private short heartbeat;
    private Outcome last;

    public Judge(DeviceMemory mem) {
        this.mem = mem;
    }

    public synchronized void setConfig(Config c) {
        cfg = c;
    }

    public synchronized Config config() {
        return cfg;
    }

    public synchronized void setListener(Listener l) {
        listener = l;
    }

    public synchronized Outcome lastOutcome() {
        return last;
    }

    /** 処理中の要求番号（0=なし、-1=テスト判別） */
    public synchronized int pending() {
        return pending;
    }

    /** PLCが書いている要求番号 */
    public synchronized int requestValue() {
        return readRequest();
    }

    private int readRequest() {
        return mem.readWords(cfg.request.code, cfg.request.number, 1)[0] & 0xFFFF;
    }

    /** 監視スレッドから短い周期（10ms程度）で呼ぶ。now は単調増加の時刻[ms] */
    public void poll(long now) {
        Outcome done = null;
        Listener l;
        synchronized (this) {
            l = listener;
            int req = readRequest();
            if (req == 0) {
                if (handled != 0 || pending > 0) {
                    handled = 0;
                    if (pending > 0) pending = 0;
                    short[] z = new short[WORDS];
                    z[HB] = heartbeat;
                    writeBlock(z);
                }
            } else if ((pending == 0 || pending == MANUAL) && req != handled) {
                begin(req, now);
            } else if (pending > 0 && req != pending) {
                begin(req, now);     // 処理中に番号が変わった → 新しい要求でやり直す
            }
            if (pending != 0 && now - pendingSince > cfg.waitMs + cfg.cameraTimeoutMs) {
                lastNg = "時間内に画像が届きません";
                done = finish(RES_CAMERA, now);
            }
        }
        if (done != null && l != null) l.onOutcome(done);
    }

    /** 1秒ごとに呼ぶ */
    public synchronized void tickHeartbeat() {
        heartbeat++;
        mem.writeWords(cfg.result.code, cfg.result.number + HB, new short[]{heartbeat});
    }

    /** 画面の「テスト判別」。PLCへは書かない */
    public synchronized boolean startManual(long now) {
        if (pending > 0) return false;
        begin(MANUAL, now);
        return true;
    }

    private void begin(int req, long now) {
        pending = req;
        pendingSince = now;
        okCount = ngCount = 0;
        sumCos = sumSin = sumCx = sumCy = sumConf = 0;
        java.util.Arrays.fill(dirVotes, 0);
        java.util.Arrays.fill(dirConf, 0);
        lastNg = "";
    }

    /** 判別中なら画像を求めている */
    public synchronized boolean wantsFrame() {
        return pending != 0;
    }

    /**
     * カメラ画像の判別結果を渡す。frameAt は画像が届いた時刻（poll と同じ時計）、now は今。
     * 要求に対する結果がそろったら Outcome を返す（それ以外 null）。
     */
    public Outcome offerFrame(AngleAnalyzer.Result r, long frameAt, long now) {
        Outcome done = null;
        Listener l;
        synchronized (this) {
            l = listener;
            if (pending == 0 || frameAt < pendingSince + cfg.waitMs) return null;
            if (r.ok) {
                okCount++;
                double a = Math.toRadians(r.angleDeg * 2);   // 軸の向きは180°周期なので2倍角で平均
                sumCos += Math.cos(a) * Math.max(r.confidence, 1);
                sumSin += Math.sin(a) * Math.max(r.confidence, 1);
                sumCx += r.cx;
                sumCy += r.cy;
                sumConf += r.confidence;
                if (r.direction != AngleAnalyzer.DIR_NONE) {
                    int d = AngleAnalyzer.directionOf(r.flatNormalDeg - cfg.offsetDeg);
                    dirVotes[d]++;
                    dirConf[d] += r.confidence;
                }
            } else {
                ngCount++;
                lastNg = r.message;
            }
            if (okCount + ngCount >= Math.max(1, cfg.average)) {
                done = finish(okCount == 0 ? RES_NG : RES_OK, now);
            }
        }
        if (done != null && l != null) l.onOutcome(done);
        return done;
    }

    private Outcome finish(int code, long now) {
        Outcome o = new Outcome();
        o.manual = pending == MANUAL;
        o.requestNo = o.manual ? 0 : pending;
        o.elapsedMs = (int) Math.min(32767, Math.max(0, now - pendingSince));
        o.atMillis = System.currentTimeMillis();
        if (code == RES_OK) {
            double ang = Math.toDegrees(Math.atan2(sumSin, sumCos)) / 2;
            o.angleDeg = AngleAnalyzer.normalize(ang - cfg.offsetDeg);
            o.cx = sumCx / okCount;
            o.cy = sumCy / okCount;
            o.confidence = sumConf / okCount;
            int best = 0, votes = 0;
            for (int d = 1; d < 5; d++) {
                if (dirVotes[d] > dirVotes[best] || (dirVotes[d] == dirVotes[best] && dirConf[d] > dirConf[best])) best = d;
                votes += dirVotes[d];
            }
            o.direction = dirVotes[best] > 0 ? best : AngleAnalyzer.DIR_NONE;
            if (ngCount > okCount) {
                code = RES_NG;
                o.message = "NGの画像が多い（" + lastNg + "）";
            } else if (o.confidence < cfg.minConfidence) {
                code = RES_NG;
                o.message = String.format(Locale.ROOT, "信頼度不足（%.0f < %d）", o.confidence, cfg.minConfidence);
            } else if (votes > 0 && dirVotes[best] * 2 <= votes) {
                code = RES_NG;
                o.message = "向きが画像ごとにばらつきます";
            }
            if (code != RES_OK) o.direction = AngleAnalyzer.DIR_NONE;
        } else {
            o.message = lastNg;
        }
        o.code = code;
        if (!o.manual) {
            short[] b = new short[WORDS];
            b[0] = (short) pending;
            b[1] = (short) code;
            if (code != RES_CAMERA && okCount > 0) {
                b[2] = (short) o.direction;
                b[3] = (short) Math.round(o.angleDeg * 10);
                b[4] = (short) Math.round(o.cx * 1000);
                b[5] = (short) Math.round(o.cy * 1000);
                b[6] = (short) Math.round(Math.min(100, o.confidence));
            }
            b[HB] = heartbeat;
            b[8] = (short) o.elapsedMs;
            writeBlock(b);
            handled = pending;
        }
        pending = 0;
        last = o;
        return o;
    }

    private void writeBlock(short[] b) {
        mem.writeWords(cfg.result.code, cfg.result.number, b);
    }
}
