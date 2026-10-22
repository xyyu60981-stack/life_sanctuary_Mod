package com.example.life_sanctuary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Species adapters implement world changes; the scheduler never guesses structure or next stage. */
public final class PlantGrowthHandlers {
    @FunctionalInterface
    public interface Handler {
        /**
         * Called on the server thread with a mature snapshot and a ticking base chunk.
         * Check all required chunks/space without loading them. On success replace/remove the snapshot
         * through the store. If blocked, leave it unchanged; retry occurs after 100 active chunk ticks.
         * A terminal stage must clear its timer (target=elapsed=0). Never reset harvests on a retry.
         */
        void onReady(ServerLevel level, PlantIndividuals store, PlantIndividualState mature);
    }
    private static final Map<ResourceLocation, Handler> HANDLERS = new ConcurrentHashMap<>();
    private PlantGrowthHandlers() {}
    public static void register(ResourceLocation species, Handler handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        if (HANDLERS.putIfAbsent(species, handler) != null)
            throw new IllegalArgumentException("Duplicate growth handler: " + species);
    }
    static Handler find(ResourceLocation species) { return HANDLERS.get(species); }
}
