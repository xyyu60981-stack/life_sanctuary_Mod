package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 到期采空叶缺裙则转换，有裙则消失；方向映射和真实裙形由物种提供。 */
public final class PlantLeafSkirtConversion {
    private PlantLeafSkirtConversion() {}

    /** 已有对应裙位时，不要求构造一个不会使用的新裙模板。 */
    public static PlantStructureCommitter.Result finishExisting(ServerLevel level, PlantIndividualState expected,
            PlantLeafData leaves, String key, Direction slot, PlantStructureSupports supports,
            Map<BlockPos, BlockState> observed, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var store = PlantIndividuals.get(level);
        if (!PlantLeafSkirts.enabled(expected.species()) || store.isReadOnly()) return rejected();
        var owned = store.partsOf(expected.id());
        if (!PlantLeafSkirts.occupied(owned, slot)) return rejected();
        for (var entry : owned.entrySet()) if (PlantLeafSkirts.belongs(entry.getValue(), slot)) {
            var state = observed.get(entry.getKey());
            if (state == null || state.isAir() || state.is(Blocks.WATER)) return rejected();
        }
        return PlantLeafShedding.removeWithExistingSkirt(level, expected, leaves, key, supports, observed, random);
    }

    /**
     * plan 完整覆盖本片叶的移除/替换及新裙，dependencies 包含全部旧部件。
     * 新裙角色为 leaf_skirt/<方向> 及其子路径；新裙依赖既有支撑或本裙部件。
     * oldSupports/targetSupports 分别完整描述转换前后，support 必须为保留的非叶支撑。
     */
    public static PlantStructureCommitter.Result convert(ServerLevel level, PlantStructurePlan plan,
            PlantLeafData leaves, String key, Direction slot, BlockPos support,
            PlantStructureSupports oldSupports, PlantStructureSupports targetSupports, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(random);
        String skirtRole = PlantLeafSkirts.role(slot);
        var expected = plan.expectedIndividual();
        var store = PlantIndividuals.get(level);
        if (!PlantLeafSkirts.enabled(expected.species()) || store.isReadOnly() || store.isApplyingStructure()
                || !PlantLeafScheduler.canRun(expected) || !store.find(expected.id()).filter(expected::equals).isPresent()
                || !store.leavesOf(expected.id()).filter(leaves::equals).isPresent()) return rejected();
        var owned = store.partsOf(expected.id());
        if (PlantLeafSkirts.occupied(owned, slot)) {
            Map<BlockPos, BlockState> observed = new HashMap<>(plan.dependencies());
            plan.changes().forEach((pos, change) -> observed.put(pos, change.expected()));
            return finishExisting(level, expected, leaves, key, slot, oldSupports, observed, random);
        }
        var leaf = leaves.leaves().leaves().get(key);
        var timer = leaves.timers().shedding().get(key);
        if (leaf == null || !leaf.present() || !leaf.exhausted() || timer == null || !timer.ready()) return rejected();
        if (!oldSupports.positions().equals(owned.keySet()) || !plan.dependencies().keySet().containsAll(owned.keySet()))
            throw new IllegalArgumentException("Skirt conversion requires complete old snapshots and supports");
        var removed = new HashSet<BlockPos>();
        owned.forEach((pos, role) -> {
            if (role.equals(leaf.role()) || role.startsWith(leaf.role() + "/")) removed.add(pos);
        });
        if (!removed.contains(leaf.anchor()) || removed.contains(expected.base()) || removed.contains(support)
                || !owned.containsKey(support) || !oldSupports.affectedBy(removed).equals(removed)
                || !plan.changes().keySet().containsAll(removed)) return rejected();
        for (var other : leaves.leaves().leaves().entrySet()) {
            var state = other.getValue();
            if (!state.present()) continue;
            if (!other.getKey().equals(key) && removed.contains(state.anchor())) return rejected();
            String role = state.role();
            if (skirtRole.equals(role) || skirtRole.startsWith(role + "/") || role.startsWith(skirtRole + "/")) return rejected();
            String supportRole = owned.get(support);
            if (supportRole.equals(role) || supportRole.startsWith(role + "/")) return rejected();
        }
        var skirt = new HashSet<BlockPos>();
        var expectedParts = new HashMap<>(owned);
        removed.forEach(expectedParts::remove);
        for (var entry : plan.changes().entrySet()) {
            var pos = entry.getKey();
            var change = entry.getValue();
            if (pos.equals(expected.base()) || pos.equals(support)) return rejected();
            if (change.role().isEmpty()) {
                if (!removed.contains(pos)) return rejected();
                continue;
            }
            if (!PlantLeafSkirts.belongs(change.role(), slot) || (owned.containsKey(pos) && !removed.contains(pos))) return rejected();
            if (removed.contains(pos)) {
                if (change.policy() != PlantStructurePlan.Replacement.OWNED_PART
                        && change.policy() != PlantStructurePlan.Replacement.OWNED_WATER_PART) return rejected();
            } else if (change.policy().requiresOwnership()) return rejected();
            skirt.add(pos);
            expectedParts.put(pos, change.role());
        }
        if (skirt.isEmpty() || !targetSupports.positions().equals(expectedParts.keySet())) return rejected();
        for (var entry : targetSupports.requiredSupports().entrySet()) {
            if (skirt.contains(entry.getKey())) {
                if (entry.getValue().isEmpty() || entry.getValue().stream().anyMatch(pos -> !pos.equals(support) && !skirt.contains(pos)))
                    return rejected();
            } else if (entry.getValue().stream().anyMatch(skirt::contains)) return rejected();
        }
        var preflight = PlantStructurePreflight.check(level, store, plan);
        if (!preflight.ready()) return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.of(preflight));
        var result = PlantStructureCommitter.applyLeafShedding(level, plan, new PlantLeafSheddingChange(key, leaves, random));
        if (result.status() == PlantStructureCommitter.Status.COMMITTED) {
            var after = store.leavesOf(expected.id()).orElse(null);
            var expectedLeaves = leaves.leaves().replace(key, leaf, leaf.withoutStructure()).orElseThrow();
            if (after == null || !after.leaves().equals(expectedLeaves) || after.timers().shedding().containsKey(key)
                    || after.timers().regrowth().isEmpty() || !store.partsOf(expected.id()).equals(expectedParts)
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
