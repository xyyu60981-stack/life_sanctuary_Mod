package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 世界中的双柜配对。只查已加载位置，所有培育数据仍归各半柜自己的实体。 */
public final class CultivationCabinetWorldConnection {
    private CultivationCabinetWorldConnection() {}

    /** 直接查询区块缓存，不使用该版本已弃用的 LevelReader.hasChunkAt。 */
    static boolean isLoaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static CultivationCabinetPairing.Half half(BlockPos pos, BlockState state) {
        return new CultivationCabinetPairing.Half(pos, state.getValue(CultivationCabinetBlock.FACING),
                state.getValue(CultivationCabinetBlock.PART));
    }

    public static Optional<CultivationCabinetBlockEntity> partner(ServerLevel level, BlockPos pos) {
        if (!isLoaded(level, pos)) return Optional.empty();
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CultivationCabinetBlock)) return Optional.empty();
        var otherPos = half(pos, state).partnerPos().orElse(null);
        if (otherPos == null || !isLoaded(level, otherPos)) return Optional.empty();
        var otherState = level.getBlockState(otherPos);
        if (!otherState.is(state.getBlock())
                || CultivationCabinetPairing.existing(half(pos, state), half(otherPos, otherState)).isEmpty()) return Optional.empty();
        return level.getBlockEntity(otherPos) instanceof CultivationCabinetBlockEntity other && !other.isRemoved()
                ? Optional.of(other) : Optional.empty();
    }

    /** 仅需求时组合四槽视图，不保存组合副本、不创建第二份可运行的四槽控制器。 */
    public static Optional<CultivationSlots> contents(ServerLevel level, BlockPos pos) {
        if (!isLoaded(level, pos) || !(level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity own)) return Optional.empty();
        var local = own.localSlots();
        if (local.isEmpty()) return Optional.empty();
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CultivationCabinetBlock)) return Optional.empty();
        if (state.getValue(CultivationCabinetBlock.PART) == CultivationCabinetPairing.Part.SINGLE) return local;
        return partner(level, pos).flatMap(other -> other.localSlots().flatMap(otherData ->
                state.getValue(CultivationCabinetBlock.PART) == CultivationCabinetPairing.Part.FIRST
                        ? CultivationCabinetConnection.join(local.get(), otherData)
                        : CultivationCabinetConnection.join(otherData, local.get())));
    }

    public static int comparatorSignal(ServerLevel level, BlockPos pos) {
        return contents(level, pos).map(CultivationSlots::comparatorSignal).orElse(0);
    }

    public static net.minecraft.network.chat.Component displayName(ServerLevel level, BlockPos pos) {
        boolean large = isLoaded(level, pos) && level.getBlockState(pos).getBlock() instanceof CultivationCabinetBlock
                && level.getBlockState(pos).getValue(CultivationCabinetBlock.PART) != CultivationCabinetPairing.Part.SINGLE;
        return net.minecraft.network.chat.Component.translatable(large
                ? "container.life_sanctuary.large_horizontal_light_cultivation_cabinet"
                : "block.life_sanctuary.horizontal_light_cultivation_cabinet");
    }

    public static void reconcile(ServerLevel level, BlockPos pos) {
        if (!isLoaded(level, pos)) return;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CultivationCabinetBlock)
                || !(level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity own)) return;
        var current = half(pos, state);
        if (current.part() != CultivationCabinetPairing.Part.SINGLE) {
            var otherPos = current.partnerPos().orElseThrow();
            // 区块未加载不是柜体被拆除，保留配对并暂停运行。
            if (!isLoaded(level, otherPos) || partner(level, pos).isPresent()) return;
            level.setBlock(pos, state.setValue(CultivationCabinetBlock.PART, CultivationCabinetPairing.Part.SINGLE), Block.UPDATE_ALL);
            own.invalidatePower();
            level.invalidateCapabilities(pos);
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
            return;
        }
        var ownData = own.localSlots().orElse(null);
        if (ownData == null) return;
        for (var direction : new net.minecraft.core.Direction[] {current.facing().getClockWise(), current.facing().getCounterClockWise()}) {
            var otherPos = pos.relative(direction);
            if (!isLoaded(level, otherPos)) continue;
            var otherState = level.getBlockState(otherPos);
            if (!otherState.is(state.getBlock()) || !(level.getBlockEntity(otherPos) instanceof CultivationCabinetBlockEntity other)) continue;
            var otherData = other.localSlots().orElse(null);
            if (otherData == null) continue;
            var proposal = CultivationCabinetConnection.join(current, ownData, half(otherPos, otherState), otherData).orElse(null);
            if (proposal == null) continue;
            var ownPart = proposal.pair().first().pos().equals(pos)
                    ? CultivationCabinetPairing.Part.FIRST : CultivationCabinetPairing.Part.SECOND;
            var otherPart = ownPart == CultivationCabinetPairing.Part.FIRST
                    ? CultivationCabinetPairing.Part.SECOND : CultivationCabinetPairing.Part.FIRST;
            // 先写双方状态，再通知邻居；过程中不转移数据，异常断开也不会复制或清空植物。
            var ownAfter = state.setValue(CultivationCabinetBlock.PART, ownPart);
            var otherAfter = otherState.setValue(CultivationCabinetBlock.PART, otherPart);
            if (!level.setBlock(pos, ownAfter, Block.UPDATE_CLIENTS)) return;
            if (!level.getBlockState(pos).equals(ownAfter) || !level.getBlockState(otherPos).equals(otherState)
                    || !level.setBlock(otherPos, otherAfter, Block.UPDATE_CLIENTS)) {
                if (level.getBlockState(pos).equals(ownAfter)) level.setBlock(pos, state, Block.UPDATE_ALL);
                return;
            }
            if (!level.getBlockState(pos).equals(ownAfter) || !level.getBlockState(otherPos).equals(otherAfter)) {
                if (level.getBlockState(pos).equals(ownAfter)) level.setBlock(pos, state, Block.UPDATE_ALL);
                if (level.getBlockState(otherPos).equals(otherAfter)) level.setBlock(otherPos, otherState, Block.UPDATE_ALL);
                return;
            }
            own.invalidatePower();
            other.invalidatePower();
            level.invalidateCapabilities(pos);
            level.invalidateCapabilities(otherPos);
            level.updateNeighborsAt(pos, state.getBlock());
            level.updateNeighborsAt(otherPos, state.getBlock());
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
            level.updateNeighbourForOutputSignal(otherPos, state.getBlock());
            return;
        }
    }
}
