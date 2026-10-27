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
    /** 四个裙位的最近方向最大误差45度，严格小于46度可覆盖模型恰好落在对角线的叶。 */
    public static final double CORRESPONDENCE_DEGREES = 46.0;

    /** 五叶映射四裙位：最近方向唯一对应；对角线平局按北、东、南、西顺序稳定选择。 */
    public static Direction correspondingSlot(double leafYawDegrees) {
        if (!Double.isFinite(leafYawDegrees)) throw new IllegalArgumentException("Invalid leaf yaw");
        Direction best = null;
        double difference = Double.POSITIVE_INFINITY;
        for (var slot : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            double yaw = Math.toDegrees(Math.atan2(slot.getStepX(), slot.getStepZ()));
            double delta = Math.abs(Math.IEEEremainder(leafYawDegrees - yaw, 360.0));
            if (delta < difference) { difference = delta; best = slot; }
        }
        if (difference >= CORRESPONDENCE_DEGREES) throw new IllegalArgumentException("No matching leaf skirt direction");
        return best;
    }

    /** 从实际叶锚点相对芽丛的水平向量取角度，不受叶片高度或跨区块影响。 */
    public static Direction correspondingSlot(BlockPos crown, BlockPos leafAnchor) {
        long dx = (long) leafAnchor.getX() - crown.getX(), dz = (long) leafAnchor.getZ() - crown.getZ();
        if (dx == 0 && dz == 0) throw new IllegalArgumentException("Leaf anchor needs a horizontal direction");
        return correspondingSlot(Math.toDegrees(Math.atan2(dx, dz)));
    }

    public static boolean corresponding(double leafYawDegrees, Direction skirt) {
        return correspondingSlot(leafYawDegrees) == skirt;
    }
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
