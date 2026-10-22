package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Geometry matches the four simple stem models; shapes are cached, never rebuilt per frame. */
public final class HorsetailStemInteraction {
    private static final VoxelShape[] STEMS = {
            Block.box(3, 0, 3, 5, 14, 5), Block.box(11, 0, 3, 13, 14, 5),
            Block.box(3, 0, 11, 5, 14, 13), Block.box(11, 0, 11, 13, 12, 13)
    };
    private static final VoxelShape[] SHAPES = new VoxelShape[16];
    static {
        for (int mask = 0; mask < SHAPES.length; mask++) {
            VoxelShape shape = Shapes.empty();
            for (int i = 0; i < STEMS.length; i++)
                if ((mask & (1 << i)) != 0) shape = Shapes.or(shape, STEMS[i]);
            SHAPES[mask] = shape;
        }
    }

    public static int count(BlockState state) {
        return state.getBlock() instanceof PlacedHorsetailBlock
                ? state.getValue(PlacedHorsetailBlock.STEMS)
                : state.getValue(EquisetumArvenseBlock.NUTRITIONAL_STEMS);
    }

    private static int mask(BlockState state) {
        return (1 << count(state)) - 1 | (state.getValue(EquisetumArvenseBlock.SPORE_STEM) ? 8 : 0);
    }

    public static VoxelShape shape(BlockState state) {
        return SHAPES[mask(state)];
    }

    /** Shared by manual retrieval and creative pick-block, including mixed placed stems. */
    private static int targetedStem(BlockState state, BlockPos pos, Vec3 location) {
        Vec3 local = location.subtract(pos.getX(), pos.getY(), pos.getZ());
        int visible = mask(state);
        for (int i = 0; i < STEMS.length; i++)
            if ((visible & (1 << i)) != 0 && STEMS[i].bounds().inflate(0.001).contains(local)) return i;
        return -1;
    }

    public static ItemStack pickStack(BlockState state, BlockPos pos, net.minecraft.world.phys.HitResult target) {
        if (!(target instanceof net.minecraft.world.phys.BlockHitResult hit)
                || !hit.getBlockPos().equals(pos)) return ItemStack.EMPTY;
        int selected = targetedStem(state, pos, hit.getLocation());
        if (selected < 0) return ItemStack.EMPTY;
        return new ItemStack(selected == 3 ? LifeSanctuaryMod.EQUISETUM_ARVENSE_SPORE_STEM_ITEM.get()
                : LifeSanctuaryMod.EQUISETUM_ARVENSE_ITEM.get());
    }

    // An event is needed because vanilla bypasses block use when sneaking with an item in hand.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        var level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!player.isShiftKeyDown() || player.isSpectator() || player instanceof FakePlayer
                || event.getHand() != InteractionHand.MAIN_HAND
                || !(state.getBlock() instanceof PlacedHorsetailBlock
                     || state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get()))
                || !player.mayBuild() || !level.mayInteract(player, pos)
                || !player.mayUseItemAt(pos, event.getHitVec().getDirection(), event.getItemStack())
                || event.getUseBlock() == net.neoforged.neoforge.common.util.TriState.FALSE) return;

        int selected = targetedStem(state, pos, event.getHitVec().getLocation());
        if (selected < 0) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel server)) return;

        boolean spore = selected == 3;
        int remaining = count(state) - (spore ? 0 : 1);
        boolean hasSpore = state.getValue(EquisetumArvenseBlock.SPORE_STEM) && !spore;
        BlockState next;
        if (remaining == 0) {
            // With the last nutritional stem removed, a remaining detached spore stem is decoration.
            next = hasSpore ? LifeSanctuaryMod.EQUISETUM_ARVENSE_SPORE_STEM.get().defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        } else {
            next = state.setValue(EquisetumArvenseBlock.SPORE_STEM, hasSpore);
            next = state.getBlock() instanceof PlacedHorsetailBlock
                    ? next.setValue(PlacedHorsetailBlock.STEMS, remaining)
                    : next.setValue(EquisetumArvenseBlock.NUTRITIONAL_STEMS, remaining);
        }
        if (!server.setBlock(pos, next, Block.UPDATE_ALL)) return;
        if (spore && state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get()) && remaining > 0) {
            // Keep an exhausted marker: removing the structure must never mint a new quota.
            HorsetailPopulations.get(server).setSporeHarvestCount(pos, 0);
        }

        ItemStack returned = new ItemStack(spore ? LifeSanctuaryMod.EQUISETUM_ARVENSE_SPORE_STEM_ITEM.get()
                : LifeSanctuaryMod.EQUISETUM_ARVENSE_ITEM.get());
        var inventory = player.getInventory();
        boolean room = inventory.getFreeSlot() >= 0 || inventory.getSlotWithRemainingSpace(returned) >= 0;
        if (room && inventory.add(returned) && returned.isEmpty()) {
            player.containerMenu.broadcastChanges();
            server.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
        } else if (!returned.isEmpty()) {
            // Normal dropped-item delay prevents immediate pickup; dropping itself makes no pickup sound.
            player.drop(returned, false);
        }
    }
}
