package com.example.life_sanctuary;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Shared display labels; never change the stored species ID or filter identity to translate it. */
public final class PlantSpeciesNames {
    private PlantSpeciesNames() {}
    public static Component name(ResourceLocation species) {
        return Component.translatableWithFallback("species." + species.getNamespace() + "." + species.getPath(), species.toString());
    }
    public static Component bag(String label, Component baseName) {
        if (label == null || label.isBlank() || label.length() > 128) return baseName;
        var species = PlantSporeTraits.species(label).orElse(null);
        return species == null ? baseName : Component.translatable("item.life_sanctuary.reproductive_bag.named", name(species), baseName);
    }
}
