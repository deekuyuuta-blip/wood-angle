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
    /** 木口の形（背板・平行板・食パン形）と向き */
    public static final int MODE_SLAB = 2;

    public static final int SHAPE_UNKNOWN = 0, SHAPE_SLAB = 1, SHAPE_PARALLEL = 2, SHAPE_BREAD = 3, SHAPE_SQUARE = 4;
    private static final String[] SHAPE_NAMES = {"不明", "背板", "平行板", "食パン形", "四角"};

    public static String shapeName(int s) {
        return s >= 0 && s < SHAPE_NAMES.length ? SHAPE_NAMES[s] : "?";
    }

    public static final int DIR_NONE = 0, DIR_DOWN = 1, DIR_UP = 2, DIR_LEFT = 3, DIR_RIGHT = 4;
    private static final String[] DIR_NAMES = {"不明", "下", "上", "左", "右"};

    public static String dirName(int d) {
        return d >= 0 && d < DIR_NAMES.length ? DIR_NAMES[d] : "?";
    }

    public static final int POLARITY_AUTO = 0;
    public static final int POLARITY_BRIGHT = 1;   // 木材が背景より明るい
    public static final int POLARITY_DARK = 2;     // 木材が背景より暗い

    /** 判別の条件 */
    public static final class Params {
        public int mode = MODE_SLAB;
        public int polarity = POLARITY_AUTO;
        /** 判別範囲（画面に対する割合 0〜1） */
        public double roiLeft = 0.05, roiTop = 0.05, roiRight = 0.95, roiBottom = 0.95;
        /** 輪郭モード：木材とみなす最小面積（判別範囲に対する%） */
        public double minAreaPercent = 2.0;
        /** 背板モード：形の指定（SHAPE_UNKNOWN=自動） */
        public int shape = SHAPE_UNKNOWN;
        /** 背板モード（自動）：平らさ（直線に接する長さの割合×まっすぐさ）がこれ以上なら平らな面 [%] */
        public double flatMinPercent = 35;
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
        /** 背板モード：平らな面の向き（DIR_*）と、その外向き法線の角度[°] */
        public int direction = DIR_NONE;
        public double flatNormalDeg;
        /** 背板モード：形（SHAPE_*）、平ら面ビット（下1 上2 左4 右8）、向きごとの平らさ 0〜1 */
        public int shape = SHAPE_UNKNOWN;
        public int flatBits;
        public final double[] flatScore = new double[5];
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
        if (p.mode == MODE_SLAB) slab(label, bestLabel, rw, rh, mx, my, p, r);
    }

    /**
     * 木口の形と向き（背板・平行板・食パン形）。
     * 1. 輪郭がいちばん長く直線に接する向きから、平らな面の傾き θ を求める
     * 2. θ に合わせた4つの辺（右・上・左・下）それぞれで「まっすぐ接する長さ」の割合を測る
     *    （平らな面は直線に長く接し、丸い面・とがった端は一点でしか接しない）
     * 3. 平らな辺の数で形を決め、基準面（OKのとき下に来る面）の向きを返す
     *    背板：平らな面　平行板：平らな2面（上下なら下、左右なら左）　食パン形：丸い面の反対側
     */
    private static void slab(int[] label, int lab, int rw, int rh,
                             double mx, double my, Params p, Result r) {
        if (r.touchesEdge) {
            r.ok = false;
            r.message = "木口が判別範囲からはみ出しています";
            return;
        }
        // 輪郭の画素（外側の端は必ず輪郭の画素なので、これだけで足りる）
        int nb = 0;
        double[] bx = new double[rw * rh / 4 + 16], by = new double[bx.length];
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int i = y * rw + x;
                if (label[i] != lab) continue;
                boolean edge = x == 0 || y == 0 || x == rw - 1 || y == rh - 1
                        || label[i - 1] != lab || label[i + 1] != lab || label[i - rw] != lab || label[i + rw] != lab;
                if (!edge) continue;
                if (nb == bx.length) {
                    bx = java.util.Arrays.copyOf(bx, nb * 2);
                    by = java.util.Arrays.copyOf(by, nb * 2);
                }
                bx[nb] = x - mx;
                by[nb] = -(y - my);
                nb++;
            }
        }
        // 1. 傾き θ：いちばん長く直線に接する向き（1°刻み→0.1°刻み）
        double theta = 0, bestScore = -1;
        for (int t = -44; t <= 45; t++) {
            double sc = alignScore(bx, by, nb, t);
            if (sc > bestScore) {
                bestScore = sc;
                theta = t;
            }
        }
        double coarse = theta;
        for (int t = -10; t <= 10; t++) {
            double th0 = coarse + t * 0.1;
            double sc = alignScore(bx, by, nb, th0);
            if (sc > bestScore) {
                bestScore = sc;
                theta = th0;
            }
        }
        if (theta > 45) theta -= 90;
        if (theta <= -45) theta += 90;

        // 2. 4辺の平らさ（k=0 右, 1 上, 2 左, 3 下：θだけ回した向き）。他の面は±4°の傾きまで許す
        // 平らさ g = 接する長さの割合 × (1 − ふくらみ)。円弧は tol の幅だけ接してもふくらみ≈1 で g が小さくなる
        double[] f = new double[4], sag = new double[4], g = new double[4];
        int[] dirOf = new int[4];
        double[] so = new double[1];
        for (int k = 0; k < 4; k++) {
            f[k] = flatness(bx, by, nb, theta + 90 * k, 4, so);
            sag[k] = so[0];
            g[k] = f[k] * Math.max(0, 1 - Math.min(1, sag[k]));
            dirOf[k] = directionOf(theta + 90 * k);
            r.flatScore[dirOf[k]] = g[k];
        }

        // 3. 形と基準面
        double th = p.flatMinPercent / 100.0;
        boolean[] flat = new boolean[4];
        double conf;
        switch (p.shape) {
            case SHAPE_SLAB: {
                int best = argmax(g);
                flat[best] = true;
                double second = 0;
                for (int k = 0; k < 4; k++) if (k != best) second = Math.max(second, g[k]);
                conf = (g[best] - second) / 0.4;
                break;
            }
            case SHAPE_PARALLEL: {
                double s13 = g[1] + g[3], s02 = g[0] + g[2];
                int a1 = s13 >= s02 ? 1 : 0;
                flat[a1] = flat[a1 + 2] = true;
                conf = (Math.min(g[a1], g[a1 + 2]) - Math.max(g[1 - a1], g[3 - a1])) / 0.4;
                break;
            }
            case SHAPE_BREAD: {
                int worst = argmin(g);
                double minFlat = 9;
                for (int k = 0; k < 4; k++) {
                    if (k == worst) continue;
                    flat[k] = true;
                    minFlat = Math.min(minFlat, g[k]);
                }
                conf = (minFlat - g[worst]) / 0.4;
                break;
            }
            default: {
                double margin = 9;
                for (int k = 0; k < 4; k++) {
                    flat[k] = g[k] >= th;
                    margin = Math.min(margin, Math.abs(g[k] - th));
                }
                conf = margin / 0.2;
            }
        }
        int count = 0;
        for (boolean b : flat) if (b) count++;
        int base = -1, shape;
        if (count == 1) {
            shape = SHAPE_SLAB;
            for (int k = 0; k < 4; k++) if (flat[k]) base = k;
        } else if (count == 2) {
            if (flat[1] && flat[3]) {
                shape = SHAPE_PARALLEL;
                base = 3;
            } else if (flat[0] && flat[2]) {
                shape = SHAPE_PARALLEL;
                base = 2;
            } else {
                shape = SHAPE_UNKNOWN;
            }
        } else if (count == 3) {
            shape = SHAPE_BREAD;
            for (int k = 0; k < 4; k++) if (!flat[k]) base = (k + 2) % 4;
        } else if (count == 4) {
            shape = SHAPE_SQUARE;
        } else {
            shape = SHAPE_UNKNOWN;
        }
        int bits = 0;
        for (int k = 0; k < 4; k++) if (flat[k]) bits |= dirBit(dirOf[k]);

        r.shape = shape;
        r.flatBits = bits;
        r.angleDeg = theta;
        r.confidence = Math.max(0, Math.min(100, 100 * conf));
        if (shape == SHAPE_UNKNOWN) {
            r.ok = false;
            r.message = count == 0 ? "平らな面が見つかりません" : "形が分かりません（隣り合う2面だけが平ら）";
            return;
        }
        if (base >= 0) {
            r.flatNormalDeg = theta + 90 * base;
            r.direction = dirOf[base];
        } else {
            r.flatNormalDeg = theta + 270;
            r.direction = DIR_NONE;     // 四角：向きなし
        }
    }

    /** 傾き θ のときの「いちばん平らな辺の接触割合」＋全辺の合計の少し */
    private static double alignScore(double[] bx, double[] by, int nb, double theta) {
        double mx = 0, sum = 0;
        for (int k = 0; k < 4; k++) {
            double f = flatness(bx, by, nb, theta + 90 * k, 0);
            mx = Math.max(mx, f);
            sum += f;
        }
        return mx + 0.01 * sum;
    }

    /**
     * 法線 normalDeg の向きの辺が、直線にどれだけ長く接するか（0〜1）。
     * 辺の方向に1画素ごとの外側の端を取り、±maxTilt°まで傾けた直線に tol 以内で続けて接する長さ ÷ 辺の長さ。
     */
    private static double flatness(double[] bx, double[] by, int nb, double normalDeg, double maxTilt) {
        return flatness(bx, by, nb, normalDeg, maxTilt, null);
    }

    /**
     * sagOut[0] に、接している区間のふくらみ ÷ tol を返す（直線なら0付近、円弧なら1付近）。
     * 円弧でも tol の幅だけは直線に接して見えるので、ふくらみで本当の平面と見分ける。
     */
    private static double flatness(double[] bx, double[] by, int nb, double normalDeg, double maxTilt, double[] sagOut) {
        if (sagOut != null) sagOut[0] = 1;
        if (nb < 4) return 0;
        double a = Math.toRadians(normalDeg);
        double nx = Math.cos(a), ny = Math.sin(a), tx = -ny, ty = nx;
        double umin = 1e9, umax = -1e9;
        for (int i = 0; i < nb; i++) {
            double u = bx[i] * tx + by[i] * ty;
            if (u < umin) umin = u;
            if (u > umax) umax = u;
        }
        int n = (int) Math.ceil(umax - umin) + 1;
        if (n < 4) return 0;
        double[] pm = new double[n];
        java.util.Arrays.fill(pm, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < nb; i++) {
            double sv = bx[i] * nx + by[i] * ny, u = bx[i] * tx + by[i] * ty;
            int bi = (int) (u - umin);
            if (sv > pm[bi]) pm[bi] = sv;
        }
        // 斜めの辺では1画素の区間に外側の画素が入らないことがあるので、両隣との最大で埋める
        double[] q = new double[n];
        for (int i = 0; i < n; i++) {
            double v = pm[i];
            if (i > 0) v = Math.max(v, pm[i - 1]);
            if (i < n - 1) v = Math.max(v, pm[i + 1]);
            q[i] = v;
        }
        pm = q;
        double tol = 0.8 + 0.004 * n;
        int best = 0, bestEnd = 0;
        double bestTn = 0;
        for (double d = -maxTilt; d <= maxTilt + 0.001; d += 0.5) {
            double tn = Math.tan(Math.toRadians(d));
            double qmax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) qmax = Math.max(qmax, pm[i] - i * tn);
            int run = 0;
            for (int i = 0; i < n; i++) {
                if (pm[i] - i * tn >= qmax - tol) {
                    run++;
                    if (run > best) {
                        best = run;
                        bestEnd = i;
                        bestTn = tn;
                    }
                } else {
                    run = 0;
                }
            }
        }
        if (sagOut != null && best >= 6) {
            int i0 = bestEnd - best + 1;
            double[] t = new double[best], y = new double[best];
            for (int j = 0; j < best; j++) {
                int i = i0 + j;
                t[j] = best == 1 ? 0 : (2.0 * j / (best - 1) - 1);
                y[j] = pm[i] - i * bestTn;
            }
            sagOut[0] = Math.max(0, quadBulge(t, y, best)) / tol;
        }
        return (double) best / n;
    }

    /** y = a t^2 + b t + c を当てはめ、外へのふくらみ（-a）を返す */
    private static double quadBulge(double[] t, double[] s, int n) {
        double s0 = n, s1 = 0, s2 = 0, s3 = 0, s4 = 0, y0 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double x = t[i], x2 = x * x;
            s1 += x;
            s2 += x2;
            s3 += x2 * x;
            s4 += x2 * x2;
            y0 += s[i];
            y1 += s[i] * x;
            y2 += s[i] * x2;
        }
        double[][] m = {{s4, s3, s2, y2}, {s3, s2, s1, y1}, {s2, s1, s0, y0}};
        for (int c = 0; c < 3; c++) {
            int piv = c;
            for (int k = c + 1; k < 3; k++) if (Math.abs(m[k][c]) > Math.abs(m[piv][c])) piv = k;
            double[] tmp = m[c];
            m[c] = m[piv];
            m[piv] = tmp;
            if (Math.abs(m[c][c]) < 1e-12) return 0;
            for (int k = 0; k < 3; k++) {
                if (k == c) continue;
                double f = m[k][c] / m[c][c];
                for (int j = c; j < 4; j++) m[k][j] -= f * m[c][j];
            }
        }
        return -m[0][3] / m[0][0];
    }

    private static int argmax(double[] a) {
        int b = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[b]) b = i;
        return b;
    }

    private static int argmin(double[] a) {
        int b = 0;
        for (int i = 1; i < a.length; i++) if (a[i] < a[b]) b = i;
        return b;
    }

    /** 平ら面ビット：下=1 上=2 左=4 右=8 */
    public static int dirBit(int dir) {
        switch (dir) {
            case DIR_DOWN: return 1;
            case DIR_UP: return 2;
            case DIR_LEFT: return 4;
            case DIR_RIGHT: return 8;
            default: return 0;
        }
    }

    public static int opposite(int dir) {
        switch (dir) {
            case DIR_DOWN: return DIR_UP;
            case DIR_UP: return DIR_DOWN;
            case DIR_LEFT: return DIR_RIGHT;
            case DIR_RIGHT: return DIR_LEFT;
            default: return DIR_NONE;
        }
    }

    /** 画面表示用の説明（例「背板 平らな面:下」「食パン形 丸い面:上」「平行板 平ら面:上下」） */
    public static String describe(int shape, int dir) {
        switch (shape) {
            case SHAPE_SLAB: return "背板 平らな面:" + dirName(dir);
            case SHAPE_BREAD: return "食パン形 丸い面:" + dirName(opposite(dir));
            case SHAPE_PARALLEL: return "平行板 平ら面:" + (dir == DIR_DOWN || dir == DIR_UP ? "上下" : "左右");
            case SHAPE_SQUARE: return "四角（全面平ら）";
            default: return "形不明";
        }
    }

    /** 外向き法線の角度（右0°・反時計回り＋）から上下左右 */
    public static int directionOf(double normalDeg) {
        double a = ((normalDeg % 360) + 360) % 360;
        if (a >= 45 && a < 135) return DIR_UP;
        if (a >= 135 && a < 225) return DIR_LEFT;
        if (a >= 225 && a < 315) return DIR_DOWN;
        return DIR_RIGHT;
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
