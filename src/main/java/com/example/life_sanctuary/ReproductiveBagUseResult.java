package com.example.life_sanctuary;

/** Result returned by a species-specific spore or pollen effect. */
public record ReproductiveBagUseResult(boolean success, boolean consumeOne, boolean greenParticles) {
    public static ReproductiveBagUseResult rejected() {
        return new ReproductiveBagUseResult(false, false, false);
    }

    public static ReproductiveBagUseResult consumed(boolean greenParticles) {
        return new ReproductiveBagUseResult(true, true, greenParticles);
    }
}
