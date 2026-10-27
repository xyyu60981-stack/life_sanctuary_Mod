package com.example.life_sanctuary;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 培养柜服务器运行与保存基础。
 * 不在构造／读取 NBT 的线程上创建控制器；仅在服务器实际调用时绑定所属线程。
 */
public final class CultivationCabinetBlockEntity extends BlockEntity {
    private static final String DATA_KEY = "Cultivation";
    public final CultivationCabinetInputHandler inputHandler = new CultivationCabinetInputHandler(this);
    public final CultivationCabinetOutputHandler outputHandler = new CultivationCabinetOutputHandler(this);
    private CultivationSlotsCodec.ReadResult stored = new CultivationSlotsCodec.Loaded(CultivationSlots.empty());
    private CultivationOutputSlotsCodec.ReadResult outputs = new CultivationOutputSlotsCodec.Loaded(CultivationOutputSlots.empty());
    private boolean outputApplying;
    private CultivationController controller;
    private boolean powerKnown;
    private boolean powered;
    private boolean hasTicked;
    private long lastTick;

    public CultivationCabinetBlockEntity(BlockPos pos, BlockState state) {
        this(LifeSanctuaryMod.CULTIVATION_BLOCK_ENTITY.get(), pos, state);
    }

    public CultivationCabinetBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override public void onLoad() {
        super.onLoad();
        powerKnown = false;
        hasTicked = false;
        if (level instanceof ServerLevel server && getBlockState().getBlock() instanceof CultivationCabinetBlock)
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        if (level instanceof ServerLevel server) {
            server.invalidateCapabilities(worldPosition);
            CultivationCabinetWorldConnection.partner(server, worldPosition)
                    .ifPresent(other -> server.invalidateCapabilities(other.getBlockPos()));
        }
    }

    private CultivationController controller() {
        if (!(level instanceof ServerLevel) || isRemoved())
            throw new IllegalStateException("Cultivation cabinet requires an active server block entity");
        if (controller == null) controller = new CultivationController(stored);
        return controller;
    }

    /** 未来方块邻居更新回调只令缓存失效，下一活动 tick 才重新查询红石。 */
    public void invalidatePower() { powerKnown = false; }
    public java.util.Optional<Boolean> cachedPower() { return powerKnown ? java.util.Optional.of(powered) : java.util.Optional.empty(); }

    /** 每半柜始终只拥有本地两槽；连接视图不搬移或复制批次。 */
    java.util.Optional<CultivationSlots> localSlots() {
        if (outputApplying || controller != null && controller.isApplying()) return java.util.Optional.empty();
        var snapshot = controller == null ? stored : controller.snapshot();
        if (!(snapshot instanceof CultivationSlotsCodec.Loaded loaded) || loaded.state().isReadOnly()
                || loaded.state().capacity() != CultivationRules.SINGLE_SLOT_COUNT
                || !(outputs instanceof CultivationOutputSlotsCodec.Loaded ready) || ready.state().isReadOnly()) return java.util.Optional.empty();
        if (ready.state().slots().stream().allMatch(java.util.Optional::isEmpty)) return java.util.Optional.of(loaded.state());
        var view = new java.util.ArrayList<>(loaded.state().slots());
        for (int i = 0; i < CultivationRules.SINGLE_SLOT_COUNT; i++) {
            var pending = ready.state().batchAt(i).orElse(null);
            if (pending == null) continue;
            if (view.get(i).isPresent()) return java.util.Optional.empty();
            var source = pending.failed() ? pending.source().freezeForRecovery() : pending.source();
            view.set(i, java.util.Optional.of(new CultivationBatchCodec.Loaded(source)));
        }
        try { return java.util.Optional.of(new CultivationSlots(view)); }
        catch (IllegalArgumentException conflict) { return java.util.Optional.empty(); }
    }

    CultivationOutputSlotsCodec.ReadResult outputSnapshot() { return outputs; }
    java.util.Optional<CultivationOutputBatch> pendingOutput(int slot) {
        return outputs instanceof CultivationOutputSlotsCodec.Loaded ready ? ready.state().batchAt(slot) : java.util.Optional.empty();
    }

