package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Shared geometry selection; species identity stays on the individual/item, never in this enum. */
public enum GametophyteAppearance {
    SPHENOPHYTE, FERN, HOMOSPOROUS_LYCOPOD,
    FEMALE_LYCOPOD, MALE_LYCOPOD, MIXED_LYCOPOD;

    public static Optional<GametophyteAppearance> forSpecies(ResourceLocation species, SporeBagContents.Form form) {
        var traits = PlantSporeTraits.find(species);
        if (!SporeBagContents.allowed(traits, form)) return Optional.empty();
        return switch (traits.group()) {
            case SPHENOPHYTE -> Optional.of(SPHENOPHYTE);
            case FERN -> Optional.of(FERN);
            case LYCOPOD -> Optional.of(HOMOSPOROUS_LYCOPOD);
            case TREE_LYCOPOD -> switch (form) {
                case MEGA -> Optional.of(FEMALE_LYCOPOD);
                case MICRO -> Optional.of(MALE_LYCOPOD);
                case MIXED -> Optional.of(MIXED_LYCOPOD);
                default -> Optional.empty();
            };
            default -> Optional.empty();
        };
    }
}
