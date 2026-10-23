package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** 一次补一片的世界/叶资源联合提交，不自行猜测形状、可育概率或初始采集次数。 */
public final class PlantLeafRegrowth {
    private PlantLeafRegrowth() {}

    /**
     * plan 只含本片新叶，dependencies 覆盖全部旧部件及物种额外生态读取。
     * 完整支撑图包含旧部件与新叶；新叶的支撑只能指向本叶其他部件或指定的现有中心芽丛。
     */
    public static PlantStructureCommitter.Result place(ServerLevel level, PlantStructurePlan plan,
            PlantLeafData leaves, String newKey, PlantLeafState leaf, BlockPos center,
            PlantStructureSupports supports, RandomSource random) {
        return place(level, plan, leaves, newKey, leaf, center, supports, random, Optional.empty());
    }

    /** 新叶判为可育时，转移指定被砍叶的完整剩余次数，不新发一份额度。 */
    public static PlantStructureCommitter.Result placeInherited(ServerLevel level, PlantStructurePlan plan,
            PlantLeafData leaves, String retainedKey, String newKey, PlantLeafState leaf, BlockPos center,
            PlantStructureSupports supports, RandomSource random) {
        return place(level, plan, leaves, newKey, leaf, center, supports, random, Optional.of(retainedKey));
    }

    private static PlantStructureCommitter.Result place(ServerLevel level, PlantStructurePlan plan,
            PlantLeafData leaves, String newKey, PlantLeafState leaf, BlockPos center,
            PlantStructureSupports supports, RandomSource random, Optional<String> retainedKey) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        java.util.Objects.requireNonNull(random);
        var expected = plan.expectedIndividual();
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || !PlantLeafScheduler.canRun(expected)
                || !store.find(expected.id()).filter(expected::equals).isPresent()
                || !store.leavesOf(expected.id()).filter(leaves::equals).isPresent()
                || leaves.timers().regrowth().isEmpty() || !leaves.timers().regrowth().get().ready()) return rejected();
        if (expected.harvests().containsKey(newKey) || expected.reproduction().containsKey(newKey)) return rejected();
        if (leaf.reservesFertileSlot() && !PlantHarvestEligibility.canCollectSporeOrSeed(expected)) return rejected();
        // 有旧额度时可育新叶必须走继承入口，不能用空余名额绕过用户确认的继承规则。
        if (retainedKey.isEmpty() && leaf.fertile() && PlantLeafFertility.retainedKey(leaves.leaves()).isPresent()) return rejected();
        var afterLeaves = retainedKey.isPresent() ? leaves.leaves().inheritRetained(retainedKey.get(), newKey, leaf).orElse(null)
                : leaves.leaves().addNew(newKey, leaf).orElse(null);
        if (afterLeaves == null || leaf.fertile() && leaf.remainingCollections() == 0) return rejected();
        var owned = store.partsOf(expected.id());
        if (!owned.containsKey(center) || !plan.dependencies().keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Regrowth requires a retained center and all old structure snapshots");
        var added = plan.changes().keySet();
        var anchor = plan.changes().get(leaf.anchor());
        if (anchor == null || !anchor.role().equals(leaf.role())) return rejected();
        if (leaves.leaves().leaves().values().stream().anyMatch(old -> old.present()
                && (old.role().equals(leaf.role()) || old.role().startsWith(leaf.role() + "/")
                    || leaf.role().startsWith(old.role() + "/")))) return rejected();
        for (var entry : plan.changes().entrySet()) {
            var change = entry.getValue();
            if (owned.containsKey(entry.getKey()) || entry.getKey().equals(expected.base())
                    || change.policy().requiresOwnership() || change.role().isEmpty()
                    || !(change.role().equals(leaf.role()) || change.role().startsWith(leaf.role() + "/"))) return rejected();
        }
        var allParts = new HashSet<>(owned.keySet());
        allParts.addAll(added);
        if (!supports.positions().equals(allParts)) throw new IllegalArgumentException("Incomplete regrowth support graph");
        if (!supports.hasSupportPath(center, expected.base())) return rejected();
        for (var entry : supports.requiredSupports().entrySet()) {
            if (added.contains(entry.getKey())) {
                if (entry.getValue().isEmpty() || entry.getValue().stream().anyMatch(pos -> !pos.equals(center) && !added.contains(pos)))
                    return rejected();
            } else if (entry.getValue().stream().anyMatch(added::contains)) return rejected();
        }
        var checked = PlantStructurePreflight.check(level, store, plan);
        if (!checked.ready()) return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.of(checked));
        var result = PlantStructureCommitter.applyLeafRegrowth(level, plan,
                new PlantLeafRegrowthChange(newKey, leaf, leaves, random, retainedKey));
        if (result.status() == PlantStructureCommitter.Status.COMMITTED) {
            var after = store.leavesOf(expected.id()).orElse(null);
            var expectedParts = new HashMap<>(owned);
            plan.changes().forEach((pos, change) -> expectedParts.put(pos, change.role()));
            if (after == null || !after.leaves().equals(afterLeaves) || !store.partsOf(expected.id()).equals(expectedParts)
                    || after.timers().regrowth().isPresent() != (afterLeaves.freeLeafSlots() > 0)
                    || !store.find(expected.id()).filter(expected::equals).isPresent()) {
                store.find(expected.id()).ifPresent(current -> {
                    var reasons = new HashSet<>(current.environmentReasons());
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
