package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 物种在初始化时登记实际出苗回调；到期本身不保证找到可用路径。 */
public final class RhizomeShootHandlers {
    private RhizomeShootHandlers() {}
    private static final Map<ResourceLocation, Handler> HANDLERS = new ConcurrentHashMap<>();
    @FunctionalInterface
    public interface Handler {
        void onReady(ServerLevel level, PlantRhizomeSystems store, PlantRhizomeSystem system);
    }
    public static void register(ResourceLocation species, Handler handler) {
        if (HANDLERS.putIfAbsent(Objects.requireNonNull(species), Objects.requireNonNull(handler)) != null)
            throw new IllegalArgumentException("Duplicate rhizome shoot handler: " + species);
    }
    static Handler find(ResourceLocation species) { return HANDLERS.get(species); }
}
