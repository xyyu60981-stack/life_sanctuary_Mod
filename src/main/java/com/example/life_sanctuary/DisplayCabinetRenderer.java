package com.example.life_sanctuary;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.MapItem;

/** The glass case is baked; only its single, stationary exhibit uses this renderer. */
public final class DisplayCabinetRenderer implements BlockEntityRenderer<DisplayCabinetBlockEntity> {
    public DisplayCabinetRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public int getViewDistance() { return 32; }
    @Override public void render(DisplayCabinetBlockEntity cabinet, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var stack = cabinet.exhibit();
        if (stack.isEmpty() || cabinet.getLevel() == null) return;
        var minecraft = Minecraft.getInstance();
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(cabinet.getBlockState().getValue(DisplayCabinetBlock.FACING).getRotation());
        pose.mulPose(Axis.XP.rotationDegrees(-90));
        pose.translate(0, 0, 0.22);
        var mapId = stack.get(DataComponents.MAP_ID);
        var map = mapId == null ? null : MapItem.getSavedData(mapId, cabinet.getLevel());
        if (map == null) map = cabinet.mapPreview();
        if (mapId != null && map != null) {
            pose.scale(0.006F, -0.006F, 0.006F); pose.translate(-64, -64, 0);
            minecraft.gameRenderer.getMapRenderer().render(pose, buffers, mapId, map, true, light);
        } else {
            pose.translate(0, 0, -0.12);
            pose.scale(0.55F, 0.55F, 0.55F);
            minecraft.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, overlay, pose, buffers,
                    cabinet.getLevel(), (int) cabinet.getBlockPos().asLong());
        }
        pose.popPose();
    }
}
