package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Client UI registration only. Visibility or clicking an icon never authorizes a server game action. */
public final class HandheldApps {
    private HandheldApps() {}
    public record App(ResourceLocation id, Component name, Supplier<ItemStack> icon, Consumer<Screen> open) {
        public App { Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(icon); Objects.requireNonNull(open); }
    }
    private static final Map<ResourceLocation, App> APPS = new LinkedHashMap<>();
    /** Register once during client setup, in the intended presentation order. */
    public static void register(App app) {
        if (APPS.putIfAbsent(app.id(), app) != null) throw new IllegalArgumentException("Duplicate handheld app " + app.id());
    }
    public static List<App> entries() { return List.copyOf(APPS.values()); }
}
