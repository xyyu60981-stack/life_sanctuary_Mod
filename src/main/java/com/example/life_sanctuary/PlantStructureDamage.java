package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 保留个体基部的局部结构破坏；走茎切断和整株拔除应使用各自专用流程。 */
public final class PlantStructureDamage {
    private PlantStructureDamage() {}

    public record Result(PlantStructureCommitter.Result commit, Map<BlockPos, BlockState> removed,
            Optional<PlantStructureSupports> remainingSupports) {
        public Result { removed = Map.copyOf(removed); }
    }

    /**
     * 在破坏世界方块之前调用，由适配器拦截普通破坏并捕获完整结构/外部支撑快照。
     * 只有 COMMITTED 的 removed 可用于后续掉落；共享层不生成物品、音效或经验。
     * 不能同时再执行原版掉落，不能从已经清掉方块的 onRemove 回调事后调用。
     */
    public static Result removeParts(ServerLevel level, PlantIndividualState expected, PlantStructureSupports supports,
            Set<BlockPos> removed, Map<BlockPos, BlockState> observed) {
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || !expected.equals(store.find(expected.id()).orElse(null))) return rejected();
        var owned = store.partsOf(expected.id());
        if (!supports.positions().equals(owned.keySet()))
            throw new IllegalArgumentException("Support graph must describe every owned plant part");
        var affected = supports.affectedBy(removed);
        if (affected.isEmpty() || affected.contains(expected.base())) return rejected();
        if (!owned.containsKey(expected.base()) || !observed.keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Partial removal requires a retained base and complete read set");
        Map<BlockPos, PlantStructurePlan.Change> changes = new LinkedHashMap<>();
        Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        for (BlockPos pos : affected.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            BlockState state = java.util.Objects.requireNonNull(observed.get(pos));
            changes.put(pos, PlantStructurePlan.Change.removeOwned(state));
            previous.put(pos, state);
        }
        var remaining = supports.afterRemoving(removed);
        var plan = new PlantStructurePlan(expected, changes, observed);
        var result = PlantStructureCommitter.apply(level, store, plan, expected);
        boolean committed = result.status() == PlantStructureCommitter.Status.COMMITTED;
        return new Result(result, committed ? previous : Map.of(), committed ? remaining : Optional.empty());
    }

    private static Result rejected() {
        return new Result(new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.empty()),
                Map.of(), Optional.empty());
    }
}
