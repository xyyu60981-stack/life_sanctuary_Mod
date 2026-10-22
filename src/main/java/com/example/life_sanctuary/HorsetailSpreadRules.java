package com.example.life_sanctuary;

/** Timing and limits for player-grown horsetail spread. */
public final class HorsetailSpreadRules {
    /** One in-game day is 24,000 ticks; these are the provisional first-pass growth windows. */
    public static final int GAMETOPHYTE_TO_SPROUTS_MIN_TICKS =
            PlantSpeciesRegistry.HORSETAIL.requireTransition("GAMETOPHYTE").minimumTicks();
    public static final int GAMETOPHYTE_TO_SPROUTS_MAX_TICKS =
            PlantSpeciesRegistry.HORSETAIL.requireTransition("GAMETOPHYTE").maximumTicks();
    public static final int SPROUTS_TO_ADULT_TICKS =
            PlantSpeciesRegistry.HORSETAIL.requireTransition("SPROUTS").minimumTicks();
    /** A harvested spore structure is a one-shot resource and must never be regenerated. */
    public static final boolean HARVESTED_SPORE_STEM_REGENERATES = false;
    public static final int IMMEDIATE_MIN_OFFSPRING = 2;
    public static final int IMMEDIATE_MAX_OFFSPRING = 3;
    public static final int DELAY_MIN_TICKS = 100;
    public static final int DELAY_MAX_TICKS = 200;
    public static final int MAX_OFFSPRING_PER_PARENT_MIN = 2;
    public static final int MAX_OFFSPRING_PER_PARENT_MAX = 3;
    public static final int MAX_RETRY_DELAY_TICKS = 2400;

    private HorsetailSpreadRules() {}
}
