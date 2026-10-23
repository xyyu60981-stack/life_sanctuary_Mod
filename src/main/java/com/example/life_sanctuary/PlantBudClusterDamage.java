package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 独立叶植株中心芽丛毁坏：叶与芽丛无掉落消失，保留其他结构并原子启动枯死。 */
public final class PlantBudClusterDamage {
    private PlantBudClusterDamage() {}

    /**
     * 物种确认命中真实芽丛后，在原版实际破坏前调用；budRole 包含该命名空间全部芽丛/拳卷叶。
     * duration 由物种提供，supports/observed 必须覆盖整株，不推断形状、时长或掉落。
     */
    public static PlantStructureCommitter.Result destroy(ServerLevel level, PlantIndividualState expected,
            PlantLeafData leaves, String budRole, long duration, PlantStructureSupports supports,
            Map<BlockPos, BlockState> observed) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (budRole == null || budRole.isBlank() || duration <= 0)
            throw new IllegalArgumentException("Missing bud role or withering duration");
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || PlantWithering.isWithering(expected)
                || expected.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))
                || store.witheringTimer(expected.id()).isPresent()
                || !store.find(expected.id()).filter(expected::equals).isPresent()
                || !store.leavesOf(expected.id()).filter(leaves::equals).isPresent()) return rejected();
        var owned = store.partsOf(expected.id());
        if (!supports.positions().equals(owned.keySet()) || !observed.keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Bud loss requires complete structure snapshots and supports");
        var removed = new HashSet<BlockPos>();
        owned.forEach((pos, role) -> { if (belongs(role, budRole)) removed.add(pos); });
        if (removed.isEmpty()) return rejected();
        for (var leaf : leaves.leaves().leaves().values()) if (leaf.present())
            owned.forEach((pos, role) -> { if (belongs(role, leaf.role())) removed.add(pos); });
        if (removed.contains(expected.base()) || !owned.containsKey(expected.base())
                || !supports.affectedBy(removed).equals(removed)) return rejected();
        // 裙可保留，不能由过宽的芽丛/叶角色把裙一并吞掉。
        for (var pos : removed)
            if (owned.get(pos).startsWith("leaf_skirt/")) return rejected();
        var changes = new LinkedHashMap<BlockPos, PlantStructurePlan.Change>();
        for (var pos : removed.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            var before = java.util.Objects.requireNonNull(observed.get(pos));
            if (before.isAir()) return rejected();
            changes.put(pos, PlantStructurePlan.Change.removeOwned(before));
        }
        var plan = new PlantStructurePlan(expected, changes, observed);
        var loss = new PlantBudLossChange(leaves, duration);
        var result = PlantStructureCommitter.applyBudLoss(level, plan, loss);
        if (result.status() == PlantStructureCommitter.Status.COMMITTED) {
            var next = loss.nextOwner(expected);
            var after = store.leavesOf(expected.id()).orElse(null);
            var expectedParts = new HashMap<>(owned);
            removed.forEach(expectedParts::remove);
            var expectedLeaves = leaves.afterParts(expectedParts).leaves();
            var timer = store.witheringTimer(expected.id()).orElse(null);
            if (after == null || !after.leaves().equals(expectedLeaves) || !after.timers().equals(PlantLeafTimers.EMPTY)
                    || timer == null || timer.failed() || timer.targetTicks() != duration
                    || !store.partsOf(expected.id()).equals(expectedParts) || !store.find(expected.id()).filter(next::equals).isPresent()) {
                pause(store, expected);
                return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());
            }
        } else if (result.status() == PlantStructureCommitter.Status.RECOVERY_REQUIRED) pause(store, expected);
        return result;
    }

    private static boolean belongs(String role, String root) { return role.equals(root) || role.startsWith(root + "/"); }
    private static void pause(PlantIndividuals store, PlantIndividualState expected) {
        store.find(expected.id()).filter(current -> current.species().equals(expected.species())
                && current.base().equals(expected.base())).ifPresent(current -> {
            var reasons = new HashSet<>(current.environmentReasons());
            reasons.add("structure_commit_error");
            store.replace(current, current.withEnvironmentReasons(reasons));
            if (store.witheringTimer(current.id()).isPresent()) store.failWithering(current.id());
        });
    }
    private static PlantStructureCommitter.Result rejected() {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.empty());
    }
}
