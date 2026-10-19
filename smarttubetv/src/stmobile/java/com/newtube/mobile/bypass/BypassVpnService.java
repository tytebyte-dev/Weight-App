package com.newtube.mobile.bypass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The tunnel. Per-app mode lists only this app as allowed, so nothing else on the phone is touched;
 * device mode carries everything except this app. The chain is
 * {@code app traffic -> TUN -> hev-socks5-tunnel -> 127.0.0.1 SOCKS5 -> Xray-core -> profile}.
 * Xray lives in this same process, so its own sockets are {@code protect()}ed out of the VPN, or they
 * would be routed straight back into it.
 */
public class BypassVpnService extends VpnService {
    static final String ACTION_START = "com.newtube.mobile.bypass.START";
    static final String ACTION_STOP = "com.newtube.mobile.bypass.STOP";

    private static final String CHANNEL = "bypass";
    private static final int NOTIFICATION_ID = 7421;

    @Nullable private ParcelFileDescriptor mTun;
    private Thread mWorker;
    private boolean mStopping;

    static void start(@NonNull Context context) {
        Intent intent = new Intent(context, BypassVpnService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(@NonNull Context context) {
        context.startService(new Intent(context, BypassVpnService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            BypassController.instance(this).onVpnStopRequested();
            shutdown(true);
            return START_NOT_STICKY;
        }
        // START, or the system recreating the service: always (re)start the chain.
        goForeground();
        synchronized (this) {
            if (mWorker != null && mWorker.isAlive()) {
                return START_NOT_STICKY;
            }
            mStopping = false;
            mWorker = new Thread(this::runTunnel, "bypass-start");
            mWorker.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        // The user turned the VPN off (system dialog / another VPN took over).
        BypassController.instance(this).onVpnRevoked();
        shutdown(true);
    }

    @Override
    public void onDestroy() {
        shutdown(false);
        super.onDestroy();
    }

    private void runTunnel() {
        BypassController controller = BypassController.instance(this);
        BypassSettings settings = BypassSettings.instance(this);
        controller.onVpnStarting();
        try {
            if (!XrayCore.isAvailable() || !HevTunnel.isAvailable()) {
                throw new IllegalStateException(getString(R.string.bypass_error_core_missing));
            }
            ProxyProfile profile = ProfileStore.instance(this).selected(settings);
            BypassController.Runtime rt = BypassController.prepareRuntime(this, profile, settings, true);

            XrayCore.setProtector(this::protect);
            XrayCore.start(rt.xrayJson);

            boolean deviceWide = settings.mode() == BypassSettings.MODE_DEVICE_VPN;
            Builder b = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(HevTunnel.TUN_MTU)
                    .addAddress(HevTunnel.TUN_ADDRESS_V4, 32)
                    .addDnsServer("1.1.1.1");
            for (String route : LanRoutes.V4) {
                String[] p = route.split("/");
                b.addRoute(p[0], Integer.parseInt(p[1]));
            }
            if (settings.ipv6()) {
                b.addAddress(HevTunnel.TUN_ADDRESS_V6, 128).addDnsServer("2606:4700:4700::1111");
                for (String route : LanRoutes.V6) {
                    String[] p = route.split("/");
                    b.addRoute(p[0], Integer.parseInt(p[1]));
                }
            }
            try {
                if (deviceWide) {
                    b.addDisallowedApplication(getPackageName());
                } else {
                    b.addAllowedApplication(getPackageName());
                }
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalStateException(e);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                b.setMetered(false);
            }
            b.setBlocking(false);

            ParcelFileDescriptor tun = b.establish();
            if (tun == null) {
                throw new IllegalStateException(getString(R.string.bypass_error_no_permission));
            }
            synchronized (this) {
                if (mStopping) {
                    closeQuietly(tun);
                    return;
                }
                mTun = tun;
            }

            File conf = new File(getCacheDir(), "hev-socks5-tunnel.yml");
            try (FileOutputStream out = new FileOutputStream(conf)) {
                out.write(HevTunnel.config(rt.port, rt.user, rt.pass, settings.ipv6()).getBytes(StandardCharsets.UTF_8));
            }
            if (!HevTunnel.start(conf.getAbsolutePath(), tun.getFd())) {
                throw new IllegalStateException(getString(R.string.bypass_error_tunnel));
            }
            // Credentials are in the process memory and Xray's config only; do not leave them on disk.
            //noinspection ResultOfMethodCallIgnored
            conf.delete();

            controller.onVpnRunning(profile.name);
            updateNotification(profile.name, deviceWide);
        } catch (Throwable t) {
            controller.onVpnFailed(describe(t));
            shutdown(true);
        }
    }

    private String describe(Throwable t) {
        String m = t.getMessage();
        return m != null && !m.isEmpty() ? m : t.getClass().getSimpleName();
    }

    private void shutdown(boolean stopService) {
        ParcelFileDescriptor tun;
        synchronized (this) {
            mStopping = true;
            tun = mTun;
            mTun = null;
        }
        HevTunnel.stop();
        try {
            if (XrayCore.isAvailable()) {
                XrayCore.stop();
                XrayCore.setProtector(null);
            }
        } catch (Exception ignored) {
            // already stopped
        }
        closeQuietly(tun);
        BypassController.instance(this).onVpnStopped();
        if (stopService) {
            stopForeground(true);
            stopSelf();
        }
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor fd) {
        if (fd != null) {
            try {
                fd.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    // ---- notification --------------------------------------------------------------------------

    private void goForeground() {
        startForeground(NOTIFICATION_ID, notification(getString(R.string.bypass_notif_connecting)));
    }

    private void updateNotification(String profileName, boolean deviceWide) {
        String text = getString(deviceWide ? R.string.bypass_notif_device : R.string.bypass_notif_app, profileName);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification(text));
        }
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.bypass_notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, BypassVpnService.class).setAction(ACTION_STOP), piFlags);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        n.setSmallIcon(R.drawable.ic_settings_bypass)
                .setContentTitle(getString(R.string.bypass_notif_title))
                .setContentText(text)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(0, getString(R.string.bypass_notif_stop), stop).build());
        return n.build();
    }
}
