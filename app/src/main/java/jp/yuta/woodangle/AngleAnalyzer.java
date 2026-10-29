package jp.yuta.woodangle;

/**
 * 木材の回転角度を求める画像処理（Android非依存・純Java）。
 * 入力はグレースケール画像（1画素1バイト、行の詰め物なし）。
 *
 * 角度の決め方：画面の右方向を0°、反時計回り（左回り）を＋とし、-90.0°〜+90.0°で返す。
 * 向き（元口・末口）は区別しない（軸の傾きだけ）。
 *
 * モード
 *  - 輪郭：背景との明るさの差で木材を切り出し、形の長手方向（主軸）を求める。
 *          木材が画面内に全体（または大部分）見えているとき向き。
 *  - エッジ：画面内のエッジ（木の縁・木目）の向きの平均を求める（構造テンソル）。
 *          木材が画面からはみ出すとき・画面いっぱいに映るとき向き。
 */
public final class AngleAnalyzer {

    public static final int MODE_CONTOUR = 0;
    public static final int MODE_EDGE = 1;

    public static final int POLARITY_AUTO = 0;
    public static final int POLARITY_BRIGHT = 1;   // 木材が背景より明るい
    public static final int POLARITY_DARK = 2;     // 木材が背景より暗い

    /** 判別の条件 */
    public static final class Params {
        public int mode = MODE_CONTOUR;
        public int polarity = POLARITY_AUTO;
        /** 判別範囲（画面に対する割合 0〜1） */
        public double roiLeft = 0.05, roiTop = 0.05, roiRight = 0.95, roiBottom = 0.95;
        /** 輪郭モード：木材とみなす最小面積（判別範囲に対する%） */
        public double minAreaPercent = 2.0;
        /** 輪郭モード：明暗の差がこれ未満なら「コントラスト不足」 */
        public int minContrast = 20;
        /** 表示用のマスク（木材と判定した画素）を作るか */
        public boolean makeMask = true;
    }

    /** 判別結果 */
    public static final class Result {
        public boolean ok;
        public String message = "";
        /** 角度 [°] -90〜+90、反時計回りが＋ */
        public double angleDeg;
        /** 木材の中心（画面に対する割合 0〜1、左上が原点） */
        public double cx, cy;
        /** 信頼度 0〜100（輪郭：細長さ、エッジ：向きのそろい具合） */
        public double confidence;
        /** 表示用：主軸の半分の長さ（画素） */
        public double halfLength;
        /** 表示用マスク（null可）。1=木材 */
        public byte[] mask;
        public int width, height;
        public int roiX0, roiY0, roiX1, roiY1;
        /** 輪郭モード：木材が判別範囲の端に接している */
        public boolean touchesEdge;
    }

    private AngleAnalyzer() {
    }

    public static Result analyze(byte[] gray, int w, int h, Params p) {
        Result r = new Result();
        r.width = w;
        r.height = h;
        int x0 = clamp((int) Math.round(Math.min(p.roiLeft, p.roiRight) * w), 0, w - 1);
        int x1 = clamp((int) Math.round(Math.max(p.roiLeft, p.roiRight) * w), x0 + 1, w);
        int y0 = clamp((int) Math.round(Math.min(p.roiTop, p.roiBottom) * h), 0, h - 1);
        int y1 = clamp((int) Math.round(Math.max(p.roiTop, p.roiBottom) * h), y0 + 1, h);
        r.roiX0 = x0;
        r.roiY0 = y0;
        r.roiX1 = x1;
        r.roiY1 = y1;
        if (x1 - x0 < 8 || y1 - y0 < 8) {
            r.message = "判別範囲が小さすぎます";
            return r;
        }
        int[] blur = boxBlur(gray, w, h, x0, y0, x1, y1);
        if (p.mode == MODE_EDGE) edge(blur, w, x0, y0, x1, y1, p, r);
        else contour(blur, w, x0, y0, x1, y1, p, r);
        return r;
    }

    // ------------------------------------------------------------------ 輪郭モード

