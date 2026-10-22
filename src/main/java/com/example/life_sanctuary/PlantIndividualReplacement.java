package com.example.life_sanctuary;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/** Immutable state-only replacement proposal; world cutting/placement is handled separately. */
public record PlantIndividualReplacement(List<PlantIndividualState> expected,
        List<PlantIndividualState> replacements, Map<UUID, Map<net.minecraft.core.BlockPos, String>> structures,
        Map<String, Integer> retiredHarvests, Map<String, Integer> retiredReproduction) {
    public PlantIndividualReplacement(List<PlantIndividualState> expected,
            List<PlantIndividualState> replacements, Map<UUID, Map<net.minecraft.core.BlockPos, String>> structures,
            Map<String, Integer> retiredHarvests) {
        this(expected, replacements, structures, retiredHarvests, Map.of());
    }
    public PlantIndividualReplacement {
        expected = List.copyOf(expected);
        replacements = List.copyOf(replacements);
        retiredHarvests = Map.copyOf(retiredHarvests);
        retiredReproduction = Map.copyOf(retiredReproduction);
        Map<UUID, Map<net.minecraft.core.BlockPos, String>> copied = new HashMap<>();
        structures.forEach((id, parts) -> {
            Map<net.minecraft.core.BlockPos, String> positions = new HashMap<>();
            parts.forEach((pos, role) -> {
                if (role == null || role.isBlank()) throw new IllegalArgumentException("Empty structure role");
                positions.put(pos.immutable(), role);
            });
            copied.put(id, Map.copyOf(positions));
        });
        structures = Map.copyOf(copied);
        if (expected.isEmpty()) throw new IllegalArgumentException("No original individuals");
        if (expected.size() > 1 && replacements.size() > 1)
            throw new IllegalArgumentException("Many-to-many replacement needs a separate policy");
    }
}
