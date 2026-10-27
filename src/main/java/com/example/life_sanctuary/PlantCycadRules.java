package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** 苏铁物种显式接入；产种上下限与供粉资格不填默认值。 */
public final class PlantCycadRules {
    private PlantCycadRules() {}
    public record Profile(PlantNaturalPollination.SeedYield yield, Predicate<PlantIndividualState> eligibleDonor) {
        public Profile { Objects.requireNonNull(yield); Objects.requireNonNull(eligibleDonor); }
    }
    private static final Map<ResourceLocation, Profile> PROFILES = new ConcurrentHashMap<>();
    public static void register(ResourceLocation species, Profile profile) {
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        if (definition.population().isEmpty() || definition.population().get().horizontalSize() != 32)
            throw new IllegalArgumentException("Cycads require a 32x32 population with unchanged individual limit");
        if (PROFILES.putIfAbsent(species, Objects.requireNonNull(profile)) != null) throw new IllegalArgumentException("Duplicate cycad rules");
    }
    static Profile find(ResourceLocation species) { return PROFILES.get(species); }
}
