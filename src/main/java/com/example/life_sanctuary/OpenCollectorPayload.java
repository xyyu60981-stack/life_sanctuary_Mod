package com.example.life_sanctuary;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Only requests opening a slot; no client-supplied inventory or mutation is trusted. */
public record OpenCollectorPayload(int inventorySlot) implements CustomPacketPayload {
    /** Player inventory indices: main/hotbar 0..35, leggings 37. */
    public static boolean isSupportedSlot(int slot) { return (slot >= 0 && slot < 36) || slot == 37; }
    public static final Type<OpenCollectorPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "open_collector"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCollectorPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeVarInt(value.inventorySlot()),
            buffer -> new OpenCollectorPayload(buffer.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenCollectorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
            int slot = payload.inventorySlot();
            if (!isSupportedSlot(slot) || player.containerMenu != player.inventoryMenu
                    || !player.containerMenu.getCarried().isEmpty()) return;
            ItemStack box = player.getInventory().getItem(slot);
            int capacity = CollectorInventory.capacity(box);
            if (capacity == 0 || box.getCount() != 1) return;
            player.openMenu(new SimpleMenuProvider((id, inventory, user) ->
                    new CollectorItemMenu(id, inventory, slot, capacity / 9), box.getHoverName()),
                    buffer -> { buffer.writeVarInt(slot); buffer.writeVarInt(capacity / 9); });
            player.level().playSound(null, player.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 0.5F, 1.0F);
        });
    }
}
