package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable species metadata. This is not per-plant state and must never contain
 * mutable harvest counts or timers. Stage keys are stable saved names, not enum ordinals.
 */
public record PlantSpeciesDefinition(ResourceLocation id, Map<String, StageDefinition> stages,
        Optional<PopulationRules> population) {
    public PlantSpeciesDefinition {
        Objects.requireNonNull(id, "id");
        stages = Map.copyOf(stages);
        Objects.requireNonNull(population, "population");
        if (stages.isEmpty()) throw new IllegalArgumentException("A species needs at least one stage");
        for (var entry : stages.entrySet()) {
            if (entry.getKey().isBlank()) throw new IllegalArgumentException("Empty stage key");
            var transition = entry.getValue().transition();
            if (transition.isPresent() && !stages.containsKey(transition.get().nextStage()))
                throw new IllegalArgumentException("Unknown target stage: " + transition.get().nextStage());
        }
    }

    /** Empty transition means no automatic stage change; reproduction can still run separately. */
    public record StageDefinition(Optional<Transition> transition) {
        public StageDefinition { Objects.requireNonNull(transition, "transition"); }
        public static StageDefinition terminal() { return new StageDefinition(Optional.empty()); }
        public static StageDefinition growing(String nextStage, int minimumTicks, int maximumTicks) {
            return new StageDefinition(Optional.of(new Transition(nextStage, minimumTicks, maximumTicks)));
        }
    }

    public record Transition(String nextStage, int minimumTicks, int maximumTicks) {
        public Transition {
            Objects.requireNonNull(nextStage, "nextStage");
            if (nextStage.isBlank() || minimumTicks <= 0 || maximumTicks < minimumTicks)
                throw new IllegalArgumentException("Invalid stage transition");
        }
    }

    public record PopulationRules(int horizontalSize, int maximumIndividuals) {
        public PopulationRules {
            if ((horizontalSize != 16 && horizontalSize != 32) || maximumIndividuals <= 0)
                throw new IllegalArgumentException("Invalid population region or limit");
        }
    }

    public Transition requireTransition(String stage) {
        StageDefinition definition = stages.get(stage);
        if (definition == null) throw new IllegalArgumentException("Unknown stage: " + stage);
        return definition.transition().orElseThrow(() -> new IllegalArgumentException("Stage has no transition: " + stage));
    }
}
