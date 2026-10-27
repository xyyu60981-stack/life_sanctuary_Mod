package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable growth/structure record. Updates go through PlantIndividuals for persistence.
 * For clonal rhizomes, id is a plant record handle; RhizomeIndividualIdentity resolves the shared
 * biological individual. Stage/population counts still count every above-ground plant separately.
 */
public record PlantIndividualState(UUID id, ResourceLocation species, BlockPos base, PlantSource source,
        String stage, String population, boolean canBePruned, boolean pruned,
        long targetTicks, long elapsedTicks, Set<String> environmentReasons,
        Map<String, Integer> harvests, PlantOrigin origin, Map<String, Integer> reproduction,
        Optional<RunnerGrowthAdmission.Budget> runnerBudget) {
    public PlantIndividualState(UUID id, ResourceLocation species, BlockPos base, PlantSource source,
            String stage, String population, boolean canBePruned, boolean pruned,
            long targetTicks, long elapsedTicks, Set<String> environmentReasons,
            Map<String, Integer> harvests, PlantOrigin origin, Map<String, Integer> reproduction) {
        this(id, species, base, source, stage, population, canBePruned, pruned,
                targetTicks, elapsedTicks, environmentReasons, harvests, origin, reproduction, Optional.empty());
    }
    /** Old callers do not implicitly receive a fresh reproduction budget. */
    public PlantIndividualState(UUID id, ResourceLocation species, BlockPos base, PlantSource source,
            String stage, String population, boolean canBePruned, boolean pruned,
            long targetTicks, long elapsedTicks, Set<String> environmentReasons,
            Map<String, Integer> harvests, PlantOrigin origin) {
        this(id, species, base, source, stage, population, canBePruned, pruned,
                targetTicks, elapsedTicks, environmentReasons, harvests, origin, Map.of());
    }
    /** Legacy callers have no evidence of the establishment path. */
    public PlantIndividualState(UUID id, ResourceLocation species, BlockPos base, PlantSource source,
            String stage, String population, boolean canBePruned, boolean pruned,
            long targetTicks, long elapsedTicks, Set<String> environmentReasons, Map<String, Integer> harvests) {
        this(id, species, base, source, stage, population, canBePruned, pruned,
                targetTicks, elapsedTicks, environmentReasons, harvests, PlantOrigin.UNKNOWN);
    }

    public PlantIndividualState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(species, "species");
        base = base.immutable();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(population, "population");
        environmentReasons = Set.copyOf(environmentReasons);
        harvests = Map.copyOf(harvests);
        reproduction = Map.copyOf(reproduction);
        Objects.requireNonNull(runnerBudget, "runnerBudget");
        if (stage.isBlank() || (pruned && !canBePruned) || targetTicks < 0
                || elapsedTicks < 0 || elapsedTicks > targetTicks)
            throw new IllegalArgumentException("Invalid plant state");
        if (environmentReasons.stream().anyMatch(String::isBlank)
                || harvests.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() < 0)
                || reproduction.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() < 0))
            throw new IllegalArgumentException("Invalid environment reason or harvest quota");
    }

    /** Zero means exhausted; a missing structure key means uninitialized, never automatically replenished. */
    public long remainingTicks() { return targetTicks - elapsedTicks; }

    public PlantIndividualState withTimer(long target, long elapsed) {
        return new PlantIndividualState(id, species, base, source, stage, population,
                canBePruned, pruned, target, elapsed, environmentReasons, harvests, origin, reproduction, runnerBudget);
    }

    /** Stage transitions retain identity, source and all existing resource quotas. */
    public PlantIndividualState beginStage(String nextStage, long target) {
        return new PlantIndividualState(id, species, base, source, nextStage, population,
                canBePruned, pruned, target, 0, environmentReasons, harvests, origin, reproduction, runnerBudget);
    }

    /** Pausing/resuming preserves the elapsed snapshot supplied by the store. */
    public PlantIndividualState withEnvironmentReasons(Set<String> reasons) {
        return new PlantIndividualState(id, species, base, source, stage, population,
                canBePruned, pruned, targetTicks, elapsedTicks, reasons, harvests, origin, reproduction, runnerBudget);
    }

    public PlantIndividualState withPruned(boolean value) {
        return new PlantIndividualState(id, species, base, source, stage, population,
                canBePruned, value, targetTicks, elapsedTicks, environmentReasons, harvests, origin, reproduction, runnerBudget);
    }
    /** Capability changes preserve identity, resources and timing; removing capability clears pruning. */
    public PlantIndividualState withPruningCapability(boolean enabled) {
        return new PlantIndividualState(id, species, base, source, stage, population, enabled, enabled && pruned,
                targetTicks, elapsedTicks, environmentReasons, harvests, origin, reproduction, runnerBudget);
    }

    /** Missing means uninitialized, zero means exhausted. Neither is an unlimited budget. */
    public PlantIndividualState withReproduction(Map<String, Integer> budgets) {
        return new PlantIndividualState(id, species, base, source, stage, population,
                canBePruned, pruned, targetTicks, elapsedTicks, environmentReasons, harvests, origin, budgets, runnerBudget);
    }

    /** Explicit initialization/update only; ordinary lifecycle operations preserve the budget. */
    public PlantIndividualState withRunnerBudget(RunnerGrowthAdmission.Budget budget) {
        return new PlantIndividualState(id, species, base, source, stage, population,
                canBePruned, pruned, targetTicks, elapsedTicks, environmentReasons, harvests, origin, reproduction,
                Optional.of(budget));
    }
}
