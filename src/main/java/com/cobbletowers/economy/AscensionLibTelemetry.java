package com.cobbletowers.economy;

import com.cobbletowers.TowerLog;
import java.lang.reflect.Method;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Writes a gameplay event into AscensionLib's telemetry log ({@code com.ascensionlib.AscensionTelemetry.event(String, Map)}), reached
 * by reflection like the other AscensionLib calls: the library is optional. Without it, or with one too old to have the call, every
 * event is dropped. The library hands the line to its own writer thread, so nothing here touches the disk on the server thread.
 * Never throws.
 */
public final class AscensionLibTelemetry {
    private static final String MOD_ID = "ascensionlib";
    private static final String CLASS = "com.ascensionlib.AscensionTelemetry";

    private static volatile boolean resolved;
    private static volatile Method event;
    private static volatile boolean failureLogged;

    private AscensionLibTelemetry() {}

    /** Returns whether the library took the event. */
    public static boolean event(String type, Map<String, ?> fields) {
        Method method = resolve();
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null, type, fields));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (!failureLogged) {
                failureLogged = true;
                TowerLog.error("Writing tower telemetry through AscensionLib failed; later failures are not logged", ex);
            }
            return false;
        }
    }

    private static Method resolve() {
        if (!resolved) {
            resolved = true;
            try {
                if (FabricLoader.getInstance().isModLoaded(MOD_ID)) event = Class.forName(CLASS).getMethod("event", String.class, Map.class);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                event = null; // an AscensionLib without gameplay telemetry
            }
        }
        return event;
    }
}
