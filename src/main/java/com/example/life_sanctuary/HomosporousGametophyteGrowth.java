package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Ordinary mature gametophytes enter the shared atomic stage transition, with optional first foliage. */
public final class HomosporousGametophyteGrowth {
    private HomosporousGametophyteGrowth() {}
    public record Seedling(PlantStageStructure structure, Map<BlockPos, BlockState> observed,
            PlantStageResources resources, Optional<PlantInitialCrown> crown) {
        public Seedling {
            java.util.Objects.requireNonNull(structure);
            java.util.Objects.requireNonNull(resources);
            java.util.Objects.requireNonNull(crown);
            var snapshot = new java.util.LinkedHashMap<BlockPos, BlockState>();
            observed.forEach((pos, state) -> snapshot.put(pos.immutable(), java.util.Objects.requireNonNull(state)));
            observed = Map.copyOf(snapshot);
        }
    }
    @FunctionalInterface public interface Planner {
        /** Read-only real seedling geometry/environment. Empty means blocked; check chunks before reads. */
        Optional<Seedling> prepare(ServerLevel level, PlantIndividualState expected);
    }

    /** Compose with the species' ordinary handler, so later stages retain their original behavior. */
    public static PlantGrowthHandlers.Handler handler(ResourceLocation species, String stage,
            Planner planner, PlantGrowthHandlers.Handler otherStages) {
        java.util.Objects.requireNonNull(planner);
        java.util.Objects.requireNonNull(otherStages);
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        if (!GametophyteIdentity.isRegisteredStage(species, stage))
            throw new IllegalArgumentException("Register the homosporous gametophyte stage first");
        var next = definition.requireTransition(stage).nextStage();
        if (definition.stages().get(next).fullyAdult())
            throw new IllegalArgumentException("A gametophyte must enter a juvenile stage");
        return (level, store, expected) -> {
            if (!expected.species().equals(species)) throw new IllegalArgumentException("Wrong growth species");
            if (!expected.stage().equals(stage)) { otherStages.onReady(level, store, expected); return; }
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
            if (store != PlantIndividuals.get(level) || store.isReadOnly() || store.isApplyingStructure()
                    || !PlantLifecycle.canRunTimer(expected) || expected.remainingTicks() != 0
                    || !store.find(expected.id()).filter(expected::equals).isPresent()) return;
            var identity = GametophyteIdentity.at(level, expected.base()).orElse(null);
            if (identity == null || !identity.species().equals(species)
                    || identity.form() != SporeBagContents.Form.NORMAL
                    || !store.atPart(expected.base()).map(plant -> plant.id().equals(expected.id())).orElse(false)) {
                PlantLifecycle.setPauseReason(store, expected.id(), "gametophyte_identity_error", true);
                LifeSanctuaryMod.LOGGER.error("Cannot verify mature gametophyte identity for {}", expected.id());
                return;
            }
            try {
                var target = java.util.Objects.requireNonNull(planner.prepare(level, expected)).orElse(null);
                if (target == null) return;
                if (!target.structure().stage().equals(next)) throw new IllegalArgumentException("Wrong seedling stage");
                var result = target.crown().isPresent()
                        ? PlantStageGrowth.advanceWithDirectionalInitialCrown(level, expected, target.structure(),
                                target.observed(), target.resources(), target.crown().orElseThrow(), level.random)
                        : PlantStageGrowth.advance(level, expected, target.structure(), target.observed(), target.resources(), level.random);
                if (result.status() == PlantStructureCommitter.Status.REJECTED) return;
                if (result.status() != PlantStructureCommitter.Status.COMMITTED)
                    throw new IllegalStateException("Seedling transition needs recovery");
                var grown = store.find(expected.id()).orElseThrow();
                if (!grown.species().equals(species) || !grown.base().equals(expected.base()) || !grown.stage().equals(next))
                    throw new IllegalStateException("Seedling identity changed during callback");
                PlantWaterMonitoring.get(level).register(level, grown.base(), grown.source(), grown.population());
            } catch (RuntimeException failure) {
                LifeSanctuaryMod.LOGGER.error("Homosporous gametophyte growth failed for " + expected.id(), failure);
                try {
                    PlantLifecycle.setPauseReason(store, expected.id(), "gametophyte_growth_error", true);
                } catch (RuntimeException diagnosticFailure) {
                    LifeSanctuaryMod.LOGGER.error("Cannot pause failed gametophyte growth for " + expected.id(), diagnosticFailure);
                }
            }
        };
    }
}
