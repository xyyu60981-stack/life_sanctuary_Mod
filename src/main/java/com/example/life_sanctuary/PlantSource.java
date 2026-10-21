package com.example.life_sanctuary;

/**
 * Persistent source label attached to a plant individual.
 *
 * <p>These permissions are deliberately kept here instead of being inferred
 * from the block type. A natural and a player-placed plant may share the same
 * block state while following different lifecycle rules.</p>
 */
public enum PlantSource {
    PLAYER,
    ONE_TIME,
    STATIC;

    /** Static landscape plants never advance or expand. */
    public boolean isStatic() {
        return this == STATIC;
    }

    /** Player plants may use the complete growth and expansion pipeline. */
    public boolean canProgress() {
        return this == PLAYER;
    }

    /** Player and one-time plants may finish their already-started growth cycle. */
    public boolean canFinishCurrentCycle() {
        return this == PLAYER || this == ONE_TIME;
    }

    /** One-time plants may finish their current cycle but may not start expansion. */
    public boolean canExpand() {
        return this == PLAYER;
    }
}
