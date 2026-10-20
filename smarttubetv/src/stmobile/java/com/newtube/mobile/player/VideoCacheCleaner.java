package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.media3.datasource.cache.Cache;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.newtube.mobile.downloads.DownloadItem;
import com.newtube.mobile.downloads.DownloadRegistry;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the streaming cache from growing like a short-video feed: once a video has been watched to
 * (nearly) the end AND the player has really been closed, its cached ranges are dropped.
 *
 * <p>Never touches a video that is in the Downloads tab (those files live in their own folder and
 * are also skipped by id here), and never a video a live player session is on. The list of videos
 * waiting to be cleaned is persisted, so a process that dies right after the player closes still
 * gets cleaned on the next start ({@link #sweepPending}).</p>
 */
public final class VideoCacheCleaner {
    private static final String TAG = VideoCacheCleaner.class.getSimpleName();
    private static final String PREFS = "cache_cleaner";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PENDING = "pending";
    /** Watched = this much of the video played, or only the credits tail is left. */
    private static final double WATCHED_FRACTION = 0.9;
    private static final long TAIL_MS = 30_000;
    private static final long MIN_DURATION_MS = 10_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-cache-cleaner");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private VideoCacheCleaner() {
    }

    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Whether a video at {@code positionMs} of {@code durationMs} (or one that ended) counts as watched. */
    public static boolean isWatched(long positionMs, long durationMs, boolean ended) {
        if (ended) {
            return true;
        }
        if (durationMs < MIN_DURATION_MS || positionMs < 0) {
            return false; // live, unknown length or a clip too short to judge
        }
        return positionMs >= durationMs * WATCHED_FRACTION || durationMs - positionMs <= TAIL_MS;
    }

    /** The player was closed (not just minimized) after these videos had been watched. */
    public static void onPlayerClosed(@NonNull Context context, @NonNull Collection<String> watchedIds) {
        if (!isEnabled(context) || watchedIds.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        synchronized (VideoCacheCleaner.class) {
            Set<String> pending = new HashSet<>(prefs(app).getStringSet(KEY_PENDING, new HashSet<>()));
            pending.addAll(watchedIds);
            prefs(app).edit().putStringSet(KEY_PENDING, pending).apply();
        }
        IO.execute(() -> processPending(app));
    }

    /** Cleans what an earlier run left waiting (call once at app start, off the main thread's critical path). */
    public static void sweepPending(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app)) {
            return;
        }
        IO.execute(() -> processPending(app));
    }

    private static void processPending(Context context) {
        try {
            Set<String> pending;
            synchronized (VideoCacheCleaner.class) {
                pending = new HashSet<>(prefs(context).getStringSet(KEY_PENDING, new HashSet<>()));
            }
            if (pending.isEmpty()) {
                return;
            }
            Cache cache = Media3PlayerCache.get(context);
            Set<String> done = new HashSet<>();
            for (String id : pending) {
                if (id == null || id.isEmpty() || isDownloaded(context, id) || isOnLivePlayer(id)) {
                    done.add(id); // not ours to delete; it is judged again when its player closes
                    continue;
                }
                if (cache == null || purge(cache, id)) {
                    done.add(id);
                }
            }
            synchronized (VideoCacheCleaner.class) {
                Set<String> left = new HashSet<>(prefs(context).getStringSet(KEY_PENDING, new HashSet<>()));
                left.removeAll(done);
                prefs(context).edit().putStringSet(KEY_PENDING, left).apply();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Cache cleanup failed: " + t.getMessage());
        }
    }

    private static boolean isDownloaded(Context context, String videoId) {
        for (DownloadItem item : DownloadRegistry.instance(context).items()) {
            if (videoId.equals(item.videoId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnLivePlayer(String videoId) {
        Video playing = MiniPlayerBridge.isActive() ? MiniPlayerBridge.getVideo() : null;
        return playing != null && videoId.equals(playing.videoId);
    }

    /** Removes every cached range of the video (all formats). @return false if the cache refused. */
    private static boolean purge(Cache cache, String videoId) {
        String prefix = "yt." + videoId + ".";
        try {
            List<String> keys = new ArrayList<>(cache.getKeys());
            for (String key : keys) {
                if (key.startsWith(prefix)) {
                    cache.removeResource(key);
                }
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot purge " + videoId + ": " + e.getMessage());
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
