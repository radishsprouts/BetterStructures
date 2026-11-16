package com.magmaguy.betterstructures.content;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.commands.ReloadCommand;
import com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfigFields;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.nightbreak.AbstractNightbreakContentPackage;
import com.magmaguy.magmacore.nightbreak.NightbreakSetupMenuHelper;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakContentManager;

public class BSPackage extends AbstractNightbreakContentPackage {
    @Getter
    private static final Map<String, BSPackage> bsPackages = new ConcurrentHashMap<>();
    @Getter
    private final ContentPackageConfigFields contentPackageConfigFields;

    public BSPackage(ContentPackageConfigFields contentPackageConfigFields) {
        this.contentPackageConfigFields = contentPackageConfigFields;
        bsPackages.put(contentPackageConfigFields.getFilename(), this);
    }

    public static void shutdown() {
        bsPackages.clear();
    }

    @Override
    protected void doInstall(Player player) {
        player.closeInventory();
        File folder = getSpecificContentFolder();
        if (!folder.exists()) {
            Logger.sendMessage(player, "Failed to find directory " + folder.getAbsolutePath());
            return;
        }

        handleStateSave(player,
                toggleContentState(true),
                () -> {
                    ReloadCommand.reload(player);
                    if (player.isOnline()) {
                        Logger.sendMessage(player, "Installed " + contentPackageConfigFields.getName());
                    }
                },
                "&cFailed to update BetterStructures package state. Check the console.");
    }

    @Override
    public void doUninstall(Player player) {
        player.closeInventory();
        File folder = getSpecificContentFolder();
        if (!folder.exists()) {
            Logger.sendMessage(player, "Failed to find directory " + folder.getAbsolutePath());
            return;
        }

        handleStateSave(player,
                toggleContentState(false),
                () -> {
                    ReloadCommand.reload(player);
                    if (player.isOnline()) {
                        Logger.sendMessage(player, "Uninstalled " + contentPackageConfigFields.getName());
                    }
                },
                "&cFailed to update BetterStructures package state. Check the console.");
    }

    private CompletableFuture<Void> toggleContentState(boolean enabled) {
        return contentPackageConfigFields.setEnabledAndSave(enabled);
    }

    @Override
    protected void handleStateSave(Player player, CompletableFuture<Void> future, Runnable onSuccess, String errorMessage) {
        if (!PlatformScheduler.isFolia()) {
            super.handleStateSave(player, future, onSuccess, errorMessage);
            return;
        }

        future.whenComplete((v, t) -> {
            PlatformScheduler.runEntity(player, () -> {
                if (t != null) {
                    Logger.warn("Failed to update content package state: " + t.getMessage());
                    if (player.isOnline()) {
                        Logger.sendSimpleMessage(player, errorMessage);
                    }
                } else {
                    onSuccess.run();
                }
            });
        });
    }

