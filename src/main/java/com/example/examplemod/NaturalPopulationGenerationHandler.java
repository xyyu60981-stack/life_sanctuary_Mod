package com.example.examplemod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Entry point for data-driven natural population generation on chunk load. */
public final class NaturalPopulationGenerationHandler {
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        NaturalPopulationGeneration.generate(level, chunk);
    }
}
