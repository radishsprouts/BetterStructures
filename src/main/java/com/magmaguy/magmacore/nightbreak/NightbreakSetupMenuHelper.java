package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.menus.NightbreakSetupIcons;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class NightbreakSetupMenuHelper {
    private static final String SEPARATOR = NightbreakChatStyle.separator();

    private NightbreakSetupMenuHelper() {
    }

    public static ItemStack createItem(String displayName, List<String> description, Material material, String modelId, List<String> stateLore) {
        ArrayList<String> lore = new ArrayList<>(stateLore);
        lore.addAll(description);
        ItemStack itemStack = ItemStackGenerator.generateItemStack(material, displayName, lore);
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta != null) {
            itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            itemMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            itemStack.setItemMeta(itemMeta);
        }
        NightbreakSetupIcons.applyItemModel(itemStack, modelId);
        return itemStack;
    }

    public static ItemStack createInstalledItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.GREEN_STAINED_GLASS_PANE, "nightbreak:ui/checkmark", List.of("&aContent is installed and up to date.", "&7Click to uninstall it."));
    }

    public static ItemStack createPartiallyInstalledItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.ORANGE_STAINED_GLASS_PANE, "nightbreak:ui/gray_x", List.of("&6Content is only partially installed!", "&7Some files appear to be missing or disabled.", "&7Click to redownload and repair this package."));
    }

    public static ItemStack createNotInstalledItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.YELLOW_STAINED_GLASS_PANE, "nightbreak:ui/gray_x", List.of("&eContent is downloaded but disabled.", "&7Click to install it."));
    }

    public static ItemStack createNotDownloadedItem(String displayName, List<String> description, String slug, NightbreakAccount.AccessInfo accessInfo) {
        String modelId = slug == null || slug.isEmpty() ? "nightbreak:ui/unlocked" : (!NightbreakAccount.hasToken() ? "nightbreak:ui/lockedunlinked" : (accessInfo != null && accessInfo.hasAccess ? "nightbreak:ui/unlocked" : "nightbreak:ui/lockedunpaid"));
        return createItem(displayName, description, Material.YELLOW_STAINED_GLASS_PANE, modelId, List.of("&eContent is not downloaded yet.", "&7Click to download it."));
    }

    public static ItemStack createComingSoonItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.CLOCK, null, List.of("&bComing soon!", "&7This content isn't released yet."));
    }

    public static ItemStack createNeedsAccessItem(String displayName, List<String> description, NightbreakAccount.AccessInfo accessInfo) {
        ArrayList<String> stateLore = new ArrayList<>();
        stateLore.add("&dAccount access is required.");
        stateLore.add("&7Click to view access links.");
        if (accessInfo != null) {
            if (accessInfo.patreonLink != null && !accessInfo.patreonLink.isEmpty()) {
                stateLore.add("&5Available on Patreon");
            }
            if (accessInfo.itchLink != null && !accessInfo.itchLink.isEmpty()) {
                stateLore.add("&5Available on itch.io");
            }
        }
        return createItem(displayName, description, Material.PURPLE_STAINED_GLASS_PANE, "nightbreak:ui/lockedunpaid", stateLore);
    }

    public static ItemStack createOutOfDateUpdatableItem(String displayName, List<String> description, String slug) {
        String modelId = slug == null || slug.isEmpty() ? "nightbreak:ui/update" : (!NightbreakAccount.hasToken() ? "nightbreak:ui/updateunlinked" : "nightbreak:ui/update");
        return createItem(displayName, description, Material.YELLOW_STAINED_GLASS_PANE, modelId, List.of("&eAn update is available!", "&7Click to download the update."));
    }

    public static ItemStack createOutOfDateNoAccessItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.ORANGE_STAINED_GLASS_PANE, "nightbreak:ui/updateunpaid", List.of("&6An update is available!", "&7You need account access before you can update it.", "&7Click to view access links."));
    }

    public static void sendLinkCommand(CommandSender sender, String prefix) {
        if (sender instanceof Player player) {
            try {
                player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage(prefix), SpigotMessage.commandHoverMessage("&a/nightbreaklogin", "&7Click to get a link that connects this server to your Nightbreak account.", "/nightbreaklogin")});
            } catch (Throwable ignored) {
            }
            player.sendMessage(ChatColorConverter.convert(prefix + "&a/nightbreaklogin"));
        } else {
            Logger.sendSimpleMessage(sender, prefix + "&a/nightbreaklogin");
        }
    }

    public static void sendNoTokenPrompt(Player player, String pluginName, String contentUrl) {
        player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
        player.sendMessage(ChatColorConverter.convert("&2[" + pluginName + "] &aサーバー連携（Nightbreak）の案内:"));
        player.sendMessage(ChatColorConverter.convert("&7ゲーム内から自動ダウンロード・更新を行うにはサーバー連携が必要です。"));
        player.sendMessage(ChatColorConverter.convert("&6【方法1: ゲーム内自動連携（無料・約1分）】"));
        player.sendMessage(ChatColorConverter.convert("&7・コマンド &a/nightbreaklogin &7を実行して表示されるURLをブラウザで開いてください。"));
        player.sendMessage(ChatColorConverter.convert("&6【方法2: ログイン不要の手動ダウンロード】"));
        player.sendMessage(ChatColorConverter.convert("&7・ブラウザで以下のURLを開いて手動で zip をダウンロードできます："));
        player.sendMessage(ChatColorConverter.convert("&9&n" + contentUrl));
        player.sendMessage(ChatColorConverter.convert("&7・ダウンロードしたファイルを &eplugins/" + pluginName + "/imports/ &7に配置後、&a/bs reload &7を実行してください。"));
        player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));

        try {
            sendLinkCommand(player, "&2Link this server: ");
            player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage("&2" + pluginName + " content: "), SpigotMessage.hoverLinkMessage("&a" + contentUrl, "&7Click to browse content for " + pluginName + ".", contentUrl)});
        } catch (Throwable ignored) {
        }
    }

    public static void sendTokenUpdatePrompt(CommandSender sender, String pluginName) {
        if (sender == null) {
            return;
        }
        if (sender instanceof Player player) {
            player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
            player.sendMessage(ChatColorConverter.convert("&e[" + pluginName + "] 保存されている認証トークンの有効期限が切れています。"));
            player.sendMessage(ChatColorConverter.convert("&7再度 &a/nightbreaklogin &7を実行して連携し直してください。"));
            player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
        }
        Logger.sendSimpleMessage(sender, SEPARATOR);
        Logger.sendSimpleMessage(sender, "&e[" + pluginName + "] The saved account token needs to be updated.");
        sendLinkCommand(sender, "&7Link this server again with ");
        Logger.sendSimpleMessage(sender, "&7then try again.");
        Logger.sendSimpleMessage(sender, SEPARATOR);
    }

    public static void sendFirstTimeSetupResources(Player player, NightbreakFirstTimeSetupSpec spec) {
        player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
        if (NightbreakAccount.hasToken()) {
            player.sendMessage(ChatColorConverter.convert("&2Nightbreak account: &aconnected"));
        } else {
            sendLinkCommand(player, "&2Link this server: ");
        }
        player.sendMessage(ChatColorConverter.convert("&2Plugin page: &9&n" + spec.contentUrl()));
        player.sendMessage(ChatColorConverter.convert("&2Content browser: &a" + spec.setupCommand()));
        String recommendedCommand = recommendedPluginsCommand(spec);
        if (!recommendedCommand.isBlank()) {
            player.sendMessage(ChatColorConverter.convert("&2Recommended plugins: &a" + recommendedCommand));
        }
        if (spec.supportUrl() != null && !spec.supportUrl().isEmpty()) {
            player.sendMessage(ChatColorConverter.convert("&2Support: &9&n" + spec.supportUrl()));
        }
        player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));

        try {
            player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage("&2Plugin page: "), SpigotMessage.hoverLinkMessage("&9&n" + spec.contentUrl(), "&7Click to browse " + spec.pluginDisplayName() + " content.", spec.contentUrl())});
            player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage("&2Content browser: "), SpigotMessage.commandHoverMessage("&a" + spec.setupCommand(), "&7Click to open the " + spec.pluginDisplayName() + " setup menu.", spec.setupCommand())});
            if (!recommendedCommand.isBlank()) {
                player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage("&2Recommended plugins: "), SpigotMessage.commandHoverMessage("&a" + recommendedCommand, "&7Click to see plugins that work well with " + spec.pluginDisplayName() + ".", recommendedCommand)});
            }
            if (spec.supportUrl() != null && !spec.supportUrl().isEmpty()) {
                player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage("&2Support: "), SpigotMessage.hoverLinkMessage("&9&n" + spec.supportUrl(), "&7Click to open the support link.", spec.supportUrl())});
            }
        } catch (Throwable ignored) {
        }
    }

    public static void sendRecommendedSetupInstructions(Player player, NightbreakFirstTimeSetupSpec spec) {
        boolean startLink;
        Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
        Logger.sendSimpleMessage(player, "&a" + spec.pluginDisplayName() + " setup is now marked as complete.");
        boolean connected = NightbreakAccount.hasToken();
        startLink = !connected && player.hasPermission("nightbreak.login") && MagmaCore.getInstance() != null;
        if (!connected) {
            Logger.sendSimpleMessage(player, "&7Connect this server so " + spec.pluginDisplayName() + " can install content and download plugin updates from in-game.");
            if (startLink) {
                Logger.sendSimpleMessage(player, "&7Step 1: open the link below and approve this server.");
            } else {
                sendLinkCommand(player, "&7Step 1: link this server with ");
            }
        }
        try {
            player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage(connected ? "&7Open the setup menu with " : "&7Step 2: open the setup menu with "), SpigotMessage.commandHoverMessage("&a" + spec.setupCommand(), "&7Click to open the " + spec.pluginDisplayName() + " setup menu.", spec.setupCommand())});
        } catch (Throwable ignored) {
        }
        player.sendMessage(ChatColorConverter.convert((connected ? "&7Open the setup menu with " : "&7Step 2: open the setup menu with ") + "&a" + spec.setupCommand()));
        String recommendedCommand = recommendedPluginsCommand(spec);
        if (!recommendedCommand.isBlank()) {
            try {
                player.spigot().sendMessage(new BaseComponent[]{SpigotMessage.simpleMessage("&7Recommended plugins: "), SpigotMessage.commandHoverMessage("&a" + recommendedCommand, "&7Click to see plugins that work well with " + spec.pluginDisplayName() + ".", recommendedCommand)});
            } catch (Throwable ignored) {
            }
            player.sendMessage(ChatColorConverter.convert("&7Recommended plugins: &a" + recommendedCommand));
        }
        for (String note : spec.recommendedNotes()) {
            Logger.sendSimpleMessage(player, note);
        }
        Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
        if (startLink) {
            NightbreakServerLink.start((JavaPlugin) MagmaCore.getInstance().getRequestingPlugin(), player);
        }
    }

    public static void sendManualDownloadMessage(CommandSender sender, String downloadLink, String importsFolderName, String reloadCommand) {
        if (sender instanceof Player player) {
            player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
            player.sendMessage(ChatColorConverter.convert("&6手動ダウンロード案内:"));
            player.sendMessage(ChatColorConverter.convert("&7以下のURLから zip ファイルをダウンロードしてください："));
            player.sendMessage(ChatColorConverter.convert("&9&n" + downloadLink));
            player.sendMessage(ChatColorConverter.convert("&7ダウンロードしたファイルを &e" + importsFolderName + " &7フォルダに配置してください。"));
            if (reloadCommand != null && !reloadCommand.isEmpty()) {
                player.sendMessage(ChatColorConverter.convert("&7配置後、コマンド &a" + reloadCommand + " &7を実行すると反映されます。"));
            }
            player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
        } else {
            Logger.sendSimpleMessage(sender, SEPARATOR);
            Logger.sendSimpleMessage(sender, "&6Download this package here: &9&n" + downloadLink);
            Logger.sendSimpleMessage(sender, "&7Then put the downloaded file in the &f" + importsFolderName + "&7 folder.");
            if (reloadCommand != null && !reloadCommand.isEmpty()) {
                Logger.sendSimpleMessage(sender, "&7Finish by running &a" + reloadCommand + "&7.");
            }
            Logger.sendSimpleMessage(sender, SEPARATOR);
        }
    }

    public static void sendAccessInfo(Player player, String packageName, NightbreakAccount.AccessInfo accessInfo, String contentUrl) {
        player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
        player.sendMessage(ChatColorConverter.convert("&6[BetterStructures] &f" + packageName + " &7のアクセス権案内:"));
        player.sendMessage(ChatColorConverter.convert("&7このコンテンツを利用するにはアカウントアクセスが必要です。"));
        player.sendMessage(ChatColorConverter.convert("&2コンテンツページ: &9&n" + contentUrl));
        if (accessInfo != null) {
            if (accessInfo.patreonLink != null && !accessInfo.patreonLink.isEmpty()) {
                player.sendMessage(ChatColorConverter.convert("&2Patreon: &9&n" + accessInfo.patreonLink));
            }
            if (accessInfo.itchLink != null && !accessInfo.itchLink.isEmpty()) {
                player.sendMessage(ChatColorConverter.convert("&2itch.io: &9&n" + accessInfo.itchLink));
            }
        }
        if (!NightbreakAccount.hasToken()) {
            player.sendMessage(ChatColorConverter.convert("&7連携コマンド: &a/nightbreaklogin"));
        }
        player.sendMessage(ChatColorConverter.convert("&8&m-----------------------------------------------------"));
    }

    public static String getSeparator() {
        return SEPARATOR;
    }

    private static String recommendedPluginsCommand(NightbreakFirstTimeSetupSpec spec) {
        if (spec == null || spec.setupCommand() == null || spec.setupCommand().isBlank()) {
            return "";
        }
        String setupCommand = spec.setupCommand().trim();
        if (!setupCommand.endsWith(" setup")) {
            return "";
        }
        return setupCommand.substring(0, setupCommand.length() - " setup".length()) + " recommendedplugins";
    }
}
