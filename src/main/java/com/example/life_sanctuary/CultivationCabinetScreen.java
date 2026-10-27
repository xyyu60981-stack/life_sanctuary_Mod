package com.example.life_sanctuary;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 两槽／四槽静态界面；物品和进度由原版菜单差量同步，不写客户端库存。 */
public final class CultivationCabinetScreen extends AbstractContainerScreen<CultivationCabinetMenu> {
    public CultivationCabinetScreen(CultivationCabinetMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = 72;
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF555555);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFC6C6C6);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
            graphics.fill(x, y, x + 17, y + 17, 0xFF8B8B8B);
        }
        for (int i = 0; i < menu.capacity(); i++) {
            var slot = menu.slots.get(i);
            int x = leftPos + slot.x, y = topPos + slot.y + 20;
            graphics.fill(x, y, x + 16, y + 3, 0xFF555555);
            graphics.fill(x, y, x + 16 * menu.progress(i) / 1000, y + 3,
                    menu.state(i) == 2 || menu.state(i) == 3 ? 0xFFCC6666 : 0xFF55AA55);
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (hoveredSlot != null && hoveredSlot.index < menu.capacity()) {
            int index = hoveredSlot.index;
            var lines = new java.util.ArrayList<Component>();
            if (!hoveredSlot.getItem().isEmpty()) lines.add(hoveredSlot.getItem().getHoverName());
            menu.speciesName(index).ifPresent(name -> lines.add(Component.translatable("gui.life_sanctuary.cultivation.species", name)));
            lines.add(Component.translatable("gui.life_sanctuary.cultivation.state." + menu.state(index)));
            if (menu.phaseCount(index) > 0) lines.add(Component.translatable("gui.life_sanctuary.cultivation.phase",
                    menu.phaseNumber(index), menu.phaseCount(index), menu.progress(index) / 10));
            if (menu.remainingProducts(index) > 0) lines.add(Component.translatable("gui.life_sanctuary.cultivation.remaining",
                    menu.remainingProducts(index)));
            if (menu.remainingProducts(index) > 0 && menu.state(index) != 2)
                lines.add(Component.translatable("gui.life_sanctuary.cultivation.claim"));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else renderTooltip(graphics, mouseX, mouseY);
    }
}
