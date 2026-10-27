package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Ghost slots are display samples: no pickup, transfer, clone, throw, or container clear can yield them. */
public final class CollectorFilterMenu extends AbstractContainerMenu {
    public static final int SELECT = 32, MOVE = 64;
    private final SimpleContainer ghosts = new SimpleContainer(CollectorFilter.SIZE);
    private final DataSlot mode = DataSlot.standalone();
    private final Inventory inventory;
    private final ItemStack box;
    private final int locked;
    private final CollectorBlockEntity block;
    private int selected = -1;
    private CollectorFilter selectedSnapshot;
    public CollectorFilterMenu(int id, Inventory inventory) { this(id, inventory, ItemStack.EMPTY, -1, null); }
    public CollectorFilterMenu(int id, Inventory inventory, ItemStack box, int locked, CollectorBlockEntity block) {
        super(LifeSanctuaryMod.COLLECTOR_FILTER_MENU.get(), id);
        this.inventory = inventory; this.box = box; this.locked = locked; this.block = block;
        for (int i = 0; i < CollectorFilter.SIZE; i++) addSlot(new Slot(ghosts, i, 8 + i % 9 * 18, 18 + i / 9 * 18) {
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addPlayerSlot(col + row * 9 + 9, 8 + col * 18, 94 + row * 18);
        for (int col = 0; col < 9; col++) addPlayerSlot(col, 8 + col * 18, 152);
        addDataSlot(mode);
    }
    private void addPlayerSlot(int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override public boolean mayPickup(Player player) { return index != locked; }
            @Override public boolean mayPlace(ItemStack stack) { return index != locked; }
        });
    }
    public CollectorFilter.Mode mode() {
        int value = mode.get(); return value >= 0 && value < CollectorFilter.Mode.values().length ? CollectorFilter.Mode.values()[value] : CollectorFilter.Mode.BROKEN;
    }
    private CollectorFilter read() { return block != null ? block.filter() : CollectorFilter.read(box); }
    private void write(CollectorFilter filter) {
        if (inventory.player.level().isClientSide) return;
        if (block != null) block.setFilter(filter); else { filter.write(box); inventory.setChanged(); }
    }
    @Override public void broadcastChanges() {
        if (!inventory.player.level().isClientSide && stillValid(inventory.player)) {
            var filter = read(); mode.set(filter.mode().ordinal());
            for (int i = 0; i < CollectorFilter.SIZE; i++) {
                var display = filter.entries().get(i).map(CollectorFilter.Rule::display).orElse(ItemStack.EMPTY);
                if (!ItemStack.matches(ghosts.getItem(i), display)) ghosts.setItem(i, display);
            }
        }
        super.broadcastChanges();
    }
    @Override public boolean stillValid(Player player) {
        if (!player.isAlive() || player.isSpectator()) return false;
        if (player.level().isClientSide) return true;
        return block != null ? block.stillValid(player)
                : locked >= 0 && locked < inventory.getContainerSize() && inventory.getItem(locked) == box
                    && box.getCount() == 1 && CollectorInventory.capacity(box) != 0;
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player) || type == ClickType.SWAP && button == locked) return;
        if (slot >= 0 && slot < CollectorFilter.SIZE) {
            resetQuickCraft();
            if (type != ClickType.PICKUP || button < 0 || button > 1 || player.level().isClientSide) return;
            var carried = getCarried(); var before = read();
            if (before.mode() == CollectorFilter.Mode.BROKEN) return;
            if (carried.isEmpty()) {
                if (button == 1) write(before.set(slot, Optional.empty()));
            } else if (CollectorInventory.accepts(carried) && !CollectorFilter.isSupply(carried)) {
                try { write(before.set(slot, Optional.of(CollectorFilter.Rule.from(carried, button == 1)))); }
                catch (IllegalArgumentException ignored) { /* Malformed species samples never rewrite the filter. */ }
            }
            broadcastChanges(); return;
        }
        super.clicked(slot, button, type, player);
    }
    @Override public boolean clickMenuButton(Player player, int button) {
        if (player.level().isClientSide || !stillValid(player) || !getCarried().isEmpty()) return false;
        if (button == 2 && player instanceof net.minecraft.server.level.ServerPlayer server) {
            if (block != null) server.openMenu(block, buffer -> {
                buffer.writeBlockPos(block.getBlockPos()); buffer.writeVarInt(block.getContainerSize() / 9);
            });
            else server.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, user) ->
                    new CollectorItemMenu(id, inv, locked, CollectorInventory.capacity(box) / 9, true), box.getHoverName()), buffer -> {
                        buffer.writeVarInt(locked); buffer.writeVarInt(CollectorInventory.capacity(box) / 9);
                    });
            return true;
        }
        var filter = read();
        if (filter.mode() == CollectorFilter.Mode.BROKEN && button != 1) return false;
        if (button == 0) { write(filter.cycleMode()); selected = -1; }
        else if (button == 1) { write(CollectorFilter.empty()); selected = -1; }
        else if (button >= SELECT && button < SELECT + CollectorFilter.SIZE) {
            selected = button - SELECT; selectedSnapshot = filter; return true;
        } else if (button >= MOVE && button < MOVE + CollectorFilter.SIZE) {
            int source = selected; selected = -1;
            if (source < 0 || !filter.equals(selectedSnapshot)) return false;
            write(filter.move(source, button - MOVE));
        } else return false;
        broadcastChanges(); return true;
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
