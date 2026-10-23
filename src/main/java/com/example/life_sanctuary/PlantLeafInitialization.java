package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 为已登记个体添加首批新叶；阶段切换由阶段入口负责，此处不重置阶段计时。 */
public final class PlantLeafInitialization {
    private PlantLeafInitialization() {}

    /**
     * 完整旧结构快照及支撑图必填。initial 不得含历史叶或计时，亦不能给已有叶补发额度。
     * 人工个体尚未满冠时，在结构提交内启动一次整株补叶等待；其他来源不启动循环。
     */
    public static PlantStructureCommitter.Result place(ServerLevel level, PlantStructurePlan plan,
            PlantLeafData initial, BlockPos center, PlantStructureSupports supports) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var owner = plan.expectedIndividual();
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || owner.pruned() || !owner.environmentReasons().isEmpty()
                || !store.find(owner.id()).filter(owner::equals).isPresent()
                || store.leavesOf(owner.id()).isPresent()
                || !PlantHarvestEligibility.permitsInitialLeaves(initial, owner.species(), owner.stage())
                || !initial.timers().equals(PlantLeafTimers.EMPTY)
                || initial.leaves().leaves().isEmpty()) return rejected();
        var owned = store.partsOf(owner.id());
        if (!owned.containsKey(center) || !plan.dependencies().keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Initialization needs a retained center and complete old snapshots");
        var target = new HashMap<>(owned);
        var added = plan.changes().keySet();
        for (var entry : plan.changes().entrySet()) {
            if (owned.containsKey(entry.getKey()) || entry.getValue().role().isEmpty()
                    || entry.getValue().policy().requiresOwnership()) return rejected();
            target.put(entry.getKey(), entry.getValue().role());
        }
        var covered = new HashSet<BlockPos>();
        for (var leaf : initial.leaves().leaves().values()) {
            if (!leaf.present() || leaf.fertile() && leaf.remainingCollections() == 0) return rejected();
            if (owned.values().stream().anyMatch(role -> role.equals(leaf.role()) || role.startsWith(leaf.role() + "/")))
                return rejected();
            var anchor = plan.changes().get(leaf.anchor());
            if (anchor == null || !anchor.role().equals(leaf.role())) return rejected();
            var positions = new HashSet<BlockPos>();
            plan.changes().forEach((pos, change) -> {
                if (change.role().equals(leaf.role()) || change.role().startsWith(leaf.role() + "/")) positions.add(pos);
            });
            for (var pos : positions) {
                if (!covered.add(pos) || pos.equals(center) || pos.equals(owner.base())) return rejected();
                var parents = supports.requiredSupports().get(pos);
                if (parents == null || parents.isEmpty()
                        || parents.stream().anyMatch(parent -> !parent.equals(center) && !positions.contains(parent))) return rejected();
            }
        }
        if (!covered.equals(added) || !supports.positions().equals(target.keySet())
                || !supports.hasSupportPath(center, owner.base())) return rejected();
        for (var entry : supports.requiredSupports().entrySet())
            if (!added.contains(entry.getKey()) && entry.getValue().stream().anyMatch(added::contains)) return rejected();
        initial.validateOwner(owner, target);
        var checked = PlantStructurePreflight.check(level, store, plan);
        if (!checked.ready()) return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.of(checked));
        var result = PlantStructureCommitter.applyLeafInitialization(level, plan, new PlantLeafInitializationChange(initial, level.random));
        if (result.status() == PlantStructureCommitter.Status.COMMITTED
                && (!store.leavesOf(owner.id()).filter(after -> matchesInitial(after, initial, owner)).isPresent()
                    || !store.partsOf(owner.id()).equals(target) || !store.find(owner.id()).filter(owner::equals).isPresent())) {
            store.find(owner.id()).filter(current -> current.species().equals(owner.species()) && current.base().equals(owner.base()))
                    .ifPresent(current -> {
                        var reasons = new HashSet<>(current.environmentReasons());
                        reasons.add("structure_commit_error");
                        store.replace(current, current.withEnvironmentReasons(reasons));
                    });
            return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.RECOVERY_REQUIRED, Optional.empty());
        }
        return result;
    }

    static boolean matchesInitial(PlantLeafData after, PlantLeafData initial, PlantIndividualState owner) {
        boolean needsWait = owner.source().canProgress() && initial.leaves().freeLeafSlots() > 0;
        return after.leaves().equals(initial.leaves()) && after.timers().shedding().isEmpty()
                && after.timers().regrowth().isPresent() == needsWait
                && after.timers().regrowth().map(timer -> timer.kind() == PlantLeafTimer.Kind.REGROWTH
                    && timer.elapsedTicks() == 0 && !timer.failed()).orElse(!needsWait);
    }

    private static PlantStructureCommitter.Result rejected() {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.empty());
    }
}
