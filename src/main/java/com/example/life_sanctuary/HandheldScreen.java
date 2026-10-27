package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Local icon focus only. Actual games must still call their authenticated server session APIs. */
public final class HandheldScreen extends Screen {
    private final InteractionHand hand;
    private final List<HandheldApps.App> apps = HandheldApps.entries();
    private final List<Button> buttons = new ArrayList<>();
    private final List<ItemStack> icons = new ArrayList<>();
    private int selected, columns, page, panelX, panelY, panelWidth;
    private static final class AppButton extends Button {
        AppButton(int x, int y, Component title, Runnable open) { super(x, y, 70, 42, title, button -> open.run(), DEFAULT_NARRATION); }
        @Override public void renderString(GuiGraphics graphics, net.minecraft.client.gui.Font font, int color) {
            graphics.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + 27, color);
        }
    }
    public HandheldScreen(InteractionHand hand) { super(Component.translatable("item.life_sanctuary.game_start")); this.hand = hand; selected = apps.isEmpty() ? -1 : 0; }
    private int pageSize() { return columns * 2; }
    @Override protected void init() {
        columns = Math.max(1, Math.min(4, (width - 40) / 76)); panelWidth = columns * 76 + 24;
        panelX = (width - panelWidth) / 2; panelY = Math.max(6, (height - 204) / 2);
        page = selected < 0 ? 0 : selected / pageSize();
        buttons.clear(); icons.clear();
        int start = page * pageSize();
        for (int index = start; index < Math.min(apps.size(), start + pageSize()); index++) {
            int appIndex = index, local = index - start;
            var app = apps.get(index);
            var button = new AppButton(panelX + 12 + local % columns * 76, panelY + 48 + local / columns * 56,
                    app.name(), () -> { selected = appIndex; app.open().accept(this); });
            buttons.add(addRenderableWidget(button)); icons.add(app.icon().get().copy());
        }
        if (selected >= 0 && !buttons.isEmpty()) setInitialFocus(buttons.get(selected - start));
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1)).bounds(panelX + 12, panelY + 168, 24, 20).build());
        previous.active = page > 0;
        var next = addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1)).bounds(panelX + panelWidth - 36, panelY + 168, 24, 20).build());
        next.active = start + pageSize() < apps.size();
    }
    private void changePage(int delta) {
        long destination = (long) (page + delta) * pageSize();
        if (destination >= 0 && destination < apps.size()) { selected = (int) destination; rebuildWidgets(); }
    }
    private void navigate(int dx, int dy) {
        selected = HandheldNavigation.move(selected, apps.size(), columns, dx, dy);
        if (selected < 0) return;
        if (selected / pageSize() != page) rebuildWidgets();
        else setFocused(buttons.get(selected - page * pageSize()));
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> navigate(0, -1);
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> navigate(0, 1);
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> navigate(-1, 0);
            case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> navigate(1, 0);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (getFocused() != null && !buttons.contains(getFocused())) return super.keyPressed(key, scanCode, modifiers);
                if (selected >= 0) apps.get(selected).open().accept(this);
            }
            default -> {
                boolean handled = super.keyPressed(key, scanCode, modifiers);
                int focus = buttons.indexOf(getFocused()); if (focus >= 0) selected = page * pageSize() + focus;
                return handled;
            }
        }
        return true;
    }
    @Override public void mouseMoved(double x, double y) {
        super.mouseMoved(x, y);
        for (int index = 0; index < buttons.size(); index++) if (buttons.get(index).isMouseOver(x, y)) {
            selected = page * pageSize() + index; setFocused(buttons.get(index)); break;
        }
    }
    public boolean hasHandheld() { return minecraft != null && minecraft.player != null && minecraft.player.isAlive()
                && minecraft.player.getItemInHand(hand).getItem() instanceof HandheldItem; }
    @Override public void tick() { if (!hasHandheld()) onClose(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) {
        super.render(graphics, x, y, partialTick);
        for (int index = 0; index < buttons.size(); index++) {
            var button = buttons.get(index); graphics.renderItem(icons.get(index), button.getX() + 27, button.getY() + 2);
            if (page * pageSize() + index == selected) {
                int bx = button.getX(), by = button.getY();
                graphics.hLine(bx - 1, bx + button.getWidth(), by - 1, 0xFF80CBA2);
                graphics.hLine(bx - 1, bx + button.getWidth(), by + button.getHeight(), 0xFF80CBA2);
                graphics.vLine(bx - 1, by - 1, by + button.getHeight(), 0xFF80CBA2);
                graphics.vLine(bx + button.getWidth(), by - 1, by + button.getHeight(), 0xFF80CBA2);
            }
        }
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partialTick) {
        super.renderBackground(graphics, x, y, partialTick);
        graphics.fill(panelX - 4, panelY - 4, panelX + panelWidth + 4, panelY + 200, 0xFF5A5A66);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 196, 0xFF181A22);
        graphics.drawCenteredString(font, title, width / 2, panelY + 10, 0xEEEEEE);
        graphics.drawCenteredString(font, Component.translatable("gui.life_sanctuary.handheld.controls"), width / 2, panelY + 28, 0xAAAAAA);
    }
}
