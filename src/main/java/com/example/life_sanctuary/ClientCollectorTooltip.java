package com.example.life_sanctuary;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Static grid only: no animation, nested tooltip rendering, world access or networking. */
public final class ClientCollectorTooltip implements ClientTooltipComponent {
    private static final int COLUMNS = 9;
    private static final int SLOT = 18;
    private static final float FOOTER_SCALE = 0.75F;
    private final CollectorTooltip data;
    private final Component footer;

    public ClientCollectorTooltip(CollectorTooltip data) {
        this.data = data;
        footer = Component.translatable("tooltip.life_sanctuary.collector.empty_bags",
                data.summary().emptySporeBags(), data.summary().emptyPollenBags());
    }

    @Override public int getHeight() { return gridHeight() + 13; }
    @Override public int getWidth(Font font) {
        return Math.max(COLUMNS * SLOT + 2, (int) Math.ceil(font.width(footer) * FOOTER_SCALE) + 2);
    }
    private int gridHeight() { return data.summary().capacity() / COLUMNS * SLOT + 2; }

    @Override public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        graphics.fill(x, y, x + COLUMNS * SLOT + 2, y + gridHeight(), 0xFF303038);
        for (int i = 0; i < data.summary().capacity(); i++) {
            int sx = x + 1 + i % COLUMNS * SLOT;
            int sy = y + 1 + i / COLUMNS * SLOT;
            graphics.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, 0xFF17171D);
            ItemStack item = i < data.contents().getSlots() ? data.contents().getStackInSlot(i) : ItemStack.EMPTY;
            if (!item.isEmpty()) {
                graphics.renderItem(item, sx + 1, sy + 1, i);
                graphics.renderItemDecorations(font, item, sx + 1, sy + 1);
            }
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x + 1, y + gridHeight() + 3, 0);
        graphics.pose().scale(FOOTER_SCALE, FOOTER_SCALE, 1);
        graphics.drawString(font, footer, 0, 0, 0xFFAAAAAA, false);
        graphics.pose().popPose();
    }
}
