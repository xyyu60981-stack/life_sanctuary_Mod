package com.example.life_sanctuary;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/** Native inventory cursor plus non-consuming ghost samples; reorder sends bounded vanilla button commands. */
public final class CollectorFilterScreen extends AbstractContainerScreen<CollectorFilterMenu> {
    private int dragging = -1;
    private Button modeButton;
    private Button backButton;
    public CollectorFilterScreen(CollectorFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 176; imageHeight = 176; inventoryLabelY = 82;
    }
    @Override protected void init() {
        super.init();
        backButton = addRenderableWidget(Button.builder(Component.translatable("menu.life_sanctuary.collector_filter.back"), button -> send(2))
                .bounds(leftPos + 122, topPos + 2, 46, 14).build());
        modeButton = addRenderableWidget(Button.builder(Component.empty(), button -> send(0)).bounds(leftPos + 8, topPos + 60, 92, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("menu.life_sanctuary.collector_filter.clear"), button -> send(1))
                .bounds(leftPos + 106, topPos + 60, 62, 18).build());
    }
    private void send(int command) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, command);
    }
    private Slot ghost(double x, double y) {
        for (int i = 0; i < CollectorFilter.SIZE; i++) {
            var slot = menu.slots.get(i);
            if (x >= leftPos + slot.x && x < leftPos + slot.x + 16 && y >= topPos + slot.y && y < topPos + slot.y + 16) return slot;
        }
        return null;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var slot = ghost(x, y);
        if (slot != null && button == 0 && menu.getCarried().isEmpty() && slot.hasItem()) {
            dragging = slot.index; return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging >= 0 || ghost(x, y) != null && !menu.getCarried().isEmpty()) return true;
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        var slot = ghost(x, y);
        if (dragging >= 0) {
            if (button == 0 && slot != null) { send(CollectorFilterMenu.SELECT + dragging); send(CollectorFilterMenu.MOVE + slot.index); }
            dragging = -1; return true;
        }
        if (slot != null && !menu.getCarried().isEmpty() && (button == 0 || button == 1)) {
            isQuickCrafting = false; quickCraftSlots.clear();
            slotClicked(slot, slot.index, button, ClickType.PICKUP); return true;
        }
        return super.mouseReleased(x, y, button);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF888899);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF24242D);
        for (var slot : menu.slots) graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1,
                leftPos + slot.x + 17, topPos + slot.y + 17, slot.index < CollectorFilter.SIZE ? 0xFF424258 : 0xFF101015);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        modeButton.setMessage(Component.translatable("menu.life_sanctuary.collector_filter.mode." + menu.mode().name().toLowerCase(java.util.Locale.ROOT)));
        modeButton.active = menu.getCarried().isEmpty() && menu.mode() != CollectorFilter.Mode.BROKEN;
        backButton.active = menu.getCarried().isEmpty();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (dragging >= 0) graphics.renderItem(menu.slots.get(dragging).getItem(), mouseX - 8, mouseY - 8);
        else if (ghost(mouseX, mouseY) != null) {
            var sample = ghost(mouseX, mouseY).getItem();
            var tooltip = new java.util.ArrayList<Component>();
            if (!sample.isEmpty()) {
                tooltip.add(sample.getHoverName());
                var species = SpeciesTaggedItemData.read(sample);
                tooltip.add(Component.translatable(species == null ? "menu.life_sanctuary.collector_filter.any_species"
                        : "menu.life_sanctuary.collector_filter.species", species == null ? "" : species));
            }
            tooltip.add(Component.translatable("menu.life_sanctuary.collector_filter.help1"));
            tooltip.add(Component.translatable("menu.life_sanctuary.collector_filter.help2"));
            graphics.renderTooltip(font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
        }
        else renderTooltip(graphics, mouseX, mouseY);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xEEEEEE, false);
        graphics.drawString(font, Component.translatable("container.inventory"), 8, inventoryLabelY, 0xBBBBBB, false);
    }
}
