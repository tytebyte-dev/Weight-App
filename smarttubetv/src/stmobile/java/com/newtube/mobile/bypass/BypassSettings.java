package com.newtube.mobile.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Everything the user can set for the censorship bypass, in one SharedPreferences file. */
public final class BypassSettings {
    /** When the bypass runs. */
    public static final int POLICY_OFF = 0;
    /** By itself, in regions that block or throttle YouTube, or when YouTube is unreachable directly. */
    public static final int POLICY_AUTO = 1;
    /** Whenever the app starts. */
    public static final int POLICY_ALWAYS = 2;

    /** Whose traffic the tunnel carries. */
    public static final int MODE_APP_VPN = 0;
    /** In-process proxy only, no VPN permission; covers the app's OkHttp traffic. */
    public static final int MODE_LOCAL_PROXY = 1;
    /** Whole device through the tunnel (this app excluded from the loop by protect()). */
    public static final int MODE_DEVICE_VPN = 2;

    private static final String FILE = "bypass_prefs";
    private static final String POLICY = "policy";
    private static final String MODE = "mode";
    private static final String SELECTED = "selected_profile";
    private static final String REGIONS = "regions";
    private static final String PROBE = "probe_direct";
    private static final String IPV6 = "ipv6";
    private static final String SNIFFING = "sniffing";
    private static final String ROUTE_ALL = "route_all";
    private static final String FRAGMENT_PACKETS = "fragment_packets";
    private static final String FRAGMENT_LENGTH = "fragment_length";
    private static final String FRAGMENT_INTERVAL = "fragment_interval";
    private static final String FRAGMENT_PROXY = "fragment_proxy";
    private static final String DNS = "dns_servers";
    private static final String DIRECT_DOMAINS = "direct_domains";
    private static final String PROXY_DOMAINS = "proxy_domains";
    private static final String LOG_LEVEL = "log_level";
    private static final String SUB_AUTO_UPDATE = "subscriptions_auto_update";
    private static final String CONSENT_NEEDED = "vpn_consent_needed";

    private final SharedPreferences mPrefs;
    private static BypassSettings sInstance;

