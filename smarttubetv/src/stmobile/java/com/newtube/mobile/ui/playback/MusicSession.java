package com.newtube.mobile.ui.playback;

/**
 * Marks a playback session started from the Music tab. Such a session never shows the player:
 * the playback screen starts invisible and minimizes itself as soon as the stream is ready, and
 * Browse shows a compact music bar instead of the video card. Main-thread only.
 */
public final class MusicSession {
    private static boolean sArmed;
    private static boolean sMusic;

    private MusicSession() {
    }

    /** A Music-tab tap is about to open the player. */
    public static void arm() {
        sArmed = true;
        sMusic = true;
    }

    /** A track was handed to an already live player: the bar takes over from the video card. */
    public static void markMusic() {
        sMusic = true;
    }

    public static boolean isArmed() {
        return sArmed;
    }

    /** True once: the player is ready and should now minimize itself. */
    public static boolean consumeArmed() {
        boolean armed = sArmed;
        sArmed = false;
        return armed;
    }

    /** The live mini session came from the Music tab. */
    public static boolean isMusic() {
        return sMusic;
    }

    /** The player was opened normally (or closed): back to ordinary video behaviour. */
    public static void end() {
        sArmed = false;
        sMusic = false;
    }
}
