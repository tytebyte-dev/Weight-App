package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One way out: a server from a share link or a subscription, a pasted full Xray config, or the
 * built-in server-less "DPI bypass" profile. Immutable except for the name, the subscription it
 * belongs to and the last measured ping.
 */
public final class ProxyProfile {
    /** Built-in profile: connect directly but split the TLS hello and use encrypted DNS. No server needed. */
    public static final String PROTO_FRAGMENT = "fragment";
    /** A complete Xray JSON config pasted by the user; the app only swaps in its own inbounds. */
    public static final String PROTO_CUSTOM = "custom";

    /** Stable id of the built-in profile. */
    public static final String BUILTIN_ID = "builtin-fragment";

    public final String id;
    public String name;
    @NonNull public final String protocol;
    @NonNull public final String host;
    public final int port;
    /** Xray outbound object as JSON text, without "tag". Null for {@link #PROTO_FRAGMENT} and {@link #PROTO_CUSTOM}. */
    @Nullable public final String outbound;
    /** Full Xray config for {@link #PROTO_CUSTOM}, otherwise null. */
    @Nullable public final String fullConfig;
    /** The share link this came from, when there was one (for "copy link"). */
    @Nullable public final String link;
    @Nullable public String subscriptionId;
    /** Last TCP connect time to the server in ms, or -1 when unknown / failed. */
    public long pingMs = -1;

    public ProxyProfile(@NonNull String id, @NonNull String name, @NonNull String protocol, @NonNull String host, int port,
                        @Nullable String outbound, @Nullable String fullConfig, @Nullable String link) {
        this.id = id;
        this.name = name;
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.outbound = outbound;
        this.fullConfig = fullConfig;
        this.link = link;
    }

    public static ProxyProfile server(@NonNull String name, @NonNull String protocol, @NonNull String host, int port,
                                      @NonNull String outboundJson, @Nullable String link) {
        return new ProxyProfile(UUID.randomUUID().toString(), name, protocol, host, port, outboundJson, null, link);
    }

    public static ProxyProfile custom(@NonNull String name, @NonNull String fullConfigJson) {
        return new ProxyProfile(UUID.randomUUID().toString(), name, PROTO_CUSTOM, "", 0, null, fullConfigJson, null);
    }

    public static ProxyProfile builtin(@NonNull String name) {
        return new ProxyProfile(BUILTIN_ID, name, PROTO_FRAGMENT, "", 0, null, null, null);
    }

    public boolean isBuiltin() {
        return PROTO_FRAGMENT.equals(protocol);
    }

    public boolean isCustom() {
        return PROTO_CUSTOM.equals(protocol);
    }

    /** Has a single server address that can be pinged. */
    public boolean hasServer() {
        return !host.isEmpty() && port > 0;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("protocol", protocol);
        o.put("host", host);
        o.put("port", port);
        if (outbound != null) {
            o.put("outbound", outbound);
        }
        if (fullConfig != null) {
            o.put("fullConfig", fullConfig);
        }
        if (link != null) {
            o.put("link", link);
        }
        if (subscriptionId != null) {
            o.put("sub", subscriptionId);
        }
        return o;
    }

    @NonNull
    public static ProxyProfile fromJson(@NonNull JSONObject o) throws JSONException {
        ProxyProfile p = new ProxyProfile(
                o.getString("id"),
                o.optString("name", ""),
                o.getString("protocol"),
                o.optString("host", ""),
                o.optInt("port", 0),
                o.has("outbound") ? o.getString("outbound") : null,
                o.has("fullConfig") ? o.getString("fullConfig") : null,
                o.has("link") ? o.getString("link") : null);
        p.subscriptionId = o.has("sub") ? o.getString("sub") : null;
        return p;
    }
}
