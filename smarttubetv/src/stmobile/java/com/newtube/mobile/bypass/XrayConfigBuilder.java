package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the complete Xray-core JSON the app runs: one authenticated SOCKS5 inbound on loopback
 * (hev-socks5-tunnel, or the in-process local proxy, talks to it), the chosen profile as the
 * "proxy" outbound, a DNS outbound that answers every port-53 query through Xray's own encrypted
 * DNS (so an ISP's poisoned resolver never sees the lookups), and direct/blackhole outbounds.
 *
 * <p>Pure Java (org.json only); field names are the ones Xray-core 26.3.27's loader accepts.</p>
 */
public final class XrayConfigBuilder {
    public static final String TAG_IN = "socks-in";
    public static final String TAG_PROXY = "proxy";
    public static final String TAG_DIRECT = "direct";
    public static final String TAG_BLOCK = "block";
    public static final String TAG_DNS = "dns-out";

    /** DNS-over-HTTPS in "local" mode: the query leaves directly (never through the proxy that may itself need resolving). */
    /** Plain (unencrypted) resolvers, used when encrypted DNS is switched off. */
    public static final List<String> PLAIN_DNS = Arrays.asList("1.1.1.1", "8.8.8.8");

    public static final List<String> DEFAULT_DNS = Arrays.asList(
            "https+local://1.1.1.1/dns-query",
            "https+local://8.8.8.8/dns-query",
            "https+local://9.9.9.9/dns-query");

    /** Private and loopback ranges: never sent through a proxy. */
    private static final String[] PRIVATE_RANGES = {
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
            "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128"};

    /** Everything the settings screens can change about the generated config. */
    public static final class Options {
        public int socksPort = 10808;
        /** Null/empty: no authentication (local-proxy mode on loopback). */
        @Nullable public String socksUser;
        @Nullable public String socksPass;
        public String logLevel = "warning";
        /** Sniff the TLS/HTTP/QUIC hello so rules can match domains although apps connect by IP. */
        public boolean sniffing = true;
        public boolean ipv6 = false;
        public List<String> dnsServers = DEFAULT_DNS;
        /** False: the built-in list becomes plain UDP resolvers instead of DNS-over-HTTPS. */
        public boolean dnsEncrypted = true;
        /** False: no TLS-hello splitting anywhere (the built-in profile becomes a plain direct connection). */
        public boolean fragmentEnabled = true;
        /** The built-in profile's TLS-hello fragmentation: packets, length range, interval range (ms). */
        public String fragmentPackets = "tlshello";
        public String fragmentLength = "100-200";
        public String fragmentInterval = "10-20";
        /** Also split the first packets of connections to the proxy server itself (finalmask "fragment"). */
        public boolean fragmentProxyConnection = false;
        /** True: all traffic through the proxy. False: only Google/YouTube domains, the rest direct. */
        public boolean routeAll = true;
        /** Send private/LAN ranges direct even when routeAll. */
        public boolean directPrivate = true;
        /** Extra domain rules (Xray syntax: domain:example.com, full:..., geosite:...) forced direct / forced via proxy. */
        public List<String> directDomains = Arrays.asList();
        public List<String> proxyDomains = Arrays.asList();
    }

    private XrayConfigBuilder() {
    }

    /** Domains the "only Google/YouTube via proxy" routing sends through the proxy. */
    private static final String[] GOOGLE_DOMAINS = {
            "domain:youtube.com", "domain:youtu.be", "domain:youtube-nocookie.com", "domain:googlevideo.com",
            "domain:ytimg.com", "domain:ggpht.com", "domain:googleusercontent.com", "domain:googleapis.com",
            "domain:gstatic.com", "domain:google.com", "domain:googleadservices.com", "domain:withgoogle.com",
            "domain:yt.be", "domain:youtubei.googleapis.com", "domain:accounts.google.com"};

    @NonNull
    public static String build(@NonNull ProxyProfile profile, @NonNull Options o) throws JSONException {
        JSONObject root;
        if (profile.isCustom() && profile.fullConfig != null) {
            root = new JSONObject(profile.fullConfig);
            root.put("inbounds", new JSONArray().put(inbound(o)));
            if (!root.has("log")) {
                root.put("log", new JSONObject().put("loglevel", o.logLevel));
            }
            return root.toString();
        }

        root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", o.logLevel));
        root.put("dns", dns(o));
        root.put("inbounds", new JSONArray().put(inbound(o)));

        JSONArray outbounds = new JSONArray();
        boolean builtin = profile.isBuiltin();
        if (builtin) {
            outbounds.put(freedom(TAG_PROXY, o, o.fragmentEnabled));   // "proxy" is the fragmenting direct connection
        } else {
            outbounds.put(proxyOutbound(profile, o));
        }
        outbounds.put(freedom(TAG_DIRECT, o, false));
        outbounds.put(new JSONObject().put("protocol", "blackhole").put("tag", TAG_BLOCK));
        outbounds.put(new JSONObject().put("protocol", "dns").put("tag", TAG_DNS));
        root.put("outbounds", outbounds);

        root.put("routing", routing(builtin, o));
        return root.toString();
    }

    private static JSONObject inbound(Options o) throws JSONException {
        JSONObject settings = new JSONObject().put("udp", true).put("ip", "127.0.0.1");
        if (o.socksUser != null && !o.socksUser.isEmpty() && o.socksPass != null) {
            settings.put("auth", "password");
            settings.put("accounts", new JSONArray().put(new JSONObject().put("user", o.socksUser).put("pass", o.socksPass)));
        } else {
            settings.put("auth", "noauth");
        }
        JSONObject in = new JSONObject()
                .put("tag", TAG_IN)
                .put("listen", "127.0.0.1")
                .put("port", o.socksPort)
                .put("protocol", "socks")
                .put("settings", settings);
        if (o.sniffing) {
            in.put("sniffing", new JSONObject()
                    .put("enabled", true)
                    .put("destOverride", new JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", true));
        }
        return in;
    }

    private static JSONObject dns(Options o) throws JSONException {
        JSONArray servers = new JSONArray();
        List<String> list = o.dnsServers == null || o.dnsServers.isEmpty() || o.dnsServers.equals(DEFAULT_DNS)
                ? (o.dnsEncrypted ? DEFAULT_DNS : PLAIN_DNS) : o.dnsServers;
        for (String s : list) {
            servers.put(s);
        }
        return new JSONObject()
                .put("servers", servers)
                .put("queryStrategy", o.ipv6 ? "UseIP" : "UseIPv4")
                .put("tag", "dns-internal");
    }

    private static JSONObject freedom(String tag, Options o, boolean fragment) throws JSONException {
        JSONObject settings = new JSONObject().put("domainStrategy", o.ipv6 ? "UseIP" : "UseIPv4");
        if (fragment) {
            settings.put("fragment", new JSONObject()
                    .put("packets", o.fragmentPackets)
                    .put("length", o.fragmentLength)
                    .put("interval", o.fragmentInterval));
        }
        return new JSONObject().put("protocol", "freedom").put("tag", tag).put("settings", settings);
    }

    private static JSONObject proxyOutbound(ProxyProfile profile, Options o) throws JSONException {
        if (profile.outbound == null) {
            throw new JSONException("Profile has no outbound: " + profile.name);
        }
        JSONObject out = new JSONObject(profile.outbound);
        out.put("tag", TAG_PROXY);

        String protocol = out.optString("protocol");
        boolean udpTransport = false;
        JSONObject stream = out.optJSONObject("streamSettings");
        if (stream != null) {
            String net = stream.optString("network", "tcp");
            udpTransport = "hysteria".equals(net) || "kcp".equals(net);
        }
        boolean hasStream = !"wireguard".equals(protocol) && !"hysteria".equals(protocol);
        if (hasStream) {
            if (stream == null) {
                stream = new JSONObject().put("network", "tcp");
                out.put("streamSettings", stream);
            }
            // The server's own domain is resolved by Xray (DoH), not by the system resolver that the
            // tunnel itself would feed back into.
            JSONObject sockopt = stream.optJSONObject("sockopt");
            if (sockopt == null) {
                sockopt = new JSONObject();
                stream.put("sockopt", sockopt);
            }
            sockopt.put("domainStrategy", o.ipv6 ? "UseIP" : "UseIPv4");
        }
        if (o.fragmentEnabled && o.fragmentProxyConnection && stream != null && !udpTransport) {
            JSONObject mask = new JSONObject()
                    .put("type", "fragment")
                    .put("settings", new JSONObject()
                            .put("packets", o.fragmentPackets)
                            .put("length", o.fragmentLength)
                            .put("delay", o.fragmentInterval));
            JSONObject finalmask = stream.optJSONObject("finalmask");
            if (finalmask == null) {
                finalmask = new JSONObject();
                stream.put("finalmask", finalmask);
            }
            JSONArray tcp = finalmask.optJSONArray("tcp");
            if (tcp == null) {
                tcp = new JSONArray();
                finalmask.put("tcp", tcp);
            }
            tcp.put(mask);
        }
        return out;
    }

    private static JSONObject routing(boolean builtin, Options o) throws JSONException {
        JSONArray rules = new JSONArray();

        // Every DNS lookup of the tunnelled app is answered by Xray's own DNS client.
        rules.put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put(TAG_IN))
                .put("port", "53").put("outboundTag", TAG_DNS));

        if (!builtin) {
            if (o.directPrivate) {
                JSONArray ips = new JSONArray();
                for (String r : PRIVATE_RANGES) {
                    ips.put(r);
                }
                rules.put(new JSONObject().put("type", "field").put("ip", ips).put("outboundTag", TAG_DIRECT));
            }
            if (o.directDomains != null && !o.directDomains.isEmpty()) {
                rules.put(new JSONObject().put("type", "field").put("domain", new JSONArray(o.directDomains)).put("outboundTag", TAG_DIRECT));
            }
            if (o.proxyDomains != null && !o.proxyDomains.isEmpty()) {
                rules.put(new JSONObject().put("type", "field").put("domain", new JSONArray(o.proxyDomains)).put("outboundTag", TAG_PROXY));
            }
            if (!o.routeAll) {
                JSONArray domains = new JSONArray();
                for (String d : GOOGLE_DOMAINS) {
                    domains.put(d);
                }
                rules.put(new JSONObject().put("type", "field").put("domain", domains).put("outboundTag", TAG_PROXY));
                // Everything else (update checks, mirrors, ...) goes direct.
                rules.put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put(TAG_IN)).put("outboundTag", TAG_DIRECT));
            }
        }
        // Default: the first outbound ("proxy").
        return new JSONObject().put("domainStrategy", "AsIs").put("rules", rules);
    }

    /** A random credential for the loopback SOCKS inbound; pass a SecureRandom. */
    @NonNull
    public static String randomToken(@NonNull java.util.Random random, int length) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
