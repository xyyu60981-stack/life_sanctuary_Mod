package com.example.life_sanctuary;

/** Parameters shared by species-specific natural population generation. */
public record NaturalPopulationRules(int maximumIndividuals, int minimumSpacing,
        float confirmationChance, boolean retainPopulation) {
    public NaturalPopulationRules {
        if (maximumIndividuals < 1) throw new IllegalArgumentException("maximumIndividuals must be positive");
        if (minimumSpacing < 0) throw new IllegalArgumentException("minimumSpacing cannot be negative");
        if (confirmationChance < 0.0F || confirmationChance > 1.0F) {
            throw new IllegalArgumentException("confirmationChance must be between 0 and 1");
        }
    }

    public int naturalSpawnLimit() {
        return Math.max(1, (int) Math.floor(maximumIndividuals * 0.80D));
    }
}
