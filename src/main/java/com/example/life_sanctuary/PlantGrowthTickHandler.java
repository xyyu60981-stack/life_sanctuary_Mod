package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class PlantGrowthTickHandler {
    @SubscribeEvent
    public void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) PlantIndividuals.get(level).tickGrowth(level);
    }
}