    private static void contour(int[] v, int w, int x0, int y0, int x1, int y1, Params p, Result r) {
        int rw = x1 - x0, rh = y1 - y0, n = rw * rh;
        int[] hist = new int[256];
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) hist[v[y * w + x]]++;

        // 大津の二値化
        long sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += (long) i * hist[i];
        long wB = 0, sumB = 0;
        double best = -1;
        int th = 128;
        double mLow = 0, mHigh = 0;
        for (int t = 0; t < 255; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = n - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sumAll - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                th = t;
                mLow = mB;
                mHigh = mF;
            }
        }
        if (mHigh - mLow < p.minContrast) {
            r.message = String.format(java.util.Locale.ROOT, "コントラスト不足（明暗差 %.0f）", mHigh - mLow);
            return;
        }

        boolean woodBright;
        if (p.polarity == POLARITY_BRIGHT) woodBright = true;
        else if (p.polarity == POLARITY_DARK) woodBright = false;
        else {
            // 自動：判別範囲の外周に多い方を背景とみなす
            int brightBorder = 0, border = 0;
            for (int x = x0; x < x1; x++) {
                if (v[y0 * w + x] > th) brightBorder++;
                if (v[(y1 - 1) * w + x] > th) brightBorder++;
                border += 2;
            }
            for (int y = y0; y < y1; y++) {
                if (v[y * w + x0] > th) brightBorder++;
                if (v[y * w + x1 - 1] > th) brightBorder++;
                border += 2;
            }
            woodBright = brightBorder * 2 < border;
        }

        // 木材側の画素で、いちばん大きいかたまり（4近傍）を選ぶ
        byte[] fg = new byte[n];
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int val = v[(y + y0) * w + x + x0];
                fg[y * rw + x] = (byte) ((val > th) == woodBright ? 1 : 0);
            }
        }
        int[] label = new int[n];
        int[] stack = new int[n];
        int bestLabel = 0, bestArea = 0, cur = 0;
        for (int i = 0; i < n; i++) {
            if (fg[i] == 0 || label[i] != 0) continue;
            cur++;
            int area = 0, sp = 0;
            stack[sp++] = i;
            label[i] = cur;
            while (sp > 0) {
                int q = stack[--sp];
                area++;
                int qx = q % rw, qy = q / rw;
                if (qx > 0 && fg[q - 1] != 0 && label[q - 1] == 0) { label[q - 1] = cur; stack[sp++] = q - 1; }
                if (qx < rw - 1 && fg[q + 1] != 0 && label[q + 1] == 0) { label[q + 1] = cur; stack[sp++] = q + 1; }
                if (qy > 0 && fg[q - rw] != 0 && label[q - rw] == 0) { label[q - rw] = cur; stack[sp++] = q - rw; }
                if (qy < rh - 1 && fg[q + rw] != 0 && label[q + rw] == 0) { label[q + rw] = cur; stack[sp++] = q + rw; }
            }
            if (area > bestArea) {
                bestArea = area;
                bestLabel = cur;
            }
        }

        if (p.makeMask) {
            r.mask = new byte[r.width * r.height];
            if (bestLabel != 0) {
                for (int y = 0; y < rh; y++)
                    for (int x = 0; x < rw; x++)
                        if (label[y * rw + x] == bestLabel) r.mask[(y + y0) * r.width + x + x0] = 1;
            }
        }
        if (bestArea * 100.0 / n < p.minAreaPercent) {
            r.message = "木材が見つかりません（面積不足）";
            return;
        }

        // モーメント（y軸は上向きを＋にして角度を反時計回り＋にする）
        double sx = 0, sy = 0;
        boolean touch = false;
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                if (label[y * rw + x] != bestLabel) continue;
                sx += x;
                sy += y;
                if (x == 0 || y == 0 || x == rw - 1 || y == rh - 1) touch = true;
            }
        }
        double mx = sx / bestArea, my = sy / bestArea;
        double sxx = 0, syy = 0, sxy = 0;
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                if (label[y * rw + x] != bestLabel) continue;
                double dx = x - mx, dy = -(y - my);
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
        }
        sxx /= bestArea;
        syy /= bestArea;
        sxy /= bestArea;
        double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double common = Math.sqrt((sxx - syy) * (sxx - syy) / 4 + sxy * sxy);
        double l1 = (sxx + syy) / 2 + common;
        double l2 = Math.max(0, (sxx + syy) / 2 - common);

        r.ok = true;
        r.touchesEdge = touch;
        r.angleDeg = normalize(Math.toDegrees(theta));
        r.cx = (mx + x0 + 0.5) / r.width;
        r.cy = (my + y0 + 0.5) / r.height;
        r.confidence = l1 <= 0 ? 0 : 100 * (1 - Math.sqrt(l2 / l1));
        r.halfLength = Math.sqrt(3 * l1);
        r.message = touch ? "判別範囲の端に接しています" : "";
    }

    // ------------------------------------------------------------------ エッジモード

    private static void edge(int[] v, int w, int x0, int y0, int x1, int y1, Params p, Result r) {
        double jxx = 0, jyy = 0, jxy = 0;
        int cnt = 0;
        float[] mag = p.makeMask ? new float[r.width * r.height] : null;
        double magSum = 0;
        for (int y = y0 + 1; y < y1 - 1; y++) {
            for (int x = x0 + 1; x < x1 - 1; x++) {
                int i = y * w + x;
                int gx = (v[i - w + 1] + 2 * v[i + 1] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - 1] + v[i + w - 1]);
                int gyDown = (v[i + w - 1] + 2 * v[i + w] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - w] + v[i - w + 1]);
                double gy = -gyDown;   // 上向きを＋
                jxx += (double) gx * gx;
                jyy += gy * gy;
                jxy += gx * gy;
                cnt++;
                if (mag != null) {
                    float m = (float) Math.sqrt((double) gx * gx + gy * gy);
                    mag[i] = m;
                    magSum += m;
                }
            }
        }
        r.cx = (x0 + x1) / 2.0 / r.width;
        r.cy = (y0 + y1) / 2.0 / r.height;
        r.halfLength = Math.min(x1 - x0, y1 - y0) * 0.45;
        if (mag != null && cnt > 0) {
            r.mask = new byte[r.width * r.height];
            float lim = (float) (magSum / cnt * 2.0);
            for (int i = 0; i < mag.length; i++) if (mag[i] > lim) r.mask[i] = 1;
        }
        if (cnt == 0) {
            r.message = "判別範囲が小さすぎます";
            return;
        }
        double energy = (jxx + jyy) / cnt;
        // Sobelの係数込みで、明暗差およそ5段階以下のエッジしかない＝ほぼ無地
        if (energy < 400) {
            r.message = "エッジが見つかりません（無地・暗すぎ）";
            return;
        }
        double phi = 0.5 * Math.atan2(2 * jxy, jxx - jyy);   // 明るさが変わる向き
        double coherence = Math.sqrt((jxx - jyy) * (jxx - jyy) + 4 * jxy * jxy) / (jxx + jyy);
        r.ok = true;
        r.angleDeg = normalize(Math.toDegrees(phi) + 90);  // エッジ（線）の向きはその直角
        r.confidence = 100 * coherence;
        r.message = "";
    }

    // ------------------------------------------------------------------ 共通

    /** 3x3平均（判別範囲＋1画素） */
    private static int[] boxBlur(byte[] g, int w, int h, int x0, int y0, int x1, int y1) {
        int[] out = new int[w * h];
        int ax0 = Math.max(0, x0 - 1), ax1 = Math.min(w, x1 + 1);
        int ay0 = Math.max(0, y0 - 1), ay1 = Math.min(h, y1 + 1);
        for (int y = ay0; y < ay1; y++) {
            for (int x = ax0; x < ax1; x++) {
                int s = 0, c = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        s += g[yy * w + xx] & 0xFF;
                        c++;
                    }
                }
                out[y * w + x] = s / c;
            }
        }
        return out;
    }

    /** 角度を -90 < a <= 90 にそろえる */
    public static double normalize(double deg) {
        double a = deg % 180.0;
        if (a <= -90) a += 180;
        if (a > 90) a -= 180;
        return a;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
