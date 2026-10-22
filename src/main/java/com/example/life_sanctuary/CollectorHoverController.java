package com.example.life_sanctuary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only hover timer; sends one request after a completed, uninterrupted dwell. */
public final class CollectorHoverController {
    private static final long HOVER_DELAY_MS = 800;
    private record RingPixel(int x, int y, double progress) {}
    private static final RingPixel[] RING = buildRing();
    private static Object previousScreen;
    private static int previousSlot = -1;
    private static long hoverSince;
    private static long cooldownUntil;
    private static boolean requested;
    private static long requestPendingUntil;
    static int anchorX;
    static int anchorY;
    static boolean anchorEquipped;
    static boolean hoverOpening = true;
    static boolean returnToInventory = true;
    static ItemStack anchorItem = ItemStack.EMPTY;
    private static ItemStack previousStack = ItemStack.EMPTY;

    private CollectorHoverController() {}

    /** Share the in-flight guard so a key press cannot change a pending hover window's mode. */
    public static boolean beginOpenRequest() {
        long now = System.currentTimeMillis();
        if (now < requestPendingUntil) return false;
        requestPendingUntil = now + 2000;
        return true;
    }

    public static void opened() { requestPendingUntil = 0; }

    public static void keyboardOpening(boolean fromInventory) {
        hoverOpening = false;
        returnToInventory = fromInventory;
        anchorEquipped = false;
        // Prevent the hover timer sending a second request while the key request is in flight.
        requested = true;
        cooldownUntil = System.currentTimeMillis() + 800;
    }

    private static RingPixel[] buildRing() {
        var pixels = new ArrayList<RingPixel>();
        for (int y = -6; y <= 6; y++) for (int x = -6; x <= 6; x++) {
            int radiusSquared = x * x + y * y;
            if (radiusSquared < 20 || radiusSquared > 36) continue;
            double angle = Math.atan2(y, x) + Math.PI / 2;
            if (angle < 0) angle += Math.PI * 2;
            pixels.add(new RingPixel(x, y, angle / (Math.PI * 2)));
        }
        return pixels.toArray(RingPixel[]::new);
    }

    private static void drawProgress(GuiGraphics graphics, int mouseX, int mouseY, int width, int height, double progress) {
        int cx = mouseX + 15;
        int cy = mouseY + 15;
        if (cx + 7 >= width) cx = mouseX - 15;
        if (cy + 7 >= height) cy = mouseY - 15;
        cx = Math.max(7, cx);
        cy = Math.max(7, cy);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        // Rasterized once: no per-frame trigonometry, textures, or network progress updates.
        for (RingPixel pixel : RING) {
            int color = pixel.progress() < progress ? 0xFFFFFFFF : 0x70888888;
            graphics.fill(cx + pixel.x(), cy + pixel.y(), cx + pixel.x() + 1, cy + pixel.y() + 1, color);
        }
        graphics.pose().popPose();
    }

    public static void suppressReopen() {
        cooldownUntil = System.currentTimeMillis() + 800;
        previousScreen = null;
    }

    public static void afterRender(ScreenEvent.Render.Post event) {
        var minecraft = Minecraft.getInstance();
        if (!(event.getScreen() instanceof InventoryScreen screen) || minecraft.player == null) {
            previousScreen = null;
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        int index = slot == null ? -1 : slot.getContainerSlot();
        if (slot == null || slot.container != minecraft.player.getInventory() || !OpenCollectorPayload.isSupportedSlot(index)
                || CollectorInventory.capacity(slot.getItem()) == 0 || slot.getItem().getCount() != 1
                || !screen.getMenu().getCarried().isEmpty()) {
            previousScreen = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (previousScreen != screen || previousSlot != index || previousStack != slot.getItem()) {
            previousScreen = screen;
            previousSlot = index;
            previousStack = slot.getItem();
            hoverSince = now;
            requested = false;
        }
        long elapsed = Math.max(0, now - hoverSince);
        if (!requested && elapsed < HOVER_DELAY_MS) {
            drawProgress(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), screen.width, screen.height,
                    (double) elapsed / HOVER_DELAY_MS);
        }
        if (!requested && elapsed >= HOVER_DELAY_MS && now >= cooldownUntil && beginOpenRequest()) {
            requested = true;
            hoverOpening = true;
            returnToInventory = true;
            anchorX = screen.getGuiLeft() + slot.x;
            anchorY = screen.getGuiTop() + slot.y;
            anchorEquipped = index == 37;
            anchorItem = slot.getItem().copy();
            PacketDistributor.sendToServer(new OpenCollectorPayload(index));
        }
    }
}
