package com.magmaguy.betterstructures.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Safe reflection-based bridge to BetterModel.
 * <p>
 * Spawns 3D custom models via BetterModel's DummyTracker without compile-time coupling
 * or bytecode version conflicts (Java 21 vs Java 25).
 * </p>
 */
public final class BetterModelBridge {

    private static Boolean available = null;
    private static Method modelMethod = null;
    private static Method adaptLocationMethod = null;
    private static Method createMethod = null;
    private static Class<?> platformLocationClass = null;

    private BetterModelBridge() {
    }

    /**
     * Checks if BetterModel is loaded and active.
     */
    public static boolean isEnabled() {
        if (available == null) {
            init();
        }
        return available;
    }

    private static synchronized void init() {
        if (available != null) return;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
                available = false;
                return;
            }
            Class<?> betterModelClass = Class.forName("kr.toxicity.model.api.BetterModel");
            modelMethod = betterModelClass.getMethod("model", String.class);

            Class<?> bukkitAdapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            adaptLocationMethod = bukkitAdapterClass.getMethod("adapt", Location.class);

            platformLocationClass = Class.forName("kr.toxicity.model.api.platform.PlatformLocation");
            Class<?> modelRendererClass = Class.forName("kr.toxicity.model.api.data.renderer.ModelRenderer");
            createMethod = modelRendererClass.getMethod("create", platformLocationClass);

            available = true;
            Logger.info("[BetterModelBridge] BetterModel reflection bridge initialized successfully.");
        } catch (Throwable t) {
            available = false;
            Logger.warn("[BetterModelBridge] Failed to initialize BetterModel bridge: " + t.getMessage());
        }
    }

    /**
     * Spawns a 3D model prop at the specified location.
     *
     * @param location  Target world location.
     * @param modelName Name of the model (from schematic PDC tag).
     * @return true if the model was successfully created, false otherwise.
     */
    public static boolean spawnProp(@NotNull Location location, @Nullable String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        if (!isEnabled()) return false;

        try {
            // 1. Search model by exact name
            Optional<?> optRenderer = (Optional<?>) modelMethod.invoke(null, modelName);
            if (optRenderer.isEmpty()) {
                // 2. Try lower case
                optRenderer = (Optional<?>) modelMethod.invoke(null, modelName.toLowerCase());
            }
            if (optRenderer.isEmpty()) {
                return false;
            }

            Object renderer = optRenderer.get();
            Object platformLoc = adaptLocationMethod.invoke(null, location);
            createMethod.invoke(renderer, platformLoc);
            return true;
        } catch (Throwable t) {
            Logger.warn("[BetterModelBridge] Failed to spawn BetterModel prop '" + modelName + "': " + t.getMessage());
            return false;
        }
    }
}
