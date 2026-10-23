package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** 普通采空叶整片无掉落脱落；枯叶裙转换必须另用物种专用事务。 */
public final class PlantLeafShedding {
    private PlantLeafShedding() {}

    /**
     * 叶的全部部件角色为 leaf.role 或 leaf.role + "/" 下的子角色，不能与其他叶混用命名空间。
     * supports/observed 描述整株结构，包含未改动的中心与外部支撑依赖。不能事后从 onRemove 调用。
     */
    public static PlantStructureCommitter.Result remove(ServerLevel level, PlantIndividualState expected,
            PlantLeafData leaves, String key, PlantStructureSupports supports,
            java.util.Map<BlockPos, BlockState> observed, RandomSource random) {
        // 已选择枯叶裙机制的物种应走转换分派，不能直接绕过缺裙转换。
        if (PlantLeafSkirts.enabled(expected.species())) return rejected();
        return remove(level, expected, leaves, key, supports, observed, random, false);
    }

    static PlantStructureCommitter.Result removeWithExistingSkirt(ServerLevel level, PlantIndividualState expected,
            PlantLeafData leaves, String key, PlantStructureSupports supports,
            java.util.Map<BlockPos, BlockState> observed, RandomSource random) {
        return remove(level, expected, leaves, key, supports, observed, random, false);
    }

    static PlantStructureCommitter.Result cut(ServerLevel level, PlantIndividualState expected,
            PlantLeafData leaves, String key, PlantStructureSupports supports,
            java.util.Map<BlockPos, BlockState> observed, RandomSource random) {
        return remove(level, expected, leaves, key, supports, observed, random, true);
    }

    private static PlantStructureCommitter.Result remove(ServerLevel level, PlantIndividualState expected,
            PlantLeafData leaves, String key, PlantStructureSupports supports,
            java.util.Map<BlockPos, BlockState> observed, RandomSource random, boolean manual) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        java.util.Objects.requireNonNull(random);
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || (!manual && !PlantLeafScheduler.canRun(expected))
                || expected.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))
                || !store.find(expected.id()).filter(expected::equals).isPresent()
                || !store.leavesOf(expected.id()).filter(leaves::equals).isPresent()) return rejected();
        var leaf = leaves.leaves().leaves().get(key);
        var timer = leaves.timers().shedding().get(key);
        if (leaf == null || !leaf.present() || (!manual && (!leaf.exhausted() || timer == null || !timer.ready()))) return rejected();
        var owned = store.partsOf(expected.id());
        if (!supports.positions().equals(owned.keySet()) || !observed.keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Leaf shedding requires complete structure/support snapshots");
        var removed = new java.util.HashSet<BlockPos>();
        owned.forEach((pos, role) -> {
            if (role.equals(leaf.role()) || role.startsWith(leaf.role() + "/")) removed.add(pos);
        });
        if (!removed.contains(leaf.anchor()) || removed.contains(expected.base())
                || !supports.affectedBy(removed).equals(removed)
                || leaves.leaves().leaves().entrySet().stream().anyMatch(entry -> !entry.getKey().equals(key)
                    && entry.getValue().present() && removed.contains(entry.getValue().anchor()))) return rejected();
        var changes = new LinkedHashMap<BlockPos, PlantStructurePlan.Change>();
        removed.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).forEach(pos ->
                changes.put(pos, PlantStructurePlan.Change.removeOwned(java.util.Objects.requireNonNull(observed.get(pos)))));
        var plan = new PlantStructurePlan(expected, changes, observed);
        var preflight = PlantStructurePreflight.check(level, store, plan);
        if (!preflight.ready()) return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.of(preflight));
        var result = manual ? PlantStructureCommitter.applyLeafCut(level, plan, new PlantLeafCutChange(key, leaves, random))
                : PlantStructureCommitter.applyLeafShedding(level, plan, new PlantLeafSheddingChange(key, leaves, random));
        if (result.status() == PlantStructureCommitter.Status.COMMITTED) {
            var after = store.leavesOf(expected.id()).orElse(null);
            var expectedLeaves = leaves.leaves().replace(key, leaf, leaf.withoutStructure()).orElseThrow();
            var expectedParts = new HashMap<>(owned);
            removed.forEach(expectedParts::remove);
            if (after == null || !after.leaves().equals(expectedLeaves) || after.timers().shedding().containsKey(key)
                    || after.timers().regrowth().isPresent() != expected.source().canProgress()
                    || !store.partsOf(expected.id()).equals(expectedParts)
                    || !store.find(expected.id()).filter(expected::equals).isPresent()) {
                store.find(expected.id()).ifPresent(current -> {
                    var reasons = new java.util.HashSet<>(current.environmentReasons());
                    reasons.add("structure_commit_error");
                    store.replace(current, current.withEnvironmentReasons(reasons));
                });
                return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());
            }
        }
        return result;
    }

    private static PlantStructureCommitter.Result rejected() {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.empty());
    }
}
