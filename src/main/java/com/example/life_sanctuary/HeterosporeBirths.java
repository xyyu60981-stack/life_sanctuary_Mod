package com.example.life_sanctuary;

import java.util.EnumMap;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Manual spore sowing: a fresh identity and frozen timeline precede the shared world transaction. */
public final class HeterosporeBirths {
    private HeterosporeBirths() {}
    public enum Result { PLANTED, REJECTED, RECOVERY_REQUIRED }
    @FunctionalInterface public interface Planner {
        /** Pure species checks and fresh zero-timer identity/shape; check chunk availability before world reads. */
        Optional<PlantStructurePlan> prepare(ServerLevel level, BlockPos clicked, HeterosporeGametophyteRules.Kind kind);
    }
    /** Setup-only. No invented species, blocks, legal substrate or world-generation defaults. */
    public static void register(ResourceLocation species, Planner planner) {
        java.util.Objects.requireNonNull(planner);
        var traits = PlantSporeTraits.find(species);
        if (!traits.differentiatedSpores() || HeterosporeAdapters.find(species) == null)
            throw new IllegalArgumentException("Register traits and terminal adapter before spore sowing");
        var effects = new EnumMap<SporeBagContents.Form, SporeBagEffect>(SporeBagContents.Form.class);
        for (var form : SporeBagContents.Form.values()) {
            if (form == SporeBagContents.Form.NORMAL || !SporeBagContents.allowed(traits, form)) continue;
            var kind = HeterosporeGametophyteRules.kind(form);
            effects.put(form, (level, target, label) -> {
                if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
                if (!PlantSporeTraits.species(label).filter(species::equals).isPresent()) return ReproductiveBagUseResult.rejected();
                Optional<PlantStructurePlan> plan;
                try {
                    plan = java.util.Objects.requireNonNull(planner.prepare(level, target, kind));
                } catch (RuntimeException failure) {
                    LifeSanctuaryMod.LOGGER.error("Heterospore sowing plan failed for {}", species, failure);
                    return ReproductiveBagUseResult.rejected();
                }
                if (plan.isEmpty() || !plan.get().expectedIndividual().species().equals(species)) return ReproductiveBagUseResult.rejected();
                return switch (place(level, plan.get(), kind)) {
                    case PLANTED -> ReproductiveBagUseResult.consumed(false);
                    case REJECTED -> ReproductiveBagUseResult.rejected();
                    case RECOVERY_REQUIRED -> ReproductiveBagUseResult.recovery();
                };
            });
        }
        ReproductiveBagEffects.registerSporeForms(species, effects);
    }
    /** Manual planting does not spend a parent budget or apply automatic-spread population caps. */
    public static Result place(ServerLevel level, PlantStructurePlan plan, HeterosporeGametophyteRules.Kind kind) {
        return placeInternal(level, plan, kind, null);
    }
    /** Trusted server output adapter supplies an explicit natural timeline; never infer it from cabinet speed. */
    public static Result placeCultivated(ServerLevel level, PlantStructurePlan plan, HeterosporeCycle timeline) {
        java.util.Objects.requireNonNull(timeline);
        if (!validCultivated(plan.expectedIndividual(), timeline)) return Result.REJECTED;
        return placeInternal(level, plan, timeline.kind(), timeline);
    }
    private static Result placeInternal(ServerLevel level, PlantStructurePlan plan, HeterosporeGametophyteRules.Kind kind,
            HeterosporeCycle cultivated) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var child = plan.expectedIndividual();
        if (!(cultivated == null ? validIndividual(child, kind) : validCultivated(child, cultivated)) || !newStructure(plan)) return Result.REJECTED;
        var form = switch (kind) {
            case FEMALE -> SporeBagContents.Form.MEGA;
            case MALE -> SporeBagContents.Form.MICRO;
            case MIXED -> SporeBagContents.Form.MIXED;
        };
        if (!SharedGametophyteBlocks.permits(child.species(), form, plan.changes().get(child.base()).replacement()))
            return Result.REJECTED;
        var store = PlantIndividuals.get(level); var biology = HeterosporeWorldData.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || biology.isReadOnly()
                || store.find(child.id()).isPresent() || store.atBase(child.base()).isPresent() || store.atPart(child.base()).isPresent()
                || biology.containsIdentity(child.id())) return Result.REJECTED;
        boolean registered = false, attemptedWorld = false;
        try {
            store.create(child); registered = true;
            var proof = biology.reserveBirth(level, child, kind, cultivated);
            if (proof == null) return discardUncommitted(store, child) ? Result.REJECTED : recovery(level, child);
            attemptedWorld = true;
            var commit = PlantStructureCommitter.apply(level, store, plan, child);
            if (commit.status() == PlantStructureCommitter.Status.REJECTED || commit.status() == PlantStructureCommitter.Status.ROLLED_BACK) {
                if (discardUncommitted(store, child)) return Result.REJECTED;
                return recovery(level, child);
            }
            if (commit.status() != PlantStructureCommitter.Status.COMMITTED) return recovery(level, child);
            var parts = new java.util.LinkedHashMap<BlockPos, String>();
            plan.changes().forEach((pos, change) -> parts.put(pos, change.role()));
            if (!biology.activateBirth(level, child, proof, parts)) return recovery(level, child);
            PlantWaterMonitoring.get(level).register(level, child.base(), child.source(), child.population());
            return Result.PLANTED;
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Heterospore birth failed for " + child.id(), failure);
            if (!registered && !attemptedWorld && store.find(child.id()).isEmpty()) return Result.REJECTED;
            if (registered && !attemptedWorld) {
                if (discardUncommitted(store, child)) return Result.REJECTED;
            }
            return recovery(level, child);
        }
    }
    static boolean validIndividual(PlantIndividualState child, HeterosporeGametophyteRules.Kind kind) {
        return child.source() == PlantSource.PLAYER && child.origin() == PlantOrigin.PLAYER_CULTIVATED && validCommon(child, kind);
    }
    static boolean validCultivated(PlantIndividualState child, HeterosporeCycle timeline) {
        return timeline.kind() == HeterosporeGametophyteRules.Kind.MIXED && timeline.action() != HeterosporeCycle.Action.SEEDLING
                && child.source() == PlantSource.ONE_TIME && child.origin() == PlantOrigin.ONE_TIME_CULTIVATION
                && validCommon(child, timeline.kind());
    }
    private static boolean validCommon(PlantIndividualState child, HeterosporeGametophyteRules.Kind kind) {
        var traits = PlantSporeTraits.find(child.species()); var adapter = HeterosporeAdapters.find(child.species());
        var form = switch (kind) { case FEMALE -> SporeBagContents.Form.MEGA; case MALE -> SporeBagContents.Form.MICRO; case MIXED -> SporeBagContents.Form.MIXED; };
        return traits.differentiatedSpores() && SporeBagContents.allowed(traits, form) && adapter != null && adapter.gametophyteStage().equals(child.stage())
                && !child.pruned() && HeterosporePopulation.matches(child) && child.targetTicks() == 0 && child.elapsedTicks() == 0
                && child.harvests().isEmpty() && child.reproduction().isEmpty() && child.runnerBudget().isEmpty()
                && child.environmentReasons().stream().noneMatch(reason -> reason.endsWith("_error"));
    }
    static boolean newStructure(PlantStructurePlan plan) {
        var base = plan.expectedIndividual().base();
        return plan.changes().containsKey(base) && plan.dependencies().containsKey(base.below()) && !plan.changes().containsKey(base.below())
                && plan.changes().values().stream().allMatch(change -> !change.role().isEmpty() && !change.policy().requiresOwnership());
    }
    private static boolean discardUncommitted(PlantIndividuals store, PlantIndividualState child) {
        try {
            return store.remove(child.id(), child.base());
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot discard uncommitted spore birth for {}", child.id(), failure);
            return false;
        }
    }
    private static Result recovery(ServerLevel level, PlantIndividualState expected) {
        LifeSanctuaryMod.LOGGER.warn("Spore birth requires recovery for {}; not accepting or consuming the bag", expected.id());
        try {
            HeterosporeWorldData.get(level).freezeForRecovery(expected);
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot freeze failed spore birth for {}", expected.id(), failure);
        }
        try {
            var store = PlantIndividuals.get(level);
            store.find(expected.id()).filter(state -> state.base().equals(expected.base()) && state.species().equals(expected.species())).ifPresent(state -> {
                var reasons = new java.util.HashSet<>(state.environmentReasons()); reasons.add("spore_birth_error");
                store.replace(state, state.withEnvironmentReasons(reasons));
            });
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot mark failed spore birth for {}", expected.id(), failure);
        }
        return Result.RECOVERY_REQUIRED;
    }
}
