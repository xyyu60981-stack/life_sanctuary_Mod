package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 槽内物品仅是服务器预览，真实批次归方块实体；所有培育槽点击走显式交换事务。 */
public final class CultivationCabinetMenu extends AbstractContainerMenu {
    private static final int DISPLAY_FIELDS = 5;
    private static final String PREVIEW_SPECIES = "CultivationPreviewSpecies";
    private final Inventory inventory;
    private final BlockPos pos;
    private final int capacity;
    private final SimpleContainer preview;
    private final SimpleContainerData display;
    private final CultivationCabinetAccess binding;
    private record PreviewKey(java.util.UUID id, CultivationRecipe recipe, int phase, boolean ready, boolean failed,
            Optional<CultivationBatch.Product> product, boolean mapped, CultivationOutputBatch pending) {}
    private final PreviewKey[] previewKeys;

    public CultivationCabinetMenu(int id, Inventory inventory, BlockPos pos, int capacity) {
        super(LifeSanctuaryMod.CULTIVATION_MENU.get(), id);
        if (!CultivationRules.validSlotCount(capacity)) throw new IllegalArgumentException("Invalid cultivation menu size");
        this.inventory = inventory;
        this.pos = pos.immutable();
        this.capacity = capacity;
        previewKeys = new PreviewKey[capacity];
        preview = new SimpleContainer(capacity);
        display = new SimpleContainerData(capacity * DISPLAY_FIELDS);
        binding = inventory.player.level() instanceof ServerLevel server
                ? CultivationCabinetAccess.open(server, pos).orElse(null) : null;
        for (int i = 0; i < capacity; i++) addSlot(new Slot(preview, i, 92 - capacity * 12 + i * 24, 30) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public ItemStack remove(int amount) { return ItemStack.EMPTY; }
        });
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        addDataSlots(display);
    }

    public int capacity() { return capacity; }
    public int progress(int slot) { return display.get(slot * DISPLAY_FIELDS); }
    public int state(int slot) { return display.get(slot * DISPLAY_FIELDS + 1); }
    public int remainingProducts(int slot) { return display.get(slot * DISPLAY_FIELDS + 2); }
    public int phaseNumber(int slot) { return display.get(slot * DISPLAY_FIELDS + 3); }
    public int phaseCount(int slot) { return display.get(slot * DISPLAY_FIELDS + 4); }

    /** 只解释服务器同步的预览标签；不能用来决定输入物种、产物或采集效果。 */
    public Optional<net.minecraft.network.chat.Component> speciesName(int slot) {
        var tag = preview.getItem(slot).getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if (!tag.contains(PREVIEW_SPECIES, net.minecraft.nbt.Tag.TAG_STRING)) return Optional.empty();
        var species = net.minecraft.resources.ResourceLocation.tryParse(tag.getString(PREVIEW_SPECIES));
        if (species == null) return Optional.empty();
        return Optional.of(net.minecraft.network.chat.Component.translatableWithFallback(
                "species." + species.getNamespace() + "." + species.getPath(), species.toString()));
    }

    @Override public boolean stillValid(Player player) {
        if (player != inventory.player || !player.isAlive() || player.isSpectator()
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64) return false;
        if (!(player.level() instanceof ServerLevel server)) return true;
        if (binding == null || !binding.isSameCabinet() || !permitted(server, pos, player)) return false;
        var other = CultivationCabinetWorldConnection.partner(server, pos);
        if (other.isPresent() && !permitted(server, other.get().getBlockPos(), player)) return false;
        return CultivationCabinetAccess.open(server, pos).filter(access -> access.snapshot().capacity() == capacity).isPresent();
    }

    private static boolean permitted(ServerLevel server, BlockPos pos, Player player) {
        return player.mayBuild() && server.mayInteract(player, pos)
                && player.mayUseItemAt(pos, Direction.UP, ItemStack.EMPTY);
    }

    @Override public boolean canDragTo(Slot slot) { return slot.container != preview; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return slot.container != preview; }

    @Override public void clicked(int index, int button, ClickType type, Player player) {
        if (!stillValid(player)) return;
        if (index >= 0 && index < capacity) {
            if (player.level() instanceof ServerLevel && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
                if (type == ClickType.QUICK_MOVE) extract(index, true);
                else if (button == 0 || button == 1) {
                    if (getCarried().isEmpty()) extract(index, false);
                    else insertCursor(index);
                }
                broadcastChanges();
            }
            return;
        }
        super.clicked(index, button, type, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || !(player.level() instanceof ServerLevel server) || index < capacity || index >= slots.size())
            return ItemStack.EMPTY;
        var source = slots.get(index);
        var stack = source.getItem();
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (!ReproductiveBagKind.SPORE.isFilledBag(stack)) {
            var original = stack.copy();
            boolean moved = index < capacity + 27
                    ? moveItemStackTo(stack, capacity + 27, capacity + 36, false)
                    : moveItemStackTo(stack, capacity, capacity + 27, false);
            if (!moved) return ItemStack.EMPTY;
            if (stack.isEmpty()) source.set(ItemStack.EMPTY); else source.setChanged();
            return original;
        }
        var recipe = recipe(stack);
        var access = CultivationCabinetAccess.open(server, pos).orElse(null);
        if (recipe == null) {
            if (ReproductiveBagKind.SPORE.isFilledBag(stack)) recipeUnavailable();
            return ItemStack.EMPTY;
        }
        if (access == null) return ItemStack.EMPTY;
        for (int i = 0; i < capacity; i++) {
            if (!access.snapshot().canInsertAt(i)) continue;
            access.start(i, java.util.UUID.randomUUID(), recipe,
                    new CultivationSporeBagInput(inventory, source.getContainerSlot(), stack));
            break;
        }
        // 事务已直接改真实库存，禁止原版按返回栈再次搬运或循环投入。
        return ItemStack.EMPTY;
    }

    private void insertCursor(int slot) {
        var recipe = recipe(getCarried());
        if (recipe == null) {
            if (ReproductiveBagKind.SPORE.isFilledBag(getCarried())) recipeUnavailable();
            return;
        }
        if (!(inventory.player.level() instanceof ServerLevel server)) return;
        var access = CultivationCabinetAccess.open(server, pos).orElse(null);
        if (access == null) return;
        var expected = getCarried().copy();
        access.start(slot, java.util.UUID.randomUUID(), recipe, species -> {
            if (!CultivationSporeBagInput.matchesSpecies(getCarried(), species) || !same(getCarried(), expected)) return false;
            setCarried(expected.getCount() == 1 ? ItemStack.EMPTY : expected.copyWithCount(expected.getCount() - 1));
            return true;
        });
    }

    private static CultivationRecipe recipe(ItemStack stack) {
        return CultivationRecipes.forSporeBag(stack).filter(CultivationProducts::isReady).orElse(null);
    }

    private void recipeUnavailable() {
        inventory.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.life_sanctuary.cultivation.recipe_unavailable"), true);
    }

    private void extract(int slot, boolean toInventory) {
        if (!(inventory.player.level() instanceof ServerLevel server)) return;
        var access = CultivationCabinetAccess.open(server, pos).orElse(null);
        if (access == null) return;
        if (toInventory) {
            access.extract(slot, false, new CultivationContainerOutput(inventory,
                    java.util.stream.IntStream.range(0, 36).boxed().toList()));
        } else {
            var expected = getCarried().copy();
            access.extract(slot, false, new CultivationInventoryExchange.Output() {
                @Override public boolean deliver(CultivationBatch.Product product) {
                    return CultivationProducts.resolve(product).map(this::deliverStored).orElse(false);
                }
                @Override public boolean deliverStored(java.util.List<ItemStack> stacks) {
                    if (!same(getCarried(), expected) || stacks.size() != 1) return false;
                    var incoming = stacks.getFirst();
                    if (!expected.isEmpty() && !ItemStack.isSameItemSameComponents(expected, incoming)) return false;
                    int count = expected.getCount() + incoming.getCount();
                    if (count > incoming.getMaxStackSize()) return false;
                    setCarried(incoming.copyWithCount(count));
                    return true;
                }
            });
        }
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.isEmpty() && b.isEmpty() || a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }

    @Override public void broadcastChanges() {
        if (inventory.player.level() instanceof ServerLevel server && stillValid(inventory.player)) {
            var access = CultivationCabinetAccess.open(server, pos).orElse(null);
            if (access != null) {
                boolean powered = server.hasNeighborSignal(pos) || CultivationCabinetWorldConnection.partner(server, pos)
                        .filter(other -> server.hasNeighborSignal(other.getBlockPos())).isPresent();
                for (int i = 0; i < capacity; i++) {
                    var batch = access.snapshot().batchAt(i).orElse(null);
                    int progress = 0, state = 0, remaining = 0, phaseNumber = 0, phaseCount = 0;
                    if (batch != null) {
                        var pending = access.pendingOutput(i).orElse(null);
                        // 仅用于短整型菜单字段展示，不修改原配方或阶段下标。
                        phaseNumber = Math.min(Short.MAX_VALUE, batch.phaseIndex() + 1);
                        phaseCount = Math.min(Short.MAX_VALUE, batch.recipe().phases().size());
                        progress = (int) (1000.0 * batch.progress().elapsedTicks() / batch.progress().targetTicks());
                        state = batch.progress().failed() ? 2 : !CultivationProducts.isAvailable(batch.recipe().species(), batch.phase().output())
                                ? 3 : batch.finalReady() ? 4 : !powered ? 5 : 1;
                        if (pending != null) state = pending.failed() ? 2 : 4;
                        if (!batch.progress().failed() && batch.progress().ready() && !batch.finalReady()
                                && !CultivationProducts.isAvailable(batch.recipe().species(),
                                        batch.recipe().phases().get(batch.phaseIndex() + 1).output())) state = 3;
                        var product = batch.manualProduct();
                        boolean mapped = product.filter(value -> CultivationProducts.isAvailable(value.species(), value.output())).isPresent();
                        remaining = pending != null ? pending.remainingCount() : mapped ? product.orElseThrow().count() : 0;
                        var key = new PreviewKey(batch.id(), batch.recipe(), batch.phaseIndex(), batch.progress().ready(),
                                batch.progress().failed(), product, mapped, pending);
                        if (!key.equals(previewKeys[i])) {
                            var item = pending != null ? pending.remainingStacks().getFirst() : product.flatMap(CultivationProducts::resolve).filter(list -> !list.isEmpty())
                                    .map(list -> list.getFirst().copy())
                                    .orElseGet(() -> SporeBagItem.filled(batch.recipe().species().toString()));
                            // 仅修改预览副本，不向真实袋子、产物模板或输出缓冲写入显示字段。
                            var metadata = item.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                            metadata.putString(PREVIEW_SPECIES, batch.recipe().species().toString());
                            item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                    net.minecraft.world.item.component.CustomData.of(metadata));
                            if (!same(preview.getItem(i), item)) preview.setItem(i, item);
                            previewKeys[i] = key;
                        }
                    } else if (previewKeys[i] != null) {
                        preview.setItem(i, ItemStack.EMPTY);
                        previewKeys[i] = null;
                    }
                    display.set(i * DISPLAY_FIELDS, progress);
                    display.set(i * DISPLAY_FIELDS + 1, state);
                    display.set(i * DISPLAY_FIELDS + 2, remaining);
                    display.set(i * DISPLAY_FIELDS + 3, phaseNumber);
                    display.set(i * DISPLAY_FIELDS + 4, phaseCount);
                }
            }
        }
        super.broadcastChanges();
    }
}
