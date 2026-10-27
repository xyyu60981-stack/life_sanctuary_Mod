package com.example.life_sanctuary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Central species effect registry; item interaction stays independent of species code. */
public final class ReproductiveBagEffects {
    private static final Map<String, SporeBagEffect> SPORE_EFFECTS = new ConcurrentHashMap<>();
    private static final Map<String, PollenBagEffect> POLLEN_EFFECTS = new ConcurrentHashMap<>();
    private record SporeKey(net.minecraft.resources.ResourceLocation species, SporeBagContents.Form form) {}
    private static final Map<SporeKey, SporeBagEffect> TYPED_SPORE_EFFECTS = new ConcurrentHashMap<>();

    static {
        registerSpore("equisetum_arvense", EquisetumSporeBagEffect.INSTANCE);
    }

    private ReproductiveBagEffects() {}

    public static void registerSpore(String speciesId, SporeBagEffect effect) {
        if (speciesId == null || speciesId.isBlank() || effect == null) {
            throw new IllegalArgumentException("Species id and effect are required");
        }
        SPORE_EFFECTS.put(speciesId, effect);
    }

    public static void registerPollen(String speciesId, PollenBagEffect effect) {
        if (speciesId == null || speciesId.isBlank() || effect == null) {
            throw new IllegalArgumentException("Species id and effect are required");
        }
        POLLEN_EFFECTS.put(speciesId, effect);
    }

    public static SporeBagEffect spore(String speciesId) { return SPORE_EFFECTS.get(speciesId); }
    public static void registerSpore(String speciesId, SporeBagContents.Form form, SporeBagEffect effect) {
        var species = PlantSporeTraits.species(speciesId).orElseThrow();
        if (!SporeBagContents.allowed(PlantSporeTraits.find(species), form) || effect == null) throw new IllegalArgumentException("Invalid typed spore effect");
        if (TYPED_SPORE_EFFECTS.putIfAbsent(new SporeKey(species, form), effect) != null) throw new IllegalArgumentException("Duplicate typed spore effect");
    }
    public static SporeBagEffect spore(String speciesId, SporeBagContents.Form form) {
        var species = PlantSporeTraits.species(speciesId).orElse(null);
        if (species == null || !SporeBagContents.allowed(PlantSporeTraits.find(species), form)) return null;
        var effect = TYPED_SPORE_EFFECTS.get(new SporeKey(species, form));
        if (effect != null || form == SporeBagContents.Form.MEGA || form == SporeBagContents.Form.MICRO) return effect;
        effect = SPORE_EFFECTS.get(speciesId);
        if (effect == null && species.getNamespace().equals(LifeSanctuaryMod.MODID)) effect = SPORE_EFFECTS.get(species.getPath());
        return effect == null ? SPORE_EFFECTS.get(species.toString()) : effect;
    }

    public static PollenBagEffect pollen(String speciesId) { return POLLEN_EFFECTS.get(speciesId); }
}
