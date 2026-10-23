package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一次自然种群的完整几何提案。仅合并并校验物种给出的快照，不读世界、不登记身份、不写方块。
 * 不能逐株调用单株放置器来代替未来的整组提交，否则后半组失败会留下部分种群。
 */
public record RhizomeNaturalStructurePlan(RhizomeNaturalPopulationPlan population,
        Map<UUID, PlantStructurePlan> plants, Map<BlockPos, Underground> underground,
        Map<BlockPos, BlockState> dependencies) {
    public record Underground(BlockState expected, BlockState replacement) {
        public Underground {
            Objects.requireNonNull(expected);
            Objects.requireNonNull(replacement);
            if (!RhizomePathRules.canConvert(expected) || !(replacement.getBlock() instanceof RhizomeBlock)
                    || replacement.hasBlockEntity())
                throw new IllegalArgumentException("Invalid natural underground conversion");
            // 共用精确水状态检查；装饰配对清理须在未来整组提交器中处理。
            new RhizomeShootStructure.Part(RhizomeShootStructure.Kind.RHIZOME, expected, replacement);
        }
    }

    public RhizomeNaturalStructurePlan {
        Objects.requireNonNull(population);
        plants = Map.copyOf(plants);
        underground = positions(underground);
        dependencies = positions(dependencies);
        if (!plants.keySet().equals(population.plants().keySet()))
            throw new IllegalArgumentException("Natural geometry must cover every planned plant exactly once");

        var occupied = new HashSet<BlockPos>();
        Map<BlockPos, BlockState> reads = new HashMap<>(dependencies);
        var reference = population.plants().values().iterator().next().base();
        Map<UUID, Map<BlockPos, PlantStructurePlan.Change>> parts = new HashMap<>();
        for (var entry : plants.entrySet()) {
            var plan = entry.getValue();
            var plant = population.plants().get(entry.getKey());
            if (!plan.expectedIndividual().equals(plant) || !plan.changes().containsKey(plant.base()))
                throw new IllegalArgumentException("Natural plant geometry has a different identity or missing base");
            for (var changeEntry : plan.changes().entrySet()) {
                var pos = changeEntry.getKey();
                var change = changeEntry.getValue();
                if (change.role().isEmpty() || change.policy().requiresOwnership()
                        || !sameRegion(reference, pos) || !occupied.add(pos))
                    throw new IllegalArgumentException("Natural plant geometry overlaps, removes parts or leaves its population");
                read(reads, pos, change.expected());
            }
            plan.dependencies().forEach((pos, state) -> read(reads, pos, state));
            parts.put(entry.getKey(), plan.changes());
        }

        var requiredNodes = new HashSet<BlockPos>();
        for (var system : population.systems().values()) {
            var nodes = new HashSet<>(system.rhizomes());
            nodes.addAll(system.risingRhizomes());
            Map<BlockPos, BlockState> states = new HashMap<>();
            for (var pos : nodes) {
                var change = underground.get(pos);
                if (change == null || !requiredNodes.add(pos) || !occupied.add(pos) || !sameRegion(reference, pos))
                    throw new IllegalArgumentException("Missing or overlapping natural underground geometry");
                read(reads, pos, change.expected());
                states.put(pos, change.replacement());
            }
            if (!RhizomeGrowthPoints.naturalStates(states, system).equals(states))
                throw new IllegalArgumentException("Natural endpoint markers do not match complete underground geometry");
            // 根位必须确实由对应地上结构提供，不能只存在于元数据中。
            if (!parts.get(system.id()).containsKey(system.origin()))
                throw new IllegalArgumentException("Missing primary fiber root geometry");
            if (system.growth().isPresent()) for (var branch : system.growth().get().branches().entrySet()) {
                var branchParts = parts.get(branch.getKey());
                if (!system.shoots().containsKey(branch.getKey()) || branchParts == null
                        || !branchParts.containsKey(branch.getValue().root()))
                    throw new IllegalArgumentException("Missing linked branch fiber root geometry");
            }
        }
        if (!requiredNodes.equals(underground.keySet()))
            throw new IllegalArgumentException("Unregistered underground geometry in natural population");
    }

    /** 合并后唯一的预期读取集合；物种依赖可能超出种群，写入位置不能超出。 */
    public Map<BlockPos, BlockState> expectedStates() {
        Map<BlockPos, BlockState> result = new HashMap<>(dependencies);
        plants.values().forEach(plan -> {
            plan.dependencies().forEach((pos, state) -> read(result, pos, state));
            plan.changes().forEach((pos, change) -> read(result, pos, change.expected()));
        });
        underground.forEach((pos, change) -> read(result, pos, change.expected()));
        return Map.copyOf(result);
    }

    private static void read(Map<BlockPos, BlockState> reads, BlockPos pos, BlockState state) {
        var previous = reads.putIfAbsent(pos.immutable(), state);
        if (previous != null && !previous.equals(state))
            throw new IllegalArgumentException("Conflicting natural structure snapshots at " + pos);
    }

    private static boolean sameRegion(BlockPos reference, BlockPos pos) {
        return Math.floorDiv(reference.getX(), 32) == Math.floorDiv(pos.getX(), 32)
                && Math.floorDiv(reference.getZ(), 32) == Math.floorDiv(pos.getZ(), 32);
    }

    private static <T> Map<BlockPos, T> positions(Map<BlockPos, T> source) {
        Map<BlockPos, T> copy = new HashMap<>();
        source.forEach((pos, value) -> copy.put(pos.immutable(), Objects.requireNonNull(value)));
        return Map.copyOf(copy);
    }
}
