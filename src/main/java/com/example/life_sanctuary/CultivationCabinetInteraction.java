package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** 手持满孢子袋的服务器投入入口；每次右键最多消费一袋，不搜索背包中的其他袋子。 */
public final class CultivationCabinetInteraction {
    private CultivationCabinetInteraction() {}

    public static void open(ServerLevel level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isSecondaryUseActive() || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || player instanceof net.neoforged.neoforge.common.util.FakePlayer || player.isSpectator()
                || player.containerMenu != player.inventoryMenu) return;
        if (!permitted(level, pos, player, hit, ItemStack.EMPTY)) { message(player, "no_access"); return; }
        var other = CultivationCabinetWorldConnection.partner(level, pos);
        if (other.isPresent() && !permitted(level, other.get().getBlockPos(), player, hit, ItemStack.EMPTY)) {
            message(player, "no_access"); return;
        }
        var access = CultivationCabinetAccess.open(level, pos).orElse(null);
        if (access == null) { message(player, "unavailable"); return; }
        int capacity = access.snapshot().capacity();
        serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, user) ->
                new CultivationCabinetMenu(id, inventory, pos, capacity), CultivationCabinetWorldConnection.displayName(level, pos)),
                buffer -> { buffer.writeBlockPos(pos); buffer.writeVarInt(capacity); });
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.CHEST_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    public static void insertHeldBag(ServerLevel level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (player.isSecondaryUseActive()) return;
        var stack = player.getItemInHand(hand);
        if (!ReproductiveBagKind.SPORE.isFilledBag(stack)) return;
        if (player.isSpectator() || !permitted(level, pos, player, hit, stack)) {
            message(player, "no_access");
            return;
        }
        var other = CultivationCabinetWorldConnection.partner(level, pos);
        if (other.isPresent() && !permitted(level, other.get().getBlockPos(), player, hit, stack)) {
            message(player, "no_access");
            return;
        }
        var access = CultivationCabinetAccess.open(level, pos).orElse(null);
        if (access == null) { message(player, "unavailable"); return; }
        var recipe = CultivationRecipes.forSporeBag(stack).orElse(null);
        if (recipe == null) { message(player, "no_recipe"); return; }
        if (!CultivationProducts.isReady(recipe)) { message(player, "missing_output"); return; }
        var snapshot = access.snapshot();
        for (int slot = 0; slot < snapshot.capacity(); slot++) {
            if (!snapshot.canInsertAt(slot)) continue;
            int sourceSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
            var result = access.start(slot, java.util.UUID.randomUUID(), recipe,
                    new CultivationSporeBagInput(player.getInventory(), sourceSlot, stack));
            if (result == CultivationController.Result.CHANGED) {
                player.containerMenu.broadcastChanges();
                player.displayClientMessage(Component.translatable("message.life_sanctuary.cultivation.started", slot + 1), true);
            } else message(player, result == CultivationController.Result.RECOVERY_REQUIRED ? "recovery_required" : "changed");
            // 旧快照尝试过一次即结束，不在另一槽重试可能已发生副作用的输入。
            return;
        }
        message(player, "full");
    }

    private static boolean permitted(ServerLevel level, BlockPos pos, Player player, BlockHitResult hit, ItemStack stack) {
        return player.mayBuild() && level.mayInteract(player, pos)
                && player.mayUseItemAt(pos, hit.getDirection(), stack);
    }

    private static void message(Player player, String reason) {
        player.displayClientMessage(Component.translatable("message.life_sanctuary.cultivation." + reason), true);
    }
}
