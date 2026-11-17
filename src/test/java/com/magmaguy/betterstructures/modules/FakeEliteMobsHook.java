package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.integration.EliteMobsHook;
import org.bukkit.Location;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** EliteMobs のクラスを一切読み込まずに、呼び出しだけを記録する試験用の窓口。 */
final class FakeEliteMobsHook implements EliteMobsHook {

    record Instanced(Location location, String bossFilename, int level, String difficultyId, String pool) {
    }

    record Spawn(Location location, String bossFilename) {
    }

    final boolean available;
    final Set<String> existing;
    final Set<String> instanced;
    final List<Spawn> spawned = new CopyOnWriteArrayList<>();
    final List<Spawn> poolSpawned = new CopyOnWriteArrayList<>();
    final List<Instanced> instancedSpawned = new CopyOnWriteArrayList<>();

    FakeEliteMobsHook(boolean available, Set<String> existing, Set<String> instanced) {
        this.available = available;
        this.existing = existing;
        this.instanced = instanced;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean spawnBoss(Location location, String bossFilename) {
        spawned.add(new Spawn(location, bossFilename));
        return true;
    }

    @Override
    public boolean isInstancedBoss(String bossFilename) {
        return instanced.contains(bossFilename);
    }

    @Override
    public boolean bossExists(String bossFilename) {
        return existing.contains(bossFilename);
    }

    @Override
    public boolean spawnPoolBoss(Location location, String bossFilename) {
        poolSpawned.add(new Spawn(location, bossFilename));
        return true;
    }

    @Override
    public Object spawnInstancedBoss(Location location, String bossFilename, int level, String difficultyId,
                                     String originalSpawnPool) {
        Instanced call = new Instanced(location, bossFilename, level, difficultyId, originalSpawnPool);
        instancedSpawned.add(call);
        return call;
    }
}
