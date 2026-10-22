package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.UUID;
import net.minecraft.util.RandomSource;

/**
 * Server-thread operations on the universal store. These methods change state only:
 * item consumption, particles, permissions and species-specific structural changes belong to adapters.
 */
public final class PlantLifecycle {
    public enum Result { CHANGED, UNCHANGED, REJECTED }
    private PlantLifecycle() {}

    public static boolean canRunTimer(PlantIndividualState state) {
        return state.source().canFinishCurrentCycle() && !state.pruned()
                && state.environmentReasons().isEmpty() && state.targetTicks() > 0;
    }

    /** Each environment subsystem owns its reason; clearing water cannot clear cold or callback errors. */
    public static Result setPauseReason(PlantIndividuals store, UUID id, String reason, boolean present) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Empty pause reason");
        if (store.isReadOnly()) return Result.REJECTED;
        var current = store.find(id).orElse(null);
        if (current == null || current.source().isStatic()) return Result.REJECTED;
        var reasons = new HashSet<>(current.environmentReasons());
        boolean changed = present ? reasons.add(reason) : reasons.remove(reason);
        if (!changed) return Result.UNCHANGED;
        return store.replace(current, current.withEnvironmentReasons(reasons)) ? Result.CHANGED : Result.REJECTED;
    }

    /**
     * Pruning restarts accumulated growth, preserving the already sampled target and harvested resources.
     * Future reproductive/expansion timers need their own pruning hooks before a species enables this action.
     */
    public static Result prune(PlantIndividuals store, UUID id) {
        if (store.isReadOnly()) return Result.REJECTED;
        var current = store.find(id).orElse(null);
        if (current == null || current.source().isStatic() || !current.canBePruned()) return Result.REJECTED;
        if (current.pruned()) return Result.UNCHANGED;
        return store.replace(current, current.withPruned(true).withTimer(current.targetTicks(), 0))
                ? Result.CHANGED : Result.REJECTED;
    }

    /** Removes pruning only, not climatic/water pauses; cannot wake natural static scenery. */
    public static Result restorePruned(PlantIndividuals store, UUID id) {
        if (store.isReadOnly()) return Result.REJECTED;
        var current = store.find(id).orElse(null);
        if (current == null || current.source().isStatic() || !current.canBePruned()) return Result.REJECTED;
        if (!current.pruned()) return Result.UNCHANGED;
        return store.replace(current, current.withPruned(false)) ? Result.CHANGED : Result.REJECTED;
    }

    /**
     * Finish exactly the transition declared for the current stage.
     * Call only after the species adapter validates/applies the corresponding world structure.
     * A stale snapshot cannot draw a fresh timer or advance twice.
     */
    public static Result finishStage(PlantIndividuals store, PlantIndividualState expected, RandomSource random) {
        if (store.isReadOnly() || !canRunTimer(expected) || expected.remainingTicks() != 0
                || !expected.equals(store.find(expected.id()).orElse(null))) return Result.REJECTED;
        var definition = PlantSpeciesRegistry.find(expected.species()).orElse(null);
        if (definition == null) return Result.REJECTED;
        var currentStage = definition.stages().get(expected.stage());
        if (currentStage == null || currentStage.transition().isEmpty()) return Result.REJECTED;
        String nextStage = currentStage.transition().get().nextStage();
        var next = definition.stages().get(nextStage);
        long duration = 0;
        if (next.transition().isPresent()) {
            var transition = next.transition().get();
            duration = transition.minimumTicks()
                    + random.nextInt(transition.maximumTicks() - transition.minimumTicks() + 1);
        }
        return store.replace(expected, expected.beginStage(nextStage, duration))
                ? Result.CHANGED : Result.REJECTED;
    }
}
