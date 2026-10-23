package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** 生长点状态规划，只读系统/方块快照；状态变化和地下茎增长必须由同一事务写入。 */
public final class RhizomeGrowthPoints {
    private RhizomeGrowthPoints() {}
    public static BlockState mark(BlockState rhizome, boolean active) {
        if (!rhizome.hasProperty(RhizomeBlock.GROWTH_POINT))
            throw new IllegalArgumentException("Rhizome must expose growth_point state");
        return rhizome.setValue(RhizomeBlock.GROWTH_POINT, active);
    }

    /** 自然生成结构的端点也带生长点外形，但 STATIC 不因此启动生长。 */
    public static Map<BlockPos, BlockState> naturalStates(Map<BlockPos, BlockState> rhizomes, BlockPos origin) {
        return naturalStates(rhizomes, Set.of(origin));
    }

    public static Map<BlockPos, BlockState> naturalStates(Map<BlockPos, BlockState> rhizomes, PlantRhizomeSystem system) {
        Set<BlockPos> expected = new HashSet<>(system.rhizomes());
        expected.addAll(system.risingRhizomes());
        if (!system.source().isStatic() || !rhizomes.keySet().equals(expected))
            throw new IllegalArgumentException("Natural markers require a complete static system");
        return naturalStates(rhizomes, system.fiberAnchors());
    }

    private static Map<BlockPos, BlockState> naturalStates(Map<BlockPos, BlockState> rhizomes, Set<BlockPos> roots) {
        Set<BlockPos> nodes = rhizomes.keySet();
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (var entry : rhizomes.entrySet()) {
            int neighbors = 0;
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = entry.getKey().relative(direction);
                if (nodes.contains(adjacent) || roots.contains(adjacent)) neighbors++;
            }
            result.put(entry.getKey().immutable(), mark(entry.getValue(), neighbors <= 1));
        }
        return Map.copyOf(result);
    }

    /** 当前确有方块的已登记端点；本轮长度耗尽也保留外形，后续 D 可在同一端继续。 */
    public static Set<BlockPos> active(PlantRhizomeSystem system) {
        Set<BlockPos> result = new HashSet<>();
        if (system.source().canExpand()) system.growth().ifPresent(growth -> growth.allTips().values().forEach(tip -> {
            if (system.rhizomes().contains(tip.position())) result.add(tip.position());
        }));
        return Set.copyOf(result);
    }
}
