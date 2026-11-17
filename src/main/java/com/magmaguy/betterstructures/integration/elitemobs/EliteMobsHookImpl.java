package com.magmaguy.betterstructures.integration.elitemobs;

import com.magmaguy.betterstructures.integration.EliteMobsHook;
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig;
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields;
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity;
import com.magmaguy.elitemobs.mobconstructor.custombosses.InstancedBossEntity;
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;

/**
 * EliteMobs が入っている時だけ生成する {@link EliteMobsHook} の実体。このクラスだけが EliteMobs の型を
 * 直接参照する。EliteMobs が無いサーバーでは {@link EliteMobsHookSelector} が {@link EliteMobsHook#none()}
 * を返し、このクラスは読み込まれない。
 *
 * <p>呼び出すスレッド: 目印の処理（そのブロックを所有するリージョンのスレッド）。待たない・ブロックしない。
 */
final class EliteMobsHookImpl implements EliteMobsHook {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean spawnBoss(Location location, String bossFilename) {
        RegionalBossEntity regionalBossEntity = RegionalBossEntity.SpawnRegionalBoss(bossFilename, location);
        if (regionalBossEntity == null) {
            Logger.warn("Failed to spawn regional boss " + bossFilename
                    + "! The filename for this boss probably does not match the filename that should be in ~/plugins/EliteMobs/custombosses/");
            return false;
        }
        regionalBossEntity.spawn(false);
        return true;
    }

    @Override
    public boolean isInstancedBoss(String bossFilename) {
        CustomBossesConfigFields fields = CustomBossesConfig.getCustomBoss(bossFilename);
        return fields != null && fields.isInstanced();
    }

    @Override
    public boolean bossExists(String bossFilename) {
        return CustomBossesConfig.getCustomBoss(bossFilename) != null;
    }

    @Override
    public boolean spawnPoolBoss(Location location, String bossFilename) {
        CustomBossesConfigFields fields = CustomBossesConfig.getCustomBoss(bossFilename);
        if (fields == null) return false;
        new CustomBossEntity(fields).spawn(location, true);
        return true;
    }

    @Override
    public Object spawnInstancedBoss(Location location, String bossFilename, int level,
                                     String difficultyId, String originalSpawnPool) {
        CustomBossesConfigFields fields = CustomBossesConfig.getCustomBoss(bossFilename);
        if (fields == null) return null;
        InstancedBossEntity entity = new InstancedBossEntity(fields, location, level, difficultyId);
        entity.spawn(true);
        entity.addCustomData(new NamespacedKey("betterstructures", "spawnpool"), originalSpawnPool);
        return entity;
    }
}
