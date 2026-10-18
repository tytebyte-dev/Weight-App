package com.newtube.mobile.ui.playback;

import com.liskovsoft.smartyoutubetv2.common.misc.PhoneBackgroundMode;

/**
 * NEWTUBE(background-mode): what leaving a playing video does on the phone, from the user's
 * "Play in background" choice ({@code PlayerData.getBackgroundMode()}, the same radio list in the
 * player menu and in Settings &gt; General, which on the phone offers just "Picture in picture" and
 * "Only audio"; both read stored values through {@link PhoneBackgroundMode}).
 *
 * <p>Until 1.11.0 the phone never read that choice: the player armed Android's auto-enter PiP and
 * entered PiP from {@code onUserLeaveHint} for every playing video, so "Only audio" still shrank
 * the video into a PiP window on Home, and closing that window stopped the audio the user had asked
 * for (Reddit, 2026-09-29). Leaving from the choice dialog itself did play audio, only because the
 * player is paused under that dialog and so never auto-enters PiP.</p>
 *
 * <p>The phone keeps a single rule per event, whatever key the TV-era label names:</p>
 * <ul>
 *   <li>Home / leaving the app: picture-in-picture, unless the user picked "Only audio" - then the
 *       video leaves the screen and the audio keeps playing with the media notification (the
 *       player's {@code onStop} audio-only path, the same one screen-off always took). A stored
 *       "Disabled" (the default; no longer offered on the phone) keeps PiP, so nothing changes for
 *       anyone who never opened the list.</li>
 *   <li>Closing a PiP window (X, swipe away) closes the video in every mode, like YouTube
 *       ({@code finishFromPipDismiss}, not decided here): the user dismissed the video, and a PiP
 *       entered by hand from the player menu is a video window too. With "Only audio" a PiP only
 *       exists when the user asked for it from the menu.</li>
 *   <li>Back and screen-off are not decided here: Back keeps its in-app meaning, and screen-off
 *       always keeps the audio playing.</li>
 * </ul>
 */
final class BackgroundModePolicy {
    enum Action {
        /** Shrink the playing video into a system picture-in-picture window. */
        PIP,
        /** Let the window go; the service keeps the audio playing (video track dropped). */
        BACKGROUND_AUDIO
    }

    private BackgroundModePolicy() {
    }

    /** The user leaves the app (Home, the home gesture, recents, another app) while a video plays. */
    static Action onLeave(int backgroundMode) {
        return PhoneBackgroundMode.isOnlyAudio(backgroundMode) ? Action.BACKGROUND_AUDIO : Action.PIP;
    }

    /** Whether the Android 12+ standing auto-enter flag may be armed for this choice. */
    static boolean autoEnterPip(int backgroundMode) {
        return onLeave(backgroundMode) == Action.PIP;
    }
}
