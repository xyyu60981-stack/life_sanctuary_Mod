package com.example.life_sanctuary;

/** 单株花期记录；failed 仅表示受粉失败，不关闭雄性供粉功能。 */
public record PlantFlowerRound(long id, boolean active, boolean failed, int cycadAttempts, long cycadElapsedTicks) {
    public static final int CYCAD_INTERVAL_TICKS = 2000;
    public static final int CYCAD_MAX_ATTEMPTS = 3;
    public PlantFlowerRound(long id, boolean active, boolean failed) { this(id, active, failed, 0, 0); }
    public PlantFlowerRound {
        if (id < 0 || cycadAttempts < 0 || cycadAttempts > CYCAD_MAX_ATTEMPTS
                || cycadElapsedTicks < 0 || cycadElapsedTicks > CYCAD_INTERVAL_TICKS
                || cycadAttempts == CYCAD_MAX_ATTEMPTS && cycadElapsedTicks != 0)
            throw new IllegalArgumentException("Invalid flower round or cycad attempt clock");
    }
    public boolean allowsPollenSupply() { return active; }
    public boolean allowsPollenReception() { return active && !failed; }
    public boolean cycadReady() { return allowsPollenReception() && cycadAttempts < CYCAD_MAX_ATTEMPTS && cycadElapsedTicks == CYCAD_INTERVAL_TICKS; }
    public PlantFlowerRound advanceCycad(long activeTicks) {
        if (activeTicks < 0) throw new IllegalArgumentException("Negative cycad active ticks");
        if (!allowsPollenReception() || cycadAttempts == CYCAD_MAX_ATTEMPTS) return this;
        return new PlantFlowerRound(id, active, failed, cycadAttempts,
                cycadElapsedTicks + Math.min(activeTicks, CYCAD_INTERVAL_TICKS - cycadElapsedTicks));
    }
    public PlantFlowerRound resolveCycad(boolean success) {
        if (!cycadReady()) throw new IllegalStateException("Cycad attempt is not due");
        int attempts = cycadAttempts + 1;
        return new PlantFlowerRound(id, active, !success && attempts == CYCAD_MAX_ATTEMPTS, attempts, 0);
    }
}
