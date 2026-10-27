package com.example.life_sanctuary;

import java.util.Set;
import java.util.UUID;

/** Species-selected counted stages. This governs automatic offspring, never manual placement. */
public record PlantSpreadAdmission(Set<String> countedStages, boolean includeCultivated) {
    public PlantSpreadAdmission(Set<String> countedStages) { this(countedStages, false); }
    public enum Result { REGISTERED, FULL, NO_RULE, INVALID, PARENT_PAUSED, STALE_PARENT,
        NO_REPRODUCTION_BUDGET, POSITION_OCCUPIED, READ_ONLY }
    public PlantSpreadAdmission {
        countedStages = Set.copyOf(countedStages);
        if (countedStages.isEmpty() || countedStages.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Counted stages must be explicit");
    }

    public boolean counts(PlantOrigin origin, String stage) {
        return (includeCultivated || origin != PlantOrigin.PLAYER_CULTIVATED) && countedStages.contains(stage);
    }

    public int occupied(PlantPopulationCounts population) {
        return population.groups().entrySet().stream()
                .filter(entry -> counts(entry.getKey().origin(), entry.getKey().stage()))
                .mapToInt(java.util.Map.Entry::getValue).sum();
    }

    /**
     * Single server-thread operation: check fresh counts and register the child without intervening callbacks.
     * State-only admission, not world placement. Caller must remove the child if later placement fails.
     */
    public static Result registerOffspring(PlantIndividuals store, UUID parentId, PlantIndividualState child) {
        if (store.isReadOnly()) return Result.READ_ONLY;
        var parent = store.find(parentId).orElse(null);
        if (parent == null) return Result.INVALID;
        return registerOffspring(store, parent, child);
    }

    /** Use the snapshot against which the caller checked reproduction conditions and built the plan. */
    public static Result registerOffspring(PlantIndividuals store, PlantIndividualState parent,
            PlantIndividualState child) {
        if (store.isReadOnly()) return Result.READ_ONLY;
        if (!store.find(parent.id()).filter(parent::equals).isPresent()) return Result.STALE_PARENT;
        if (!parent.species().equals(child.species()) || parent.population().isBlank()
                || !parent.population().equals(child.population()) || child.origin() != PlantOrigin.AUTOMATIC_SPREAD
                || child.source().isStatic()) return Result.INVALID;
        if (!parent.source().canExpand() || parent.pruned() || !parent.environmentReasons().isEmpty())
            return Result.PARENT_PAUSED;
        var definition = PlantSpeciesRegistry.find(child.species()).orElse(null);
        var rule = PlantSpeciesRegistry.spreadRule(child.species()).orElse(null);
        if (definition == null || definition.population().isEmpty() || rule == null) return Result.NO_RULE;
        if (!definition.stages().containsKey(child.stage())) return Result.INVALID;
        int size = definition.population().get().horizontalSize();
        if (Math.floorDiv(parent.base().getX(), size) != Math.floorDiv(child.base().getX(), size)
                || Math.floorDiv(parent.base().getZ(), size) != Math.floorDiv(child.base().getZ(), size))
            return Result.INVALID;
        if (store.find(child.id()).isPresent() || store.atBase(child.base()).isPresent()
                || store.atPart(child.base()).isPresent()) return Result.POSITION_OCCUPIED;
        if (rule.counts(child.origin(), child.stage())
                && rule.occupied(store.populationCounts(child.species(), child.population()))
                >= definition.population().get().maximumIndividuals()) return Result.FULL;
        store.create(child);
        return Result.REGISTERED;
    }
}
