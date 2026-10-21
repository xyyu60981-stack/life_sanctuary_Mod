package com.example.life_sanctuary;

/**
 * Lifecycle stages shared by plants that use the standard spore route.
 *
 * <p>The stage is saved separately from the rendered block state so a future
 * visual variant cannot accidentally change growth or source permissions.</p>
 */
public enum PlantStage {
    GAMETOPHYTE,
    SPROUTS,
    ADULT
}
