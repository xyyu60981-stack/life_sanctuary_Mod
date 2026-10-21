package com.example.examplemod;

/** Shared gate for deciding whether a nearly full legal area may be reused. */
public final class NaturalGenerationSpace {
    private NaturalGenerationSpace() {}

    public static boolean hasRoom(int availableLegalPositions, int totalLegalPositions, int speciesLimit) {
        if (totalLegalPositions <= 0 || availableLegalPositions <= 0) return false;
        // A nearly exhausted area is closed when free space is at most 20%
        // of the species limit or at most 20% of all legal positions.
        int populationThreshold = Math.max(1, (int) Math.floor(speciesLimit * 0.20D));
        return availableLegalPositions > populationThreshold
                && availableLegalPositions * 100L > (long) totalLegalPositions * 20L;
    }
}
