package com.example.life_sanctuary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 枯死到期回调与成长回调独立注册；无回调不猜测死体形态。 */
public final class PlantWitheringHandlers {
    @FunctionalInterface
    public interface Handler {
        /** 校验所需区块后调用 PlantWithering.finish；空间不可用时保持原计时，100 活动刻后重试。 */
        void onReady(ServerLevel level, PlantIndividuals store, PlantIndividualState withering);
    }
    private static final Map<ResourceLocation, Handler> HANDLERS = new ConcurrentHashMap<>();
    private PlantWitheringHandlers() {}

    public static void register(ResourceLocation species, Handler handler) {
        java.util.Objects.requireNonNull(handler);
        if (HANDLERS.putIfAbsent(species, handler) != null)
            throw new IllegalArgumentException("Duplicate withering handler: " + species);
    }
    static Handler find(ResourceLocation species) { return HANDLERS.get(species); }
}
