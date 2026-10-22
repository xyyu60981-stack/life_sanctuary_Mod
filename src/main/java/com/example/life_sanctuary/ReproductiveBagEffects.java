package com.example.life_sanctuary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Central species effect registry; item interaction stays independent of species code. */
public final class ReproductiveBagEffects {
    private static final Map<String, SporeBagEffect> SPORE_EFFECTS = new ConcurrentHashMap<>();
    private static final Map<String, PollenBagEffect> POLLEN_EFFECTS = new ConcurrentHashMap<>();

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

    public static PollenBagEffect pollen(String speciesId) { return POLLEN_EFFECTS.get(speciesId); }
}
