package jp.yuta.woodangle;

import android.os.SystemClock;

/**
 * アプリ全体で1つ：スマホ内デバイスメモリ、待受サーバ、判別の受け渡し、監視スレッド。
 */
final class Core {

    static final DeviceMemory mem = new DeviceMemory();
    static final Judge judge = new Judge(mem);

    private static SlmpServer server;
    private static int port;
    private static String plcIp = "";
    private static Thread poller;

    private Core() {
    }

    static synchronized SlmpServer server() {
        return server;
    }

    static synchronized boolean isRunning() {
        return server != null;
    }

    /** 待受開始（呼び出し側で別スレッドから呼ぶ）。失敗時は例外 */
    static void start(int port, String plcIp) throws Exception {
        synchronized (Core.class) {
            if (server != null) return;
        }
        SlmpServer s = new SlmpServer(mem, port, plcIp);
        s.start();
        synchronized (Core.class) {
            server = s;
            Core.port = port;
            Core.plcIp = plcIp == null ? "" : plcIp;
        }
        startPoller();
    }

    static void stop() {
        SlmpServer s;
        synchronized (Core.class) {
            s = server;
            server = null;
        }
        if (s != null) new Thread(s::stop, "slmp-stop").start();
    }

    /** 待受中で設定が変わっていれば、止めてから開き直す（別スレッドから呼ぶ） */
    static void restartIfChanged(int newPort, String newIp) throws Exception {
        SlmpServer old;
        synchronized (Core.class) {
            if (server == null || (port == newPort && plcIp.equals(newIp))) return;
            old = server;
            server = null;
        }
        old.stop();
        start(newPort, newIp);
    }

    /** 直近 ms 以内にPLCから要求があれば true */
    static boolean isFresh(long ms) {
        SlmpServer s = server();
        return s != null && s.lastRequestMillis != 0 && System.currentTimeMillis() - s.lastRequestMillis <= ms;
    }

    /** 要求の監視（10ms周期）とハートビート（1秒周期） */
    private static synchronized void startPoller() {
        if (poller != null) return;
        poller = new Thread(() -> {
            long nextBeat = SystemClock.elapsedRealtime() + 1000;
            while (true) {
                long now = SystemClock.elapsedRealtime();
                try {
                    judge.poll(now);
                    if (now >= nextBeat) {
                        judge.tickHeartbeat();
                        nextBeat = now + 1000;
                    }
                } catch (RuntimeException ignored) {
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "judge-poll");
        poller.setDaemon(true);
        poller.start();
    }
}
