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

/** V8 stem centers and cached selection shapes; no geometry is allocated during rendering. */
public final class HorsetailStemInteraction {
    private static final double[][][] CENTERS = {
            {}, {{8, 8}}, {{5.4, 5.4}, {10.6, 10.6}},
            {{4.9, 4.9}, {11.2, 5.3}, {7.7, 11.3}}
    };
    private static final double[][] SPORE_CENTERS = {{8, 8}, {12.2, 12.2}, {10.9, 5.1}, {11.3, 10.9}};
    private static final VoxelShape[][][] SHAPES = new VoxelShape[3][4][2];
    private static final VoxelShape[][][] STEM_SHAPES = new VoxelShape[3][4][4];
    static {
        for (int form = 0; form < 3; form++) {
            for (int count = 0; count <= 3; count++) {
                VoxelShape foliage = Shapes.empty();
                for (int stem = 0; stem < count; stem++) {
                    double[] center = CENTERS[count][stem];
                    // Broad foliage target around each distinct stem, clipped to the occupied half.
                    double height = form == 0 ? 15.5 : form == 1 ? 16 : 7.3;
                    VoxelShape shape = Block.box(center[0] - 2, 0, center[1] - 2,
                            center[0] + 2, height, center[1] + 2);
                    STEM_SHAPES[form][count][stem] = shape;
                    foliage = Shapes.or(foliage, shape);
                }
                double[] spore = SPORE_CENTERS[count];
                VoxelShape sporeShape = form == 2 ? Shapes.empty()
                        : Block.box(spore[0] - 1, 0, spore[1] - 1,
                                spore[0] + 1, 15.9, spore[1] + 1);
                STEM_SHAPES[form][count][3] = sporeShape;
                SHAPES[form][count][0] = foliage;
                SHAPES[form][count][1] = Shapes.or(foliage, sporeShape);
            }
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

    private static int form(BlockState state) {
        if (state.getBlock() instanceof PlacedHorsetailBlock) return 0;
        return state.getValue(EquisetumArvenseBlock.PART)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? 2 : 1;
    }

    public static VoxelShape shape(BlockState state) {
        return SHAPES[form(state)][count(state)][state.getValue(EquisetumArvenseBlock.SPORE_STEM) ? 1 : 0];
    }

    /** Upper foliage never selects the reproductive stem, which only exists below. */
    private static int targetedStem(BlockState state, BlockPos pos, Vec3 location) {
        Vec3 local = location.subtract(pos.getX(), pos.getY(), pos.getZ());
        int visible = mask(state);
        int form = form(state);
        int count = count(state);
        int selected = -1;
        double closest = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            VoxelShape shape = STEM_SHAPES[form][count][i];
            if ((visible & (1 << i)) == 0 || shape == null || shape.isEmpty()
                    || !shape.bounds().inflate(0.001).contains(local)) continue;
            double[] center = i == 3 ? SPORE_CENTERS[count] : CENTERS[count][i];
            double distance = Math.pow(local.x * 16 - center[0], 2) + Math.pow(local.z * 16 - center[1], 2);
            if (distance < closest) { closest = distance; selected = i; }
        }
        return selected;
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
        BlockPos base = state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())
                ? EquisetumArvenseBlock.base(state, pos) : pos;
        BlockState baseState = level.getBlockState(base);
        if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())
                && (!baseState.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())
                    || !EquisetumArvenseBlock.hasMatchingHalves(level, base, baseState))) return;
        // An upper-half hit still changes the base and can remove the entire pair.
        if (!level.mayInteract(player, base)
                || !player.mayUseItemAt(base, event.getHitVec().getDirection(), event.getItemStack())
                || (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())
                    && (!level.mayInteract(player, base.above())
                        || !player.mayUseItemAt(base.above(), event.getHitVec().getDirection(), event.getItemStack())))) return;
        // A half awaiting neighbor synchronization must not return a stem absent from the shared base state.
        if ((mask(baseState) & (1 << selected)) == 0) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel server)) return;

        boolean spore = selected == 3;
        int remaining = count(baseState) - (spore ? 0 : 1);
        boolean hasSpore = baseState.getValue(EquisetumArvenseBlock.SPORE_STEM) && !spore;
        BlockState next;
        if (remaining == 0) {
            // With the last nutritional stem removed, a remaining detached spore stem is decoration.
            next = hasSpore ? LifeSanctuaryMod.EQUISETUM_ARVENSE_SPORE_STEM.get().defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        } else {
            next = baseState.setValue(EquisetumArvenseBlock.SPORE_STEM, hasSpore);
            next = baseState.getBlock() instanceof PlacedHorsetailBlock
                    ? next.setValue(PlacedHorsetailBlock.STEMS, remaining)
                    : next.setValue(EquisetumArvenseBlock.NUTRITIONAL_STEMS, remaining);
        }
        if (!server.setBlock(base, next, Block.UPDATE_ALL)) return;
        if (!server.getBlockState(base).equals(next)) {
            LifeSanctuaryMod.LOGGER.error("Horsetail retrieval changed during callback at {}; no item issued", base);
            return;
        }
        if (spore && baseState.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get()) && remaining > 0) {
            // Keep an exhausted marker: removing the structure must never mint a new quota.
            HorsetailPopulations.get(server).setSporeHarvestCount(base, 0);
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
