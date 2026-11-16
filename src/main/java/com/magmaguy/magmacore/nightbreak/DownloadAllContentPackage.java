package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.menus.NightbreakSetupIcons;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

public class DownloadAllContentPackage<T extends NightbreakManagedContent> extends ContentPackage {
    private final Supplier<Collection<T>> packagesSupplier;
    private final String pluginName;
    private final String contentUrl;
    private final String downloadAllCommand;

    public DownloadAllContentPackage(Supplier<Collection<T>> packagesSupplier, String pluginName, String contentUrl, String downloadAllCommand) {
        this.packagesSupplier = packagesSupplier;
        this.pluginName = pluginName;
        this.contentUrl = contentUrl;
        this.downloadAllCommand = downloadAllCommand == null ? "" : downloadAllCommand.trim();
    }

    @Override
    public ItemStack getItemstack() {
        List<String> lore;
        String displayName;
        Material baseMaterial;
        String iconModel;
        if (!NightbreakAccount.hasToken()) {
            iconModel = "nightbreak:ui/redcross";
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = this.includesPluginUpdate() ? "&cDownload Everything" : "&cDownload All Content";
            lore = List.of("&7No account token linked.", "&7Click for setup instructions.");
        } else if (NightbreakAccount.hasAuthFailure()) {
            iconModel = "nightbreak:ui/redcross";
            baseMaterial = Material.RED_STAINED_GLASS_PANE;
            displayName = "&eUpdate Account Token";
            lore = List.of("&7Your saved account token needs", "&7to be updated before downloads work.");
        } else if (this.includesPluginUpdate()) {
            iconModel = "nightbreak:ui/yellowcrown";
            baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
            displayName = "&eDownload / Update Everything";
            lore = List.of("&7Checks for a plugin update,", "&7then downloads and updates content.", "&7Restart the server to use plugin updates.");
        } else {
            Collection<T> allPackages = this.packagesSupplier.get();
            long notDownloadedCount = this.countNotDownloaded(allPackages);
            long outdatedCount = this.countOutdated(allPackages);
            if (notDownloadedCount > 0L && outdatedCount > 0L) {
                iconModel = "nightbreak:ui/yellowcrown";
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eDownload & Update All";
                lore = List.of("&7Click to download &e" + notDownloadedCount + " &7new package" + (notDownloadedCount != 1L ? "s" : ""), "&7and update &e" + outdatedCount + " &7outdated package" + (outdatedCount != 1L ? "s" : "") + ".");
            } else if (notDownloadedCount > 0L) {
                iconModel = "nightbreak:ui/yellowcrown";
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eDownload All";
                lore = List.of("&7Click to download &e" + notDownloadedCount + " &7available package" + (notDownloadedCount != 1L ? "s" : "") + ".");
            } else if (outdatedCount > 0L) {
                iconModel = "nightbreak:ui/yellowcrown";
                baseMaterial = Material.YELLOW_STAINED_GLASS_PANE;
                displayName = "&eUpdate All";
                lore = List.of("&7Click to update &e" + outdatedCount + " &7outdated package" + (outdatedCount != 1L ? "s" : "") + ".");
            } else {
                iconModel = "nightbreak:ui/checkmark";
                baseMaterial = Material.LIME_STAINED_GLASS_PANE;
                displayName = "&aAll Content Up To Date";
                lore = List.of("&7All available content is downloaded", "&7and up to date!");
            }
        }
        ItemStack itemStack = ItemStackGenerator.generateItemStack(baseMaterial, displayName, lore);
        NightbreakSetupIcons.applyItemModel(itemStack, iconModel);
        return itemStack;
    }

