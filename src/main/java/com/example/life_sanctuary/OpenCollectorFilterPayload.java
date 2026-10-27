package com.example.life_sanctuary;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client supplies only the current menu id. Server derives and pins the actual owned collector. */
public record OpenCollectorFilterPayload(int containerId) implements CustomPacketPayload {
    public static final Type<OpenCollectorFilterPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("life_sanctuary", "open_collector_filter"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCollectorFilterPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeVarInt(value.containerId()), buf -> new OpenCollectorFilterPayload(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(OpenCollectorFilterPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.isAlive()) return;
            var menu = player.containerMenu;
            if (menu.containerId != payload.containerId() || !menu.stillValid(player) || !menu.getCarried().isEmpty()) return;
            ItemStack box; int slot; CollectorBlockEntity block;
            if (menu instanceof CollectorItemMenu item) { box = item.sourceBox(); slot = item.sourceInventorySlot(); block = null; }
            else if (menu instanceof CollectorBlockMenu placed && placed.sourceBlock() != null) { box = ItemStack.EMPTY; slot = -1; block = placed.sourceBlock(); }
            else return;
            player.openMenu(new SimpleMenuProvider((id, inventory, user) -> new CollectorFilterMenu(id, inventory, box, slot, block),
                    Component.translatable("menu.life_sanctuary.collector_filter")));
        });
    }
}
