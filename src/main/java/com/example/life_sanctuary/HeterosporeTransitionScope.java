package com.example.life_sanctuary;

import java.util.Objects;
import java.util.function.Supplier;

/** Ephemeral callback authority; never saved, handed to clients or retained across ticks. */
final class HeterosporeTransitionScope {
    private PlantIndividualState owner;
    private HeterosporeCycle.Action action;
    boolean matches(PlantIndividualState expected, HeterosporeCycle.Action requested) {
        return owner != null && owner.equals(expected) && action == requested;
    }
    HeterosporeAdapters.Result run(PlantIndividualState expected, HeterosporeCycle.Action requested,
            Supplier<HeterosporeAdapters.Result> operation) {
        if (owner != null) throw new IllegalStateException("Nested heterospore transition");
        if (requested != HeterosporeCycle.Action.SEEDLING && requested != HeterosporeCycle.Action.DISAPPEAR)
            throw new IllegalArgumentException("Not a terminal heterospore event");
        owner = Objects.requireNonNull(expected); action = requested;
        try { return Objects.requireNonNull(operation.get(), "Missing world transition result"); }
        finally { owner = null; action = null; }
    }
}
