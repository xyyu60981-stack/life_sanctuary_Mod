package com.example.life_sanctuary;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Inventory is inspected only on an actual key press; the default B binding remains configurable. */
public final class CollectorKeyHandler {
    public static final KeyMapping OPEN = new KeyMapping("key.life_sanctuary.open_collector",
            GLFW.GLFW_KEY_B, "key.categories.life_sanctuary");
    private static long nextRequest;
    private CollectorKeyHandler() {}

    public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }

    public static void tick(ClientTickEvent.Post event) {
        boolean pressed = false;
        while (OPEN.consumeClick()) pressed = true;
        if (!pressed) return;
        requestOpen();
    }

    public static void screenKey(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof InventoryScreen && OPEN.matches(event.getKeyCode(), event.getScanCode())) {
            event.setCanceled(true);
            requestOpen();
        }
    }

    private static void requestOpen() {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || !player.isAlive() || player.isSpectator()
                || (minecraft.screen != null && !(minecraft.screen instanceof InventoryScreen))
                || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < nextRequest) return;
        int selected = -1;
        if (minecraft.screen instanceof InventoryScreen screen) {
            var hovered = screen.getSlotUnderMouse();
            if (hovered != null && hovered.container == player.getInventory()
                    && OpenCollectorPayload.isSupportedSlot(hovered.getContainerSlot()) && isBox(hovered.getItem()))
                selected = hovered.getContainerSlot();
        }
        if (selected < 0 && isBox(player.getInventory().getItem(37))) selected = 37;
        if (selected < 0) {
            int mostFree = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack box = player.getInventory().getItem(i);
                if (!isBox(box)) continue;
                var summary = CollectorInventory.summarize(box);
                int free = Math.max(0, summary.capacity() - summary.occupiedSlots());
                // Equal free capacity keeps the earlier inventory slot, as specified by the user.
                if (free > mostFree) { mostFree = free; selected = i; }
            }
        }
        if (selected < 0) {
            player.displayClientMessage(Component.translatable("message.life_sanctuary.no_collector"), true);
            return;
        }
        if (!CollectorHoverController.beginOpenRequest()) return;
        nextRequest = now + 800;
        CollectorHoverController.keyboardOpening(minecraft.screen instanceof InventoryScreen);
        PacketDistributor.sendToServer(new OpenCollectorPayload(selected));
    }

    private static boolean isBox(ItemStack stack) {
        return stack.getCount() == 1 && CollectorInventory.capacity(stack) > 0;
    }
}
