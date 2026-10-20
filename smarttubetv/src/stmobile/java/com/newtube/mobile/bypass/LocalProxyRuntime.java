package com.newtube.mobile.bypass;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * Local-proxy mode: Xray runs in this process with an unauthenticated SOCKS5 inbound on a random
 * loopback port and the JVM's default proxy points at it. No VPN permission and nothing to wait for,
 * but only traffic that honours Java's proxy settings (OkHttp: the API calls, images, the OkHttp
 * player source) goes through it. Cronet and native sockets do not, so while it runs the player is
 * switched to its OkHttp data source and put back afterwards.
 */
final class LocalProxyRuntime {
    private static final String[] PROPS = {"socksProxyHost", "socksProxyPort", "http.proxyHost", "http.proxyPort",
            "https.proxyHost", "https.proxyPort"};

    private static boolean sActive;
    @Nullable private static String[] sSavedProps;
    private static int sSavedDataSource = -1;

    private LocalProxyRuntime() {
    }

    static synchronized boolean isActive() {
        return sActive;
    }

    /** Starts Xray and points the process at it. Throws with a user-readable message on failure. */
    static synchronized void start(@NonNull Context context, @NonNull ProxyProfile profile,
                                   @NonNull BypassSettings settings) throws Exception {
        if (sActive) {
            stop(context);
        }
        if (!XrayCore.isAvailable()) {
            throw new IllegalStateException(context.getString(com.liskovsoft.smartyoutubetv2.tv.R.string.bypass_error_core_missing));
        }
        BypassController.Runtime rt = BypassController.prepareRuntime(context, profile, settings, false);
        XrayCore.setProtector(null); // nothing to protect from: there is no VPN in this mode
        XrayCore.start(rt.xrayJson);

        sSavedProps = new String[PROPS.length];
        for (int i = 0; i < PROPS.length; i++) {
            sSavedProps[i] = System.getProperty(PROPS[i]);
        }
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.setProperty("socksProxyHost", "127.0.0.1");
        System.setProperty("socksProxyPort", String.valueOf(rt.port));

        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);
        sSavedDataSource = tweaks.getPlayerDataSource();
        tweaks.setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
        OkHttpManager.unhold(); // rebuild the shared client so it picks the proxy up
        sActive = true;
    }

    static synchronized void stop(@NonNull Context context) {
        if (!sActive) {
            return;
        }
        sActive = false;
        if (sSavedProps != null) {
            for (int i = 0; i < PROPS.length; i++) {
                if (sSavedProps[i] == null) {
                    System.clearProperty(PROPS[i]);
                } else {
                    System.setProperty(PROPS[i], sSavedProps[i]);
                }
            }
            sSavedProps = null;
        }
        if (sSavedDataSource >= 0) {
            PlayerTweaksData.instance(context).setPlayerDataSource(sSavedDataSource);
            sSavedDataSource = -1;
        }
        try {
            XrayCore.stop();
        } catch (Exception ignored) {
            // already stopped
        }
        OkHttpManager.unhold();
    }
}
