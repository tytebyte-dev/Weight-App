package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The embedded Xray-core, reached by reflection.
 *
 * <p>{@code native/build-cores.sh} builds it with gomobile into {@code xraycore.jar} plus
 * {@code libgojni.so}. Those files are build artefacts, not in git, so the app must still compile
 * and start without them: nothing here is linked at compile time, and every call degrades to
 * "core not built".</p>
 *
 * <p>gomobile names: package {@code xraybridge} -> class {@code xraybridge.Xraybridge} with static
 * methods {@code start/stop/isRunning/version/checkConfig/setAssetDir/setProtector}; Go {@code int}
 * becomes Java {@code long}; a Go {@code error} result becomes a thrown {@code Exception}.</p>
 */
final class XrayCore {
    /** Keeps a socket out of the VPN (VpnService.protect). */
    interface SocketProtector {
        boolean protect(int fd);
    }

    private static final String BRIDGE = "xraybridge.Xraybridge";
    private static final String PROTECTOR = "xraybridge.Protector";

    private static Class<?> sBridge;
    private static Class<?> sProtector;
    @Nullable private static Throwable sLoadError;
    private static boolean sTried;

    private XrayCore() {
    }

    static synchronized boolean isAvailable() {
        if (!sTried) {
            sTried = true;
            try {
                sBridge = Class.forName(BRIDGE);
                sProtector = Class.forName(PROTECTOR);
            } catch (Throwable t) { // ClassNotFound, or UnsatisfiedLinkError from libgojni
                sLoadError = t;
            }
        }
        return sBridge != null;
    }

    @Nullable
    static Throwable loadError() {
        isAvailable();
        return sLoadError;
    }

    @NonNull
    static String version() {
        try {
            return (String) call("version");
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isRunning() {
        try {
            return Boolean.TRUE.equals(call("isRunning"));
        } catch (Exception e) {
            return false;
        }
    }

    static void setAssetDir(@NonNull String dir) throws Exception {
        call("setAssetDir", new Class<?>[] {String.class}, dir);
    }

    /** Throws with Xray's own message when the config is invalid. */
    static void checkConfig(@NonNull String json) throws Exception {
        call("checkConfig", new Class<?>[] {String.class}, json);
    }

    static void start(@NonNull String json) throws Exception {
        call("start", new Class<?>[] {String.class}, json);
    }

    static void stop() throws Exception {
        call("stop");
    }

    static void setProtector(@Nullable SocketProtector protector) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Xray core is not built into this APK", sLoadError);
        }
        Object arg = null;
        if (protector != null) {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "protect":
                        return protector.protect(((Number) args[0]).intValue());
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "XrayCore.Protector";
                    default:
                        return null;
                }
            };
            arg = Proxy.newProxyInstance(sProtector.getClassLoader(), new Class<?>[] {sProtector}, handler);
        }
        call("setProtector", new Class<?>[] {sProtector}, arg);
    }

    private static Object call(String name) throws Exception {
        return call(name, new Class<?>[0]);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Xray core is not built into this APK", sLoadError);
        }
        Method m = sBridge.getMethod(name, types);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new Exception(cause);
        }
    }
}
