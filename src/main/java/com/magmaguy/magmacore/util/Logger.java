package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.minimessage.MiniMessageParser;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Content;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Logger {
    private Logger() {
    }

    public static void info(String msg) {
        Bukkit.getLogger().info(prefix() + msg);
    }

    public static void warn(String msg) {
        Bukkit.getLogger().warning(prefix() + msg);
    }

    public static void warn(String msg, boolean printStackTrace) {
        warn(msg);
        if (printStackTrace) {
            Thread.dumpStack();
        }
    }

    public static void debug(String msg) {
        Bukkit.getLogger().info(prefix() + "Developer message:" + msg);
    }

    public static void sendMessage(CommandSender commandSender, String message) {
        dispatch(commandSender, "&8" + prefix() + "&f" + message);
    }

    private static String prefix() {
        MagmaCore core = MagmaCore.getInstance();
        return "[" + (core == null ? "MagmaCore" : core.getRequestingPlugin().getName()) + "] ";
    }

    public static void sendSimpleMessage(CommandSender commandSender, String message) {
        dispatch(commandSender, message);
    }

    private static void dispatch(CommandSender commandSender, String message) {
        if (commandSender instanceof Player player) {
            try {
                BaseComponent[] components = MiniMessageParser.parse(message);
                player.spigot().sendMessage(components);
            } catch (Throwable ignored) {
            }
            player.sendMessage(ChatColorConverter.convert(message));
        } else if (commandSender != null) {
            commandSender.sendMessage(ChatColorConverter.convert(message));
        } else {
            Bukkit.getConsoleSender().sendMessage(ChatColorConverter.convert(message));
        }
    }

    public static TextComponent simpleMessage(String message) {
        return new TextComponent(ChatColorConverter.convert(message));
    }

    public static void sendTitle(Player player, String title, String subtitle) {
        player.sendTitle(ChatColorConverter.convert(title), ChatColorConverter.convert(subtitle));
    }

    public static void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(ChatColorConverter.convert(title), ChatColorConverter.convert(subtitle), fadeIn, stay, fadeOut);
    }

    public static TextComponent hoverMessage(String message, String hoverMessage) {
        TextComponent textComponent = simpleMessage(message);
        textComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Content[]{new Text(hoverMessage)}));
        return textComponent;
    }

    public static TextComponent commandHoverMessage(String message, String hoverMessage, String commandString) {
        TextComponent textComponent = hoverMessage(message, ChatColorConverter.convert(hoverMessage));
        if (!commandString.isEmpty()) {
            textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, ChatColorConverter.convert(commandString)));
        }
        return textComponent;
    }

    public static TextComponent hoverLinkMessage(String message, String hoverMessage, String link) {
        TextComponent textComponent = hoverMessage(message, hoverMessage);
        textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ChatColorConverter.convert(link)));
        return textComponent;
    }

    public static TextComponent hoverCopyMessage(String message, String hoverMessage, String stringToCopy) {
        TextComponent textComponent = hoverMessage(message, hoverMessage);
        textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, stringToCopy));
        return textComponent;
    }

    public static void showLocation(Location location) {
        location.getWorld().spawnParticle(Particle.BLOCK_MARKER, location, 1, Material.BARRIER.createBlockData());
    }
}
