package com.newtube.mobile.ui.common;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(haptics): the app's haptic vocabulary, taken from what the YouTube app plays (read from
 * the vibrator service's history while using YouTube 21.18): a light tick when the seek bar crosses
 * a chapter boundary, a click when it snaps back to where the drag started ("Release to cancel")
 * and on a like, and a firm buzz when press-and-hold turns on 2x speed. Taps, double-tap seeks,
 * swipes and play/pause stay silent there, and here too: haptics mark a boundary, a snap or a
 * confirmed action, never an ordinary touch.
 *
 * <p>Drags that commit on release (swipe the player down, swipe the mini card away, pull to
 * refresh) speak the Pixel's own drag language instead, read the same way from a Pixel 9 on
 * Android 17 while its owner swiped notifications away and went home: a faint grain of LOW_TICKs
 * while something is held back ({@link #tension}), and one CLICK at 0.7 the moment it lets go or
 * sticks again ({@link #threshold}). Those are vibration primitives, which only the Vibrator plays;
 * a phone without them gets the platform's threshold constant and no grain.</p>
 *
 * <p>performHapticFeedback needs no permission and follows the system's touch-feedback setting,
 * so a phone with vibration off stays silent; the primitives check the same setting themselves and
 * play as touch feedback, which the system scales or mutes with the touch intensity.</p>
 */
public final class Haptics {

    /**
     * The grain: five LOW_TICKs, at most one burst per 60 ms, scaled like SystemUI's notification
     * pull (MagneticNotificationRowManagerImpl: 0.2 * n^1.27, then ^(1/0.89) for perception, where
     * n reaches 0.5 at the detach point). It is barely there - the Pixel's own peaks at ~0.06.
     */
    private static final int TENSION_TICKS = 5;
    private static final long TENSION_INTERVAL_MS = 60;
    private static final float TENSION_GAIN = 0.2f;
    /** SystemUI's SWIPE_THRESHOLD_INDICATOR token: one CLICK primitive at 0.7. */
    private static final float THRESHOLD_CLICK_SCALE = 0.7f;
    /**
     * No grain this soon after a click: a new vibration cancels the one playing, and a grain
     * started with the click (the same touch event that crossed the line back) cut it after 1-5 ms
     * - the owner felt the click come back only sometimes (Pixel vibrator log: cancelled_superseded).
     */
    private static final long CLICK_GUARD_MS = 100;

    private static long sLastTensionAt;
    private static long sLastThresholdAt;
    private static boolean sProbed;
    @Nullable
    private static Vibrator sComposer;

    private Haptics() {
    }

    /** A boundary crossed while dragging (a chapter on the seek bar, a zoom snap). */
    public static void tick(@Nullable View view) {
        if (view != null) {
            // SEGMENT_TICK is the platform's name for exactly this (API 34); before it,
            // CONTEXT_CLICK is the constant that plays the same light TICK effect.
            view.performHapticFeedback(Build.VERSION.SDK_INT >= 34
                    ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CONTEXT_CLICK);
        }
    }

    /** A snap into a resting place, or an action taking effect (like, dislike). */
    public static void click(@Nullable View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /** Press-and-hold switching a mode on. */
    public static void longPress(@Nullable View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /**
     * A drag crossed the point where letting go acts ({@code engaged}), or came back behind it.
     * The Pixel plays the same click both ways; the platform fallback has a pair.
     */
    public static void threshold(@Nullable View view, boolean engaged) {
        if (view == null) {
            return;
        }
        sLastThresholdAt = SystemClock.uptimeMillis();
        Vibrator composer = composer(view);
        if (composer != null) {
            vibrate(composer, VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, THRESHOLD_CLICK_SCALE)
                    .compose());
        } else if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(engaged ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    : HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE);
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /**
     * Something is being pulled against a hold; {@code progress} is how far toward its threshold
     * (0..1). Call it on every move - it paces itself - and it stays silent where the phone has no
     * primitives: a platform tick every 60 ms would be a buzz, not a grain.
     */
    public static void tension(@Nullable View view, float progress) {
        if (view == null || progress <= 0f) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastTensionAt < TENSION_INTERVAL_MS || now - sLastThresholdAt < CLICK_GUARD_MS) {
            return;
        }
        Vibrator composer = composer(view);
        if (composer == null) {
            return;
        }
        sLastTensionAt = now;
        double pulled = 0.5 * Math.min(1f, progress);
        float scale = (float) Math.min(1.0, Math.pow(TENSION_GAIN * Math.pow(pulled, 1.27), 1 / 0.89));
        VibrationEffect.Composition grain = VibrationEffect.startComposition();
        for (int i = 0; i < TENSION_TICKS; i++) {
            grain.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale);
        }
        vibrate(composer, grain.compose());
    }

    /** The vibrator when it can compose primitives and touch feedback is on, else null. */
    @Nullable
    private static Vibrator composer(View view) {
        if (Build.VERSION.SDK_INT < 33 || !view.isHapticFeedbackEnabled()) {
            return null;
        }
        Context context = view.getContext();
        if (Settings.System.getInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
            return null;
        }
        if (!sProbed) {
            sProbed = true;
            VibratorManager manager = context.getApplicationContext().getSystemService(VibratorManager.class);
            Vibrator vibrator = manager != null ? manager.getDefaultVibrator() : null;
            if (vibrator != null && vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                sComposer = vibrator;
            }
        }
        return sComposer;
    }

    private static void vibrate(Vibrator vibrator, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        }
    }
}
