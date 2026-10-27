package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 整株结构移除完成后注销个体；失败时保留身份供恢复，不先腾出种群名额。 */
public final class PlantStructureRemoval {
    private PlantStructureRemoval() {}

    public record Result(PlantStructureCommitter.Result commit, Map<BlockPos, BlockState> removed) {
        public Result { removed = Map.copyOf(removed); }
    }

    /**
     * 支持主动破坏及物种判定后的消失；触发原因、权限、掉落由适配器负责。
     * 必须在实际破坏前拦截并捕获全株快照，不能同时让原版再破坏和发放掉落。
     * 所有涉及区块须可用，不主动加载，也不处理缺失/损坏的残存结构。
     */
    public static Result removeIndividual(ServerLevel level, PlantIndividualState expected,
            Map<BlockPos, BlockState> observed) {
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || !expected.equals(store.find(expected.id()).orElse(null))
                || !RhizomeShootLifecycle.canRetire(level, expected))
            return result(PlantStructureCommitter.Status.REJECTED);
        var owned = store.partsOf(expected.id());
        if (owned.isEmpty() || !owned.containsKey(expected.base()))
            return result(PlantStructureCommitter.Status.REJECTED);
        if (!observed.keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Whole plant removal requires a complete structural read set");

        Map<BlockPos, PlantStructurePlan.Change> changes = new LinkedHashMap<>();
        Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        for (BlockPos pos : owned.keySet().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            BlockState state = java.util.Objects.requireNonNull(observed.get(pos));
            // 空气中的残留归属属于恢复问题，不能假装完整移除并放出名额。
            if (state.isAir()) return result(PlantStructureCommitter.Status.REJECTED);
            changes.put(pos, PlantStructurePlan.Change.removeOwned(state));
            previous.put(pos, state);
        }
        var plan = new PlantStructurePlan(expected, changes, observed);
        var commit = PlantStructureCommitter.apply(level, store, plan, expected);
        if (commit.status() != PlantStructureCommitter.Status.COMMITTED) return new Result(commit, Map.of());

        // 邻居通知可能触发第三方回调；身份和空归属都须再次确认，不能删掉回调创建的新状态。
        if (!retire(level, store, expected, Map.of())) return result(PlantStructureCommitter.Status.RECOVERY_REQUIRED);
        // remove 同步清理基底索引、部件索引、成长调度与种群计数；之后才允许适配器结算掉落。
        return new Result(commit, previous);
    }

    /** 移除或枯死转换成功后共用；不写世界，不替第三方回调修改过的状态注销。 */
    static boolean retire(ServerLevel level, PlantIndividuals store, PlantIndividualState expected, Map<BlockPos, String> expectedParts) {
        return retire(level, store, expected, expectedParts, null, Map.of());
    }

    static boolean retire(ServerLevel level, PlantIndividuals store, PlantIndividualState expected, Map<BlockPos, String> expectedParts,
            Map<BlockPos, BlockState> corpses, Map<String, PlantFlowerState> matureBefore) {
        if (store != PlantIndividuals.get(level)) throw new IllegalArgumentException("Wrong dimension individual store");
        if (!expected.equals(store.find(expected.id()).orElse(null))
                || !store.partsOf(expected.id()).equals(expectedParts)
                || !RhizomeShootLifecycle.canRetire(level, expected)) {
            pauseForRecovery(store, expected);
            return false;
        }
        var systems = PlantRhizomeSystems.get(level);
        var owner = systems.forShoot(expected.id());
        if (corpses == null ? store.remove(expected.id(), expected.base()) : store.removeWithRescuedSeeds(expected, corpses, matureBefore)) {
            if (owner.isEmpty()) return true;
            try {
                if (RhizomeShootLifecycle.releaseRemoved(level, owner.get(), expected.id())
                        == RhizomeShootLifecycle.Result.RELEASED) return true;
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(PlantStructureRemoval.class).error("Cannot release retired shoot " + expected.id(), failure);
            }
            systems.find(owner.get().id()).ifPresent(system -> systems.requireRecovery(system.id()));
            return false;
        }
        pauseForRecovery(store, expected);
        return false;
    }

    private static void pauseForRecovery(PlantIndividuals store, PlantIndividualState expected) {
        store.find(expected.id()).filter(state -> state.base().equals(expected.base())
                && state.species().equals(expected.species())).ifPresent(state -> {
            var reasons = new HashSet<>(state.environmentReasons());
            reasons.add("structure_removal_error");
            store.replace(state, state.withEnvironmentReasons(reasons));
        });
    }

    private static Result result(PlantStructureCommitter.Status status) {
        return new Result(new PlantStructureCommitter.Result(status, Optional.empty()), Map.of());
    }
}
