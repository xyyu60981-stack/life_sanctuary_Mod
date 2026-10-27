package com.example.life_sanctuary;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Drag the handle from the left; server-acknowledged progress drives the visible blade. */
public final class FossilSlicerScreen extends AbstractContainerScreen<FossilSlicerMenu> {
    private boolean dragging, finishPending;
    private int desired, revision;
    public FossilSlicerScreen(FossilSlicerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 176; imageHeight = 222; inventoryLabelY = 114;
    }
    private void send(int command) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, command);
    }
    private int destination(double x) { return Math.max(0, Math.min(FossilSlicingRules.END, (int) Math.round(x - leftPos - 54))); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && menu.getCarried().isEmpty() && menu.state() == FossilSlicerMenu.READY
                && x >= leftPos + 49 && x <= leftPos + 59 && y >= topPos + 88 && y <= topPos + 106) {
            dragging = true; finishPending = false; desired = 0; revision = menu.revision(); send(FossilSlicingRules.BEGIN); return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) { desired = destination(x); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (dragging && button == 0) {
            desired = destination(x); dragging = false;
            if (desired == FossilSlicingRules.END) finishPending = true;
            else send(FossilSlicingRules.CANCEL);
            return true;
        }
        return super.mouseReleased(x, y, button);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!dragging && !finishPending) return;
        if (menu.revision() != revision) { dragging = false; finishPending = false; return; }
        if (menu.state() == FossilSlicerMenu.NO_INPUT || menu.state() == FossilSlicerMenu.BUSY || !menu.getCarried().isEmpty()) {
            send(FossilSlicingRules.CANCEL); dragging = false; finishPending = false; return;
        }
        int progress = menu.progress();
        int delta = Math.max(-FossilSlicingRules.MAXIMUM_STEP, Math.min(FossilSlicingRules.MAXIMUM_STEP, desired - progress));
        if (delta != 0) send(FossilSlicingRules.MOVE_BASE + progress + delta);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF77777F);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF24242D);
        for (var slot : menu.slots) graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF101015);
        graphics.fill(leftPos + 49, topPos + 21, leftPos + 127, topPos + 86, 0xFF77777F);
        graphics.fill(leftPos + 50, topPos + 22, leftPos + 126, topPos + 85, 0xFF101015);
        graphics.fill(leftPos + 50, topPos + 94, leftPos + 123, topPos + 100, 0xFF555563);
        graphics.fill(leftPos + 54, topPos + 95, leftPos + 54 + menu.progress(), topPos + 99, 0xFF75A88A);
        int handle = leftPos + 54 + menu.progress();
        graphics.fill(handle - 4, topPos + 88, handle + 4, topPos + 106, 0xFFD5D5DD);
        graphics.fill(handle - 1, topPos + 90, handle + 1, topPos + 104, 0xFF555563);
        graphics.drawCenteredString(font, Component.translatable("gui.life_sanctuary.fossil_slicer.state." + menu.state()),
                leftPos + imageWidth / 2, topPos + 210, 0xDDDDDD);
    }
    @Override protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (slot.index != 1) { super.renderSlot(graphics, slot); return; }
        var stack = slot.getItem();
        if (stack.isEmpty()) return;
        graphics.pose().pushPose();
        graphics.pose().translate(64, 30, 0);
        graphics.pose().scale(3, 3, 1);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }
    @Override protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (x == 80 && y == 48 && width == 16 && height == 16)
            return super.isHovering(50, 22, 76, 63, mouseX, mouseY);
        return super.isHovering(x, y, width, height, mouseX, mouseY);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick); renderTooltip(graphics, mouseX, mouseY);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xEEEEEE, false);
        graphics.drawString(font, Component.translatable("container.inventory"), 8, inventoryLabelY, 0xBBBBBB, false);
    }
}