    private boolean localPower(ServerLevel server) {
        if (!powerKnown) {
            powered = server.hasNeighborSignal(worldPosition);
            powerKnown = true;
        }
        return powered;
    }

    /** 柜体掉落物保留整个批次快照；未知存档也原样保留，不转换成空柜。 */
    public void writeToItem(net.minecraft.world.item.ItemStack stack) {
        var tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        saveCabinet(tag, java.util.Objects.requireNonNull(level).registryAccess());
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
    }

    public boolean hasStoredContents() {
        var snapshot = controller == null ? stored : controller.snapshot();
        return !(snapshot instanceof CultivationSlotsCodec.Loaded loaded)
                || loaded.state().slots().stream().anyMatch(java.util.Optional::isPresent)
                || !(outputs instanceof CultivationOutputSlotsCodec.Loaded ready)
                || ready.state().slots().stream().anyMatch(java.util.Optional::isPresent);
    }

    /** 仅服务端放置时调用；当前独立柜不能执行带有四槽旧数据的任务，但仍保留原数据。 */
    public void restoreFromItem(net.minecraft.world.item.ItemStack stack) {
        if (!(level instanceof ServerLevel)) return;
        var tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if (!tag.contains(DATA_KEY) && !tag.contains("Outputs") && !tag.contains("CabinetVersion")) return;
        var saved = tag.getCompound(DATA_KEY);
        loadCabinet(tag, level.registryAccess());
        if (stored instanceof CultivationSlotsCodec.Loaded loaded
                && loaded.state().capacity() != CultivationRules.SINGLE_SLOT_COUNT)
            stored = new CultivationSlotsCodec.Preserved(saved, "Four-slot data requires an attached large cabinet");
        controller = null;
        powerKnown = false;
        hasTicked = false;
        setChanged();
    }

