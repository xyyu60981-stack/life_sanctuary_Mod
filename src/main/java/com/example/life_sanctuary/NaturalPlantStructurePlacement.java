package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;

/**
 * One-shot static structure placement. The caller selects biome-specific stage/height once
 * and checks ecological conditions before calling. No height fallback or retry is scheduled.
 * Runtime loaded-chunk adapter only; this is not a WorldGenRegion generation feature.
 */
public final class NaturalPlantStructurePlacement {
    private NaturalPlantStructurePlacement() {}

    /** 一次性规划完整结构及首冠；不保存跨 tick 的半成品提案。 */
    public record CrownPlan(PlantStructurePlan structure, PlantInitialCrown crown) {
        public CrownPlan {
            java.util.Objects.requireNonNull(structure);
            java.util.Objects.requireNonNull(crown);
        }
    }

    /**
     * Claims before selecting a plan, so failed ecological checks and random selection also run once.
     * Empty supplier result means ecological rejection; empty return means no placement was attempted.
     * Invoke only when all planning chunks are available; chunk readiness is the caller's responsibility.
     */
    public static java.util.Optional<PlantStructureCommitter.Result> placeOnce(ServerLevel level,
            net.minecraft.resources.ResourceLocation rule, net.minecraft.core.BlockPos candidate,
            java.util.function.Supplier<java.util.Optional<PlantStructurePlan>> planner) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        java.util.Objects.requireNonNull(planner, "planner");
        if (PlantIndividuals.get(level).isApplyingStructure()) return java.util.Optional.empty();
        if (PlantIndividuals.get(level).isReadOnly()) return java.util.Optional.of(
                rejected(PlantStructurePreflight.Reason.READ_ONLY));
        var ledger = NaturalPlantGenerationAttempts.get(level);
        if (ledger.isReadOnly()) return java.util.Optional.of(rejected(PlantStructurePreflight.Reason.READ_ONLY));
        if (!ledger.claim(rule, candidate)) return java.util.Optional.empty();
        return planner.get().map(plan -> placeSelected(level, plan));
    }

    /** 自然静止叶冠与整株结构一起登记；与无叶版本共用尝试账本，不另开重试机会。 */
    public static java.util.Optional<PlantStructureCommitter.Result> placeWithInitialCrownOnce(ServerLevel level,
            net.minecraft.resources.ResourceLocation rule, net.minecraft.core.BlockPos candidate,
            java.util.function.Supplier<java.util.Optional<CrownPlan>> planner) {
        return placeCrownOnce(level, rule, candidate, planner, false);
    }

    /** 逐方向筛选全部候选叶片；三方向及以上受阻则整株拒绝，仍消耗本次自然生成尝试。 */
    public static java.util.Optional<PlantStructureCommitter.Result> placeWithDirectionalInitialCrownOnce(ServerLevel level,
            net.minecraft.resources.ResourceLocation rule, net.minecraft.core.BlockPos candidate,
            java.util.function.Supplier<java.util.Optional<CrownPlan>> planner) {
        return placeCrownOnce(level, rule, candidate, planner, true);
    }

    private static java.util.Optional<PlantStructureCommitter.Result> placeCrownOnce(ServerLevel level,
            net.minecraft.resources.ResourceLocation rule, net.minecraft.core.BlockPos candidate,
            java.util.function.Supplier<java.util.Optional<CrownPlan>> planner, boolean directional) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        java.util.Objects.requireNonNull(planner, "planner");
        var store = PlantIndividuals.get(level);
        if (store.isApplyingStructure()) return java.util.Optional.empty();
        if (store.isReadOnly()) return java.util.Optional.of(rejected(PlantStructurePreflight.Reason.READ_ONLY));
        var ledger = NaturalPlantGenerationAttempts.get(level);
        if (ledger.isReadOnly()) return java.util.Optional.of(rejected(PlantStructurePreflight.Reason.READ_ONLY));
        if (!ledger.claim(rule, candidate)) return java.util.Optional.empty();
        return planner.get().map(selected -> placeSelected(level, selected.structure(), selected.crown(), directional));
    }

    private static PlantStructureCommitter.Result placeSelected(ServerLevel level, PlantStructurePlan plan) {
        return placeSelected(level, plan, null);
    }

    private static PlantStructureCommitter.Result placeSelected(ServerLevel level, PlantStructurePlan plan,
            PlantInitialCrown crown) {
        return placeSelected(level, plan, crown, false);
    }

    private static PlantStructureCommitter.Result placeSelected(ServerLevel level, PlantStructurePlan plan,
            PlantInitialCrown crown, boolean directional) {
        var state = plan.expectedIndividual();
        if (state.source() != PlantSource.STATIC || state.origin() != PlantOrigin.NATURAL
                || state.targetTicks() != 0 || state.elapsedTicks() != 0)
            throw new IllegalArgumentException("Natural structures must be static with no growth timer");
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly()) return rejected(PlantStructurePreflight.Reason.READ_ONLY);
        if (store.find(state.id()).isPresent() || store.atBase(state.base()).isPresent()
                || store.atPart(state.base()).isPresent())
            return rejected(PlantStructurePreflight.Reason.OTHER_INDIVIDUAL);
        var definition = PlantSpeciesRegistry.find(state.species()).orElse(null);
        if (definition == null || !definition.stages().containsKey(state.stage()))
            throw new IllegalArgumentException("Unknown natural species or stage");
        if (crown != null && (!PlantHarvestEligibility.permitsInitialLeaves(crown.leaves(), state.species(), state.stage())
                || !crown.valid(state, java.util.Map.of(), plan)))
            return rejected(PlantStructurePreflight.Reason.NOT_OWNED);
        // Temporarily register the identity for the common ownership preflight. STATIC never queues growth.
        store.create(state);
        try {
            return directional ? placeDirectionalRegistered(level, plan, crown) : commitRegistered(level, plan, crown);
        } catch (RuntimeException exception) {
            // apply normally handles world-write failures itself. Preserve any attached structure on error.
            if (store.partsOf(state.id()).isEmpty()) store.remove(state.id(), state.base());
            throw exception;
        }
    }

    private static PlantStructureCommitter.Result placeDirectionalRegistered(ServerLevel level,
            PlantStructurePlan plan, PlantInitialCrown crown) {
        var state = plan.expectedIndividual();
        var store = PlantIndividuals.get(level);
        var observed = new java.util.HashMap<>(plan.dependencies());
        var parts = new java.util.HashMap<net.minecraft.core.BlockPos, PlantStageStructure.Part>();
        for (var entry : plan.changes().entrySet()) {
            var change = entry.getValue();
            if (change.role().isEmpty() || change.policy().requiresOwnership())
                throw new IllegalArgumentException("Natural crown requires entirely new structure positions");
            observed.put(entry.getKey(), change.expected());
            parts.put(entry.getKey(), new PlantStageStructure.Part(change.replacement(), change.role(), change.policy()));
        }
        var result = PlantCrownSpace.select(level, state, new PlantStageStructure(state.stage(), parts), crown, observed);
        if (result.selection().isEmpty()) {
            store.remove(state.id(), state.base());
            return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, result.failure());
        }
        var selected = result.selection().get();
        return commitRegistered(level, selected.structure().plan(state, java.util.Map.of(), observed), selected.crown());
    }

    /** 身份仅在外层登记一次；方向筛选与最终提交共用此身份。 */
    private static PlantStructureCommitter.Result commitRegistered(ServerLevel level, PlantStructurePlan plan,
            PlantInitialCrown crown) {
        var state = plan.expectedIndividual();
        var store = PlantIndividuals.get(level);
        var result = crown == null ? PlantStructureCommitter.apply(level, store, plan, state)
                : PlantStructureCommitter.applyLeafInitialization(level, plan,
                    new PlantLeafInitializationChange(crown.leaves(), level.random));
        if (result.status() == PlantStructureCommitter.Status.REJECTED
                || result.status() == PlantStructureCommitter.Status.ROLLED_BACK)
            store.remove(state.id(), state.base());
        if (crown != null && result.status() == PlantStructureCommitter.Status.COMMITTED) {
            var expectedParts = new java.util.HashMap<net.minecraft.core.BlockPos, String>();
            plan.changes().forEach((pos, change) -> {
                if (!change.role().isEmpty()) expectedParts.put(pos, change.role());
            });
            if (!store.find(state.id()).filter(state::equals).isPresent()
                    || !store.partsOf(state.id()).equals(expectedParts)
                    || !store.leavesOf(state.id()).filter(data ->
                        PlantLeafInitialization.matchesInitial(data, crown.leaves(), state)).isPresent()) {
                store.find(state.id()).filter(current -> current.species().equals(state.species()) && current.base().equals(state.base()))
                        .ifPresent(current -> {
                    var reasons = new java.util.HashSet<>(current.environmentReasons());
                    reasons.add("structure_commit_error");
                    store.replace(current, current.withEnvironmentReasons(reasons));
                });
                return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.RECOVERY_REQUIRED,
                        java.util.Optional.empty());
            }
        }
        // Incomplete recovery keeps its identity/error record for diagnosis, never retries.
        return result;
    }

    private static PlantStructureCommitter.Result rejected(PlantStructurePreflight.Reason reason) {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED,
                java.util.Optional.of(new PlantStructurePreflight.Result(reason, java.util.Optional.empty())));
    }
}
