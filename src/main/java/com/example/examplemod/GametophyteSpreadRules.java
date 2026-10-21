package com.example.examplemod;

/** Shared limits for rain-triggered reproductive-structure spread. */
public final class GametophyteSpreadRules {
    public static final int RAIN_CHANCE_PERCENT = 50;
    public static final int MIN_OFFSPRING = 1;
    public static final int MAX_OFFSPRING = 2;
    public static final int HORIZONTAL_RADIUS = 5;
    public static final int VERTICAL_RADIUS = 1;
    public static final int POPULATION_LIMIT = 10;
    public static final int FAILED_RETRY_THRESHOLD = 3;

    private GametophyteSpreadRules() {}
}
