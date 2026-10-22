package com.example.life_sanctuary;

/** Species/stage response to climate, separate from raw dry/cold/hot flags. */
public enum PlantClimateResponse {
    GROWING,
    PAUSED,
    WITHERING,
    DISAPPEARING;

    public boolean allowsBonemeal() {
        return this == GROWING;
    }
}
