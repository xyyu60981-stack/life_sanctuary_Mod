package com.example.life_sanctuary;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;

/** Food definitions for material authors; no speculative item IDs or changes to horsetail porridge. */
public final class PlantFoodProfiles {
    private PlantFoodProfiles() {}
    public static final float MARSILEA_HUNGER_CHANCE = 0.30F;
    public static final int MARSILEA_HUNGER_TICKS = 100;
    public static final int MARSILEA_HUNGER_AMPLIFIER = 0;
    /** Share the vanilla definition, including nutrition, saturation and normal eating duration. */
    public static FoodProperties roastedThornTreeFernStem() {
        return net.minecraft.world.food.Foods.BREAD;
    }
    public static FoodProperties marsileaPorridge() {
        return new FoodProperties.Builder().nutrition(10).saturationModifier(0)
                .effect(() -> new MobEffectInstance(MobEffects.HUNGER, MARSILEA_HUNGER_TICKS, MARSILEA_HUNGER_AMPLIFIER),
                        MARSILEA_HUNGER_CHANCE).build();
    }
}
