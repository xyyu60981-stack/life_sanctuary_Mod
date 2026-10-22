package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;

/** Read-only counts for species-specific population admission policies; not an automatic growth limit. */
public record PlantPopulationCounts(Map<Group, Integer> groups) {
    public record Group(PlantSource source, String stage, PlantOrigin origin) {
        public Group {
            Objects.requireNonNull(source);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(origin);
            if (stage.isBlank()) throw new IllegalArgumentException("Empty stage");
        }
    }
    public PlantPopulationCounts {
        groups = Map.copyOf(groups);
        if (groups.values().stream().anyMatch(count -> count <= 0))
            throw new IllegalArgumentException("Invalid population count");
    }
    public int total() { return groups.values().stream().mapToInt(Integer::intValue).sum(); }
    public int count(PlantSource source, String stage) {
        return groups.entrySet().stream().filter(entry -> entry.getKey().source() == source
                && entry.getKey().stage().equals(stage)).mapToInt(Map.Entry::getValue).sum();
    }
    public int count(PlantOrigin origin) {
        return groups.entrySet().stream().filter(entry -> entry.getKey().origin() == origin)
                .mapToInt(Map.Entry::getValue).sum();
    }
    public int count(PlantSource source) {
        return groups.entrySet().stream().filter(entry -> entry.getKey().source() == source)
                .mapToInt(Map.Entry::getValue).sum();
    }
}
