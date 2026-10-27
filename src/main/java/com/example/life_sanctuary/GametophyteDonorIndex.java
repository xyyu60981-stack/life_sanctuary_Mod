package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Derived 4x4x4 buckets: a 5-cube query touches at most eight buckets, not every plant in a tall chunk. */
final class GametophyteDonorIndex {
    private record Bucket(ResourceLocation species, int x, int y, int z) {}
    private final Map<UUID, Bucket> entries = new HashMap<>();
    private final Map<Bucket, Set<UUID>> buckets = new HashMap<>();
    void update(UUID id, ResourceLocation species, BlockPos pos, boolean enabled) {
        if (!enabled) { remove(id); return; }
        var bucket = new Bucket(species, Math.floorDiv(pos.getX(), 4), Math.floorDiv(pos.getY(), 4), Math.floorDiv(pos.getZ(), 4));
        if (bucket.equals(entries.get(id))) return;
        remove(id); entries.put(id, bucket); buckets.computeIfAbsent(bucket, ignored -> new HashSet<>()).add(id);
    }
    void remove(UUID id) {
        var old = entries.remove(id); if (old == null) return;
        var group = buckets.get(old); group.remove(id); if (group.isEmpty()) buckets.remove(old);
    }
    Set<UUID> nearby(ResourceLocation species, BlockPos center) {
        var result = new HashSet<UUID>();
        for (int x = (int) Math.floorDiv((long) center.getX() - 2, 4); x <= Math.floorDiv((long) center.getX() + 2, 4); x++)
            for (int y = (int) Math.floorDiv((long) center.getY() - 2, 4); y <= Math.floorDiv((long) center.getY() + 2, 4); y++)
                for (int z = (int) Math.floorDiv((long) center.getZ() - 2, 4); z <= Math.floorDiv((long) center.getZ() + 2, 4); z++)
                    result.addAll(buckets.getOrDefault(new Bucket(species, x, y, z), Set.of()));
        return Set.copyOf(result);
    }
}
