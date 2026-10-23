package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** 可选物种机制。四个裙位存在于普通部件角色中，不占叶位、可育名额或独立计时。 */
public final class PlantLeafSkirts {
    private PlantLeafSkirts() {}
    private static final Set<ResourceLocation> SPECIES = ConcurrentHashMap.newKeySet();
    public static void register(ResourceLocation species) {
        if (PlantSpeciesRegistry.find(Objects.requireNonNull(species)).isEmpty())
            throw new IllegalArgumentException("Leaf skirt species must be defined first");
        if (!SPECIES.add(species)) throw new IllegalArgumentException("Duplicate leaf skirt species: " + species);
    }
    public static boolean enabled(ResourceLocation species) { return SPECIES.contains(species); }
    public static String role(Direction slot) {
        if (Objects.requireNonNull(slot).getAxis() == Direction.Axis.Y)
            throw new IllegalArgumentException("Leaf skirt slots must be horizontal");
        return "leaf_skirt/" + slot.name().toLowerCase(java.util.Locale.ROOT);
    }
    public static boolean belongs(String partRole, Direction slot) {
        String root = role(slot);
        return partRole.equals(root) || partRole.startsWith(root + "/");
    }
    public static boolean occupied(Map<BlockPos, String> parts, Direction slot) {
        String root = role(slot);
        return parts.values().stream().anyMatch(value -> value.equals(root) || value.startsWith(root + "/"));
    }

    /** 单次结构操作使用，不从世界搜索，也不把每个方块当成单独裙位。 */
    public static Set<BlockPos> positions(Map<BlockPos, String> parts, Direction slot) {
        String root = role(slot);
        var positions = new java.util.HashSet<BlockPos>();
        parts.forEach((pos, value) -> {
            if (value.equals(root) || value.startsWith(root + "/")) positions.add(pos.immutable());
        });
        return Set.copyOf(positions);
    }
}
