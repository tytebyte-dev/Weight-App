package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the share links every client app exchanges (vless://, vmess://, trojan://, ss://,
 * hysteria2://, socks5://, wireguard://) into an Xray outbound. The field names follow Xray-core
 * 26.3.27's own JSON loader (native/xray-core/infra/conf), not the older v2ray ones.
 *
 * <p>Pure Java (org.json only), so it is unit-tested without a device.</p>
 */
public final class ShareLinkParser {
    /** A link that is malformed or uses something this Xray build cannot do. */
    public static final class LinkException extends Exception {
        public LinkException(String message) {
            super(message);
        }
    }

    private ShareLinkParser() {
    }

    /** True when the text looks like a link this parser handles (cheap check for clipboard import). */
    public static boolean looksLikeLink(@Nullable String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("vless://") || t.startsWith("vmess://") || t.startsWith("trojan://")
                || t.startsWith("ss://") || t.startsWith("hysteria2://") || t.startsWith("hy2://")
                || t.startsWith("socks://") || t.startsWith("socks5://") || t.startsWith("wireguard://");
    }

    @NonNull
    public static ProxyProfile parse(@NonNull String rawLink) throws LinkException {
        String link = rawLink.trim();
        int schemeEnd = link.indexOf("://");
        if (schemeEnd <= 0) {
            throw new LinkException("Not a share link");
        }
        String scheme = link.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        try {
            switch (scheme) {
                case "vless":
                    return parseVless(link);
                case "vmess":
                    return parseVmess(link);
                case "trojan":
                    return parseTrojan(link);
                case "ss":
                    return parseShadowsocks(link);
                case "hysteria2":
                case "hy2":
                    return parseHysteria2(link);
                case "socks":
                case "socks5":
                    return parseSocks(link);
                case "wireguard":
                    return parseWireguard(link);
                default:
                    throw new LinkException("Unsupported scheme: " + scheme);
            }
        } catch (JSONException | IllegalArgumentException e) {
            throw new LinkException("Malformed link: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Link anatomy
    // ---------------------------------------------------------------------------------------------

    private static final class Parts {
        String userInfo;       // raw, still percent-encoded
        String host;
        int port;
        final Map<String, String> query = new LinkedHashMap<>();
        String fragment = "";  // decoded
    }

    private static Parts split(String link) throws LinkException {
        Parts p = new Parts();
        String rest = link.substring(link.indexOf("://") + 3);

        int hash = rest.indexOf('#');
        if (hash >= 0) {
            p.fragment = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        int q = rest.indexOf('?');
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k = eq >= 0 ? pair.substring(0, eq) : pair;
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                p.query.put(decode(k).toLowerCase(Locale.ROOT), decode(v));
            }
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            p.userInfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }
        hostPort(p, rest);
        return p;
    }

    private static void hostPort(Parts p, String hostPort) throws LinkException {
        String host;
        String port;
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end < 0) {
                throw new LinkException("Bad IPv6 address");
            }
            host = hostPort.substring(1, end);
            port = hostPort.length() > end + 2 && hostPort.charAt(end + 1) == ':' ? hostPort.substring(end + 2) : "";
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) {
                throw new LinkException("No port in link");
            }
            host = hostPort.substring(0, colon);
            port = hostPort.substring(colon + 1);
        }
        if (host.isEmpty()) {
            throw new LinkException("No host in link");
        }
        int number;
        try {
            number = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            throw new LinkException("Bad port: " + port);
        }
        if (number < 1 || number > 65535) {
            throw new LinkException("Bad port: " + port);
        }
        p.host = host;
        p.port = number;
    }

    private static String decode(String s) {
        try {
            // '+' is a literal plus in these links (base64, paths), not a space.
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private static String get(Map<String, String> q, String key, String fallback) {
        String v = q.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static boolean truthy(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static String name(Parts p, String fallbackKind) {
        return p.fragment.isEmpty() ? fallbackKind + " " + p.host : p.fragment;
    }

    // ---------------------------------------------------------------------------------------------
    // Protocols
    // ---------------------------------------------------------------------------------------------

    private static ProxyProfile parseVless(String link) throws LinkException, JSONException {
        Parts p = split(link);
        if (p.userInfo == null || p.userInfo.isEmpty()) {
            throw new LinkException("VLESS link has no user id");
        }
        JSONObject settings = new JSONObject()
                .put("address", p.host)
                .put("port", p.port)
                .put("id", decode(p.userInfo))
                .put("encryption", get(p.query, "encryption", "none"));
        String flow = get(p.query, "flow", "");
        if (!flow.isEmpty()) {
            settings.put("flow", flow);
        }
        JSONObject outbound = new JSONObject()
                .put("protocol", "vless")
                .put("settings", settings)
                .put("streamSettings", stream(p.query, p.host, get(p.query, "security", "none")));
        return ProxyProfile.server(name(p, "VLESS"), "vless", p.host, p.port, outbound.toString(), link);
    }

    private static ProxyProfile parseTrojan(String link) throws LinkException, JSONException {
        Parts p = split(link);
        if (p.userInfo == null || p.userInfo.isEmpty()) {
            throw new LinkException("Trojan link has no password");
        }
        JSONObject settings = new JSONObject()
                .put("address", p.host)
                .put("port", p.port)
                .put("password", decode(p.userInfo));
        String flow = get(p.query, "flow", "");
        if (!flow.isEmpty()) {
            settings.put("flow", flow);
        }
        // Trojan is TLS by definition: a link without security= still means TLS.
        JSONObject outbound = new JSONObject()
                .put("protocol", "trojan")
                .put("settings", settings)
                .put("streamSettings", stream(p.query, p.host, get(p.query, "security", "tls")));
        return ProxyProfile.server(name(p, "Trojan"), "trojan", p.host, p.port, outbound.toString(), link);
    }

    private static ProxyProfile parseVmess(String link) throws LinkException, JSONException {
        String body = link.substring("vmess://".length()).trim();
        // The common form is base64 of a JSON object; the URL form (vmess://uuid@host:port?...) is rarer.
        if (body.contains("@")) {
            Parts p = split(link);
            JSONObject settings = new JSONObject()
                    .put("address", p.host)
                    .put("port", p.port)
                    .put("id", decode(p.userInfo))
                    .put("security", get(p.query, "encryption", "auto"));
            JSONObject outbound = new JSONObject()
                    .put("protocol", "vmess")
                    .put("settings", settings)
                    .put("streamSettings", stream(p.query, p.host, get(p.query, "security", "none")));
            return ProxyProfile.server(name(p, "VMess"), "vmess", p.host, p.port, outbound.toString(), link);
        }

        int hash = body.indexOf('#');
        if (hash >= 0) {
            body = body.substring(0, hash);
        }
        JSONObject j = new JSONObject(new String(base64(body), StandardCharsets.UTF_8));
        String host = j.optString("add", "");
        int port = j.optInt("port", 0);
        if (host.isEmpty() || port < 1 || port > 65535) {
            throw new LinkException("VMess link has no usable address");
        }
        String id = j.optString("id", "");
        if (id.isEmpty()) {
            throw new LinkException("VMess link has no user id");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("type", j.optString("net", "tcp"));
        q.put("headertype", j.optString("type", "none"));
        q.put("host", j.optString("host", ""));
        q.put("path", j.optString("path", ""));
        q.put("sni", j.optString("sni", ""));
        q.put("alpn", j.optString("alpn", ""));
        q.put("fp", j.optString("fp", ""));
        String tls = j.optString("tls", "");
        String security = "tls".equalsIgnoreCase(tls) ? "tls" : "none";
        if ("grpc".equalsIgnoreCase(q.get("type"))) {
            q.put("servicename", j.optString("path", ""));
        }

        JSONObject settings = new JSONObject()
                .put("address", host)
                .put("port", port)
                .put("id", id)
                .put("security", j.optString("scy", "auto").isEmpty() ? "auto" : j.optString("scy", "auto"));
        JSONObject outbound = new JSONObject()
                .put("protocol", "vmess")
                .put("settings", settings)
                .put("streamSettings", stream(q, host, security));
        String ps = j.optString("ps", "");
        return ProxyProfile.server(ps.isEmpty() ? "VMess " + host : ps, "vmess", host, port, outbound.toString(), link);
    }

    private static ProxyProfile parseShadowsocks(String link) throws LinkException, JSONException {
        String body = link.substring("ss://".length()).trim();
        String fragment = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            fragment = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        Map<String, String> query = new LinkedHashMap<>();
        int qm = body.indexOf('?');
        if (qm >= 0) {
            for (String pair : body.substring(qm + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(decode(pair.substring(0, eq)).toLowerCase(Locale.ROOT), decode(pair.substring(eq + 1)));
                }
            }
            body = body.substring(0, qm);
        }
        if (body.endsWith("/")) {
            body = body.substring(0, body.length() - 1);
        }
        if (query.containsKey("plugin")) {
            throw new LinkException("Shadowsocks plugins are not supported");
        }

        String methodPassword;
        String hostPort;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            // SIP002: base64(method:password)@host:port  (or plain method:password)
            String user = body.substring(0, at);
            hostPort = body.substring(at + 1);
            methodPassword = user.contains(":") ? decode(user) : new String(base64(decode(user)), StandardCharsets.UTF_8);
        } else {
            // Legacy: base64(method:password@host:port)
            String all = new String(base64(body), StandardCharsets.UTF_8);
            int lastAt = all.lastIndexOf('@');
            if (lastAt < 0) {
                throw new LinkException("Malformed Shadowsocks link");
            }
            methodPassword = all.substring(0, lastAt);
            hostPort = all.substring(lastAt + 1);
        }
        int colon = methodPassword.indexOf(':');
        if (colon <= 0) {
            throw new LinkException("Shadowsocks link has no method or password");
        }
        Parts p = new Parts();
        hostPort(p, hostPort);

        JSONObject settings = new JSONObject()
                .put("address", p.host)
                .put("port", p.port)
                .put("method", methodPassword.substring(0, colon))
                .put("password", methodPassword.substring(colon + 1));
        JSONObject outbound = new JSONObject()
                .put("protocol", "shadowsocks")
                .put("settings", settings);
        String title = fragment.isEmpty() ? "Shadowsocks " + p.host : fragment;
        return ProxyProfile.server(title, "shadowsocks", p.host, p.port, outbound.toString(), link);
    }

    private static ProxyProfile parseHysteria2(String link) throws LinkException, JSONException {
        Parts p = split(link);
        String auth = p.userInfo == null ? "" : decode(p.userInfo);
        if (auth.isEmpty()) {
            throw new LinkException("Hysteria2 link has no auth");
        }
        JSONObject tls = new JSONObject()
                .put("serverName", get(p.query, "sni", p.host))
                .put("alpn", new JSONArray().put("h3"));
        if (truthy(p.query.get("insecure")) || truthy(p.query.get("allowinsecure"))) {
            tls.put("allowInsecure", true);
        }
        String pin = get(p.query, "pinsha256", "");
        if (!pin.isEmpty()) {
            tls.put("pinnedPeerCertSha256", pin.replace(":", "").toLowerCase(Locale.ROOT));
        }
        JSONObject stream = new JSONObject()
                .put("network", "hysteria")
                .put("security", "tls")
                .put("tlsSettings", tls)
                .put("hysteriaSettings", new JSONObject().put("version", 2).put("auth", auth));
        if ("salamander".equalsIgnoreCase(p.query.get("obfs")) && !get(p.query, "obfs-password", "").isEmpty()) {
            stream.put("finalmask", new JSONObject().put("udp", new JSONArray().put(new JSONObject()
                    .put("type", "salamander")
                    .put("settings", new JSONObject().put("password", p.query.get("obfs-password"))))));
        }
        JSONObject outbound = new JSONObject()
                .put("protocol", "hysteria")
                .put("settings", new JSONObject().put("version", 2).put("address", p.host).put("port", p.port))
                .put("streamSettings", stream);
        return ProxyProfile.server(name(p, "Hysteria2"), "hysteria2", p.host, p.port, outbound.toString(), link);
    }

    private static ProxyProfile parseSocks(String link) throws LinkException, JSONException {
        Parts p = split(link);
        JSONObject settings = new JSONObject().put("address", p.host).put("port", p.port);
        if (p.userInfo != null && !p.userInfo.isEmpty()) {
            String user = decode(p.userInfo);
            if (!user.contains(":")) {
                user = new String(base64(user), StandardCharsets.UTF_8); // socks://base64(user:pass)@host:port
            }
            int colon = user.indexOf(':');
            if (colon > 0) {
                settings.put("user", user.substring(0, colon)).put("pass", user.substring(colon + 1));
            }
        }
        JSONObject outbound = new JSONObject().put("protocol", "socks").put("settings", settings);
        return ProxyProfile.server(name(p, "SOCKS"), "socks", p.host, p.port, outbound.toString(), link);
    }

    private static ProxyProfile parseWireguard(String link) throws LinkException, JSONException {
        Parts p = split(link);
        if (p.userInfo == null || p.userInfo.isEmpty()) {
            throw new LinkException("WireGuard link has no private key");
        }
        String publicKey = get(p.query, "publickey", "");
        if (publicKey.isEmpty()) {
            throw new LinkException("WireGuard link has no public key");
        }
        JSONArray address = new JSONArray();
        for (String a : get(p.query, "address", "10.0.0.2/32").split(",")) {
            if (!a.trim().isEmpty()) {
                address.put(a.trim());
            }
        }
        JSONObject peer = new JSONObject()
                .put("publicKey", publicKey)
                .put("endpoint", (p.host.contains(":") ? "[" + p.host + "]" : p.host) + ":" + p.port)
                .put("allowedIPs", new JSONArray().put("0.0.0.0/0").put("::/0"));
        if (!get(p.query, "presharedkey", "").isEmpty()) {
            peer.put("preSharedKey", p.query.get("presharedkey"));
        }
        JSONObject settings = new JSONObject()
                .put("secretKey", decode(p.userInfo))
                .put("address", address)
                .put("peers", new JSONArray().put(peer))
                .put("mtu", Integer.parseInt(get(p.query, "mtu", "1280")));
        String reserved = get(p.query, "reserved", "");
        if (!reserved.isEmpty()) {
            JSONArray r = new JSONArray();
            for (String n : reserved.split(",")) {
                r.put(Integer.parseInt(n.trim()));
            }
            settings.put("reserved", r);
        }
        JSONObject outbound = new JSONObject().put("protocol", "wireguard").put("settings", settings);
        return ProxyProfile.server(name(p, "WireGuard"), "wireguard", p.host, p.port, outbound.toString(), link);
    }

    // ---------------------------------------------------------------------------------------------
    // streamSettings (shared by VLESS, VMess, Trojan)
    // ---------------------------------------------------------------------------------------------

    private static JSONObject stream(Map<String, String> q, String address, String security) throws LinkException, JSONException {
        String type = get(q, "type", "tcp").toLowerCase(Locale.ROOT);
        String network;
        switch (type) {
            case "tcp":
            case "raw":
                network = "tcp";
                break;
            case "ws":
            case "websocket":
                network = "ws";
                break;
            case "grpc":
                network = "grpc";
                break;
            case "httpupgrade":
                network = "httpupgrade";
                break;
            case "xhttp":
            case "splithttp":
                network = "xhttp";
                break;
            case "kcp":
            case "mkcp":
                network = "kcp";
                break;
            default:
                // h2/http and quic transports were removed from Xray-core; XHTTP replaces them.
                throw new LinkException("Unsupported transport: " + type + " (use xhttp/ws/grpc/tcp)");
        }

        String host = get(q, "host", "");
        String path = get(q, "path", "");
        JSONObject s = new JSONObject().put("network", network);

        switch (network) {
            case "ws": {
                JSONObject ws = new JSONObject().put("path", path.isEmpty() ? "/" : path);
                if (!host.isEmpty()) {
                    ws.put("host", host);
                }
                s.put("wsSettings", ws);
                break;
            }
            case "httpupgrade": {
                JSONObject hu = new JSONObject().put("path", path.isEmpty() ? "/" : path);
                if (!host.isEmpty()) {
                    hu.put("host", host);
                }
                s.put("httpupgradeSettings", hu);
                break;
            }
            case "grpc": {
                JSONObject g = new JSONObject().put("serviceName", get(q, "servicename", path));
                if (!get(q, "authority", "").isEmpty()) {
                    g.put("authority", q.get("authority"));
                }
                g.put("multiMode", "multi".equalsIgnoreCase(q.get("mode")));
                s.put("grpcSettings", g);
                break;
            }
            case "xhttp": {
                JSONObject x = new JSONObject().put("path", path.isEmpty() ? "/" : path).put("mode", get(q, "mode", "auto"));
                if (!host.isEmpty()) {
                    x.put("host", host);
                }
                String extra = get(q, "extra", "");
                if (!extra.isEmpty()) {
                    x.put("extra", new JSONObject(extra));
                }
                s.put("xhttpSettings", x);
                break;
            }
            case "kcp": {
                JSONObject k = new JSONObject().put("header", new JSONObject().put("type", get(q, "headertype", "none")));
                if (!get(q, "seed", "").isEmpty()) {
                    k.put("seed", q.get("seed"));
                }
                s.put("kcpSettings", k);
                break;
            }
            default: {
                if ("http".equalsIgnoreCase(q.get("headertype"))) {
                    JSONObject request = new JSONObject().put("path", new JSONArray().put(path.isEmpty() ? "/" : path));
                    if (!host.isEmpty()) {
                        request.put("headers", new JSONObject().put("Host", new JSONArray().put(host)));
                    }
                    s.put("tcpSettings", new JSONObject().put("header", new JSONObject().put("type", "http").put("request", request)));
                }
            }
        }

        String sec = security.toLowerCase(Locale.ROOT);
        String fp = get(q, "fp", "chrome");
        if ("tls".equals(sec)) {
            JSONObject tls = new JSONObject()
                    .put("serverName", get(q, "sni", host.isEmpty() ? address : host))
                    .put("fingerprint", fp);
            String alpn = get(q, "alpn", "");
            if (!alpn.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String v : alpn.split(",")) {
                    if (!v.trim().isEmpty()) {
                        a.put(v.trim());
                    }
                }
                tls.put("alpn", a);
            }
            if (truthy(q.get("allowinsecure")) || truthy(q.get("insecure"))) {
                tls.put("allowInsecure", true);
            }
            s.put("security", "tls").put("tlsSettings", tls);
        } else if ("reality".equals(sec)) {
            String pbk = get(q, "pbk", "");
            if (pbk.isEmpty()) {
                throw new LinkException("REALITY link has no public key (pbk)");
            }
            if (!"tcp".equals(network) && !"xhttp".equals(network) && !"grpc".equals(network)) {
                throw new LinkException("REALITY works only with tcp, xhttp and grpc");
            }
            JSONObject r = new JSONObject()
                    .put("serverName", get(q, "sni", host.isEmpty() ? address : host))
                    .put("fingerprint", fp)
                    .put("publicKey", pbk)
                    .put("shortId", get(q, "sid", ""))
                    .put("spiderX", get(q, "spx", ""));
            if (!get(q, "pqv", "").isEmpty()) {
                r.put("mldsa65Verify", q.get("pqv"));
            }
            s.put("security", "reality").put("realitySettings", r);
        } else if (!"none".equals(sec) && !sec.isEmpty()) {
            throw new LinkException("Unsupported security: " + security);
        }
        return s;
    }

    private static byte[] base64(String s) throws LinkException {
        try {
            return B64.decode(s.trim());
        } catch (IllegalArgumentException e) {
            throw new LinkException("Bad base64 in link");
        }
    }
}
