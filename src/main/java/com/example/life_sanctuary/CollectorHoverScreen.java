package com.example.life_sanctuary;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Interactive replacement for the read-only tooltip, with vanilla inventory click handling. */
public final class CollectorHoverScreen<M extends net.minecraft.world.inventory.ChestMenu> extends AbstractContainerScreen<M> {
    private long outsideSince;
    private long openedAt;
    private int sourceX;
    private int sourceY;
    private final boolean hoverOpening;
    private final boolean returnToInventory;

    public CollectorHoverScreen(M menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 114 + menu.getRowCount() * 18;
        hoverOpening = menu instanceof CollectorItemMenu item && !item.standardOpening() && CollectorHoverController.hoverOpening;
        returnToInventory = menu instanceof CollectorItemMenu item && !item.standardOpening() && CollectorHoverController.returnToInventory;
        CollectorHoverController.opened();
    }

    @Override protected void init() {
        super.init();
        if (!hoverOpening) {
            openedAt = System.currentTimeMillis();
        } else {
            sourceX = CollectorHoverController.anchorX;
            sourceY = CollectorHoverController.anchorY;
            // Prefer attaching the upper-left corner to the source slot's right edge.
            // Ordinary inventory slots may flip left/up; equipped boxes prefer the right.
            int desiredX = sourceX + 18;
            if (!CollectorHoverController.anchorEquipped && desiredX + imageWidth > width - 2)
                desiredX = sourceX - imageWidth - 2;
            int desiredY = sourceY;
            if (!CollectorHoverController.anchorEquipped && desiredY + imageHeight > height - 2)
                desiredY = sourceY + 16 - imageHeight;
            leftPos = Math.max(2, Math.min(Math.max(2, width - imageWidth - 2), desiredX));
            topPos = Math.max(2, Math.min(Math.max(2, height - imageHeight - 2), desiredY));
            openedAt = System.currentTimeMillis();
        }
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("menu.life_sanctuary.collector_filter.button"), button -> {
            if (!menu.getCarried().isEmpty()) return;
            CollectorHoverController.suppressReopen();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new OpenCollectorFilterPayload(menu.containerId));
        }).bounds(leftPos + imageWidth - 52, topPos + 2, 46, 14).build());
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (hoverOpening && CollectorHoverController.anchorEquipped) {
            // Keep the equipped source visible after the inventory menu is replaced.
            int edgeX = sourceX + 17;
            graphics.fill(Math.min(edgeX, leftPos), sourceY, Math.max(edgeX, leftPos) + 1, sourceY + 1, 0xAA9999AA);
            graphics.fill(leftPos, Math.min(sourceY, topPos), leftPos + 1, Math.max(sourceY, topPos) + 1, 0xAA9999AA);
            graphics.renderItem(CollectorHoverController.anchorItem, sourceX, sourceY);
        }
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF888899);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF24242D);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF101015);
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 66), 8, 6, 0xEEEEEE, false);
        int spores = 0;
        int pollen = 0;
        for (int i = 0; i < menu.getRowCount() * 9; i++) {
            ItemStack item = menu.slots.get(i).getItem();
            if (ReproductiveBagKind.SPORE.isEmptyBag(item)) spores += item.getCount();
            if (ReproductiveBagKind.POLLEN.isEmptyBag(item)) pollen += item.getCount();
        }
        graphics.pose().pushPose();
        graphics.pose().translate(8, 20 + menu.getRowCount() * 18, 0);
        graphics.pose().scale(0.7F, 0.7F, 1);
        graphics.drawString(font, Component.translatable("tooltip.life_sanctuary.collector.empty_bags", spores, pollen),
                0, 0, 0xAAAAAA, false);
        graphics.pose().popPose();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // Avoid nested collector grids while viewing the locked source box in this menu.
        if (hoveredSlot == null || CollectorInventory.capacity(hoveredSlot.getItem()) == 0)
            renderTooltip(graphics, mouseX, mouseY);
        if (!hoverOpening) return;
        boolean inWindow = mouseX >= leftPos - 12 && mouseX <= leftPos + imageWidth + 12
                && mouseY >= topPos - 12 && mouseY <= topPos + imageHeight + 12;
        boolean atSource = mouseX >= sourceX - 8 && mouseX <= sourceX + 24
                && mouseY >= sourceY - 8 && mouseY <= sourceY + 24;
        long now = System.currentTimeMillis();
        if (inWindow || atSource || !menu.getCarried().isEmpty() || now - openedAt < 600) outsideSince = 0;
        else if (outsideSince == 0) outsideSince = now;
        else if (now - outsideSince >= 350) onClose();
    }

    @Override public void onClose() {
        CollectorHoverController.suppressReopen();
        super.onClose(); // Vanilla returns any cursor stack before closing the container.
        if (returnToInventory && minecraft != null && minecraft.player != null && minecraft.player.isAlive())
            minecraft.setScreen(new InventoryScreen(minecraft.player));
    }
}
