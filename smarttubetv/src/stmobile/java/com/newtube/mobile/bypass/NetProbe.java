package com.newtube.mobile.bypass;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;

/** Small blocking network checks. Never call these on the main thread. */
final class NetProbe {
    private static final String YOUTUBE_204 = "https://www.youtube.com/generate_204";

    private NetProbe() {
    }

    /**
     * Whether YouTube answers a plain HTTPS request without any proxy. A throttled connection that
     * still answers is "reachable" here; the region rule is what catches throttling.
     */
    static boolean youtubeReachableDirectly() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(YOUTUBE_204).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** True when a VPN (this app's or another) is the active network. */
    static boolean vpnActive(@NonNull Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    /**
     * TCP connect time to host:port in ms, or -1. While this app's VPN is up an ordinary socket
     * would be routed into the tunnel, so the socket is made on the underlying non-VPN network.
     */
    static long tcpPing(@NonNull Context context, @NonNull String host, int port) {
        Socket socket = null;
        try {
            socket = underlyingSocket(context);
            long t0 = System.nanoTime();
            socket.connect(new InetSocketAddress(host, port), 4000);
            return Math.max(1, (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    @NonNull
    private static Socket underlyingSocket(@NonNull Context context) throws IOException {
        Network network = underlyingNetwork(context);
        if (network != null) {
            Socket s = new Socket();
            network.bindSocket(s);
            return s;
        }
        return new Socket();
    }

    @Nullable
    private static Network underlyingNetwork(@NonNull Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return n;
            }
        }
        return null;
    }
}
