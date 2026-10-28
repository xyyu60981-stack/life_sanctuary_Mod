package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Shared-block sowing for homosporous groups, preserving the species' own ordinary growth timer. */
public final class HomosporousGametophyteBirths {
    private HomosporousGametophyteBirths() {}
    @FunctionalInterface public interface Planner {
        /** Read-only legality checks and fresh identity/shape. Check chunks before reading the world. */
        Optional<PlantStructurePlan> prepare(ServerLevel level, BlockPos clicked);
    }

    /** Register traits, the gametophyte stage and the real species growth handler first. */
    public static void register(ResourceLocation species, String stage, Planner planner) {
        java.util.Objects.requireNonNull(planner);
        if (!GametophyteIdentity.isRegisteredStage(species, stage)
                || SharedGametophyteBlocks.find(species, SporeBagContents.Form.NORMAL).isEmpty()
                || PlantGrowthHandlers.find(species) == null)
            throw new IllegalArgumentException("Missing shared gametophyte stage, material or growth handler");
        ReproductiveBagEffects.registerSpore(species.toString(), SporeBagContents.Form.NORMAL, (level, clicked, label) -> {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
            if (!PlantSporeTraits.species(label).filter(species::equals).isPresent()) return ReproductiveBagUseResult.rejected();
            PlantStructurePlan plan;
            try {
                plan = java.util.Objects.requireNonNull(planner.prepare(level, clicked)).orElse(null);
            } catch (RuntimeException failure) {
                LifeSanctuaryMod.LOGGER.error("Homosporous sowing planner failed for " + species, failure);
                return ReproductiveBagUseResult.rejected();
            }
            if (plan == null || !plan.expectedIndividual().species().equals(species)
                    || !plan.expectedIndividual().stage().equals(stage)) return ReproductiveBagUseResult.rejected();
            return place(level, plan);
        });
    }

    static boolean valid(PlantStructurePlan plan) {
        return valid(plan, false);
    }
    static boolean valid(PlantStructurePlan plan, boolean cultivated) {
        var child = plan.expectedIndividual();
        return validIndividual(child, cultivated) && HeterosporeBirths.newStructure(plan)
                && SharedGametophyteBlocks.find(child.species(), SporeBagContents.Form.NORMAL).isPresent()
                && SharedGametophyteBlocks.permits(child.species(), SporeBagContents.Form.NORMAL,
                        plan.changes().get(child.base()).replacement());
    }
    static boolean validIndividual(PlantIndividualState child, boolean cultivated) {
        var definition = PlantSpeciesRegistry.find(child.species()).orElse(null);
        if (definition == null || !GametophyteIdentity.isRegisteredStage(child.species(), child.stage())
                || child.source() != (cultivated ? PlantSource.ONE_TIME : PlantSource.PLAYER)
                || child.origin() != (cultivated ? PlantOrigin.ONE_TIME_CULTIVATION : PlantOrigin.PLAYER_CULTIVATED)
                || child.pruned() || child.elapsedTicks() != 0 || !child.environmentReasons().isEmpty()
                || !child.harvests().isEmpty() || !child.reproduction().isEmpty() || child.runnerBudget().isPresent()
                || !HeterosporePopulation.matches(child)) return false;
        var stage = definition.stages().get(child.stage());
        var transition = stage == null ? null : stage.transition().orElse(null);
        return transition != null && !stage.fullyAdult()
                && child.targetTicks() >= transition.minimumTicks() && child.targetTicks() <= transition.maximumTicks();
    }

    public static ReproductiveBagUseResult place(ServerLevel level, PlantStructurePlan plan) {
        return placeInternal(level, plan, false);
    }
    /** Trusted cultivated-item adapter: completed phase retained, next stage timer starts at zero. */
    public static ReproductiveBagUseResult placeCultivated(ServerLevel level, PlantStructurePlan plan) {
        return placeInternal(level, plan, true);
    }
    private static ReproductiveBagUseResult placeInternal(ServerLevel level, PlantStructurePlan plan, boolean cultivated) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (!valid(plan, cultivated) || PlantGrowthHandlers.find(plan.expectedIndividual().species()) == null)
            return ReproductiveBagUseResult.rejected();
        var child = plan.expectedIndividual();
        var store = PlantIndividuals.get(level);
        var biology = HeterosporeWorldData.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || biology.isReadOnly()
                || store.find(child.id()).isPresent() || store.atBase(child.base()).isPresent()
                || store.atPart(child.base()).isPresent() || biology.containsIdentity(child.id())
                || biology.containsBase(child.base()) || HorsetailPopulations.get(level).member(child.base()) != null)
            return ReproductiveBagUseResult.rejected();
        boolean created = false;
        try {
            store.create(child);
            created = true;
            var result = PlantStructureCommitter.apply(level, store, plan, child);
            if (result.status() == PlantStructureCommitter.Status.REJECTED
                    || result.status() == PlantStructureCommitter.Status.ROLLED_BACK) {
                if (discard(store, child)) return ReproductiveBagUseResult.rejected();
                return recovery(level, child);
            }
            if (result.status() != PlantStructureCommitter.Status.COMMITTED) return recovery(level, child);
            var expectedParts = new java.util.LinkedHashMap<BlockPos, String>();
            plan.changes().forEach((pos, change) -> expectedParts.put(pos, change.role()));
            if (!store.find(child.id()).filter(child::equals).isPresent()
                    || !store.partsOf(child.id()).equals(expectedParts)) return recovery(level, child);
            PlantWaterMonitoring.get(level).register(level, child.base(), child.source(), child.population());
            return ReproductiveBagUseResult.consumed(false);
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Homosporous gametophyte birth failed for " + child.id(), failure);
            if (!created && store.find(child.id()).isEmpty()) return ReproductiveBagUseResult.rejected();
            return recovery(level, child);
        }
    }
    private static boolean discard(PlantIndividuals store, PlantIndividualState child) {
        try {
            return store.partsOf(child.id()).isEmpty() && store.find(child.id()).filter(child::equals).isPresent()
                    && store.remove(child.id(), child.base());
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot discard uncommitted homosporous birth for " + child.id(), failure);
            return false;
        }
    }
    private static ReproductiveBagUseResult recovery(ServerLevel level, PlantIndividualState child) {
        try {
            PlantLifecycle.setPauseReason(PlantIndividuals.get(level), child.id(), "gametophyte_birth_error", true);
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot mark failed homosporous birth for " + child.id(), failure);
        }
        LifeSanctuaryMod.LOGGER.error("Homosporous gametophyte needs recovery at {}; bag not consumed", child.base());
        return ReproductiveBagUseResult.recovery();
    }
}
