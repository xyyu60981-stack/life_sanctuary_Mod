package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/** Static-model charred material. Each actual family registers its own matching burned block. */
public class CharredMaterialBlock extends RotatedPillarBlock {
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final MapCodec<CharredMaterialBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), StringRepresentable.fromEnum(CharredMaterialRules.Kind::values).fieldOf("kind").forGetter(block -> block.kind),
            ResourceLocation.CODEC.fieldOf("burned").forGetter(block -> block.burned)
    ).apply(instance, CharredMaterialBlock::new));
    private final CharredMaterialRules.Kind kind;
    private final ResourceLocation burned;
    public CharredMaterialBlock(Properties properties, CharredMaterialRules.Kind kind, ResourceLocation burned) {
        super(properties.lightLevel(state -> state.getValue(LIT) ? kind.light : 0));
        this.kind = java.util.Objects.requireNonNull(kind); this.burned = java.util.Objects.requireNonNull(burned);
        if (burned.equals(ResourceLocation.withDefaultNamespace("air"))) throw new IllegalArgumentException("Missing burned material");
        registerDefaultState(defaultBlockState().setValue(LIT, false));
    }
    @Override public MapCodec<? extends CharredMaterialBlock> codec() { return CODEC; }
    public CharredMaterialRules.Kind kind() { return kind; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(LIT);
    }
    BlockState burnedState(BlockState before) {
        var target = BuiltInRegistries.BLOCK.getOptional(burned).orElseThrow(() -> new IllegalStateException("Unregistered burned material " + burned));
        if (!(target instanceof BurnedMaterialBlock material) || material.kind() != kind)
            throw new IllegalStateException("Mismatched burned material " + burned);
        return target.defaultBlockState().setValue(AXIS, before.getValue(AXIS));
    }
    private void synchronize(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server && server.getServer().isSameThread())
            CharredMaterialTimers.get(server).synchronize(server, pos);
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState before, boolean moving) {
        super.onPlace(state, level, pos, before, moving); synchronize(level, pos);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server && server.getServer().isSameThread())
            CharredMaterialTimers.get(server).remove(pos);
        super.onRemove(state, level, pos, next, moving);
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moving); synchronize(level, pos);
    }
    /** World-generation adapters may request this one-shot initialization without mutating SavedData off-thread. */
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        synchronize(level, pos);
    }
    @Override public BlockState getToolModifiedState(BlockState state, UseOnContext context, ItemAbility ability, boolean simulate) {
        if (context.getItemInHand().canPerformAction(ability)) {
            if (ability == ItemAbilities.FIRESTARTER_LIGHT && !state.getValue(LIT)) return state.setValue(LIT, true);
            if (ability == ItemAbilities.SHOVEL_DOUSE && state.getValue(LIT)) return state.setValue(LIT, false);
        }
        return super.getToolModifiedState(state, context, ability, simulate);
    }
    static boolean silk(ServerLevel level, ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getEnchantmentLevel(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH)) > 0;
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (silk(params.getLevel(), params.getOptionalParameter(LootContextParams.TOOL))) {
            var item = new ItemStack(this); // Recovered blocks do not carry an active fire or elapsed progress.
            return item.isEmpty() ? List.of() : List.of(item);
        }
        return List.of(new ItemStack(Items.CHARCOAL, CharredMaterialRules.charcoalCount(kind, false)));
    }
    @Override public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 0; }
    @Override public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 0; }
}
