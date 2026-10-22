package com.example.life_sanctuary;

/** Independent climate facts; hot and dry may both be true. Never represents water supply. */
public record PlantClimate(boolean dry, boolean cold, boolean hot) {}
