package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/** 由个体/花/归属变化维护的派生索引；每株只登记一次，不保存为第二份权威存档。 */
final class PlantPollenIndex {
    private record Bucket(ResourceLocation species, long chunk) {}
    private record Entry(Bucket bucket, BlockPos base) {}
    private final Map<UUID, Entry> individuals = new HashMap<>();
    private final Map<Bucket, Set<UUID>> buckets = new HashMap<>();

    void update(PlantIndividualState state, boolean providesPollen) {
        if (!providesPollen) { remove(state.id()); return; }
        Entry next = new Entry(new Bucket(state.species(), ChunkPos.asLong(state.base().getX() >> 4,
                state.base().getZ() >> 4)), state.base());
        if (next.equals(individuals.get(state.id()))) return;
        remove(state.id());
        individuals.put(state.id(), next);
        buckets.computeIfAbsent(next.bucket(), ignored -> new HashSet<>()).add(state.id());
    }

    void remove(UUID id) {
        Entry previous = individuals.remove(id);
        if (previous == null) return;
        Set<UUID> members = buckets.get(previous.bucket());
        members.remove(id);
        if (members.isEmpty()) buckets.remove(previous.bucket());
    }

    /** XYZ 21×9×21：以基底为中心，X/Z ±10、Y ±4，不搜索世界方块。 */
    Set<UUID> cycadCandidates(ResourceLocation species, BlockPos center) {
        long minX = (long) center.getX() - 10, maxX = (long) center.getX() + 10;
        long minZ = (long) center.getZ() - 10, maxZ = (long) center.getZ() + 10;
        Set<UUID> result = new HashSet<>();
        for (int x = (int) Math.floorDiv(minX, 16); x <= (int) Math.floorDiv(maxX, 16); x++) {
            for (int z = (int) Math.floorDiv(minZ, 16); z <= (int) Math.floorDiv(maxZ, 16); z++) {
                for (UUID id : buckets.getOrDefault(new Bucket(species, ChunkPos.asLong(x, z)), Set.of())) {
                    BlockPos base = individuals.get(id).base();
                    if (base.getX() >= minX && base.getX() <= maxX && base.getZ() >= minZ && base.getZ() <= maxZ
                            && Math.abs((long) base.getY() - center.getY()) <= 4) result.add(id);
                }
            }
        }
        return Set.copyOf(result);
    }

    void clear() { individuals.clear(); buckets.clear(); }
}