    private BypassSettings(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @NonNull
    public static synchronized BypassSettings instance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new BypassSettings(context);
        }
        return sInstance;
    }

    @NonNull
    SharedPreferences prefs() {
        return mPrefs;
    }

    public int policy() {
        return mPrefs.getInt(POLICY, POLICY_AUTO);
    }

    public void setPolicy(int policy) {
        mPrefs.edit().putInt(POLICY, policy).apply();
    }

    public int mode() {
        return mPrefs.getInt(MODE, MODE_APP_VPN);
    }

    public void setMode(int mode) {
        mPrefs.edit().putInt(MODE, mode).apply();
    }

    @NonNull
    public String selectedProfileId() {
        return mPrefs.getString(SELECTED, ProxyProfile.BUILTIN_ID);
    }

    public void setSelectedProfileId(@NonNull String id) {
        mPrefs.edit().putString(SELECTED, id).apply();
    }

    @NonNull
    public String regions() {
        return mPrefs.getString(REGIONS, RegionPolicy.DEFAULT_REGIONS);
    }

    public void setRegions(@NonNull String csv) {
        mPrefs.edit().putString(REGIONS, csv).apply();
    }

    /** In Auto: also start when a direct HTTPS probe to YouTube fails. */
    public boolean probeDirect() {
        return mPrefs.getBoolean(PROBE, true);
    }

    public void setProbeDirect(boolean on) {
        mPrefs.edit().putBoolean(PROBE, on).apply();
    }

    public boolean ipv6() {
        return mPrefs.getBoolean(IPV6, false);
    }

    public void setIpv6(boolean on) {
        mPrefs.edit().putBoolean(IPV6, on).apply();
    }

    public boolean sniffing() {
        return mPrefs.getBoolean(SNIFFING, true);
    }

    public void setSniffing(boolean on) {
        mPrefs.edit().putBoolean(SNIFFING, on).apply();
    }

    public boolean routeAll() {
        return mPrefs.getBoolean(ROUTE_ALL, true);
    }

    public void setRouteAll(boolean on) {
        mPrefs.edit().putBoolean(ROUTE_ALL, on).apply();
    }

    @NonNull
    public String fragmentPackets() {
        return mPrefs.getString(FRAGMENT_PACKETS, "tlshello");
    }

    public void setFragmentPackets(@NonNull String v) {
        mPrefs.edit().putString(FRAGMENT_PACKETS, v).apply();
    }

    @NonNull
    public String fragmentLength() {
        return mPrefs.getString(FRAGMENT_LENGTH, "100-200");
    }

    public void setFragmentLength(@NonNull String v) {
        mPrefs.edit().putString(FRAGMENT_LENGTH, v).apply();
    }

    @NonNull
    public String fragmentInterval() {
        return mPrefs.getString(FRAGMENT_INTERVAL, "10-20");
    }

    public void setFragmentInterval(@NonNull String v) {
        mPrefs.edit().putString(FRAGMENT_INTERVAL, v).apply();
    }

    public boolean fragmentProxyConnection() {
        return mPrefs.getBoolean(FRAGMENT_PROXY, false);
    }

    public void setFragmentProxyConnection(boolean on) {
        mPrefs.edit().putBoolean(FRAGMENT_PROXY, on).apply();
    }

    /** DNS servers, one per entry, "" for the built-in list. */
    @NonNull
    public List<String> dnsServers() {
        return splitList(mPrefs.getString(DNS, ""), XrayConfigBuilder.DEFAULT_DNS);
    }

    @NonNull
    public String dnsServersText() {
        return mPrefs.getString(DNS, "");
    }

    public void setDnsServersText(@NonNull String text) {
        mPrefs.edit().putString(DNS, text).apply();
    }

    @NonNull
    public String directDomainsText() {
        return mPrefs.getString(DIRECT_DOMAINS, "");
    }

    public void setDirectDomainsText(@NonNull String text) {
        mPrefs.edit().putString(DIRECT_DOMAINS, text).apply();
    }

    @NonNull
    public String proxyDomainsText() {
        return mPrefs.getString(PROXY_DOMAINS, "");
    }

    public void setProxyDomainsText(@NonNull String text) {
        mPrefs.edit().putString(PROXY_DOMAINS, text).apply();
    }

    @NonNull
    public String logLevel() {
        return mPrefs.getString(LOG_LEVEL, "warning");
    }

    public void setLogLevel(@NonNull String level) {
        mPrefs.edit().putString(LOG_LEVEL, level).apply();
    }

    public boolean subscriptionsAutoUpdate() {
        return mPrefs.getBoolean(SUB_AUTO_UPDATE, true);
    }

    public void setSubscriptionsAutoUpdate(boolean on) {
        mPrefs.edit().putBoolean(SUB_AUTO_UPDATE, on).apply();
    }

    /** The last automatic start fell back to the local proxy because VPN consent was missing. */
    public boolean vpnConsentNeeded() {
        return mPrefs.getBoolean(CONSENT_NEEDED, false);
    }

    public void setVpnConsentNeeded(boolean needed) {
        mPrefs.edit().putBoolean(CONSENT_NEEDED, needed).apply();
    }

    /** Builds the generator options from the saved settings (ports and credentials are the caller's). */
    @NonNull
    public XrayConfigBuilder.Options toOptions() {
        XrayConfigBuilder.Options o = new XrayConfigBuilder.Options();
        o.logLevel = logLevel();
        o.sniffing = sniffing();
        o.ipv6 = ipv6();
        o.dnsServers = dnsServers();
        o.fragmentPackets = fragmentPackets();
        o.fragmentLength = fragmentLength();
        o.fragmentInterval = fragmentInterval();
        o.fragmentProxyConnection = fragmentProxyConnection();
        o.routeAll = routeAll();
        o.directDomains = splitList(directDomainsText(), new ArrayList<>());
        o.proxyDomains = splitList(proxyDomainsText(), new ArrayList<>());
        return o;
    }

    @NonNull
    static List<String> splitList(@Nullable String text, @NonNull List<String> fallback) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String part : text.split("[,;\\s]+")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out.isEmpty() ? new ArrayList<>(fallback) : out;
    }

    /** Checks an Xray range like "100-200" or "5". */
    public static boolean isRange(@Nullable String v) {
        return v != null && v.matches("\\d{1,5}(-\\d{1,5})?");
    }

    @NonNull
    public static List<String> logLevels() {
        return Arrays.asList("none", "error", "warning", "info", "debug");
    }
}
