package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 首次叶冠逐方向空间筛选；一个叶资源键对应一个完整方向，包含其所有子部件。 */
public final class PlantCrownSpace {
    private PlantCrownSpace() {}
    public enum Status { READY, TOO_MANY_BLOCKED, CORE_BLOCKED, UNAVAILABLE, INVALID_CROWN }
    public record Selection(PlantStageStructure structure, PlantInitialCrown crown, Set<String> skippedDirections) {
        public Selection { skippedDirections = Set.copyOf(skippedDirections); }
    }
    public record Result(Status status, Optional<Selection> selection,
            Map<String, PlantStructurePreflight.Result> blockedDirections,
            Optional<PlantStructurePreflight.Result> failure) {
        public Result { blockedDirections = Map.copyOf(blockedDirections); }
    }

    /**
     * fullCrown 必须列全本阶段候选方向，不得由适配器预先删掉受阻方向以绕过两方向上限。
     * 当前入口用于首次生叶；已有叶冠换阶段的资源继承需另行构建事务。
     * 不抽随机、不写世界、不改账本；未知区块／过期快照不计作“方向被阻挡”。
     */
    public static Result select(ServerLevel level, PlantIndividualState owner, PlantStageStructure fullStructure,
            PlantInitialCrown fullCrown, Map<BlockPos, BlockState> observed) {
        return select(level, owner, fullStructure, fullCrown, observed, false);
    }

    static Result selectAdult(ServerLevel level, PlantIndividualState owner, PlantStageStructure fullStructure,
            PlantInitialCrown fullCrown, Map<BlockPos, BlockState> observed) {
        if (!PlantHarvestEligibility.isFullyAdult(owner.species(), fullStructure.stage()))
            return result(Status.INVALID_CROWN, Map.of(), Optional.empty());
        return selectGrowing(level, owner, fullStructure, fullCrown, observed);
    }

    static Result selectGrowing(ServerLevel level, PlantIndividualState owner, PlantStageStructure fullStructure,
            PlantInitialCrown fullCrown, Map<BlockPos, BlockState> observed) {
        return select(level, owner, fullStructure, fullCrown, observed, true);
    }

    private static Result select(ServerLevel level, PlantIndividualState owner, PlantStageStructure fullStructure,
            PlantInitialCrown fullCrown, Map<BlockPos, BlockState> observed, boolean replaceCrown) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var store = PlantIndividuals.get(level);
        var previous = store.leavesOf(owner.id()).orElse(null);
        if (store.isReadOnly() || store.isApplyingStructure() || (!replaceCrown && previous != null)
                || (replaceCrown && (previous == null || !PlantGrowingCrownChange.eligible(owner, previous)
                    || !PlantGrowingCrownChange.permitsTarget(owner.species(), fullStructure.stage(), fullCrown.leaves()))))
            return result(Status.UNAVAILABLE, Map.of(), Optional.empty());
        var owned = store.partsOf(owner.id());
        var fullPlan = fullStructure.plan(owner, owned, observed);
        if (fullCrown.leaves().leaves().presentLeaves() != fullCrown.leaves().leaves().leafLimit()
                || !fullCrown.valid(owner, replaceCrown ? PlantGrowingCrownChange.nonLeafParts(previous, owned) : owned, fullPlan))
            return result(Status.INVALID_CROWN, Map.of(), Optional.empty());
        if (replaceCrown) {
            var targetRoles = new HashMap<BlockPos, String>();
            fullStructure.parts().forEach((pos, part) -> targetRoles.put(pos, part.role()));
            if (!PlantGrowingCrownChange.coversOldLeafShapes(previous, fullCrown.leaves(), targetRoles))
                return result(Status.INVALID_CROWN, Map.of(), Optional.empty());
        }
        Map<String, Set<BlockPos>> directions = new HashMap<>();
        var allLeafPositions = new HashSet<BlockPos>();
        fullCrown.leaves().leaves().leaves().forEach((key, leaf) -> {
            var positions = new HashSet<BlockPos>();
            fullStructure.parts().forEach((pos, part) -> {
                if (part.role().equals(leaf.role()) || part.role().startsWith(leaf.role() + "/")) positions.add(pos);
            });
            directions.put(key, Set.copyOf(positions));
            allLeafPositions.addAll(positions);
        });
        var core = new HashMap<>(fullPlan.changes());
        allLeafPositions.forEach(core::remove);
        // 基部必在非叶结构中，故 core 不为空；全量 observed 保证先检查所有候选区块可用性。
        var coreCheck = PlantStructurePreflight.check(level, store, new PlantStructurePlan(owner, core, observed));
        if (!coreCheck.ready()) return result(isObstruction(coreCheck) ? Status.CORE_BLOCKED : Status.UNAVAILABLE,
                Map.of(), Optional.of(coreCheck));
        Map<String, PlantStructurePreflight.Result> blocked = new HashMap<>();
        for (var key : directions.keySet().stream().sorted().toList()) {
            var changes = new HashMap<BlockPos, PlantStructurePlan.Change>();
            directions.get(key).forEach(pos -> changes.put(pos, fullPlan.changes().get(pos)));
            var checked = PlantStructurePreflight.check(level, store, new PlantStructurePlan(owner, changes, observed));
            if (checked.ready()) continue;
            if (!isObstruction(checked)) return result(Status.UNAVAILABLE, blocked, Optional.of(checked));
            blocked.put(key, checked);
        }
        if (blocked.size() > 2) return result(Status.TOO_MANY_BLOCKED, blocked, Optional.empty());
        var omitted = new HashSet<BlockPos>();
        blocked.keySet().forEach(key -> omitted.addAll(directions.get(key)));
        var parts = new HashMap<>(fullStructure.parts());
        omitted.forEach(parts::remove);
        var selectedLeaves = new HashMap<>(fullCrown.leaves().leaves().leaves());
        blocked.keySet().forEach(selectedLeaves::remove);
        if (selectedLeaves.isEmpty()) return result(Status.INVALID_CROWN, blocked, Optional.empty());
        // 本阶段可补回的总数仅为实际成功方向数；跳过方向不会被视为等待补齐的空叶位。
        var leafSet = new PlantLeafSet(selectedLeaves.size(),
                Math.min(fullCrown.leaves().leaves().fertileLimit(), selectedLeaves.size()), selectedLeaves,
                selectedLeaves.values().stream().map(PlantLeafState::role).collect(java.util.stream.Collectors.toSet()));
        var supports = fullCrown.supports().afterRemoving(omitted).orElseThrow();
        if (!supports.positions().equals(parts.keySet())) return result(Status.INVALID_CROWN, blocked, Optional.empty());
        var selected = new Selection(new PlantStageStructure(fullStructure.stage(), parts),
                new PlantInitialCrown(new PlantLeafData(leafSet, PlantLeafTimers.EMPTY), fullCrown.center(), supports), blocked.keySet());
        return new Result(Status.READY, Optional.of(selected), blocked, Optional.empty());
    }

    private static boolean isObstruction(PlantStructurePreflight.Result result) {
        return switch (result.reason()) {
            case OCCUPIED, OTHER_INDIVIDUAL, BLOCK_ENTITY, FLUID_UNSUPPORTED, NOT_OWNED -> true;
            default -> false;
        };
    }
    private static Result result(Status status, Map<String, PlantStructurePreflight.Result> blocked,
            Optional<PlantStructurePreflight.Result> failure) {
        return new Result(status, Optional.empty(), blocked, failure);
    }
}
