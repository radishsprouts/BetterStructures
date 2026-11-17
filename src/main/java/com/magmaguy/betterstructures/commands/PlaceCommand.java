package com.magmaguy.betterstructures.commands;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.scan.ScanCoordinator;
import com.magmaguy.betterstructures.scan.ScanOutcome;
import com.magmaguy.betterstructures.scan.ScanRequest;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.PlatformScheduler;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.DynamicListStringCommandArgument;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import org.bukkit.entity.Player;

import java.util.List;

public class PlaceCommand extends AdvancedCommand {
    public PlaceCommand() {
        super(List.of("place"));
        addArgument(
                "schematic",
                new DynamicListStringCommandArgument(
                        () -> BetterStructures.isReloading()
                                ? List.of()
                                : SchematicContainer.getSchematics()
                                .values()
                                .stream()
                                .map(SchematicContainer::getClipboardFilename)
                                .distinct()
                                .sorted()
                                .toList(),
                        "<schematic>"));
        addArgument("type", new ListStringCommandArgument(List.of(GeneratorConfigFields.StructureType.SURFACE.toString(), GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW.toString(), GeneratorConfigFields.StructureType.UNDERGROUND_DEEP.toString(), GeneratorConfigFields.StructureType.SKY.toString(), GeneratorConfigFields.StructureType.LIQUID_SURFACE.toString()),"<type>"));
        setPermission("betterstructures.*");
        setDescription("Allows players to place structures.");
        setSenderType(SenderType.PLAYER);
        setUsage("/betterstructures place <schematic> <SURFACE/SKY/LIQUID_SURFACE/UNDERGROUND_DEEP/UNDERGROUND_SHALLOW>");
    }

    @Override
    public void execute(CommandData commandData) {
        if (BetterStructures.rejectContentCommandDuringReload(
                commandData.getCommandSender())) return;
        placeSchematic(commandData.getStringArgument("schematic"), commandData.getStringArgument("type"), commandData.getPlayerSender());
    }

    /**
     * 自然生成と同じ流れ（流量制御・hold・予算）で、実行者のいるチャンクを原点に走査する。
     * 結果は走査が終わった時に実行者へ伝える。
     */
    private void placeSchematic(String schematicFile, String schematicType, Player player) {
        try {
            SchematicContainer commandSchematicContainer = null;
            for (SchematicContainer schematicContainer : SchematicContainer.getSchematics().values())
                if (schematicContainer.getClipboardFilename().equals(schematicFile)) {
                    commandSchematicContainer = schematicContainer;
                    break;
                }
            if (commandSchematicContainer == null) {
                player.sendMessage("[BetterStructures] Invalid schematic!");
                return;
            }
            GeneratorConfigFields.StructureType structureType;
            try {
                structureType = GeneratorConfigFields.StructureType.valueOf(schematicType);
            } catch (Exception exception) {
                player.sendMessage("[BetterStructures] Failed to get valid schematic type!");
                return;
            }
            var chunk = player.getLocation().getChunk();
            ScanCoordinator.submit(new ScanRequest(chunk.getWorld(), chunk.getX(), chunk.getZ(), structureType,
                    commandSchematicContainer, outcome -> report(player, schematicFile, outcome)));
            player.sendMessage("[BetterStructures] Scanning for a place to put " + schematicFile + " ...");
        } catch (Exception ex) {
            player.sendMessage("[BetterStructures] Invalid schematic!");
        }
    }

    /** 走査の結末を実行者へ伝える。走査は別のスレッドで終わるので、実行者のスケジューラへ回す。 */
    private static void report(Player player, String schematicFile, ScanOutcome outcome) {
        String message = switch (outcome) {
            case PLACED -> "[BetterStructures] Placing " + schematicFile + " !";
            case NO_FIT -> "[BetterStructures] Could not find a suitable place for " + schematicFile + " near you.";
            default -> "[BetterStructures] Could not place " + schematicFile + ": " + outcome.description() + ".";
        };
        PlatformScheduler.runEntity(player, () -> player.sendMessage(message));
    }
}
