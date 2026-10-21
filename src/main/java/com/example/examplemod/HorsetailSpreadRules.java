package com.example.examplemod;

/** Timing and limits for player-grown horsetail spread. */
public final class HorsetailSpreadRules {
    public static final int IMMEDIATE_MIN_OFFSPRING = 2;
    public static final int IMMEDIATE_MAX_OFFSPRING = 3;
    public static final int DELAY_MIN_TICKS = 100;
    public static final int DELAY_MAX_TICKS = 200;
    public static final int MAX_OFFSPRING_PER_PARENT_MIN = 2;
    public static final int MAX_OFFSPRING_PER_PARENT_MAX = 3;
    public static final int MAX_RETRY_DELAY_TICKS = 2400;

    private HorsetailSpreadRules() {}
}
