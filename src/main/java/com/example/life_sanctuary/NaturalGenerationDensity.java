package com.example.life_sanctuary;

/** Per-biome density limits for natural plant generation. */
public record NaturalGenerationDensity(int maximumIndividualsPerChunk) {
    public NaturalGenerationDensity {
        if (maximumIndividualsPerChunk < 0) throw new IllegalArgumentException("density cannot be negative");
    }

    public boolean allows(int existingIndividuals, int additionalIndividuals) {
        return existingIndividuals + additionalIndividuals <= maximumIndividualsPerChunk;
    }
}
