package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import hev.htproxy.TProxyService;

/**
 * hev-socks5-tunnel: reads IP packets from the VPN's TUN file descriptor and relays every TCP
 * connection and UDP flow to the loopback SOCKS5 inbound of Xray. The native library is a build
 * artefact ({@code native/build-cores.sh}); a missing one is reported, never thrown at start-up.
 */
final class HevTunnel {
    @Nullable private static Throwable sLoadError;
    private static boolean sTried;
    private static boolean sOk;

    private HevTunnel() {
    }

    static synchronized boolean isAvailable() {
        if (!sTried) {
            sTried = true;
            try {
                // Touching the class runs its static initializer, which loads the .so.
                TProxyService.TProxyIsRunning();
                sOk = true;
            } catch (Throwable t) { // UnsatisfiedLinkError / ExceptionInInitializerError / NoClassDefFoundError
                sLoadError = t;
            }
        }
        return sOk;
    }

    @Nullable
    static Throwable loadError() {
        isAvailable();
        return sLoadError;
    }

    /** The YAML hev reads: tunnel addressing and where the SOCKS5 server is. */
    @NonNull
    static String config(int socksPort, @Nullable String user, @Nullable String pass, boolean ipv6) {
        StringBuilder y = new StringBuilder();
        y.append("tunnel:\n");
        y.append("  mtu: ").append(TUN_MTU).append('\n');
        y.append("  ipv4: ").append(TUN_ADDRESS_V4).append('\n');
        if (ipv6) {
            y.append("  ipv6: '").append(TUN_ADDRESS_V6).append("'\n");
        }
        y.append("socks5:\n");
        y.append("  port: ").append(socksPort).append('\n');
        y.append("  address: 127.0.0.1\n");
        y.append("  udp: 'udp'\n");
        if (user != null && !user.isEmpty() && pass != null) {
            y.append("  username: '").append(user).append("'\n");
            y.append("  password: '").append(pass).append("'\n");
        }
        y.append("misc:\n");
        y.append("  connect-timeout: 8000\n");
        y.append("  tcp-read-write-timeout: 120000\n");
        y.append("  udp-read-write-timeout: 30000\n");
        y.append("  log-file: null\n");
        y.append("  log-level: warn\n");
        return y.toString();
    }

    static final int TUN_MTU = 8500;
    static final String TUN_ADDRESS_V4 = "198.18.0.1";
    static final String TUN_ADDRESS_V6 = "fc00::1";

    static boolean start(@NonNull String configPath, int tunFd) {
        return isAvailable() && TProxyService.TProxyStartService(configPath, tunFd);
    }

    static void stop() {
        if (isAvailable()) {
            TProxyService.TProxyStopService();
        }
    }

    static boolean isRunning() {
        return isAvailable() && TProxyService.TProxyIsRunning();
    }

    /** {tx packets, tx bytes, rx packets, rx bytes}, or null. */
    @Nullable
    static long[] stats() {
        return isAvailable() ? TProxyService.TProxyGetStats() : null;
    }
}
