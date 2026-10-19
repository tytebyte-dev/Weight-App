package com.newtube.mobile.bypass;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.proxy.StartupGate;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one place that starts, stops and reports the censorship bypass. The VPN service and the local
 * proxy report their progress here; the settings screens read {@link #state()} and listen for changes.
 *
 * <p>Launch behaviour (policy "Auto"): in a region on the block list the tunnel is brought up before
 * the first network request ({@link StartupGate}); elsewhere a background probe starts it only if
 * YouTube cannot be reached directly. Without VPN consent the app does not stop to ask: it starts the
 * local proxy at once and the settings offer the one-time permission.</p>
 */
public final class BypassController {
    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    /** What carries the traffic right now. */
    public enum Via { NONE, VPN, LOCAL }

    public interface Listener {
        /** On the main thread. */
        void onBypassChanged();
    }

    /** A prepared run: Xray's JSON and the loopback SOCKS5 endpoint hev (or the JVM proxy) talks to. */
    static final class Runtime {
        final String xrayJson;
        final int port;
        @Nullable final String user;
        @Nullable final String pass;

        Runtime(String xrayJson, int port, @Nullable String user, @Nullable String pass) {
            this.xrayJson = xrayJson;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }
    }

    private static final long SUBSCRIPTION_STALE_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;

    private static BypassController sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor(r -> new Thread(r, "bypass-io"));
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Long> mPings = new ConcurrentHashMap<>();

    private volatile State mState = State.STOPPED;
    private volatile Via mVia = Via.NONE;
    private volatile String mDetail = "";
    /** The user turned it off this session: automatic start must not bring it back. */
    private volatile boolean mUserStopped;
    @Nullable private Runnable mPendingReady;

    private BypassController(Context context) {
        mContext = context.getApplicationContext();
    }

    @NonNull
    public static synchronized BypassController instance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new BypassController(context);
        }
        return sInstance;
    }

    /** Called once from the Application: lets the first screen wait for the tunnel. */
    public void installStartupGate() {
        StartupGate.setGate(this::hold);
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    @NonNull
    public State state() {
        return mState;
    }

    @NonNull
    public Via via() {
        return mVia;
    }

    /** Profile name while running, the error message after a failure. */
    @NonNull
    public String detail() {
        return mDetail;
    }

    public void addListener(@NonNull Listener l) {
        mListeners.add(l);
    }

    public void removeListener(@NonNull Listener l) {
        mListeners.remove(l);
    }

    private void set(State state, Via via, String detail) {
        mState = state;
        mVia = via;
        mDetail = detail != null ? detail : "";
        notifyChanged();
    }

    private void notifyChanged() {
        mMain.post(() -> {
            for (Listener l : mListeners) {
                l.onBypassChanged();
            }
        });
    }

    /** Last measured TCP connect time in ms, -1 for a failed ping, null if never measured. */
    @Nullable
    public Long ping(@NonNull String profileId) {
        return mPings.get(profileId);
    }

    /** Short status for the settings summary: version of the cores or what is missing. */
    @NonNull
    public String coreStatus() {
        if (!XrayCore.isAvailable()) {
            return mContext.getString(R.string.bypass_error_core_missing);
        }
        String v = XrayCore.version();
        return "Xray " + v;
    }

    // ---------------------------------------------------------------------------------------------
    // Launch
    // ---------------------------------------------------------------------------------------------

    private boolean hold(@NonNull Context context, @NonNull Runnable ready) {
        BypassSettings settings = BypassSettings.instance(mContext);
        int policy = settings.policy();
        if (policy == BypassSettings.POLICY_OFF || mUserStopped || mState == State.RUNNING) {
            return false;
        }
        if (GeneralData.instance(mContext).isProxyEnabled()) {
            return false; // the user's own manual proxy wins
        }
        if (settings.prefs().getLong("start_pending", 0) != 0) {
            // The last automatic start never finished (the process died or hung): do not trap the
            // user on the splash again; they can turn the bypass on by hand in the settings.
            settings.prefs().edit().remove("start_pending").apply();
            set(State.ERROR, Via.NONE, mContext.getString(R.string.bypass_error_tunnel));
            return false;
        }
        scheduleSubscriptionRefresh();

        boolean censored = isCensoredRegion(settings);
        if (policy == BypassSettings.POLICY_ALWAYS || censored) {
            return startAutomatically(ready);
        }
        if (settings.probeDirect()) {
            mIo.execute(() -> {
                if (!NetProbe.vpnActive(mContext) && !NetProbe.youtubeReachableDirectly()) {
                    mMain.post(() -> startAutomatically(null));
                }
            });
        }
        return false;
    }

    /** @return true when the caller must wait for {@code ready}. */
    private boolean startAutomatically(@Nullable Runnable ready) {
        BypassSettings settings = BypassSettings.instance(mContext);
        if (!XrayCore.isAvailable()) {
            set(State.ERROR, Via.NONE, mContext.getString(R.string.bypass_error_core_missing));
            return false;
        }
        // Local proxy: Xray starts off the main thread; the splash waits (at most the gate's limit).
        markPending(true);
        mIo.execute(() -> {
            startLocalNow();
            verifyOrGiveUp();
            markPending(false);
            if (ready != null) {
                ready.run();
            }
        });
        return ready != null;
    }

    private void markPending(boolean pending) {
        android.content.SharedPreferences.Editor e = BypassSettings.instance(mContext).prefs().edit();
        if (pending) {
            e.putLong("start_pending", System.currentTimeMillis());
        } else {
            e.remove("start_pending");
        }
        e.commit();
    }

    /** An automatic tunnel must never leave the app worse off than no tunnel: drop it if it carries nothing. */
    private void verifyOrGiveUp() {
        if (mState != State.RUNNING || NetProbe.youtubeReachableNow()) {
            return;
        }
        mUserStopped = false;
        stopEverything();
        set(State.ERROR, Via.NONE, mContext.getString(R.string.bypass_error_tunnel));
    }

    private boolean isCensoredRegion(BypassSettings settings) {
        String sim = null;
        String net = null;
        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            sim = tm.getSimCountryIso();
            net = tm.getNetworkCountryIso();
        }
        return RegionPolicy.isCensored(RegionPolicy.parseList(settings.regions()), sim, net, Locale.getDefault().getCountry());
    }

    // ---------------------------------------------------------------------------------------------
    // User actions
    // ---------------------------------------------------------------------------------------------

    /** Starts the in-app local proxy. */
    public void connect(@NonNull Context context) {
        mUserStopped = false;
        BypassSettings settings = BypassSettings.instance(mContext);
        if (!XrayCore.isAvailable()) {
            set(State.ERROR, Via.NONE, mContext.getString(R.string.bypass_error_core_missing));
            return;
        }
        set(State.STARTING, Via.LOCAL, "");
        mIo.execute(this::startLocalNow);
    }

    public void disconnect(@NonNull Context context) {
        mUserStopped = true;
        stopEverything();
    }

    /** Applies a changed profile or setting to a running tunnel. */
    public void reconnectIfRunning(@NonNull Context context) {
        if (mState == State.RUNNING || mState == State.STARTING) {
            stopEverything();
            connect(context); // queued behind the stop on the same worker thread
        }
    }

    private void stopEverything() {
        set(State.STOPPED, Via.NONE, "");
        // Stopping the core can take a moment: never on the main thread, never allowed to throw.
        mIo.execute(() -> {
            try {
                if (LocalProxyRuntime.isActive()) {
                    LocalProxyRuntime.stop(mContext);
                }
            } catch (Throwable ignored) {
                // already stopped
            }
        });
    }

    private void runPendingReady() {
        Runnable r;
        synchronized (this) {
            r = mPendingReady;
            mPendingReady = null;
        }
        if (r != null) {
            r.run();
        }
    }

    private void startLocalNow() {
        BypassSettings settings = BypassSettings.instance(mContext);
        ProxyProfile profile = ProfileStore.instance(mContext).selected(settings);
        try {
            set(State.STARTING, Via.LOCAL, "");
            LocalProxyRuntime.start(mContext, profile, settings);
            set(State.RUNNING, Via.LOCAL, profile.name);
        } catch (Throwable t) {
            String m = t.getMessage();
            set(State.ERROR, Via.NONE, m != null && !m.isEmpty() ? m : t.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Runtime preparation (shared by the VPN service and the local proxy)
    // ---------------------------------------------------------------------------------------------

    @NonNull
    static Runtime prepareRuntime(@NonNull Context context, @NonNull ProxyProfile profile,
                                  @NonNull BypassSettings settings, boolean authenticated) throws Exception {
        XrayConfigBuilder.Options o = settings.toOptions();
        o.socksPort = freeLoopbackPort();
        if (authenticated) {
            SecureRandom random = new SecureRandom();
            o.socksUser = XrayConfigBuilder.randomToken(random, 12);
            o.socksPass = XrayConfigBuilder.randomToken(random, 24);
        }
        String json = XrayConfigBuilder.build(profile, o);
        File assets = new File(context.getFilesDir(), "xray");
        //noinspection ResultOfMethodCallIgnored
        assets.mkdirs();
        XrayCore.setAssetDir(assets.getAbsolutePath());
        XrayCore.checkConfig(json);
        return new Runtime(json, o.socksPort, o.socksUser, o.socksPass);
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Profiles: import, ping, subscriptions
    // ---------------------------------------------------------------------------------------------

    /** What an import did. */
    public static final class ImportResult {
        public int added;
        public int skipped;
        /** A subscription URL that still needs a name/confirmation from the UI. */
        @Nullable public String subscriptionUrl;
        @Nullable public String error;
    }

    /** Adds share links or a full Xray config from pasted text. Subscription URLs are returned, not fetched. */
    @NonNull
    public ImportResult importText(@NonNull String text) {
        ImportResult r = new ImportResult();
        ProfileStore store = ProfileStore.instance(mContext);
        switch (ImportText.classify(text)) {
            case LINKS: {
                for (String link : ImportText.links(text)) {
                    try {
                        store.add(ShareLinkParser.parse(link));
                        r.added++;
                    } catch (ShareLinkParser.LinkException e) {
                        r.skipped++;
                        if (r.error == null) {
                            r.error = e.getMessage();
                        }
                    }
                }
                break;
            }
            case XRAY_JSON: {
                try {
                    new JSONObject(text.trim());
                    store.add(ProxyProfile.custom(mContext.getString(R.string.bypass_profile_custom), text.trim()));
                    r.added = 1;
                } catch (Exception e) {
                    r.error = e.getMessage();
                }
                break;
            }
            case SUBSCRIPTION_URL:
                r.subscriptionUrl = text.trim();
                break;
            default:
                r.error = mContext.getString(R.string.bypass_import_unknown);
        }
        return r;
    }

    public interface Callback<T> {
        /** On the main thread. */
        void done(@Nullable T result, @Nullable String error);
    }

    /** TCP-pings every profile that has a server address, one after another. */
    public void pingAll(@Nullable Runnable onProgress) {
        mIo.execute(() -> {
            for (ProxyProfile p : ProfileStore.instance(mContext).all()) {
                if (p.hasServer()) {
                    mPings.put(p.id, NetProbe.tcpPing(mContext, p.host, p.port));
                    if (onProgress != null) {
                        mMain.post(onProgress);
                    }
                }
            }
        });
    }

    /** Fetches a subscription URL, replaces its profiles; reports the number of usable servers. */
    public void addSubscription(@NonNull String name, @NonNull String url, @NonNull Callback<Integer> callback) {
        ProfileStore store = ProfileStore.instance(mContext);
        ProfileStore.Subscription sub = store.addSubscription(name, url);
        refresh(sub, true, callback);
    }

    public void refreshSubscription(@NonNull ProfileStore.Subscription sub, @NonNull Callback<Integer> callback) {
        refresh(sub, false, callback);
    }

    private void scheduleSubscriptionRefresh() {
        BypassSettings settings = BypassSettings.instance(mContext);
        if (!settings.subscriptionsAutoUpdate()) {
            return;
        }
        for (ProfileStore.Subscription s : ProfileStore.instance(mContext).subscriptions()) {
            if (System.currentTimeMillis() - s.updatedAt > SUBSCRIPTION_STALE_MS) {
                refresh(s, false, (n, e) -> { });
            }
        }
    }

    private void refresh(ProfileStore.Subscription sub, boolean removeOnFailure, Callback<Integer> callback) {
        mIo.execute(() -> {
            ProfileStore store = ProfileStore.instance(mContext);
            try {
                String body = download(sub.url);
                List<String> links = SubscriptionParser.extractLinks(body);
                int[] skipped = new int[1];
                List<ProxyProfile> profiles = SubscriptionParser.parseAll(links, skipped);
                if (profiles.isEmpty()) {
                    throw new IOException(mContext.getString(R.string.bypass_subscription_empty));
                }
                store.replaceSubscription(sub.id, profiles, BypassSettings.instance(mContext));
                store.markSubscriptionUpdated(sub.id, profiles.size());
                int n = profiles.size();
                mMain.post(() -> callback.done(n, null));
            } catch (IOException | RuntimeException e) {
                if (removeOnFailure) {
                    store.removeSubscription(sub.id);
                }
                String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mMain.post(() -> callback.done(null, m));
            }
        });
    }

    @NonNull
    private static String download(@NonNull String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "NewTube-Bypass");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_SUBSCRIPTION_BYTES) {
                        throw new IOException("Subscription is too large");
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
