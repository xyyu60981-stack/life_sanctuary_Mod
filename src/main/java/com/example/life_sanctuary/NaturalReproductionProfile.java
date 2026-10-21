package com.example.life_sanctuary;

/** Initial reproductive-structure distribution used by future population generation. */
public record NaturalReproductionProfile(float minimumFraction, float maximumFraction) {
    public static final NaturalReproductionProfile EQUISETUM_ARVENSE_SPORE_STEM = new NaturalReproductionProfile(0.30F, 0.40F);

    public float sample(java.util.random.RandomGenerator random) {
        return minimumFraction + random.nextFloat() * (maximumFraction - minimumFraction);
    }
}
