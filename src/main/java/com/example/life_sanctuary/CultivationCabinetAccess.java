package com.example.life_sanctuary;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 单次操作的整柜路由；调用者先校验玩家距离／权限或自动化侧面，再创建并立即使用。 */
public final class CultivationCabinetAccess {
    private final ServerLevel level;
    private final CultivationCabinetBlockEntity first;
    private final CultivationCabinetBlockEntity second;
    private final BlockState firstState;
    private final BlockState secondState;
    private final CultivationSlots expected;
    private final CultivationOutputSlotsCodec.ReadResult firstOutputs;
    private final CultivationOutputSlotsCodec.ReadResult secondOutputs;

    private CultivationCabinetAccess(ServerLevel level, CultivationCabinetBlockEntity first,
            CultivationCabinetBlockEntity second, CultivationSlots expected) {
        this.level = level;
        this.first = first;
        this.second = second;
        firstState = first.getBlockState();
        secondState = second == null ? null : second.getBlockState();
        this.expected = expected;
        firstOutputs = first.outputSnapshot();
        secondOutputs = second == null ? null : second.outputSnapshot();
    }

    /** 从任一半柜取得相同的整柜槽序；区块未加载、配对损坏或数据不可用时拒绝。 */
    public static Optional<CultivationCabinetAccess> open(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Cultivation access requires server thread");
        var contents = CultivationCabinetWorldConnection.contents(level, pos).orElse(null);
        if (contents == null || !(level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity own)) return Optional.empty();
        var part = own.getBlockState().getValue(CultivationCabinetBlock.PART);
        if (part == CultivationCabinetPairing.Part.SINGLE)
            return Optional.of(new CultivationCabinetAccess(level, own, null, contents));
        var other = CultivationCabinetWorldConnection.partner(level, pos).orElse(null);
        if (other == null) return Optional.empty();
        return Optional.of(new CultivationCabinetAccess(level,
                part == CultivationCabinetPairing.Part.FIRST ? own : other,
                part == CultivationCabinetPairing.Part.FIRST ? other : own, contents));
    }

    /** 不可变查询快照，不代表已经发出产物；跨 tick 后应为新操作重新 open。 */
    public CultivationSlots snapshot() { return expected; }

    public Optional<CultivationOutputBatch> pendingOutput(int slot) {
        return matches(slot) ? owner(slot).pendingOutput(localSlot(slot)) : Optional.empty();
    }

    public net.minecraft.world.item.ItemStack extractAutomatedStack(int slot, int stackSlot, int amount, boolean simulate) {
        return matches(slot) ? owner(slot).automaticExtract(localSlot(slot), stackSlot, amount, simulate)
                : net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** 菜单持续有效性只核对柜体身份与配对，不因正常计时变化关闭。 */
    public boolean isSameCabinet() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Cultivation access requires server thread");
        return sameEntity(first, firstState) && (second == null || sameEntity(second, secondState));
    }

    public CultivationController.Result start(int slot, UUID batchId, CultivationRecipe recipe,
            CultivationInventoryExchange.Input input) {
        if (!matches(slot)) return CultivationController.Result.REJECTED;
        java.util.Objects.requireNonNull(batchId);
        for (int i = 0; i < expected.capacity(); i++)
            if (expected.batchAt(i).filter(batch -> batch.id().equals(batchId)).isPresent())
                return CultivationController.Result.REJECTED;
        return owner(slot).start(localSlot(slot), batchId, recipe, input);
    }

    public CultivationController.Result extract(int slot, boolean automated, CultivationInventoryExchange.Output output) {
        if (!matches(slot)) return CultivationController.Result.REJECTED;
        return owner(slot).extract(localSlot(slot), automated, output);
    }

    private CultivationCabinetBlockEntity owner(int slot) {
        return slot < CultivationRules.SINGLE_SLOT_COUNT ? first : second;
    }

    private int localSlot(int slot) { return slot % CultivationRules.SINGLE_SLOT_COUNT; }

    private boolean matches(int slot) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Cultivation access requires server thread");
        if (slot < 0 || slot >= expected.capacity() || !isSameCabinet()) return false;
        if (first.outputSnapshot() != firstOutputs || second != null && second.outputSnapshot() != secondOutputs) return false;
        return CultivationCabinetWorldConnection.contents(level, first.getBlockPos()).filter(expected::equals).isPresent();
    }

    private boolean sameEntity(CultivationCabinetBlockEntity entity, BlockState state) {
        var pos = entity.getBlockPos();
        return !entity.isRemoved() && CultivationCabinetWorldConnection.isLoaded(level, pos)
                && level.getBlockEntity(pos) == entity && level.getBlockState(pos).equals(state);
    }
}
