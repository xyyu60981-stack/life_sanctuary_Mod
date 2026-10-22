package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Derived per-dimension index. Rebuilt from saved individuals; never persisted as a second source of truth.
 * Counts individuals, not stems/leaves. Empty population IDs intentionally do not form a shared population.
 */
final class PlantPopulationIndex {
    private record Key(ResourceLocation species, String population) {}
    private final Map<Key, Map<PlantPopulationCounts.Group, Integer>> counts = new HashMap<>();

    void add(PlantIndividualState state) {
        if (state.population().isBlank()) return;
        var key = new Key(state.species(), state.population());
        var group = new PlantPopulationCounts.Group(state.source(), state.stage(), state.origin());
        counts.computeIfAbsent(key, ignored -> new HashMap<>()).merge(group, 1, Integer::sum);
    }

    void remove(PlantIndividualState state) {
        if (state.population().isBlank()) return;
        var key = new Key(state.species(), state.population());
        var groups = counts.get(key);
        var group = new PlantPopulationCounts.Group(state.source(), state.stage(), state.origin());
        if (groups == null || !groups.containsKey(group))
            throw new IllegalStateException("Population index out of sync");
        int remaining = groups.get(group) - 1;
        if (remaining == 0) groups.remove(group); else groups.put(group, remaining);
        if (groups.isEmpty()) counts.remove(key);
    }

    void changed(PlantIndividualState previous, PlantIndividualState next) {
        if (previous.species().equals(next.species()) && previous.population().equals(next.population())
                && previous.source() == next.source() && previous.origin() == next.origin()
                && previous.stage().equals(next.stage())) return;
        remove(previous);
        add(next);
    }

    PlantPopulationCounts snapshot(ResourceLocation species, String population) {
        return new PlantPopulationCounts(counts.getOrDefault(new Key(species, population), Map.of()));
    }
}
