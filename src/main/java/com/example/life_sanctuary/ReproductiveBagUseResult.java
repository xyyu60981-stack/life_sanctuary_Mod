package com.example.life_sanctuary;

/** Result returned by a species-specific spore or pollen effect. */
public record ReproductiveBagUseResult(boolean success, boolean consumeOne, boolean greenParticles, boolean recoveryRequired) {
    public ReproductiveBagUseResult {
        if (recoveryRequired && (success || consumeOne || greenParticles))
            throw new IllegalArgumentException("Recovery cannot accept or consume a bag");
    }
    public ReproductiveBagUseResult(boolean success, boolean consumeOne, boolean greenParticles) {
        this(success, consumeOne, greenParticles, false);
    }
    public static ReproductiveBagUseResult rejected() {
        return new ReproductiveBagUseResult(false, false, false, false);
    }

    public static ReproductiveBagUseResult consumed(boolean greenParticles) {
        return new ReproductiveBagUseResult(true, true, greenParticles, false);
    }
    public static ReproductiveBagUseResult recovery() {
        return new ReproductiveBagUseResult(false, false, false, true);
    }
}