    private long countNotDownloaded(Collection<T> allPackages) {
        HashSet<String> seenSlugs = new HashSet<>();
        long count = 0L;
        for (NightbreakManagedContent contentPackage : allPackages) {
            String slug = contentPackage.getNightbreakSlug();
            if (slug == null || slug.isEmpty() || !seenSlugs.add(slug) || NightbreakContentManager.isUnreleased(contentPackage) || contentPackage.isDownloaded() || (contentPackage.getCachedAccessInfo() != null && !contentPackage.getCachedAccessInfo().hasAccess)) continue;
            ++count;
        }
        return count;
    }

    private long countOutdated(Collection<T> allPackages) {
        HashSet<String> seenSlugs = new HashSet<>();
        long count = 0L;
        for (NightbreakManagedContent contentPackage : allPackages) {
            String slug;
            if (!contentPackage.isOutOfDate() || (slug = contentPackage.getNightbreakSlug()) == null || slug.isEmpty() || (contentPackage.getCachedAccessInfo() != null && !contentPackage.getCachedAccessInfo().hasAccess) || !seenSlugs.add(slug)) continue;
            ++count;
        }
        return count;
    }

    @Override
    public void onClick(Player player) {
        player.closeInventory();
        if (!NightbreakAccount.hasToken()) {
            NightbreakSetupMenuHelper.sendNoTokenPrompt(player, this.pluginName, this.contentUrl);
            return;
        }
        if (NightbreakAccount.hasAuthFailure()) {
            NightbreakSetupMenuHelper.sendTokenUpdatePrompt(player, this.pluginName);
            return;
        }
        if (this.includesPluginUpdate()) {
            Bukkit.dispatchCommand(player, this.downloadAllCommand);
            return;
        }
        boolean hasNotDownloaded = false;
        boolean hasOutdated = false;
        for (NightbreakManagedContent contentPackage : this.packagesSupplier.get()) {
            String slug = contentPackage.getNightbreakSlug();
            if (slug == null || slug.isEmpty() || NightbreakContentManager.isUnreleased(contentPackage)) continue;
            if (!contentPackage.isDownloaded() && (contentPackage.getCachedAccessInfo() == null || contentPackage.getCachedAccessInfo().hasAccess)) {
                hasNotDownloaded = true;
            }
            if (contentPackage.isOutOfDate() && (contentPackage.getCachedAccessInfo() == null || contentPackage.getCachedAccessInfo().hasAccess)) {
                hasOutdated = true;
            }
            if (!hasNotDownloaded || !hasOutdated) continue;
            break;
        }
        if (!hasNotDownloaded && !hasOutdated) {
            player.sendMessage(ChatColorConverter.convert("&2[" + this.pluginName + "] &a利用可能なすべてのコンテンツは既にダウンロード済みで最新です。"));
            return;
        }
        Bukkit.dispatchCommand(player, this.downloadAllCommand);
    }

    private boolean includesPluginUpdate() {
        return this.downloadAllCommand.endsWith(" downloadall");
    }

    @Override
    protected ItemStack getInstalledItemStack() {
        return this.getItemstack();
    }

    @Override
    protected ItemStack getPartiallyInstalledItemStack() {
        return this.getItemstack();
    }

    @Override
    protected ItemStack getNotInstalledItemStack() {
        return this.getItemstack();
    }

    @Override
    protected ItemStack getNotDownloadedItemStack() {
        return this.getItemstack();
    }

    @Override
    protected ItemStack getNeedsAccessItemStack() {
        return this.getItemstack();
    }

    @Override
    protected ItemStack getOutOfDateUpdatableItemStack() {
        return this.getItemstack();
    }

    @Override
    protected ItemStack getOutOfDateNoAccessItemStack() {
        return this.getItemstack();
    }

    @Override
    protected void doInstall(Player player) {
    }

    @Override
    protected void doUninstall(Player player) {
    }

    @Override
    protected void doDownload(Player player) {
    }

    @Override
    protected void doShowAccessInfo(Player player) {
    }

    @Override
    protected ContentPackage.ContentState getContentState() {
        return ContentPackage.ContentState.INSTALLED;
    }
}
