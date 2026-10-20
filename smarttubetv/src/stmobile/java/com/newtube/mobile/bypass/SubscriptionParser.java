package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reads a subscription body: base64 of a link list, or the plain list, one link per line. */
public final class SubscriptionParser {
    private SubscriptionParser() {
    }

    /** The share links in a subscription response, in order, without duplicates. */
    @NonNull
    public static List<String> extractLinks(@NonNull String body) {
        String text = body.trim();
        if (!text.contains("://")) {
            try {
                text = new String(B64.decode(text), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return new ArrayList<>();
            }
        }
        Set<String> links = new LinkedHashSet<>();
        for (String line : text.split("[\\r\\n]+")) {
            String l = line.trim();
            if (ShareLinkParser.looksLikeLink(l)) {
                links.add(l);
            }
        }
        return new ArrayList<>(links);
    }

    /** Parses every usable link; the ones this build cannot use are counted in {@code skipped[0]}. */
    @NonNull
    public static List<ProxyProfile> parseAll(@NonNull List<String> links, @NonNull int[] skipped) {
        List<ProxyProfile> out = new ArrayList<>();
        for (String link : links) {
            try {
                out.add(ShareLinkParser.parse(link));
            } catch (ShareLinkParser.LinkException e) {
                skipped[0]++;
            }
        }
        return out;
    }
}
