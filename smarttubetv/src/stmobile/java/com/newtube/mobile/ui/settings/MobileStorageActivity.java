package com.newtube.mobile.ui.settings;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.VideoCacheCleaner;
import com.newtube.mobile.ui.common.MobileActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Storage usage": what the app's own cache holds (streamed video, pictures, other) and a button
 * that clears the ticked parts. Downloads (the Downloads tab) are never counted or touched.
 */
public class MobileStorageActivity extends MobileActivity {
    private static final int VIDEO = 0;
    private static final int IMAGES = 1;
    private static final int OTHER = 2;
    private static final int[] COLORS = {0xFFF0883E, 0xFF4D9DF7, 0xFF3FBF55};
    private static final String[] KEEP_DIRS = {"media3-media-cache", "image_manager_disk_cache", "downloads"};

    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final long[] mSizes = new long[3];
    private final CheckBox[] mChecks = new CheckBox[3];
    private final TextView[] mSizeViews = new TextView[3];
    private final TextView[] mNameViews = new TextView[3];
    private StorageDonutView mDonut;
    private TextView mTotal;
    private MaterialButton mClear;
    private boolean mBusy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_storage);

        findViewById(R.id.storage_back).setOnClickListener(v -> finish());
        mDonut = findViewById(R.id.storage_donut);
        mTotal = findViewById(R.id.storage_total);
        mClear = findViewById(R.id.storage_clear);

        LinearLayout rows = findViewById(R.id.storage_rows);
        int[] names = {R.string.storage_video, R.string.storage_images, R.string.storage_other};
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < 3; i++) {
            View row = inflater.inflate(R.layout.item_mobile_storage_row, rows, false);
            CheckBox box = row.findViewById(R.id.storage_check);
            box.setButtonTintList(ColorStateList.valueOf(COLORS[i]));
            box.setChecked(true);
            box.setOnCheckedChangeListener((b, checked) -> updateButton());
            row.setOnClickListener(v -> box.toggle());
            mChecks[i] = box;
            mNameViews[i] = row.findViewById(R.id.storage_name);
            mNameViews[i].setText(names[i]);
            mSizeViews[i] = row.findViewById(R.id.storage_size);
            rows.addView(row);
        }
        mClear.setOnClickListener(v -> clearSelected());
        refresh();
    }

    @Override
    protected void onDestroy() {
        mIo.shutdown();
        super.onDestroy();
    }

    private void refresh() {
        mIo.execute(() -> {
            long video = VideoCacheCleaner.cacheBytes(this);
            File cacheDir = getCacheDir();
            long images = size(new File(cacheDir, "image_manager_disk_cache"));
            long other = 0;
            File[] children = cacheDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!isKept(child)) {
                        other += size(child);
                    }
                }
            }
            long[] result = {video, images, other};
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                System.arraycopy(result, 0, mSizes, 0, 3);
                render();
            });
        });
    }

    private void render() {
        long total = 0;
        for (long v : mSizes) {
            total += v;
        }
        mTotal.setText(Formatter.formatFileSize(this, total));
        mDonut.setData(mSizes.clone(), COLORS);
        for (int i = 0; i < 3; i++) {
            mSizeViews[i].setText(Formatter.formatFileSize(this, mSizes[i]));
            int percent = total > 0 ? Math.round(100f * mSizes[i] / total) : 0;
            mNameViews[i].setText(getString(new int[] {R.string.storage_video, R.string.storage_images,
                    R.string.storage_other}[i]) + "  " + percent + "%");
        }
        updateButton();
    }

    private void updateButton() {
        long selected = 0;
        for (int i = 0; i < 3; i++) {
            if (mChecks[i].isChecked()) {
                selected += mSizes[i];
            }
        }
        mClear.setText(getString(R.string.storage_clear, Formatter.formatFileSize(this, selected)));
        mClear.setEnabled(!mBusy && selected > 0);
    }

    private void clearSelected() {
        if (mBusy) {
            return;
        }
        mBusy = true;
        long before = 0;
        boolean[] pick = new boolean[3];
        for (int i = 0; i < 3; i++) {
            pick[i] = mChecks[i].isChecked();
            if (pick[i]) {
                before += mSizes[i];
            }
        }
        updateButton();
        final long expected = before;
        mIo.execute(() -> {
            if (pick[VIDEO]) {
                VideoCacheCleaner.clearAll(this);
            }
            if (pick[IMAGES]) {
                Glide.get(getApplicationContext()).clearDiskCache(); // must run off the main thread
            }
            if (pick[OTHER]) {
                File[] children = getCacheDir().listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!isKept(child)) {
                            delete(child);
                        }
                    }
                }
            }
            runOnUiThread(() -> {
                mBusy = false;
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, getString(R.string.storage_cleared,
                            Formatter.formatFileSize(this, expected)), Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
        });
    }

    /** The cache's video, picture and download-part folders are counted (or left alone) on their own. */
    private static boolean isKept(File file) {
        for (String name : KEEP_DIRS) {
            if (name.equals(file.getName())) {
                return true;
            }
        }
        return false;
    }

    private static long size(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += size(child);
            }
        }
        return total;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
