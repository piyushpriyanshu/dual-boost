package com.dualboost.app.core;

/**
 * JNI bridge to the bundled hev-socks5-tunnel engine (tun2socks).
 * The native library registers these methods on this exact class name (see app/build.gradle.kts cFlags).
 */
public final class TunnelNative {
    private TunnelNative() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** [txPackets, txBytes, rxPackets, rxBytes] */
    public static native long[] TProxyGetStats();
}
