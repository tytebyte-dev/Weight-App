package com.newtube.mobile.bypass;

/**
 * What the TUN claims, so that LAN traffic (casting discovery, printers, the router page) and
 * multicast never enter the tunnel: all of IPv4 except 10/8, 172.16/12, 192.168/16 and 224/4, and
 * global-unicast IPv6 only. Android's Builder.excludeRoute needs API 33; this works from API 24.
 */
final class LanRoutes {
    private LanRoutes() {
    }

    /** "address/prefix" pairs; together they cover IPv4 minus the excluded ranges (see LanRoutesTest). */
    static final String[] V4 = {
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2",
            "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10",
            "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
            "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12",
            "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4",
            "240.0.0.0/4"};

    /** Global unicast only: link-local, unique-local and multicast stay out. */
    static final String[] V6 = {"2000::/3"};
}
