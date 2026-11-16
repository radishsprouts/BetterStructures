package com.magmaguy.betterstructures.content;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakContentManager;
import com.magmaguy.magmacore.nightbreak.NightbreakContentRefresher;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class BSPackageRefresher {
    private static final Duration REFRESH_COOLDOWN = Duration.ofMinutes(5);
    private static final String CATALOG_KEY = "nightbreak-packages";
    private static final AtomicLong LAST_FOLIA_REFRESH_NANOS = new AtomicLong(0L);

    private BSPackageRefresher() {
    }

    public static void refreshContentAndAccess() {
        if (PlatformScheduler.isFolia()) {
            refreshFoliaAsyncIfDue();
            return;
        }
        NightbreakContentRefresher.refreshAsyncIfDue(
                (JavaPlugin) MetadataHandler.PLUGIN,
                CATALOG_KEY,
                REFRESH_COOLDOWN,
                BetterStructures::availablePackages,
                bspPackage -> true,
                outdated -> {
                });
    }

    private static void refreshFoliaAsyncIfDue() {
        long now = System.nanoTime();
        long cooldownNanos = REFRESH_COOLDOWN.toNanos();
        long last = LAST_FOLIA_REFRESH_NANOS.get();
        if (last != 0L && (now - last) < cooldownNanos) {
            return;
        }
        if (!LAST_FOLIA_REFRESH_NANOS.compareAndSet(last, now)) {
            return;
        }

        PlatformScheduler.runAsync(() -> {
            try {
                if (!NightbreakAccount.hasToken()) return;
                NightbreakAccount account = NightbreakAccount.getInstance();
                if (account == null) return;

                NightbreakContentManager.refreshVersionCache((JavaPlugin) MetadataHandler.PLUGIN);

                Method cacheAccessMethod = null;
                try {
                    cacheAccessMethod = NightbreakContentManager.class.getDeclaredMethod(
                            "cacheAccess", String.class, NightbreakAccount.AccessInfo.class, NightbreakAccount.class);
                    cacheAccessMethod.setAccessible(true);
                } catch (Throwable ignored) {
                }

                for (BSPackage pkg : BetterStructures.availablePackages()) {
                    String slug = pkg.getNightbreakSlug();
                    if (slug == null || slug.isEmpty()) continue;
                    NightbreakAccount.AccessInfo access = account.checkAccess(slug);
                    if (access != null && cacheAccessMethod != null) {
                        try {
                            cacheAccessMethod.invoke(null, slug, access, account);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                Logger.warn("Failed to refresh BetterStructures package access: " + t.getMessage());
            }
        });
    }

    public static void reset() {
        LAST_FOLIA_REFRESH_NANOS.set(0L);
        NightbreakContentRefresher.resetRefreshCooldown(
                (JavaPlugin) MetadataHandler.PLUGIN, CATALOG_KEY);
    }
}
