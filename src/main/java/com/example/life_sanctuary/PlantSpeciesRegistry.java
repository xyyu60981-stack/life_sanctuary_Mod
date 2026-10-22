package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** Shared metadata lookup for growth, machines and future guidebook integrations. */
public final class PlantSpeciesRegistry {
    private static final Map<ResourceLocation, PlantSpeciesDefinition> DEFINITIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, PlantSpreadAdmission> SPREAD_RULES = new ConcurrentHashMap<>();

    public static final PlantSpeciesDefinition HORSETAIL = register(new PlantSpeciesDefinition(
            ResourceLocation.fromNamespaceAndPath("life_sanctuary", "equisetum_arvense"),
            Map.of(
                    "GAMETOPHYTE", PlantSpeciesDefinition.StageDefinition.growing("SPROUTS", 24_000, 72_000),
                    "SPROUTS", PlantSpeciesDefinition.StageDefinition.growing("ADULT", 24_000, 24_000),
                    "ADULT", PlantSpeciesDefinition.StageDefinition.terminal()),
            Optional.of(new PlantSpeciesDefinition.PopulationRules(16, 15))));

    private PlantSpeciesRegistry() {}

    /** Call during setup, never from plant ticks. Duplicate IDs are configuration errors. */
    public static PlantSpeciesDefinition register(PlantSpeciesDefinition definition) {
        if (DEFINITIONS.putIfAbsent(definition.id(), definition) != null)
            throw new IllegalArgumentException("Duplicate species definition: " + definition.id());
        return definition;
    }

    public static Optional<PlantSpeciesDefinition> find(ResourceLocation id) {
        return Optional.ofNullable(DEFINITIONS.get(id));
    }

    /** Setup only; missing rules disable automatic admission instead of guessing counted stages. */
    public static void registerSpreadRule(ResourceLocation species, PlantSpreadAdmission rule) {
        var definition = DEFINITIONS.get(species);
        if (definition == null || definition.population().isEmpty()
                || !definition.stages().keySet().containsAll(rule.countedStages()))
            throw new IllegalArgumentException("Spread rule requires a defined population and known stages");
        if (SPREAD_RULES.putIfAbsent(species, rule) != null)
            throw new IllegalArgumentException("Duplicate spread rule: " + species);
    }

    public static Optional<PlantSpreadAdmission> spreadRule(ResourceLocation species) {
        return Optional.ofNullable(SPREAD_RULES.get(species));
    }

    /** Immutable snapshot; callers cannot change the registry through this view. */
    public static Map<ResourceLocation, PlantSpeciesDefinition> snapshot() {
        return Map.copyOf(DEFINITIONS);
    }

    /** Preserves existing unnamespaced bag/save IDs for this mod. */
    public static String legacyId(PlantSpeciesDefinition definition) {
        return definition.id().getNamespace().equals("life_sanctuary")
                ? definition.id().getPath() : definition.id().toString();
    }
}