    /** 由未来方块的服务器 ticker 调用；同一实体同一世界 tick 重入不会重复累计。 */
    public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, CultivationCabinetBlockEntity cabinet) {
        if (cabinet.level != level || !cabinet.worldPosition.equals(pos) || cabinet.isRemoved()) return;
        long now = level.getGameTime();
        if (cabinet.hasTicked && cabinet.lastTick == now) return;
        cabinet.hasTicked = true;
        cabinet.lastTick = now;
        var local = cabinet.localSlots().orElse(null);
        if (local == null) return;
        boolean needsTick = false;
        for (int i = 0; i < local.capacity(); i++) {
            var batch = local.batchAt(i).orElse(null);
            if (batch != null && !batch.progress().failed() && !batch.finalReady()) { needsTick = true; break; }
        }
        // 空柜、全部完成或全部冻结时，不再查邻柜、红石或创建控制器；配对校验由方块更新处理。
        if (!needsTick) return;
        var partner = CultivationCabinetWorldConnection.partner(level, pos);
        if (state.getValue(CultivationCabinetBlock.PART) != CultivationCabinetPairing.Part.SINGLE
                && partner.isEmpty()) return;
        if (partner.filter(other -> other.localSlots().isEmpty()).isPresent()) return;
        boolean running = cabinet.localPower(level);
        if (partner.isPresent()) running |= partner.get().localPower(level);
        if (!running) return;
        var control = cabinet.controller();
        int before = cabinet.comparatorSignal();
        cabinet.finish(control.tick(true, level.random), before);
    }

    /** 手工或自动化调用方先检查访问权限及所属柜体，不能跨柜借用本方法。 */
    public CultivationController.Result start(int slot, UUID batchId, CultivationRecipe recipe,
            CultivationInventoryExchange.Input input) {
        var control = controller();
        if (CultivationCabinetWorldConnection.contents((ServerLevel) level, worldPosition).isEmpty())
            return CultivationController.Result.REJECTED;
        if (slot < 0 || slot >= CultivationRules.SINGLE_SLOT_COUNT
                || outputs instanceof CultivationOutputSlotsCodec.Loaded ready && ready.state().occupies(slot))
            return CultivationController.Result.REJECTED;
        if (!(control.snapshot() instanceof CultivationSlotsCodec.Loaded loaded)) return CultivationController.Result.REJECTED;
        int before = comparatorSignal();
        return finish(control.start(loaded.state(), slot, batchId, recipe, ((ServerLevel) level).random, input), before);
    }

    public CultivationController.Result extract(int slot, boolean automated, CultivationInventoryExchange.Output output) {
        var control = controller();
        if (CultivationCabinetWorldConnection.contents((ServerLevel) level, worldPosition).isEmpty())
            return CultivationController.Result.REJECTED;
        if (slot < 0 || slot >= CultivationRules.SINGLE_SLOT_COUNT) return CultivationController.Result.REJECTED;
        var pending = pendingOutput(slot).orElse(null);
        if (pending != null) {
            if (pending.failed() || !(outputs instanceof CultivationOutputSlotsCodec.Loaded ready)) return CultivationController.Result.REJECTED;
            int before = comparatorSignal();
            var after = ready.state().remove(slot, pending).orElseThrow();
            CultivationController.Result result;
            outputApplying = true;
            try {
                if (!output.deliverStored(pending.remainingStacks())) return CultivationController.Result.REJECTED;
                outputs = new CultivationOutputSlotsCodec.Loaded(after);
                result = CultivationController.Result.CHANGED;
            } catch (RuntimeException failure) {
                outputs = new CultivationOutputSlotsCodec.Loaded(ready.state().freeze(slot, pending));
                LifeSanctuaryMod.LOGGER.error("Cultivation buffered delivery uncertain for " + pending.id(), failure);
                result = CultivationController.Result.RECOVERY_REQUIRED;
            } finally { outputApplying = false; }
            return finish(result, before);
        }
        if (!(control.snapshot() instanceof CultivationSlotsCodec.Loaded loaded)) return CultivationController.Result.REJECTED;
        int before = comparatorSignal();
        return finish(control.extract(loaded.state(), slot, automated, output), before);
    }

    /** 下方自动化只取最终实物。模拟不物化；真实首次取物同时清任务、存余量。 */
    net.minecraft.world.item.ItemStack automaticExtract(int slot, int stackSlot, int amount, boolean simulate) {
        if (slot < 0 || slot >= CultivationRules.SINGLE_SLOT_COUNT || stackSlot < 0
                || stackSlot >= CultivationOutputBatch.STACK_SLOTS || amount <= 0
                || !(level instanceof ServerLevel server)
                || CultivationCabinetWorldConnection.contents(server, worldPosition).isEmpty()
                || !(outputs instanceof CultivationOutputSlotsCodec.Loaded ready)) return net.minecraft.world.item.ItemStack.EMPTY;
        var pending = ready.state().batchAt(slot).orElse(null);
        var rawData = controller == null ? stored : controller.snapshot();
        CultivationSlots raw = rawData instanceof CultivationSlotsCodec.Loaded loaded ? loaded.state() : null;
        if (raw == null) return net.minecraft.world.item.ItemStack.EMPTY;
        var source = raw.batchAt(slot).orElse(null);
        var prepared = pending != null ? pending : source == null ? null : CultivationOutputBatch.prepare(source).orElse(null);
        if (prepared == null || prepared.failed()) return net.minecraft.world.item.ItemStack.EMPTY;
        var taken = prepared.extract(stackSlot, amount).orElse(null);
        if (taken == null) return net.minecraft.world.item.ItemStack.EMPTY;
        if (simulate) return taken.stack();
        var control = controller();
        int before = comparatorSignal();
        var deposited = pending == null ? ready.state().deposit(slot, source, prepared).orElse(null) : ready.state();
        if (deposited == null) return net.minecraft.world.item.ItemStack.EMPTY;
        var next = deposited.extract(slot, prepared, stackSlot, amount).orElseThrow();
        CultivationController.Result result = CultivationController.Result.CHANGED;
        outputApplying = true;
        try {
            if (pending == null) result = control.extract(raw, slot, true, product -> product.equals(prepared.product()));
            if (result == CultivationController.Result.CHANGED) outputs = new CultivationOutputSlotsCodec.Loaded(next.after());
        } finally { outputApplying = false; }
        finish(result, before);
        return result == CultivationController.Result.CHANGED ? next.stack() : net.minecraft.world.item.ItemStack.EMPTY;
    }

    public CultivationStatus.Snapshot status(boolean currentPower) {
        var control = controller();
        var view = localSlots().orElse(null);
        if (view == null) return new CultivationStatus.Snapshot(0, currentPower, true, outputApplying || control.isApplying(),
                0, java.util.List.of(), java.util.Optional.of("Cultivation cabinet data or transaction unavailable"));
        var base = CultivationStatus.inspect(new CultivationSlotsCodec.Loaded(view), currentPower, false);
        var slots = new java.util.ArrayList<CultivationStatus.Slot>();
        for (var slot : base.slots()) {
            var pending = pendingOutput(slot.index()).orElse(null);
            if (pending == null) slots.add(slot);
            else slots.add(new CultivationStatus.Slot(slot.index(), pending.failed() ? CultivationStatus.State.RECOVERY_REQUIRED
                    : CultivationStatus.State.FINAL_READY, slot.batch(), java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.List.of(), java.util.Optional.of(new CultivationStatus.BufferedProduct(pending.product().species(),
                            pending.product().phase(), pending.product().output(), pending.remainingCount(), pending.failed()))));
        }
        return new CultivationStatus.Snapshot(base.capacity(), currentPower, false, false, base.comparatorSignal(), slots, base.diagnostic());
    }

    public int comparatorSignal() {
        return localSlots().map(CultivationSlots::comparatorSignal).orElse(0);
    }

    private CultivationController.Result finish(CultivationController.Result result, int previousSignal) {
        if (result == CultivationController.Result.CHANGED || result == CultivationController.Result.RECOVERY_REQUIRED) {
            stored = controller.snapshot();
            // 原版 setChanged 同时通知比较器；计时每刻变化只需标脏区块，避免重复邻居更新。
            if (level != null) level.blockEntityChanged(worldPosition);
            int currentSignal = comparatorSignal();
            if (level != null && previousSignal != currentSignal)
                level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            if (level instanceof ServerLevel server && previousSignal != currentSignal)
                CultivationCabinetWorldConnection.partner(server, worldPosition).ifPresent(other ->
                        server.updateNeighbourForOutputSignal(other.getBlockPos(), other.getBlockState().getBlock()));
        }
        return result;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        saveCabinet(tag, registries);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 新建实体由字段初始化两槽；已有存档字段缺失／类型错误不可当作新空柜。
        loadCabinet(tag, registries);
    }

    private void saveCabinet(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("CabinetVersion", 1);
        tag.put(DATA_KEY, CultivationSlotsCodec.write(controller == null ? stored : controller.snapshot()));
        tag.put("Outputs", CultivationOutputSlotsCodec.write(outputs, registries));
    }

    private void loadCabinet(CompoundTag tag, HolderLookup.Provider registries) {
        boolean legacy = !tag.contains("CabinetVersion") && !tag.contains("Outputs");
        if (!legacy && (!tag.contains("CabinetVersion", net.minecraft.nbt.Tag.TAG_INT) || tag.getInt("CabinetVersion") != 1)) {
            stored = new CultivationSlotsCodec.Preserved(tag, "Unsupported cabinet envelope version");
            outputs = new CultivationOutputSlotsCodec.Loaded(CultivationOutputSlots.empty());
        } else {
            stored = tag.contains(DATA_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)
                    ? CultivationSlotsCodec.read(tag.getCompound(DATA_KEY))
                    : new CultivationSlotsCodec.Preserved(tag, "Missing or invalid cultivation cabinet data");
            outputs = legacy ? new CultivationOutputSlotsCodec.Loaded(CultivationOutputSlots.empty())
                    : tag.contains("Outputs", net.minecraft.nbt.Tag.TAG_COMPOUND)
                    ? CultivationOutputSlotsCodec.read(tag.getCompound("Outputs"), registries)
                    : new CultivationOutputSlotsCodec.Preserved(tag, "Missing cabinet output groups");
        }
        controller = null;
        outputApplying = false;
        powerKnown = false;
        hasTicked = false;
    }
}
