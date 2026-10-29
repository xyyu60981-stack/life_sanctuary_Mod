package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/** Client chunk-mesh decoration only; no block entity, block-state mutation or per-frame work. */
public final class LuminousMossOverlayModel extends BakedModelWrapper<BakedModel> {
    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final ModelProperty<Integer> OVERLAY = new ModelProperty<>();
    private final List<List<BakedQuad>> overlays;
    private LuminousMossOverlayModel(BakedModel original, List<List<BakedQuad>> overlays) {
        super(original); this.overlays = overlays;
    }
    private static ModelResourceLocation model(int side, int variant) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID,
                "block/luminous_moss_overlay_" + SIDES.get(side).getName() + "_" + variant));
    }
    public static void register(ModelEvent.RegisterAdditional event) {
        for (int side = 0; side < 4; side++) for (int variant = 0; variant < 2; variant++) event.register(model(side, variant));
    }
    public static void bake(ModelEvent.ModifyBakingResult event) {
        var faces = new ArrayList<List<BakedQuad>>();
        for (int side = 0; side < 4; side++) for (int variant = 0; variant < 2; variant++) {
            var baked = event.getModels().get(model(side, variant));
            if (baked == null) {
                LifeSanctuaryMod.LOGGER.error("Luminous moss side model was not baked: {}", model(side, variant)); return;
            }
            faces.add(List.copyOf(baked.getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null)));
        }
        var combinations = new ArrayList<List<BakedQuad>>(256);
        for (int code = 0; code < 256; code++) {
            var quads = new ArrayList<BakedQuad>();
            for (int side = 0; side < 4; side++) if ((code & (1 << side)) != 0)
                quads.addAll(faces.get(side * 2 + ((code >>> (side + 4)) & 1)));
            combinations.add(List.copyOf(quads));
        }
        var cached = List.copyOf(combinations);
        var block = ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "lichen_photobiont");
        event.getModels().replaceAll((key, original) -> key.id().equals(block) && key.variant().startsWith("age=")
                ? new LuminousMossOverlayModel(original, cached) : original);
    }
    @Override public ModelData getModelData(BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData data) {
        var original = originalModel.getModelData(world, pos, state, data);
        var base = pos.below(); int mask = 0;
        if (!world.isOutsideBuildHeight(base) && world.getBlockState(base).isCollisionShapeFullBlock(world, base)) {
            for (int side = 0; side < 4; side++) if (world.getBlockState(base.relative(SIDES.get(side))).isAir()) mask |= 1 << side;
        }
        return original.derive().with(OVERLAY, LuminousMossOverlayLayout.code(mask, base.asLong())).build();
    }
    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
        var original = originalModel.getQuads(state, side, random, data, renderType);
        var code = data.get(OVERLAY);
        if (side != null || code == null || code < 0 || code > 255
                || renderType != null && renderType != RenderType.cutout()) return original;
        var extra = overlays.get(code);
        if (extra.isEmpty()) return original;
        var combined = new ArrayList<BakedQuad>(original.size() + extra.size());
        combined.addAll(original); combined.addAll(extra); return combined;
    }
}
