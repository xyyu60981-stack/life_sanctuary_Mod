package com.example.life_sanctuary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Loaded exclusively by the physical client entry point. */
public final class HandheldClient {
    private HandheldClient() {}
    public static void setup() {
        HandheldApps.register(new HandheldApps.App(ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "sfernm"),
                Component.literal("sfernm"), () -> new ItemStack(Items.BOOK), parent -> Minecraft.getInstance().setScreen(new SfernmScreen(parent))));
    }
    public static void use(PlayerInteractEvent.RightClickItem event) {
        var minecraft = Minecraft.getInstance();
        if (!event.getLevel().isClientSide || event.getEntity() != minecraft.player || minecraft.screen != null
                || !event.getEntity().isAlive() || event.getEntity().isSpectator()
                || !(event.getEntity().getItemInHand(event.getHand()).getItem() instanceof HandheldItem)) return;
        minecraft.setScreen(new HandheldScreen(event.getHand()));
    }
    private static final class SfernmScreen extends Screen {
        private final Screen parent;
        SfernmScreen(Screen parent) { super(Component.literal("sfernm")); this.parent = parent; }
        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose()).bounds(width / 2 - 50, height / 2 + 35, 100, 20).build());
        }
        @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) {
            super.render(graphics, x, y, partialTick);
            graphics.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xEEEEEE);
            graphics.drawCenteredString(font, Component.translatable("gui.life_sanctuary.handheld.no_games"), width / 2, height / 2 - 5, 0xBBBBBB);
        }
        @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
        @Override public void tick() {
            if (parent instanceof HandheldScreen launcher && !launcher.hasHandheld() && minecraft != null) minecraft.setScreen(null);
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}
