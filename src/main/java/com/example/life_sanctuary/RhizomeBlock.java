package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.material.PushReaction;

/** 生长点是同一地下茎方块的状态，不注册第二种方块或物品，也不需要方块实体。 */
public class RhizomeBlock extends Block {
    public static final MapCodec<RhizomeBlock> CODEC = simpleCodec(RhizomeBlock::new);
    public static final BooleanProperty GROWTH_POINT = BooleanProperty.create("growth_point");
    public RhizomeBlock(Properties properties) {
        // 固定范围与坐标归属不能随原版活塞悄悄搬走；后续专用移动适配器可另行实现。
        super(properties.pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(GROWTH_POINT, false));
    }
    @Override public MapCodec<? extends RhizomeBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(GROWTH_POINT);
    }
    /** 两种外形统一掉普通地下茎物品；生长点标记不写进掉落物。 */
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var stack = new ItemStack(this);
        return stack.isEmpty() ? List.of() : List.of(stack);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (state.is(next.getBlock()) || !(level instanceof ServerLevel server)) return;
        var store = PlantRhizomeSystems.get(server);
        if (store.isReadOnly() || store.isApplyingStructure() || PlantIndividuals.get(server).isApplyingStructure()) return;
        store.atRhizome(pos).ifPresent(owner -> {
            if (store.needsRecovery(owner.id())) return;
            var updated = owner.rhizomes().contains(pos) ? owner.removeRhizomes(java.util.Set.of(pos))
                    : owner.removeRisingRhizomes(java.util.Set.of(pos));
            if (!store.replace(owner, updated)) store.requireRecovery(owner.id());
        });
    }
}
