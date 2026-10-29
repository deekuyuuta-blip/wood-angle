package jp.yuta.woodangle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * カメラ画像（判別に使う縮小画像そのもの）と判別結果を描く。
 *  黄色の枠：判別範囲、緑：木材と判定した画素（輪郭）／強いエッジ（エッジ）
 *  赤い線：測った軸、水色の線：基準角度（ゼロ合わせ）
 *  背板モードの矢印：平らな面の向き（下=緑、それ以外=橙）
 * 範囲設定モードでは、指でなぞった四角を新しい判別範囲にする。
 */
final class FrameView extends View {

    interface RoiListener {
        void onRoiChanged(float l, float t, float r, float b);
    }

    private Bitmap bmp;
    private int[] px;
    private int fw, fh;
    private AngleAnalyzer.Result result;
    private double offsetDeg;
    private boolean roiEdit;
    private RoiListener roiListener;
    private float dragX0, dragY0, dragX1, dragY1;
    private boolean dragging;
    private String note = "カメラ準備中…";

    private final RectF dst = new RectF();
    private final Paint pImg = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pRoi = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRef = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDrag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pArrow = new Paint(Paint.ANTI_ALIAS_FLAG);

    FrameView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        float d = c.getResources().getDisplayMetrics().density;
        pRoi.setStyle(Paint.Style.STROKE);
        pRoi.setColor(0xFFFFD600);
        pRoi.setStrokeWidth(2 * d);
        pRoi.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
        pAxis.setColor(0xFFFF1744);
        pAxis.setStrokeWidth(4 * d);
        pAxis.setStrokeCap(Paint.Cap.ROUND);
        pRef.setColor(0xFF00E5FF);
        pRef.setStrokeWidth(2 * d);
        pRef.setPathEffect(new DashPathEffect(new float[]{10 * d, 6 * d}, 0));
        pText.setColor(Color.WHITE);
        pText.setTextSize(16 * d);
        pText.setShadowLayer(3 * d, 0, 0, Color.BLACK);
        pArrow.setStrokeWidth(6 * d);
        pArrow.setStrokeCap(Paint.Cap.ROUND);
        pDrag.setStyle(Paint.Style.STROKE);
        pDrag.setColor(0xFF76FF03);
        pDrag.setStrokeWidth(3 * d);
    }

    void setRoiListener(RoiListener l) {
        roiListener = l;
    }

    void setRoiEdit(boolean on) {
        roiEdit = on;
        dragging = false;
        invalidate();
    }

    boolean isRoiEdit() {
        return roiEdit;
    }

    void setOffset(double deg) {
        offsetDeg = deg;
        invalidate();
    }

    void setNote(String s) {
        note = s;
        invalidate();
    }

    /** メインスレッドから呼ぶ */
    void setFrame(byte[] gray, int w, int h, AngleAnalyzer.Result r) {
        if (bmp == null || fw != w || fh != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            px = new int[w * h];
            fw = w;
            fh = h;
        }
        byte[] m = r == null ? null : r.mask;
        for (int i = 0; i < px.length; i++) {
            int v = gray[i] & 0xFF;
            if (m != null && m[i] != 0) {
                px[i] = 0xFF000000 | ((v * 6 / 10) << 16) | ((Math.min(255, v * 6 / 10 + 100)) << 8) | (v * 6 / 10);
            } else {
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        result = r;
        note = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) {
            if (note != null) c.drawText(note, 20, getHeight() / 2f, pText);
            return;
        }
        float s = Math.min(getWidth() / (float) fw, getHeight() / (float) fh);
        float ox = (getWidth() - fw * s) / 2, oy = (getHeight() - fh * s) / 2;
        dst.set(ox, oy, ox + fw * s, oy + fh * s);
        c.drawBitmap(bmp, null, dst, pImg);
        AngleAnalyzer.Result r = result;
        if (r != null) {
            c.drawRect(ox + r.roiX0 * s, oy + r.roiY0 * s, ox + r.roiX1 * s, oy + r.roiY1 * s, pRoi);
            float cx = ox + (float) (r.cx * fw) * s, cy = oy + (float) (r.cy * fh) * s;
            float len = (float) Math.max(r.halfLength, 20) * s;
            if (offsetDeg != 0) line(c, cx, cy, offsetDeg, len * 1.15f, pRef);
            if (r.ok && r.direction != AngleAnalyzer.DIR_NONE) {
                // 背板：平らな面の向きを矢印で
                int dir = AngleAnalyzer.directionOf(r.flatNormalDeg - offsetDeg);
                pArrow.setColor(dir == AngleAnalyzer.DIR_DOWN ? 0xFF00E676 : 0xFFFF9100);
                double t = Math.toRadians(r.flatNormalDeg);
                float ex = cx + (float) (Math.cos(t) * len * 0.9), ey = cy - (float) (Math.sin(t) * len * 0.9);
                c.drawLine(cx, cy, ex, ey, pArrow);
                for (int k = -1; k <= 1; k += 2) {
                    double h = t + Math.PI + k * 0.5;
                    c.drawLine(ex, ey, ex + (float) (Math.cos(h) * len * 0.3), ey - (float) (Math.sin(h) * len * 0.3), pArrow);
                }
                line(c, cx, cy, r.angleDeg, len, pAxis);
                c.drawText("平らな面：" + AngleAnalyzer.dirName(dir) + (dir == AngleAnalyzer.DIR_DOWN ? "（OK）" : ""),
                        ox + 12, oy + fh * s - 12, pText);
            } else if (r.ok) {
                line(c, cx, cy, r.angleDeg, len, pAxis);
                c.drawCircle(cx, cy, pAxis.getStrokeWidth() * 1.6f, pAxis);
            }
        }
        if (roiEdit) {
            c.drawText("範囲設定：判別させたい範囲を指でなぞってください", ox + 12, oy + pText.getTextSize() + 8, pText);
            if (dragging) {
                c.drawRect(Math.min(dragX0, dragX1), Math.min(dragY0, dragY1),
                        Math.max(dragX0, dragX1), Math.max(dragY0, dragY1), pDrag);
            }
        }
    }

    private static void line(Canvas c, float cx, float cy, double deg, float half, Paint p) {
        double t = Math.toRadians(deg);
        float dx = (float) (Math.cos(t) * half), dy = (float) (-Math.sin(t) * half);
        c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!roiEdit || bmp == null) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragX0 = dragX1 = e.getX();
                dragY0 = dragY1 = e.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                dragX1 = e.getX();
                dragY1 = e.getY();
                break;
            case MotionEvent.ACTION_UP:
                dragging = false;
                float l = toFx(Math.min(dragX0, dragX1)), r = toFx(Math.max(dragX0, dragX1));
                float t = toFy(Math.min(dragY0, dragY1)), b = toFy(Math.max(dragY0, dragY1));
                if (r - l > 0.05f && b - t > 0.05f && roiListener != null) roiListener.onRoiChanged(l, t, r, b);
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        invalidate();
        return true;
    }

    private float toFx(float x) {
        return Math.max(0, Math.min(1, (x - dst.left) / dst.width()));
    }

    private float toFy(float y) {
        return Math.max(0, Math.min(1, (y - dst.top) / dst.height()));
    }
}
