package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 物种提供实际叶结构事务；到期不等于已经脱落或补叶成功。 */
public final class PlantLeafHandlers {
    private PlantLeafHandlers() {}
    public record Event(PlantLeafTimer.Kind kind, String leafKey) {
        public Event {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(leafKey);
            if (kind == PlantLeafTimer.Kind.SHEDDING ? leafKey.isBlank() : !leafKey.isEmpty())
                throw new IllegalArgumentException("Invalid leaf event key");
        }
    }
    @FunctionalInterface
    public interface Handler {
        /** 成功后须通过对应叶事务更新数据；受阻保持到期状态，不抽新时长，不强制加载区块。 */
        void onReady(ServerLevel level, PlantIndividuals store, PlantIndividualState plant,
                PlantLeafData leaves, Event event);
    }
    private static final Map<ResourceLocation, Handler> HANDLERS = new ConcurrentHashMap<>();
    public static void register(ResourceLocation species, Handler handler) {
        if (HANDLERS.putIfAbsent(Objects.requireNonNull(species), Objects.requireNonNull(handler)) != null)
            throw new IllegalArgumentException("Duplicate leaf handler: " + species);
    }
    static Handler find(ResourceLocation species) { return HANDLERS.get(species); }
}
