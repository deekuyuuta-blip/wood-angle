package jp.yuta.woodangle;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * 背面カメラ（Camera2）から連続で画像を取り、約320x240のグレースケールにして渡す。
 * 画面（横向き固定）と同じ向きになるよう回転してから渡す。
 * コールバックはカメラ用のスレッドで呼ばれる。
 */
final class CameraSource {

    interface Callback {
        /** gray: 1画素1バイト、atMs: SystemClock.elapsedRealtime() で画像が届いた時刻 */
        void onFrame(byte[] gray, int w, int h, long atMs);

        void onCameraError(String message);
    }

    private static final int TARGET_W = 320;

    private final Context ctx;
    private final Callback cb;
    private final int displayRotationDeg;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean torch;
    private boolean hasFlash;
    private boolean continuousAf;
    private int rotation;      // 画像を時計回りに回す角度
    private byte[] rowBuf;
    private volatile boolean closed;

    CameraSource(Context ctx, int displayRotationDeg, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.displayRotationDeg = displayRotationDeg;
        this.cb = cb;
    }

    boolean hasFlash() {
        return hasFlash;
    }

    void setTorch(boolean on) {
        torch = on;
        Handler h = bg;
        if (h != null) h.post(this::repeat);
    }

    /** カメラの権限は呼び出し側で確認しておくこと */
    void open(boolean torchOn) {
        torch = torchOn;
        closed = false;
        thread = new HandlerThread("camera");
        thread.start();
        bg = new Handler(thread.getLooper());
        bg.post(this::openOnBg);
    }

    private void openOnBg() {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            CameraCharacteristics ch = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(c);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = c;
                    ch = cc;
                    break;
                }
            }
            if (id == null && cm.getCameraIdList().length > 0) {
                id = cm.getCameraIdList()[0];
                ch = cm.getCameraCharacteristics(id);
            }
            if (id == null) {
                cb.onCameraError("カメラが見つかりません");
                return;
            }
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            rotation = ((so == null ? 90 : so) - displayRotationDeg + 360) % 360;
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            continuousAf = false;
            if (afModes != null) {
                for (int m : afModes) if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) continuousAf = true;
            }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (closed) {
                        d.close();
                        return;
                    }
                    device = d;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                    if (!closed) cb.onCameraError("カメラが切断されました（他のアプリが使用中？）");
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    device = null;
                    if (!closed) cb.onCameraError("カメラを開けません（エラー " + error + "）");
                }
            }, bg);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            cb.onCameraError("カメラを開けません: " + e.getMessage());
        }
    }

    /** 640x480前後の4:3を選ぶ（なければ幅640以上でいちばん小さいもの） */
    private static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() == 640 && s.getHeight() == 480) return s;
        }
        for (Size s : sizes) {
            if (s.getWidth() < 640 || s.getWidth() > 1920) continue;
            if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        best = sizes[0];
        for (Size s : sizes) if (s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (closed) {
                                s.close();
                                return;
                            }
                            session = s;
                            repeat();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            cb.onCameraError("カメラの設定に失敗しました");
                        }
                    }, bg);
        } catch (CameraAccessException | IllegalStateException e) {
            cb.onCameraError("カメラの設定に失敗しました: " + e.getMessage());
        }
    }

    private void repeat() {
        if (session == null || device == null || closed) return;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (continuousAf) b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (hasFlash) b.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(b.build(), null, bg);
        } catch (CameraAccessException | IllegalStateException e) {
            if (!closed) cb.onCameraError("撮影を開始できません: " + e.getMessage());
        }
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (img == null) return;
        long at = SystemClock.elapsedRealtime();
        try {
            Image.Plane p = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight();
            int step = Math.max(1, w / TARGET_W);
            int sw = w / step, sh = h / step;
            byte[] small = downsample(p.getBuffer(), p.getRowStride(), p.getPixelStride(), w, h, step, sw, sh);
            boolean swap = rotation == 90 || rotation == 270;
            int ow = swap ? sh : sw, oh = swap ? sw : sh;
            byte[] out = rotation == 0 ? small : rotate(small, sw, sh, rotation);
            if (!closed) cb.onFrame(out, ow, oh, at);
        } finally {
            img.close();
        }
    }

    /** 輝度(Y)を step×step の平均で縮小 */
    private byte[] downsample(ByteBuffer buf, int rowStride, int pixStride, int w, int h, int step, int sw, int sh) {
        int need = (w - 1) * pixStride + 1;
        if (rowBuf == null || rowBuf.length < need) rowBuf = new byte[need];
        int[] acc = new int[sw];
        byte[] out = new byte[sw * sh];
        int div = step * step;
        for (int oy = 0; oy < sh; oy++) {
            java.util.Arrays.fill(acc, 0);
            for (int k = 0; k < step; k++) {
                int y = oy * step + k;
                buf.position(y * rowStride);
                buf.get(rowBuf, 0, need);
                for (int ox = 0; ox < sw; ox++) {
                    int base = ox * step * pixStride;
                    int s = 0;
                    for (int j = 0; j < step; j++) s += rowBuf[base + j * pixStride] & 0xFF;
                    acc[ox] += s;
                }
            }
            for (int ox = 0; ox < sw; ox++) out[oy * sw + ox] = (byte) (acc[ox] / div);
        }
        return out;
    }

    /** 時計回りに deg 回す */
    private static byte[] rotate(byte[] src, int w, int h, int deg) {
        byte[] d = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                byte v = src[y * w + x];
                switch (deg) {
                    case 90: d[x * h + (h - 1 - y)] = v; break;            // 新しい幅 = h
                    case 180: d[(h - 1 - y) * w + (w - 1 - x)] = v; break;
                    default: d[(w - 1 - x) * h + y] = v; break;            // 270
                }
            }
        }
        return d;
    }

    void close() {
        closed = true;
        Handler h = bg;
        if (h == null) return;
        h.post(() -> {
            try {
                if (session != null) session.close();
            } catch (Exception ignored) {
            }
            session = null;
            if (device != null) device.close();
            device = null;
            if (reader != null) reader.close();
            reader = null;
        });
        thread.quitSafely();
        bg = null;
    }
}
