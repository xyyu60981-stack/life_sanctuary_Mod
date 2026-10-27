package com.example.life_sanctuary;

import java.util.ArrayDeque;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Defer index access until after chunk loading; never force-load a chunk from its load event. */
public final class ShelterDiscovery {
    private record Pending(ServerLevel level, StructureStart start) {}
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    @SubscribeEvent public void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (var start : event.getChunk().getAllStarts().values()) if (start.isValid()) pending.add(new Pending(level, start));
    }
    @SubscribeEvent public void afterTick(ServerTickEvent.Post event) {
        for (int budget = 0; budget < 256 && !pending.isEmpty(); budget++) {
            var entry = pending.removeFirst();
            if (entry.level().getServer() == event.getServer()) ShelterLocations.get(event.getServer()).observe(entry.level(), entry.start());
        }
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) { pending.clear(); }
}
