package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/** 芽丛损毁导致的枯死流程接口；枯死完成后保留死体方块，但注销活体个体。 */
public final class PlantWithering {
    public static final String BUD_CLUSTER_DESTROYED = "bud_cluster_destroyed";
    private PlantWithering() {}

    /**
     * 物种确认芽丛损毁后调用。冻结普通生长，不重置成熟进度、采集或繁殖额度。
     * 枯死计时独立于已暂停的成长计时，由物种提供，不能靠成长调度器继续推进。
     * 实体破坏可作用于 STATIC；这不为静止植株开启气候或水分检测。
     */
    public static PlantLifecycle.Result beginAfterBudLoss(PlantIndividuals store, PlantIndividualState expected) {
        if (store.isReadOnly() || !expected.equals(store.find(expected.id()).orElse(null)))
            return PlantLifecycle.Result.REJECTED;
        if (isWithering(expected)) return PlantLifecycle.Result.UNCHANGED;
        var reasons = new HashSet<>(expected.environmentReasons());
        reasons.add(BUD_CLUSTER_DESTROYED);
        return store.replace(expected, expected.withEnvironmentReasons(reasons))
                ? PlantLifecycle.Result.CHANGED : PlantLifecycle.Result.REJECTED;
    }

    public static boolean isWithering(PlantIndividualState state) {
        return state.environmentReasons().contains(BUD_CLUSTER_DESTROYED);
    }

    static boolean requiresRecovery(PlantIndividualState state) {
        return !isWithering(state) || state.environmentReasons().contains("structure_commit_error")
                || state.environmentReasons().contains("structure_removal_error")
                || state.environmentReasons().contains("withering_callback_error");
    }

    /** 使用独立持久化计时；duration 由物种定义，重复芽丛损毁不重置倒计时。 */
    public static PlantLifecycle.Result beginAfterBudLoss(PlantIndividuals store, PlantIndividualState expected, long duration) {
        return store.beginWithering(expected, duration);
    }

    /**
     * 仅在物种枯死程序确认完成后调用。deadStates 须覆盖全部剩余部件：
     * 提供枯死方块状态，或以空气/原水状态表示消失。共享层不猜测死体形态和掉落。
     * 枯死结构不保留活体归属，后续不能通过活体恢复接口复苏。
     */
    public static PlantStructureCommitter.Result finish(ServerLevel level, PlantIndividualState expected,
            Map<BlockPos, BlockState> deadStates, Map<BlockPos, BlockState> observed) {
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || requiresRecovery(expected)
                || !expected.equals(store.find(expected.id()).orElse(null))
                || !RhizomeShootLifecycle.canRetire(level, expected)) return result(PlantStructureCommitter.Status.REJECTED);
        var timer = store.witheringTimer(expected.id());
        if (timer.isPresent() && (timer.get().failed() || timer.get().remainingTicks() != 0))
            return result(PlantStructureCommitter.Status.REJECTED);
        var owned = store.partsOf(expected.id());
        if (!deadStates.keySet().equals(owned.keySet()) || !observed.keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Withering requires complete remaining structure targets and reads");
        if (owned.isEmpty())
            return result(PlantStructureRemoval.retire(level, store, expected, Map.of())
                    ? PlantStructureCommitter.Status.COMMITTED : PlantStructureCommitter.Status.RECOVERY_REQUIRED);

        Map<BlockPos, PlantStructurePlan.Change> changes = new LinkedHashMap<>();
        Map<BlockPos, String> remaining = new LinkedHashMap<>();
        for (BlockPos pos : owned.keySet().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            BlockState before = Objects.requireNonNull(observed.get(pos));
            BlockState after = Objects.requireNonNull(deadStates.get(pos));
            if (before.isAir()) return result(PlantStructureCommitter.Status.REJECTED);
            var removal = PlantStructurePlan.Change.removeOwned(before);
            if (after.equals(removal.replacement())) {
                changes.put(pos, removal);
            } else {
                var policy = before.getFluidState().is(FluidTags.WATER)
                        ? PlantStructurePlan.Replacement.OWNED_WATER_PART : PlantStructurePlan.Replacement.OWNED_PART;
                changes.put(pos, new PlantStructurePlan.Change(before, after, owned.get(pos), policy));
                remaining.put(pos, owned.get(pos));
            }
        }
        var matureBefore = store.matureSeedsForWithering(expected);
        var commit = PlantStructureCommitter.apply(level, store, new PlantStructurePlan(expected, changes, observed), expected);
        if (commit.status() != PlantStructureCommitter.Status.COMMITTED) return commit;
        // 死体结构已经完整落地及通知后，才释放活体身份、调度和种群名额。
        return PlantStructureRemoval.retire(level, store, expected, remaining, deadStates, matureBefore)
                ? commit : result(PlantStructureCommitter.Status.RECOVERY_REQUIRED);
    }

    private static PlantStructureCommitter.Result result(PlantStructureCommitter.Status status) {
        return new PlantStructureCommitter.Result(status, Optional.empty());
    }
}
