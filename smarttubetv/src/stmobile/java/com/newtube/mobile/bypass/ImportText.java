package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Tells what a pasted text is: share links, a subscription URL, or a full Xray JSON config. */
public final class ImportText {
    public enum Kind { LINKS, SUBSCRIPTION_URL, XRAY_JSON, UNKNOWN }

    private ImportText() {
    }

    @NonNull
    public static Kind classify(@NonNull String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return Kind.UNKNOWN;
        }
        for (String line : t.split("[\\r\\n]+")) {
            if (ShareLinkParser.looksLikeLink(line.trim())) {
                return Kind.LINKS;
            }
        }
        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                if (o.has("outbounds")) {
                    return Kind.XRAY_JSON;
                }
            } catch (JSONException ignored) {
                return Kind.UNKNOWN;
            }
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if ((lower.startsWith("http://") || lower.startsWith("https://")) && !t.contains("\n") && !t.contains(" ")) {
            return Kind.SUBSCRIPTION_URL;
        }
        return Kind.UNKNOWN;
    }

    /** The links in a pasted block, one per line. */
    @NonNull
    public static List<String> links(@NonNull String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.trim().split("[\\r\\n]+")) {
            String l = line.trim();
            if (ShareLinkParser.looksLikeLink(l)) {
                out.add(l);
            }
        }
        return out;
    }
}
