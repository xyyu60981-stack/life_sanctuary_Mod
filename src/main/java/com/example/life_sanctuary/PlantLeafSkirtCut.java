package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 直接砍下一整个方向的枯叶裙；不采集资源、不补叶，不替代中心芽丛损毁处理。 */
public final class PlantLeafSkirtCut {
    private PlantLeafSkirtCut() {}

    /**
     * 物种在实际破坏前调用，提供完整结构/支撑及原状态快照。
     * 只有 COMMITTED 的 removed 可用于一次物种掉落，不能再运行原版部件逐块掉落。
     */
    public static PlantStructureDamage.Result remove(ServerLevel level, PlantIndividualState expected,
            Direction slot, PlantStructureSupports supports, Map<BlockPos, BlockState> observed) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var store = PlantIndividuals.get(level);
        if (!PlantLeafSkirts.enabled(expected.species()) || store.isReadOnly() || store.isApplyingStructure()
                || expected.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))
                || !store.find(expected.id()).filter(expected::equals).isPresent()) return rejected();
        var owned = store.partsOf(expected.id());
        if (!supports.positions().equals(owned.keySet()) || !observed.keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Skirt removal requires complete structure snapshots and supports");
        var removed = PlantLeafSkirts.positions(owned, slot);
        if (removed.isEmpty() || removed.contains(expected.base()) || !supports.affectedBy(removed).equals(removed)) return rejected();
        var leaves = store.leavesOf(expected.id());
        if (leaves.isPresent() && leaves.get().leaves().leaves().values().stream()
                .anyMatch(leaf -> leaf.present() && removed.contains(leaf.anchor()))) return rejected();
        for (var pos : removed) {
            var state = observed.get(pos);
            if (state == null || state.isAir() || state.is(Blocks.WATER)) return rejected();
        }
        var result = PlantStructureDamage.removeParts(level, expected, supports, removed, observed);
        if (result.commit().status() != PlantStructureCommitter.Status.COMMITTED) return result;
        var expectedParts = new HashMap<>(owned);
        removed.forEach(expectedParts::remove);
        // 裙消失不能改变活叶账本或既有计时，不能误触发普通叶片补生流程。
        if (!store.partsOf(expected.id()).equals(expectedParts) || !store.leavesOf(expected.id()).equals(leaves)
                || !store.find(expected.id()).filter(expected::equals).isPresent()) {
            store.find(expected.id()).ifPresent(current -> {
                var reasons = new HashSet<>(current.environmentReasons());
                reasons.add("structure_commit_error");
                store.replace(current, current.withEnvironmentReasons(reasons));
            });
            return new PlantStructureDamage.Result(new PlantStructureCommitter.Result(
                    PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty()), Map.of(), Optional.empty());
        }
        return result;
    }

    private static PlantStructureDamage.Result rejected() {
        return new PlantStructureDamage.Result(new PlantStructureCommitter.Result(
                PlantStructureCommitter.Status.REJECTED, Optional.empty()), Map.of(), Optional.empty());
    }
}
