package com.newtube.mobile.bypass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Link parsing, config generation, subscription reading and the region rule. org.json is the real
 * implementation under Robolectric (the android.jar one is a stub in plain unit tests).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class BypassLogicTest {
    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    private static JSONObject outbound(ProxyProfile p) throws Exception {
        return new JSONObject(p.outbound);
    }

    private static String b64(String s) {
        return java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ----- links ----------------------------------------------------------------------------------

    @Test
    public void vlessReality() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("vless://" + UUID + "@example.com:443?type=tcp&security=reality"
                + "&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY&sid=ab12&spx=%2F&flow=xtls-rprx-vision#My%20Server");
        assertEquals("My Server", p.name);
        assertEquals("vless", p.protocol);
        assertEquals("example.com", p.host);
        assertEquals(443, p.port);
        JSONObject o = outbound(p);
        assertEquals("vless", o.getString("protocol"));
        JSONObject s = o.getJSONObject("settings");
        assertEquals(UUID, s.getString("id"));
        assertEquals("none", s.getString("encryption"));
        assertEquals("xtls-rprx-vision", s.getString("flow"));
        JSONObject st = o.getJSONObject("streamSettings");
        assertEquals("tcp", st.getString("network"));
        assertEquals("reality", st.getString("security"));
        JSONObject r = st.getJSONObject("realitySettings");
        assertEquals("www.microsoft.com", r.getString("serverName"));
        assertEquals("PUBKEY", r.getString("publicKey"));
        assertEquals("ab12", r.getString("shortId"));
        assertEquals("/", r.getString("spiderX"));
    }

    @Test
    public void vlessWebsocketTlsIpv6() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("vless://" + UUID + "@[2001:db8::1]:8443?type=ws&security=tls"
                + "&host=cdn.example.com&path=%2Fws%3Fed%3D2048&sni=cdn.example.com&alpn=h2,http/1.1&allowInsecure=1");
        assertEquals("2001:db8::1", p.host);
        JSONObject st = outbound(p).getJSONObject("streamSettings");
        assertEquals("ws", st.getString("network"));
        assertEquals("/ws?ed=2048", st.getJSONObject("wsSettings").getString("path"));
        assertEquals("cdn.example.com", st.getJSONObject("wsSettings").getString("host"));
        JSONObject tls = st.getJSONObject("tlsSettings");
        assertTrue(tls.getBoolean("allowInsecure"));
        assertEquals(2, tls.getJSONArray("alpn").length());
    }

    @Test
    public void vlessXhttpWithExtra() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("vless://" + UUID + "@h.example:443?type=xhttp&security=tls&path=%2Fx&mode=packet-up"
                + "&extra=%7B%22xPaddingBytes%22%3A%22100-200%22%7D");
        JSONObject x = outbound(p).getJSONObject("streamSettings").getJSONObject("xhttpSettings");
        assertEquals("/x", x.getString("path"));
        assertEquals("packet-up", x.getString("mode"));
        assertEquals("100-200", x.getJSONObject("extra").getString("xPaddingBytes"));
    }

    @Test
    public void vmessBase64Json() throws Exception {
        String json = "{\"v\":\"2\",\"ps\":\"vm\",\"add\":\"vm.example\",\"port\":\"443\",\"id\":\"" + UUID
                + "\",\"scy\":\"auto\",\"net\":\"grpc\",\"path\":\"svc\",\"tls\":\"tls\",\"sni\":\"vm.example\"}";
        ProxyProfile p = ShareLinkParser.parse("vmess://" + b64(json));
        assertEquals("vm", p.name);
        assertEquals(443, p.port);
        JSONObject st = outbound(p).getJSONObject("streamSettings");
        assertEquals("grpc", st.getString("network"));
        assertEquals("svc", st.getJSONObject("grpcSettings").getString("serviceName"));
        assertEquals("tls", st.getString("security"));
    }

    @Test
    public void trojanDefaultsToTls() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("trojan://p%40ss@t.example:443?sni=t.example#T");
        JSONObject o = outbound(p);
        assertEquals("p@ss", o.getJSONObject("settings").getString("password"));
        assertEquals("tls", o.getJSONObject("streamSettings").getString("security"));
    }

    @Test
    public void shadowsocksSip002AndLegacy() throws Exception {
        ProxyProfile a = ShareLinkParser.parse("ss://" + b64("aes-256-gcm:secret") + "@ss.example:8388#SS");
        JSONObject sa = outbound(a).getJSONObject("settings");
        assertEquals("aes-256-gcm", sa.getString("method"));
        assertEquals("secret", sa.getString("password"));
        assertEquals(8388, a.port);

        ProxyProfile b = ShareLinkParser.parse("ss://" + b64("chacha20-ietf-poly1305:pw@old.example:1234"));
        assertEquals("old.example", b.host);
        assertEquals(1234, b.port);
        assertEquals("pw", outbound(b).getJSONObject("settings").getString("password"));

        assertThrows(ShareLinkParser.LinkException.class,
                () -> ShareLinkParser.parse("ss://" + b64("aes-256-gcm:x") + "@h:1?plugin=v2ray-plugin"));
    }

    @Test
    public void hysteria2() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("hysteria2://auth%20pw@hy.example:443?sni=hy.example&insecure=1&obfs=salamander&obfs-password=ob#H");
        JSONObject o = outbound(p);
        assertEquals("hysteria", o.getString("protocol"));
        assertEquals(2, o.getJSONObject("settings").getInt("version"));
        JSONObject st = o.getJSONObject("streamSettings");
        assertEquals("hysteria", st.getString("network"));
        assertEquals("auth pw", st.getJSONObject("hysteriaSettings").getString("auth"));
        assertTrue(st.getJSONObject("tlsSettings").getBoolean("allowInsecure"));
        assertEquals("salamander", st.getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0).getString("type"));
    }

    @Test
    public void socksAndWireguard() throws Exception {
        ProxyProfile s = ShareLinkParser.parse("socks5://user:pw@s.example:1080#S");
        assertEquals("user", outbound(s).getJSONObject("settings").getString("user"));
        ProxyProfile w = ShareLinkParser.parse("wireguard://PRIV@w.example:51820?publickey=PUB&address=10.0.0.2/32&mtu=1280&reserved=1,2,3#W");
        JSONObject ws = outbound(w).getJSONObject("settings");
        assertEquals("PRIV", ws.getString("secretKey"));
        assertEquals("w.example:51820", ws.getJSONArray("peers").getJSONObject(0).getString("endpoint"));
        assertEquals(3, ws.getJSONArray("reserved").length());
    }

    @Test
    public void rejectsBadAndUnsupported() {
        assertThrows(ShareLinkParser.LinkException.class, () -> ShareLinkParser.parse("hello"));
        assertThrows(ShareLinkParser.LinkException.class, () -> ShareLinkParser.parse("vless://" + UUID + "@h:70000"));
        assertThrows(ShareLinkParser.LinkException.class, () -> ShareLinkParser.parse("vless://" + UUID + "@h:443?type=quic"));
        assertThrows(ShareLinkParser.LinkException.class, () -> ShareLinkParser.parse("vless://" + UUID + "@h:443?type=ws&security=reality&pbk=k"));
        assertThrows(ShareLinkParser.LinkException.class, () -> ShareLinkParser.parse("ftp://x@y:1"));
    }

    // ----- subscriptions --------------------------------------------------------------------------

    @Test
    public void subscriptionBase64AndPlain() {
        String l1 = "vless://" + UUID + "@a.example:443?security=tls";
        String l2 = "trojan://pw@b.example:443";
        List<String> fromB64 = SubscriptionParser.extractLinks(b64(l1 + "\n" + l2 + "\n# comment\n" + l1));
        assertEquals(Arrays.asList(l1, l2), fromB64);
        assertEquals(Arrays.asList(l1, l2), SubscriptionParser.extractLinks(l1 + "\r\n" + l2));
        assertTrue(SubscriptionParser.extractLinks("%%% not base64 %%%").isEmpty());

        int[] skipped = new int[1];
        List<ProxyProfile> parsed = SubscriptionParser.parseAll(Arrays.asList(l1, "vless://bad", l2), skipped);
        assertEquals(2, parsed.size());
        assertEquals(1, skipped[0]);
    }

    // ----- config ---------------------------------------------------------------------------------

    private static XrayConfigBuilder.Options options() {
        XrayConfigBuilder.Options o = new XrayConfigBuilder.Options();
        o.socksPort = 12345;
        o.socksUser = "u";
        o.socksPass = "p";
        return o;
    }

    @Test
    public void configForProxyProfile() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("vless://" + UUID + "@example.com:443?type=ws&security=tls&path=%2F");
        XrayConfigBuilder.Options o = options();
        o.fragmentProxyConnection = true;
        JSONObject c = new JSONObject(XrayConfigBuilder.build(p, o));

        JSONObject in = c.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("socks", in.getString("protocol"));
        assertEquals("127.0.0.1", in.getString("listen"));
        assertEquals(12345, in.getInt("port"));
        assertEquals("password", in.getJSONObject("settings").getString("auth"));
        assertTrue(in.getJSONObject("settings").getBoolean("udp"));

        JSONArray outs = c.getJSONArray("outbounds");
        assertEquals("proxy", outs.getJSONObject(0).getString("tag"));
        assertEquals("vless", outs.getJSONObject(0).getString("protocol"));
        JSONObject st = outs.getJSONObject(0).getJSONObject("streamSettings");
        assertEquals("UseIPv4", st.getJSONObject("sockopt").getString("domainStrategy"));
        assertEquals("fragment", st.getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(0).getString("type"));

        JSONArray rules = c.getJSONObject("routing").getJSONArray("rules");
        assertEquals("dns-out", rules.getJSONObject(0).getString("outboundTag"));
        assertEquals("53", rules.getJSONObject(0).getString("port"));
        boolean privateDirect = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            privateDirect |= r.has("ip") && "direct".equals(r.getString("outboundTag"));
        }
        assertTrue(privateDirect);
        assertTrue(c.getJSONObject("dns").getJSONArray("servers").getString(0).startsWith("https+local://"));
    }

    @Test
    public void configForBuiltinFragmentProfile() throws Exception {
        XrayConfigBuilder.Options o = options();
        o.socksUser = null;
        JSONObject c = new JSONObject(XrayConfigBuilder.build(ProxyProfile.builtin("DPI"), o));
        assertEquals("noauth", c.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getString("auth"));
        JSONObject first = c.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("freedom", first.getString("protocol"));
        assertEquals("proxy", first.getString("tag"));
        JSONObject frag = first.getJSONObject("settings").getJSONObject("fragment");
        assertEquals("tlshello", frag.getString("packets"));
        assertEquals("100-200", frag.getString("length"));
    }

    @Test
    public void googleOnlyRouting() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("trojan://pw@t.example:443");
        XrayConfigBuilder.Options o = options();
        o.routeAll = false;
        JSONArray rules = new JSONObject(XrayConfigBuilder.build(p, o)).getJSONObject("routing").getJSONArray("rules");
        JSONObject last = rules.getJSONObject(rules.length() - 1);
        assertEquals("direct", last.getString("outboundTag"));
        boolean googleViaProxy = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            googleViaProxy |= r.has("domain") && "proxy".equals(r.getString("outboundTag"))
                    && r.getJSONArray("domain").toString().contains("googlevideo.com");
        }
        assertTrue(googleViaProxy);
    }

    @Test
    public void customConfigKeepsOutboundsButGetsOurInbound() throws Exception {
        String user = "{\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"mine\"}],"
                + "\"inbounds\":[{\"protocol\":\"http\",\"port\":1}]}";
        JSONObject c = new JSONObject(XrayConfigBuilder.build(ProxyProfile.custom("c", user), options()));
        assertEquals(1, c.getJSONArray("inbounds").length());
        assertEquals("socks", c.getJSONArray("inbounds").getJSONObject(0).getString("protocol"));
        assertEquals("mine", c.getJSONArray("outbounds").getJSONObject(0).getString("tag"));
        assertTrue(c.has("log"));
    }

    @Test
    public void profileJsonRoundTrip() throws Exception {
        ProxyProfile p = ShareLinkParser.parse("trojan://pw@t.example:443#Name");
        p.subscriptionId = "sub1";
        ProxyProfile q = ProxyProfile.fromJson(p.toJson());
        assertEquals(p.id, q.id);
        assertEquals("Name", q.name);
        assertEquals("sub1", q.subscriptionId);
        assertEquals(p.outbound, q.outbound);
        assertNull(ProxyProfile.fromJson(ProxyProfile.builtin("b").toJson()).outbound);
    }

    // ----- region ---------------------------------------------------------------------------------

    @Test
    public void regionRule() {
        Set<String> regions = RegionPolicy.parseList("ru, ir;CN  xxx");
        assertEquals(3, regions.size());
        assertTrue(RegionPolicy.isCensored(regions, "ru", null, "US"));
        assertTrue(RegionPolicy.isCensored(regions, "us", "IR", "US"));
        // A Russian-language traveller with a foreign SIM is not in Russia.
        assertFalse(RegionPolicy.isCensored(regions, "de", "de", "RU"));
        // No SIM and no operator (Wi-Fi tablet): the locale region decides.
        assertTrue(RegionPolicy.isCensored(regions, "", null, "RU"));
        assertFalse(RegionPolicy.isCensored(regions, null, null, null));
        assertNotNull(RegionPolicy.defaultSet());
        assertTrue(RegionPolicy.defaultSet().contains("RU"));
    }

    // ----- routes ---------------------------------------------------------------------------------

    @Test
    public void lanRoutesLeaveOutExactlyPrivateAndMulticast() {
        long[][] ranges = new long[LanRoutes.V4.length][2];
        for (int i = 0; i < LanRoutes.V4.length; i++) {
            String[] cidr = LanRoutes.V4[i].split("/");
            String[] o = cidr[0].split("\\.");
            long base = (Long.parseLong(o[0]) << 24) | (Long.parseLong(o[1]) << 16) | (Long.parseLong(o[2]) << 8) | Long.parseLong(o[3]);
            long size = 1L << (32 - Integer.parseInt(cidr[1]));
            assertEquals("not aligned: " + LanRoutes.V4[i], 0, base % size);
            ranges[i] = new long[] {base, base + size - 1};
        }
        java.util.Arrays.sort(ranges, (a, b) -> Long.compare(a[0], b[0]));
        long next = 0;
        java.util.List<long[]> gaps = new java.util.ArrayList<>();
        for (long[] r : ranges) {
            assertTrue("overlap at " + r[0], r[0] >= next);
            if (r[0] > next) {
                gaps.add(new long[] {next, r[0] - 1});
            }
            next = r[1] + 1;
        }
        if (next <= 0xFFFFFFFFL) {
            gaps.add(new long[] {next, 0xFFFFFFFFL});
        }
        long[][] expected = {
                {0x0A000000L, 0x0AFFFFFFL},   // 10.0.0.0/8
                {0xAC100000L, 0xAC1FFFFFL},   // 172.16.0.0/12
                {0xC0A80000L, 0xC0A8FFFFL},   // 192.168.0.0/16
                {0xE0000000L, 0xEFFFFFFFL}};  // 224.0.0.0/4 (multicast)
        assertEquals(expected.length, gaps.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], gaps.get(i)[0]);
            assertEquals(expected[i][1], gaps.get(i)[1]);
        }
    }
}
