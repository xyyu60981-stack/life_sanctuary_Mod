package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** The gametophyte's population is the species region containing its base, never its visible extent. */
public final class HeterosporePopulation {
    private HeterosporePopulation() {}

    public static Optional<String> at(ResourceLocation species, BlockPos base) {
        var definition = PlantSpeciesRegistry.find(species).orElse(null);
        if (definition == null || definition.population().isEmpty()) return Optional.empty();
        int size = definition.population().orElseThrow().horizontalSize();
        return Optional.of(PlantPopulationTag.fromBase(PlantSpeciesRegistry.legacyId(definition), base, size)
                .serializedName());
    }

    public static boolean matches(PlantIndividualState plant) {
        return at(plant.species(), plant.base()).filter(plant.population()::equals).isPresent();
    }
}
