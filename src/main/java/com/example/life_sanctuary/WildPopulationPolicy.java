package com.example.life_sanctuary;

/**
 * Initial wild-population density policy. Natural generation uses one fixed
 * population per species and chunk; occupancy is checked before another spawn.
 */
public record WildPopulationPolicy(int maximumIndividuals, float targetOccupancy) {
    public static final WildPopulationPolicy DEFAULT = new WildPopulationPolicy(15, 0.35F);

    public boolean shouldAttemptSpawn(int currentIndividuals) {
        return currentIndividuals < maximumIndividuals
                && currentIndividuals < Math.max(1, Math.round(maximumIndividuals * targetOccupancy));
    }
}
