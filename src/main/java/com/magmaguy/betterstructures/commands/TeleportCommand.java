package com.magmaguy.betterstructures.commands;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.IntegerCommandArgument;
import com.magmaguy.magmacore.command.arguments.WorldCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

public class TeleportCommand extends AdvancedCommand {
    public TeleportCommand() {
        super(List.of("teleport", "tp"));
        addArgument("world", new WorldCommandArgument("<world>"));
        addArgument("x", new IntegerCommandArgument("<x>"));
        addArgument("y", new IntegerCommandArgument("<y>"));
        addArgument("z", new IntegerCommandArgument("<z>"));
        setUsage("/teleport <worldname> <x> <y> <z>");
        setPermission("betterstructures.warn");
        setDescription("Teleports a player to specific coordinates.");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        try {
            String worldName = commandData.getStringArgument("world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                Logger.sendMessage(commandData.getCommandSender(), "Failed to teleport: World '" + worldName + "' not found!");
                return;
            }
            double x = Double.parseDouble(commandData.getStringArgument("x"));
            double y = Double.parseDouble(commandData.getStringArgument("y"));
            double z = Double.parseDouble(commandData.getStringArgument("z"));
            Player player = commandData.getPlayerSender();
            Location destination = new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
            player.teleportAsync(destination).thenAccept(success -> {
                if (Boolean.FALSE.equals(success)) {
                    Logger.sendMessage(commandData.getCommandSender(), "Failed to teleport to location!");
                }
            }).exceptionally(ex -> {
                Logger.warn("Failed to teleport player " + player.getName() + " to " + destination + ": " + ex.getMessage());
                Logger.sendMessage(commandData.getCommandSender(), "Failed to teleport to location because an error occurred!");
                return null;
            });
        } catch (Exception ex) {
            Logger.warn("Failed to parse teleport arguments: " + ex.getMessage());
            Logger.sendMessage(commandData.getCommandSender(), "Failed to teleport to location because the location wasn't valid!");
        }
    }
}
