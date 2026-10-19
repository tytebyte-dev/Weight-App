package com.liskovsoft.smartyoutubetv2.common.proxy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the app flavor hold the very first network traffic until a censorship-bypass tunnel is up.
 *
 * <p>The phone flavor registers a {@link Gate} at process start. {@code SplashPresenter} runs its
 * one-time start-up through {@link #run}: with no gate (TV flavors, bypass off, bypass already up)
 * the continuation runs at once, exactly as before; otherwise it runs when the gate says it is
 * ready or after {@link #MAX_WAIT_MS}, whichever comes first, on the main thread.</p>
 */
public final class StartupGate {
    /** The longest the first screen waits for the tunnel. */
    public static final long MAX_WAIT_MS = 3_500;

    public interface Gate {
        /**
         * Called on the main thread once per process before the first network use. Return true if
         * the tunnel is still coming up: {@code ready} must then be run (from any thread) when it is
         * usable or has failed. Return false to continue at once.
         */
        boolean hold(@NonNull Context context, @NonNull Runnable ready);
    }

    @Nullable private static volatile Gate sGate;
    private static boolean sConsulted;

    private StartupGate() {
    }

    public static void setGate(@Nullable Gate gate) {
        sGate = gate;
    }

    /** Runs {@code continuation} on the main thread, after the gate (if any) lets it through. */
    public static void run(@NonNull Context context, @NonNull Runnable continuation) {
        Gate gate = sGate;
        if (gate == null || sConsulted) {
            continuation.run();
            return;
        }
        sConsulted = true;

        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean done = new AtomicBoolean();
        Runnable once = () -> {
            if (done.compareAndSet(false, true)) {
                continuation.run();
            }
        };
        boolean holding;
        try {
            holding = gate.hold(context, () -> main.post(once));
        } catch (RuntimeException e) {
            holding = false; // a broken gate must never keep the app from starting
        }
        if (!holding) {
            once.run();
            return;
        }
        main.postDelayed(once, MAX_WAIT_MS);
    }
}
