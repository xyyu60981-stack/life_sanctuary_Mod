package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;

/**
 * One-shot static structure placement. The caller selects biome-specific stage/height once
 * and checks ecological conditions before calling. No height fallback or retry is scheduled.
 * Runtime loaded-chunk adapter only; this is not a WorldGenRegion generation feature.
 */
public final class NaturalPlantStructurePlacement {
    private NaturalPlantStructurePlacement() {}

    /**
     * Claims before selecting a plan, so failed ecological checks and random selection also run once.
     * Empty supplier result means ecological rejection; empty return means no placement was attempted.
     * Invoke only when all planning chunks are available; chunk readiness is the caller's responsibility.
     */
    public static java.util.Optional<PlantStructureCommitter.Result> placeOnce(ServerLevel level,
            net.minecraft.resources.ResourceLocation rule, net.minecraft.core.BlockPos candidate,
            java.util.function.Supplier<java.util.Optional<PlantStructurePlan>> planner) {
        java.util.Objects.requireNonNull(planner, "planner");
        if (PlantIndividuals.get(level).isReadOnly()) return java.util.Optional.of(
                rejected(PlantStructurePreflight.Reason.READ_ONLY));
        var ledger = NaturalPlantGenerationAttempts.get(level);
        if (ledger.isReadOnly()) return java.util.Optional.of(rejected(PlantStructurePreflight.Reason.READ_ONLY));
        if (!ledger.claim(rule, candidate)) return java.util.Optional.empty();
        return planner.get().map(plan -> placeSelected(level, plan));
    }

    private static PlantStructureCommitter.Result placeSelected(ServerLevel level, PlantStructurePlan plan) {
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
        // Temporarily register the identity for the common ownership preflight. STATIC never queues growth.
        store.create(state);
        try {
            var result = PlantStructureCommitter.apply(level, store, plan, state);
            if (result.status() == PlantStructureCommitter.Status.REJECTED
                    || result.status() == PlantStructureCommitter.Status.ROLLED_BACK)
                store.remove(state.id(), state.base());
            // Incomplete recovery keeps its identity/error record for diagnosis, never retries.
            return result;
        } catch (RuntimeException exception) {
            // apply normally handles world-write failures itself. Preserve any attached structure on error.
            if (store.partsOf(state.id()).isEmpty()) store.remove(state.id(), state.base());
            throw exception;
        }
    }

    private static PlantStructureCommitter.Result rejected(PlantStructurePreflight.Reason reason) {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED,
                java.util.Optional.of(new PlantStructurePreflight.Result(reason, java.util.Optional.empty())));
    }
}
