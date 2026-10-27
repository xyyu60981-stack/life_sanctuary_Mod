package com.example.life_sanctuary;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerCopySlot;

/** Server-authoritative portable menu; never copies inventory back on close. */
public final class CollectorItemMenu extends ChestMenu {
    private final ItemStack box;
    private final int lockedInventorySlot;
    private boolean standardOpening;

    public CollectorItemMenu(int id, Inventory inventory, InteractionHand hand, ItemStack box) {
        this(id, inventory, hand == InteractionHand.MAIN_HAND ? inventory.selected : 40, box,
                LifeSanctuaryMod.COLLECTOR_ITEM_MENU.get());
        standardOpening = true;
    }

    public CollectorItemMenu(int id, Inventory inventory, int inventorySlot, int rows) {
        this(id, inventory, inventorySlot, inventory.getItem(inventorySlot),
                LifeSanctuaryMod.COLLECTOR_HOVER_MENU.get(), rows);
    }
    public CollectorItemMenu(int id, Inventory inventory, int inventorySlot, int rows, boolean standardOpening) {
        this(id, inventory, inventorySlot, inventory.getItem(inventorySlot), LifeSanctuaryMod.COLLECTOR_ITEM_MENU.get(), rows);
        this.standardOpening = standardOpening;
    }

    private CollectorItemMenu(int id, Inventory inventory, int inventorySlot, ItemStack box, MenuType<?> type) {
        this(id, inventory, inventorySlot, box, type, CollectorInventory.capacity(box) / 9);
    }

    private CollectorItemMenu(int id, Inventory inventory, int inventorySlot, ItemStack box, MenuType<?> type, int rows) {
        super(type,
                id, inventory, new SimpleContainer(rows * 9), rows);
        this.box = box;
        lockedInventorySlot = inventorySlot;
        var handler = new CollectorItemHandler(box);
        int size = rows * 9;
        for (int i = 0; i < size; i++) {
            Slot old = slots.get(i);
            // Client slots are synchronized by the menu protocol, never written into the
            // client-side box component. The server alone owns the real item handler.
            Slot replacement = inventory.player.level().isClientSide
                    ? new Slot(getContainer(), i, old.x, old.y) {
                        @Override public boolean mayPlace(ItemStack stack) { return CollectorInventory.accepts(stack); }
                    }
                    : new ItemHandlerCopySlot(handler, i, old.x, old.y);
            replacement.index = i;
            slots.set(i, replacement);
        }
        for (int i = size; i < slots.size(); i++) {
            Slot old = slots.get(i);
            if (old.getContainerSlot() != lockedInventorySlot) continue;
            Slot locked = new Slot(inventory, lockedInventorySlot, old.x, old.y) {
                @Override public boolean mayPickup(Player player) { return false; }
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            };
            locked.index = i;
            slots.set(i, locked);
        }
    }

    @Override public boolean stillValid(Player player) {
        ItemStack current = player.getInventory().getItem(lockedInventorySlot);
        if (player.level().isClientSide)
            return player.isAlive() && current.getCount() == 1 && CollectorInventory.capacity(current) == getRowCount() * 9;
        return player.isAlive() && current == box && box.getCount() == 1;
    }

    int sourceInventorySlot() { return lockedInventorySlot; }
    ItemStack sourceBox() { return box; }
    boolean standardOpening() { return standardOpening; }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) return;
        // Number keys and F swap an inventory slot that may not be the clicked slot.
        if (type == ClickType.SWAP && button == lockedInventorySlot) return;
        super.clicked(slot, button, type, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()
                || !slots.get(index).mayPickup(player)) return ItemStack.EMPTY;
        return super.quickMoveStack(player, index);
    }
}
