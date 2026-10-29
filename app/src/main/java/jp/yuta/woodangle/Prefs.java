package jp.yuta.woodangle;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定の保存 */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("woodangle", Context.MODE_PRIVATE);
    }

    // ---- 通信 ----
    String plcIp() { return sp.getString("plcIp", ""); }
    int port() { return sp.getInt("port", 5000); }
    boolean autoStart() { return sp.getBoolean("autoStart", true); }
    String requestDevice() { return sp.getString("reqDev", "D0"); }
    String resultDevice() { return sp.getString("resDev", "D100"); }

    // ---- 判別 ----
    int mode() { return sp.getInt("mode", AngleAnalyzer.MODE_CONTOUR); }
    int polarity() { return sp.getInt("polarity", AngleAnalyzer.POLARITY_AUTO); }
    float roiL() { return sp.getFloat("roiL", 0.05f); }
    float roiT() { return sp.getFloat("roiT", 0.05f); }
    float roiR() { return sp.getFloat("roiR", 0.95f); }
    float roiB() { return sp.getFloat("roiB", 0.95f); }
    int minArea() { return sp.getInt("minArea", 2); }
    int minContrast() { return sp.getInt("minContrast", 20); }
    int average() { return sp.getInt("average", 3); }
    int waitMs() { return sp.getInt("waitMs", 150); }
    int minConfidence() { return sp.getInt("minConf", 30); }
    float offset() { return sp.getFloat("offset", 0f); }
    boolean torch() { return sp.getBoolean("torch", false); }

    SharedPreferences.Editor edit() {
        return sp.edit();
    }

    void saveRoi(float l, float t, float r, float b) {
        sp.edit().putFloat("roiL", l).putFloat("roiT", t).putFloat("roiR", r).putFloat("roiB", b).apply();
    }

    void saveOffset(float v) { sp.edit().putFloat("offset", v).apply(); }

    void saveTorch(boolean v) { sp.edit().putBoolean("torch", v).apply(); }

    AngleAnalyzer.Params analyzerParams() {
        AngleAnalyzer.Params p = new AngleAnalyzer.Params();
        p.mode = mode();
        p.polarity = polarity();
        p.roiLeft = roiL();
        p.roiTop = roiT();
        p.roiRight = roiR();
        p.roiBottom = roiB();
        p.minAreaPercent = minArea();
        p.minContrast = minContrast();
        return p;
    }

    Judge.Config judgeConfig() {
        Judge.Config c = new Judge.Config();
        try {
            c.request = Device.parse(requestDevice());
        } catch (IllegalArgumentException ignored) {
        }
        try {
            c.result = Device.parse(resultDevice());
        } catch (IllegalArgumentException ignored) {
        }
        c.waitMs = waitMs();
        c.average = average();
        c.minConfidence = minConfidence();
        c.offsetDeg = offset();
        return c;
    }
}