    @Override
    protected void doDownload(Player player) {
        if (!PlatformScheduler.isFolia()) {
            super.doDownload(player);
            return;
        }

        player.closeInventory();
        if (isComingSoon()) {
            doShowComingSoon(player);
            return;
        }

        String slug = getNightbreakSlug();
        if (slug == null || slug.isEmpty()) {
            String downloadLink = getDownloadLink();
            if (downloadLink == null || downloadLink.isEmpty()) {
                downloadLink = getContentPageUrl();
            }
            NightbreakSetupMenuHelper.sendManualDownloadMessage(player,
                    downloadLink,
                    getManualImportsFolderName(),
                    getManualReloadCommand());
            return;
        }

        if (!NightbreakAccount.hasToken()) {
            NightbreakSetupMenuHelper.sendNoTokenPrompt(player, getPluginDisplayName(), getContentPageUrl());
            return;
        }

        if (NightbreakContentManager.isKnownUnpublished(slug)) {
            Logger.sendSimpleMessage(player, "&c[Nightbreak] " + getDisplayName() + " is not yet published.");
            return;
        }

        Logger.sendSimpleMessage(player, "&7Starting download for &a" + getDisplayName() + "&7...");

        PlatformScheduler.runAsync(() -> {
            NightbreakAccount account = NightbreakAccount.getInstance();
            if (account == null) {
                PlatformScheduler.runEntity(player, () -> {
                    if (player.isOnline()) {
                        Logger.sendSimpleMessage(player, "&c[Nightbreak] NightbreakAccount is not initialized.");
                    }
                });
                return;
            }

            NightbreakAccount.AccessInfo accessInfo = account.checkAccess(slug);
            setCachedAccessInfo(accessInfo);

            if (accessInfo == null) {
                PlatformScheduler.runEntity(player, () -> {
                    if (!player.isOnline()) return;
                    if (NightbreakAccount.hasAuthFailure()) {
                        NightbreakSetupMenuHelper.sendTokenUpdatePrompt(player, getOwnerPlugin().getName());
                    } else {
                        Logger.sendSimpleMessage(player, "&cFailed to contact Nightbreak for access information.");
                    }
                });
                return;
            }

            if (!accessInfo.hasAccess) {
                PlatformScheduler.runEntity(player, () -> {
                    if (player.isOnline()) {
                        doShowAccessInfo(player);
                    }
                });
                return;
            }

            File importsFolder = new File(getOwnerPlugin().getDataFolder(), "imports");
            if (!importsFolder.exists()) {
                importsFolder.mkdirs();
            }

            Path tempFile = null;
            try {
                tempFile = Files.createTempFile(importsFolder.toPath(), ".nightbreak-", ".download");
                final long[] lastNotice = new long[]{0};
                NightbreakAccount.DownloadProgressCallback progressCallback = (readBytes, totalBytes) -> {
                    if (totalBytes <= 0) return;
                    long now = System.currentTimeMillis();
                    if (now - lastNotice[0] >= 1500) {
                        lastNotice[0] = now;
                        int percent = (int) ((readBytes * 100) / totalBytes);
                        PlatformScheduler.runEntity(player, () -> {
                            if (player.isOnline()) {
                                Logger.sendSimpleMessage(player, "&7Downloading &a" + getDisplayName() + "&7: &e" + percent + "%");
                            }
                        });
                    }
                };

                boolean downloaded = account.download(slug, tempFile.toFile(), null, progressCallback);
                if (!downloaded) {
                    Files.deleteIfExists(tempFile);
                    PlatformScheduler.runEntity(player, () -> {
                        if (player.isOnline()) {
                            Logger.sendSimpleMessage(player, "&c[Nightbreak] Download failed. Please try again later.");
                        }
                    });
                    return;
                }

                Path targetFile = importsFolder.toPath().resolve(slug + ".zip");
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                PlatformScheduler.runEntity(player, () -> {
                    if (player.isOnline()) {
                        Logger.sendSimpleMessage(player, "&a[Nightbreak] Download complete! File saved to imports folder.");
                    }
                    enableAfterDownload().whenComplete((v, t) -> {
                        PlatformScheduler.runEntity(player, () -> {
                            if (t != null) {
                                Logger.warn("Failed to enable package after download: " + t.getMessage());
                            } else {
                                onDownloadStateSaved(player);
                            }
                        });
                    });
                });
            } catch (Exception e) {
                Logger.warn("Failed to download package " + slug + ": " + e.getMessage());
                e.printStackTrace();
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {}
                }
                PlatformScheduler.runEntity(player, () -> {
                    if (player.isOnline()) {
                        Logger.sendSimpleMessage(player, "&c[Nightbreak] Download failed with error: " + e.getMessage());
                    }
                });
            }
        });
    }

    private File getSpecificContentFolder() {
        String baseFolder = contentPackageConfigFields.getContentPackageType() == ContentPackageConfigFields.ContentPackageType.MODULAR
                ? "modules"
                : "schematics";
        return new File(MetadataHandler.PLUGIN.getDataFolder(), baseFolder + File.separatorChar + contentPackageConfigFields.getFolderName());
    }

    @Override
    protected JavaPlugin getOwnerPlugin() {
        return (JavaPlugin) MetadataHandler.PLUGIN;
    }

    @Override
    protected String getPluginDisplayName() {
        return "BetterStructures";
    }

    @Override
    protected String getContentPageUrl() {
        return "https://nightbreak.io/plugin/betterstructures/";
    }

    @Override
    protected List<String> getPackageDescription() {
        return contentPackageConfigFields.getDescription();
    }

    @Override
    protected String getManualImportsFolderName() {
        return "BetterStructures imports";
    }

    @Override
    protected String getManualReloadCommand() {
        return "/bs reload";
    }

    @Override
    protected void onDownloadStateSaved(Player player) {
        if (player.isOnline()) {
            Logger.sendSimpleMessage(player, "&aReloading BetterStructures so the new content is picked up...");
        }
        ReloadCommand.reload(player);
    }

    @Override
    public String getNightbreakSlug() {
        return contentPackageConfigFields.getNightbreakSlug();
    }

    @Override
    public String getDisplayName() {
        return contentPackageConfigFields.getName();
    }

    @Override
    public String getDownloadLink() {
        return contentPackageConfigFields.getDownloadLink();
    }

    @Override
    public int getLocalVersion() {
        return contentPackageConfigFields.getVersion();
    }

    @Override
    public CompletableFuture<Void> enableAfterDownload() {
        return contentPackageConfigFields.setEnabledAndSave(true);
    }

    @Override
    public boolean isInstalled() {
        return isDownloaded() && contentPackageConfigFields.isEnabled();
    }

    @Override
    public boolean isDownloaded() {
        return getSpecificContentFolder().exists();
    }
}
