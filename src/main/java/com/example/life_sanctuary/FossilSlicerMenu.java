package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Every motion command is server-checked against this live menu, block, input snapshot and lease. */
public final class FossilSlicerMenu extends AbstractContainerMenu {
    public static final int NO_INPUT = 0, READY = 1, ACTIVE = 2, OUTPUT_FULL = 3, BUSY = 4;
    private final Inventory inventory;
    private final BlockPos pos;
    private final FossilSlicerBlockEntity block;
    private final Container storage;
    private final SimpleContainerData display = new SimpleContainerData(3);
    private FossilSlicingRules.Stroke stroke;
    private long workRevision;
    private ItemStack expected = ItemStack.EMPTY, product = ItemStack.EMPTY;
    private ItemStack previewInput = ItemStack.EMPTY, previewProduct = ItemStack.EMPTY;
    public FossilSlicerMenu(int id, Inventory inventory, BlockPos pos) { this(id, inventory, pos, new SimpleContainer(2), null); }
    public FossilSlicerMenu(int id, Inventory inventory, FossilSlicerBlockEntity block) { this(id, inventory, block.getBlockPos(), block, block); }
    private FossilSlicerMenu(int id, Inventory inventory, BlockPos pos, Container storage, FossilSlicerBlockEntity block) {
        super(LifeSanctuaryMod.FOSSIL_SLICER_MENU.get(), id); this.inventory = inventory; this.pos = pos.immutable(); this.storage = storage; this.block = block;
        addSlot(new Slot(storage, 0, 16, 48) { @Override public boolean mayPlace(ItemStack stack) { return FossilSlicerBlockEntity.isInput(stack); } });
        addSlot(new Slot(storage, 1, 80, 48) { @Override public boolean mayPlace(ItemStack stack) { return false; } });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, 126 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 184));
        addDataSlots(display);
    }
    public int progress() { return Math.max(0, Math.min(FossilSlicingRules.END, display.get(0))); }
    public int state() { return display.get(1); }
    public int revision() { return display.get(2); }
    private ItemStack preview() {
        var input = storage.getItem(1);
        if (!ItemStack.isSameItemSameComponents(input, previewInput)) {
            previewInput = input.isEmpty() ? ItemStack.EMPTY : input.copyWithCount(1);
            previewProduct = LifeSanctuaryMod.FOSSIL_SLICE.get().fromInput(input);
        }
        return previewProduct;
    }
    @Override public boolean stillValid(Player player) {
        if (player != inventory.player || !player.isAlive() || player.isSpectator()) return false;
        if (player.level().isClientSide) return true;
        return block != null && player.level() == block.getLevel() && player.level().getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && player.level().getBlockEntity(pos) == block && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }
    boolean ownsWork() {
        return stroke != null && stroke.active() && inventory.player.containerMenu == this && stillValid(inventory.player)
                && block.workRevision() == workRevision && FossilSlicerBlockEntity.isInput(storage.getItem(1))
                && ItemStack.isSameItemSameComponents(expected, storage.getItem(1));
    }
    private void cancel() {
        stroke = null; expected = ItemStack.EMPTY; product = ItemStack.EMPTY;
        if (block != null) block.release(this);
        display.set(0, 0); display.set(2, (display.get(2) + 1) & 32767);
    }
    @Override public void broadcastChanges() {
        if (!inventory.player.level().isClientSide) {
            if (stroke != null && (!ownsWork() || !block.ownedBy(this))) cancel();
            int state = NO_INPUT;
            if (block != null && stillValid(inventory.player)) {
                var preview = preview();
                if (block.busy(this)) state = BUSY;
                else if (FossilSlicerBlockEntity.isFinished(storage.getItem(1))) state = OUTPUT_FULL;
                else if (!preview.isEmpty()) state = !block.canFit(preview) ? OUTPUT_FULL : stroke != null ? ACTIVE : READY;
            }
            display.set(1, state); display.set(0, stroke == null ? 0 : stroke.position());
        }
        super.broadcastChanges();
    }
    @Override public boolean clickMenuButton(Player player, int command) {
        if (player.level().isClientSide || !stillValid(player) || player.containerMenu != this) return false;
        long tick = Integer.toUnsignedLong(player.getServer().getTickCount());
        if (command == FossilSlicingRules.CANCEL) { if (stroke != null) cancel(); broadcastChanges(); return true; }
        if (!getCarried().isEmpty()) return false;
        if (command == FossilSlicingRules.BEGIN) {
            if (stroke != null) return false;
            var proposed = preview();
            if (proposed.isEmpty() || !block.canFit(proposed) || !block.acquire(this)) return false;
            expected = storage.getItem(1).copyWithCount(1); product = proposed.copy(); stroke = FossilSlicingRules.Stroke.begin(tick);
            workRevision = block.workRevision();
        } else {
            if (command < FossilSlicingRules.MOVE_BASE || command > FossilSlicingRules.MOVE_BASE + FossilSlicingRules.END
                    || !ownsWork() || !block.ownedBy(this)) return false;
            var next = stroke.move(command - FossilSlicingRules.MOVE_BASE, tick);
            if (next.isEmpty()) return false;
            stroke = next.get();
            if (stroke.complete()) {
                boolean completed = block.finish(this, expected, product); cancel();
                if (completed) player.level().playSound(null, pos, net.minecraft.sounds.SoundEvents.UI_STONECUTTER_TAKE_RESULT,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.75F, 1);
            }
        }
        broadcastChanges(); return true;
    }
    @Override public void removed(Player player) { if (!player.level().isClientSide) cancel(); super.removed(player); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size() || !slots.get(index).hasItem()) return ItemStack.EMPTY;
        var slot = slots.get(index); var item = slot.getItem(); var original = item.copy();
        if (index < 2) { if (!moveItemStackTo(item, 2, slots.size(), true)) return ItemStack.EMPTY; }
        else if (!FossilSlicerBlockEntity.isInput(item) || !moveItemStackTo(item, 0, 1, false)) return ItemStack.EMPTY;
        if (item.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, item); return original;
    }
}
