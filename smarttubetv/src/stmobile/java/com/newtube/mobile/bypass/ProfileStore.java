package com.newtube.mobile.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The saved profiles and subscriptions. The built-in server-less profile is always first and cannot
 * be removed. Reads and writes are cheap (a few KB of JSON), done synchronously.
 */
public final class ProfileStore {
    /** A subscription URL and when it was last fetched. */
    public static final class Subscription {
        public final String id;
        public String name;
        public final String url;
        public long updatedAt;
        public int count;

        Subscription(String id, String name, String url, long updatedAt, int count) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.updatedAt = updatedAt;
            this.count = count;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("url", url)
                    .put("updatedAt", updatedAt).put("count", count);
        }
    }

    private static final String PROFILES = "profiles";
    private static final String SUBSCRIPTIONS = "subscriptions";
    private static ProfileStore sInstance;

    private final SharedPreferences mPrefs;
    private final String mBuiltinName;

    private ProfileStore(Context context) {
        mPrefs = BypassSettings.instance(context).prefs();
        mBuiltinName = context.getString(com.liskovsoft.smartyoutubetv2.tv.R.string.bypass_profile_builtin);
    }

    @NonNull
    public static synchronized ProfileStore instance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new ProfileStore(context.getApplicationContext());
        }
        return sInstance;
    }

    /** Built-in first, then the saved ones in the order they were added. */
    @NonNull
    public synchronized List<ProxyProfile> all() {
        List<ProxyProfile> out = new ArrayList<>();
        out.add(ProxyProfile.builtin(mBuiltinName));
        try {
            JSONArray a = new JSONArray(mPrefs.getString(PROFILES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(ProxyProfile.fromJson(a.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            // A damaged list reads as empty rather than taking the app down.
        }
        return out;
    }

    @Nullable
    public synchronized ProxyProfile get(@NonNull String id) {
        for (ProxyProfile p : all()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** The selected profile; the built-in one when the selection no longer exists. */
    @NonNull
    public synchronized ProxyProfile selected(@NonNull BypassSettings settings) {
        ProxyProfile p = get(settings.selectedProfileId());
        return p != null ? p : all().get(0);
    }

    public synchronized void add(@NonNull ProxyProfile profile) {
        List<ProxyProfile> saved = saved();
        saved.add(profile);
        write(saved);
    }

    public synchronized void addAll(@NonNull List<ProxyProfile> profiles) {
        List<ProxyProfile> saved = saved();
        saved.addAll(profiles);
        write(saved);
    }

    public synchronized void remove(@NonNull String id) {
        List<ProxyProfile> saved = saved();
        for (Iterator<ProxyProfile> it = saved.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) {
                it.remove();
            }
        }
        write(saved);
    }

    public synchronized void rename(@NonNull String id, @NonNull String name) {
        List<ProxyProfile> saved = saved();
        for (ProxyProfile p : saved) {
            if (p.id.equals(id)) {
                p.name = name;
            }
        }
        write(saved);
    }

    /** Replaces every profile of one subscription with a fresh list (the selection survives by link). */
    public synchronized void replaceSubscription(@NonNull String subId, @NonNull List<ProxyProfile> fresh,
                                                 @NonNull BypassSettings settings) {
        List<ProxyProfile> saved = saved();
        String selectedId = settings.selectedProfileId();
        String selectedLink = null;
        for (Iterator<ProxyProfile> it = saved.iterator(); it.hasNext(); ) {
            ProxyProfile p = it.next();
            if (subId.equals(p.subscriptionId)) {
                if (p.id.equals(selectedId)) {
                    selectedLink = p.link;
                }
                it.remove();
            }
        }
        for (ProxyProfile p : fresh) {
            p.subscriptionId = subId;
            saved.add(p);
            if (selectedLink != null && selectedLink.equals(p.link)) {
                settings.setSelectedProfileId(p.id);
            }
        }
        write(saved);
    }

    // ---- subscriptions -------------------------------------------------------------------------

    @NonNull
    public synchronized List<Subscription> subscriptions() {
        List<Subscription> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(mPrefs.getString(SUBSCRIPTIONS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Subscription(o.getString("id"), o.optString("name", ""), o.getString("url"),
                        o.optLong("updatedAt", 0), o.optInt("count", 0)));
            }
        } catch (JSONException ignored) {
            // read as empty
        }
        return out;
    }

    @NonNull
    public synchronized Subscription addSubscription(@NonNull String name, @NonNull String url) {
        List<Subscription> subs = subscriptions();
        Subscription s = new Subscription(UUID.randomUUID().toString(), name, url, 0, 0);
        subs.add(s);
        writeSubs(subs);
        return s;
    }

    public synchronized void markSubscriptionUpdated(@NonNull String subId, int count) {
        List<Subscription> subs = subscriptions();
        for (Subscription s : subs) {
            if (s.id.equals(subId)) {
                s.updatedAt = System.currentTimeMillis();
                s.count = count;
            }
        }
        writeSubs(subs);
    }

    /** Removes a subscription and all of its profiles. */
    public synchronized void removeSubscription(@NonNull String subId) {
        List<Subscription> subs = subscriptions();
        for (Iterator<Subscription> it = subs.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(subId)) {
                it.remove();
            }
        }
        writeSubs(subs);
        List<ProxyProfile> saved = saved();
        for (Iterator<ProxyProfile> it = saved.iterator(); it.hasNext(); ) {
            if (subId.equals(it.next().subscriptionId)) {
                it.remove();
            }
        }
        write(saved);
    }

    // ---- storage -------------------------------------------------------------------------------

    private List<ProxyProfile> saved() {
        List<ProxyProfile> all = all();
        return new ArrayList<>(all.subList(1, all.size()));
    }

    private void write(List<ProxyProfile> saved) {
        JSONArray a = new JSONArray();
        try {
            for (ProxyProfile p : saved) {
                a.put(p.toJson());
            }
        } catch (JSONException e) {
            return; // never half-write the list
        }
        mPrefs.edit().putString(PROFILES, a.toString()).apply();
    }

    private void writeSubs(List<Subscription> subs) {
        JSONArray a = new JSONArray();
        try {
            for (Subscription s : subs) {
                a.put(s.toJson());
            }
        } catch (JSONException e) {
            return;
        }
        mPrefs.edit().putString(SUBSCRIPTIONS, a.toString()).apply();
    }
}
