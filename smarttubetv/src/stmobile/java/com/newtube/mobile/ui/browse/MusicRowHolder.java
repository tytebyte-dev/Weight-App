package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.downloads.DownloadsBridge;

import java.util.HashSet;
import java.util.Set;

/**
 * A row of the Music tab: art, title, artist, duration, then download, like (star) and the
 * three-dots menu (the same context menu as a card long-press).
 */
final class MusicRowHolder extends RecyclerView.ViewHolder {
    /** Songs starred in this session (the service only reports the rating on the watch page). */
    private static final Set<String> LIKED = new HashSet<>();

    private final ImageView mArt;
    private final TextView mTitle;
    private final TextView mArtist;
    private final TextView mDuration;
    private final ImageButton mDownload;
    private final ImageButton mLike;
    private final ImageButton mMore;
    private final EqualizerView mEq;
    private Video mVideo;

    MusicRowHolder(@NonNull View itemView, VideoCardAdapter.OnVideoClickListener clickListener) {
        super(itemView);
        mArt = itemView.findViewById(R.id.music_art);
        mTitle = itemView.findViewById(R.id.music_title);
        mArtist = itemView.findViewById(R.id.music_artist);
        mDuration = itemView.findViewById(R.id.music_duration);
        mDownload = itemView.findViewById(R.id.music_download);
        mLike = itemView.findViewById(R.id.music_like);
        mMore = itemView.findViewById(R.id.music_more);
        mEq = itemView.findViewById(R.id.music_eq);

        itemView.setOnClickListener(v -> {
            if (mVideo != null && clickListener != null) {
                com.newtube.mobile.ui.playback.PlayerTransitionBridge.clear();
                clickListener.onVideoClick(mVideo);
            }
        });
    }

    void bind(Video video, VideoCardAdapter.OnVideoLongClickListener longClickListener) {
        mVideo = video;
        Context context = itemView.getContext();

        mTitle.setText(video.getTitle());
        CharSequence artist = video.getAuthor();
        if (artist == null || artist.length() == 0) {
            artist = video.getSecondTitle();
        }
        mArtist.setText(artist);
        mArtist.setVisibility(artist == null || artist.length() == 0 ? View.GONE : View.VISIBLE);

        String duration = video.badge;
        boolean playable = video.videoId != null;
        mDuration.setText(duration);
        mDuration.setVisibility(duration == null || duration.isEmpty() ? View.GONE : View.VISIBLE);

        // Playlists and artists have nothing to download or star.
        mDownload.setVisibility(playable ? View.VISIBLE : View.GONE);
        mLike.setVisibility(playable ? View.VISIBLE : View.GONE);
        mMore.setVisibility(longClickListener == null ? View.GONE : View.VISIBLE);

        itemView.setOnLongClickListener(v ->
                mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));
        mMore.setOnClickListener(v -> {
            if (mVideo != null && longClickListener != null) {
                longClickListener.onVideoLongClick(mVideo);
            }
        });
        mMore.setContentDescription(video.getTitle() != null
                ? context.getString(R.string.mobile_more_options_for, video.getTitle())
                : context.getString(R.string.mobile_player_more));

        mDownload.setOnClickListener(v -> {
            if (mVideo != null) {
                DownloadsBridge.instance(context).requestDownload(context, mVideo);
            }
        });

        showLiked(playable && video.videoId != null && LIKED.contains(video.videoId));
        mLike.setOnClickListener(v -> toggleLike(context));

        Glide.with(context)
                .load(video.getCardImageUrl())
                .centerCrop()
                .into(mArt);
    }

    /** The track that is playing gets the dancing bars over its cover. */
    void bindNow(Video video, String nowId, boolean playing) {
        boolean current = video != null && video.videoId != null && video.videoId.equals(nowId);
        mEq.setVisibility(current ? View.VISIBLE : View.GONE);
        mEq.setPlaying(current && playing);
    }

    private void showLiked(boolean liked) {
        mLike.setImageResource(liked ? R.drawable.ic_music_star_filled : R.drawable.ic_music_star);
    }

    private void toggleLike(Context context) {
        Video video = mVideo;
        if (video == null || video.videoId == null) {
            return;
        }
        if (!YouTubeSignInService.instance().isSigned()) {
            MessageHelpers.showMessage(context, R.string.music_like_signin);
            return;
        }
        boolean wasLiked = LIKED.contains(video.videoId);
        try {
            if (wasLiked) {
                RxHelper.execute(YouTubeServiceManager.instance().getMediaItemService().removeLikeObserve(
                        video.mediaItem != null ? video.mediaItem : video.toMediaItem()));
                LIKED.remove(video.videoId);
            } else {
                RxHelper.execute(YouTubeServiceManager.instance().getMediaItemService().setLikeObserve(
                        video.mediaItem != null ? video.mediaItem : video.toMediaItem()));
                LIKED.add(video.videoId);
            }
            showLiked(!wasLiked);
        } catch (RuntimeException e) {
            MessageHelpers.showMessage(context, R.string.music_like_failed);
        }
    }

    void unbind() {
        mVideo = null;
        Glide.with(itemView.getContext().getApplicationContext()).clear(mArt);
    }
}
