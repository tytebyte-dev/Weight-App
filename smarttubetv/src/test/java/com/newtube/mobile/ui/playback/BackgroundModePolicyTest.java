package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.newtube.mobile.ui.playback.BackgroundModePolicy.Action;

import org.junit.Test;

/**
 * NEWTUBE(background-mode): the phone ignored "Play in background" and entered PiP on Home in every
 * mode, so "Only audio" users got a PiP window whose X stopped their audio (Reddit, 2026-09-29).
 */
public class BackgroundModePolicyTest {

    @Test
    public void onlyAudioLeavesWithoutPipAndKeepsTheAudio() {
        assertEquals(Action.BACKGROUND_AUDIO,
                BackgroundModePolicy.onLeave(PlayerConstants.BACKGROUND_MODE_SOUND));
    }

    @Test
    public void onlyAudioNeverArmsTheAndroid12AutoEnter() {
        // The home gesture on Android 12+ never reaches onUserLeaveHint in time: the standing flag
        // alone decides it, so it must be off too, not just the onUserLeaveHint path.
        assertFalse(BackgroundModePolicy.autoEnterPip(PlayerConstants.BACKGROUND_MODE_SOUND));
    }

    @Test
    public void pictureInPictureLeavesIntoPip() {
        assertEquals(Action.PIP, BackgroundModePolicy.onLeave(PlayerConstants.BACKGROUND_MODE_PIP));
        assertTrue(BackgroundModePolicy.autoEnterPip(PlayerConstants.BACKGROUND_MODE_PIP));
    }

    @Test
    public void disabledKeepsThePhoneDefaultPip() {
        // "Disabled" is the stored default (nobody opened the list): unchanged phone behaviour.
        assertEquals(Action.PIP,
                BackgroundModePolicy.onLeave(PlayerConstants.BACKGROUND_MODE_DEFAULT));
        assertTrue(BackgroundModePolicy.autoEnterPip(PlayerConstants.BACKGROUND_MODE_DEFAULT));
    }

    @Test
    public void theTvOnlyPlayBehindChoiceFallsBackToThePhoneDefault() {
        // Not offered on a phone (Android TV 5-7 only); a stale value must not disable PiP.
        assertEquals(Action.PIP,
                BackgroundModePolicy.onLeave(PlayerConstants.BACKGROUND_MODE_PLAY_BEHIND));
    }
}
