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
 *   +2 向き       基準面（OKのとき下に来る面）の向き 1=下（OK） 2=上 3=左 4=右 0=不明
 *                  背板：平らな面　食パン形：丸い面の反対側　平行板：平ら面が上下なら1、左右なら3
 *   +3 形         1=背板（平ら1面） 2=平行板（平ら2面） 3=食パン形（平ら3面） 4=四角 0=不明
 *   +4 平ら面ビット 下=bit0 上=bit1 左=bit2 右=bit3
 *   +5 角度       0.1°単位の符号付き（例 123 = +12.3°）
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
        /** 形（AngleAnalyzer.SHAPE_*）と平ら面ビット */
        public int shape;
        public int flatBits;
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
    /** 形×向き（shape*5+dir）ごとの票 */
    private final int[] dirVotes = new int[25];
    private final double[] dirConf = new double[25];
    private final int[] dirBits = new int[25];
    private int shapeFrames;
    private double sumCos4, sumSin4;
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
        shapeFrames = 0;
        sumCos4 = sumSin4 = 0;
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
                if (r.shape != AngleAnalyzer.SHAPE_UNKNOWN) {
                    int d = r.direction == AngleAnalyzer.DIR_NONE ? 0 : AngleAnalyzer.directionOf(r.flatNormalDeg - cfg.offsetDeg);
                    int key = r.shape * 5 + d;
                    dirVotes[key]++;
                    dirConf[key] += r.confidence;
                    dirBits[key] = r.flatBits;
                    shapeFrames++;
                    double a4 = Math.toRadians(r.angleDeg * 4);     // 形の傾きは90°周期
                    sumCos4 += Math.cos(a4);
                    sumSin4 += Math.sin(a4);
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
            if (shapeFrames > 0) {
                double a = Math.toDegrees(Math.atan2(sumSin4, sumCos4)) / 4 - cfg.offsetDeg;
                a = ((a % 90) + 90) % 90;
                o.angleDeg = a > 45 ? a - 90 : a;
            }
            o.cx = sumCx / okCount;
            o.cy = sumCy / okCount;
            o.confidence = sumConf / okCount;
            int best = 0, votes = 0;
            for (int k = 1; k < 25; k++) {
                if (dirVotes[k] > dirVotes[best] || (dirVotes[k] == dirVotes[best] && dirConf[k] > dirConf[best])) best = k;
                votes += dirVotes[k];
            }
            if (dirVotes[best] > 0) {
                o.shape = best / 5;
                o.direction = best % 5;
                o.flatBits = dirBits[best];
            }
            if (ngCount > okCount) {
                code = RES_NG;
                o.message = "NGの画像が多い（" + lastNg + "）";
            } else if (o.confidence < cfg.minConfidence) {
                code = RES_NG;
                o.message = String.format(Locale.ROOT, "信頼度不足（%.0f < %d）", o.confidence, cfg.minConfidence);
            } else if (votes > 0 && dirVotes[best] * 2 <= votes) {
                code = RES_NG;
                o.message = "形・向きが画像ごとにばらつきます";
            }
            if (code != RES_OK) {
                o.direction = AngleAnalyzer.DIR_NONE;
                o.shape = AngleAnalyzer.SHAPE_UNKNOWN;
                o.flatBits = 0;
            }
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
                b[3] = (short) o.shape;
                b[4] = (short) o.flatBits;
                b[5] = (short) Math.round(o.angleDeg * 10);
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
