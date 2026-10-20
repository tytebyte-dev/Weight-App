package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the device is in a region where YouTube / Google are blocked or throttled, so the
 * bypass should switch on by itself. The default list is a heuristic and is editable in Settings.
 */
public final class RegionPolicy {
    /** ISO 3166-1 alpha-2 codes. */
    public static final String DEFAULT_REGIONS = "RU,BY,IR,CN,TM,MM,KP";

    private RegionPolicy() {
    }

    @NonNull
    public static Set<String> parseList(@Nullable String csv) {
        if (csv == null) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split("[,;\\s]+")) {
            String c = part.trim().toUpperCase(Locale.ROOT);
            if (c.length() == 2) {
                out.add(c);
            }
        }
        return out;
    }

    @NonNull
    public static Set<String> defaultSet() {
        return new LinkedHashSet<>(Arrays.asList(DEFAULT_REGIONS.split(",")));
    }

    /**
     * SIM and network (operator) country are the trustworthy signals; the locale region only counts
     * when neither is known (Wi-Fi-only tablet), because a Russian-speaking traveller is not in Russia.
     */
    public static boolean isCensored(@NonNull Set<String> regions, @Nullable String simCountry,
                                     @Nullable String networkCountry, @Nullable String localeCountry) {
        String sim = normalize(simCountry);
        String net = normalize(networkCountry);
        if (sim != null || net != null) {
            return (sim != null && regions.contains(sim)) || (net != null && regions.contains(net));
        }
        String loc = normalize(localeCountry);
        return loc != null && regions.contains(loc);
    }

    @Nullable
    private static String normalize(@Nullable String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.length() == 2 ? c : null;
    }
}
