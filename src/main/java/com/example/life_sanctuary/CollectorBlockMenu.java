package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Custom type exposes the filter button while retaining vanilla chest inventory layout. */
public final class CollectorBlockMenu extends ChestMenu {
    private final CollectorBlockEntity source;
    public CollectorBlockMenu(int id, Inventory inventory, BlockPos pos, int rows) {
        this(id, inventory, new SimpleContainer(rows * 9), rows, null);
    }
    public CollectorBlockMenu(int id, Inventory inventory, CollectorBlockEntity source) {
        this(id, inventory, source, source.getContainerSize() / 9, source);
    }
    private CollectorBlockMenu(int id, Inventory inventory, Container container, int rows, CollectorBlockEntity source) {
        super(LifeSanctuaryMod.COLLECTOR_BLOCK_MENU.get(), id, inventory, container, rows);
        this.source = source;
        for (int i = 0; i < rows * 9; i++) {
            var old = slots.get(i);
            var slot = new Slot(container, i, old.x, old.y) {
                @Override public boolean mayPlace(ItemStack stack) { return container.canPlaceItem(getContainerSlot(), stack) && CollectorInventory.accepts(stack); }
            };
            slot.index = i; slots.set(i, slot);
        }
    }
    CollectorBlockEntity sourceBlock() { return source; }
    @Override public boolean stillValid(Player player) {
        return player.isAlive() && (player.level().isClientSide || source != null && source.stillValid(player));
    }
}
