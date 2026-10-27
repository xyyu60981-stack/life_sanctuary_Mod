package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.Optional;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;

/** Binding only on use. Vanilla angle rendering reads an untracked lodestone component, without lodestone ticks. */
public final class ShelterCompassItem extends Item {
    private final boolean skylight;
    public ShelterCompassItem(Properties properties, boolean skylight) { super(properties); this.skylight = skylight; }
    public static Optional<ShelterLocations.Target> target(ItemStack stack) {
        if (!(stack.getItem() instanceof ShelterCompassItem item)) return Optional.empty();
        var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        try {
            if (!root.contains("ShelterTarget", Tag.TAG_COMPOUND)) return Optional.empty();
            var target = ShelterLocations.Target.read(root.getCompound("ShelterTarget"));
            return target.skylight() == item.skylight ? Optional.of(target) : Optional.empty();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    @Override public Component getName(ItemStack stack) {
        return target(stack).<Component>map(target -> Component.translatable(getDescriptionId() + ".bound", ShelterLocations.letters(target.number())))
                .orElseGet(() -> super.getName(stack));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.sidedSuccess(stack, true);
        var excluded = new HashSet<GlobalPos>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var other = player.getInventory().getItem(slot);
            if (other != stack) target(other).ifPresent(target -> excluded.add(target.location()));
        }
        var index = ShelterLocations.get(server.getServer());
        var found = ShelterLocators.find(server, player.blockPosition(), skylight, excluded);
        player.getCooldowns().addCooldown(this, 20);
        if (found.isEmpty()) {
            player.displayClientMessage(Component.translatable(index.isReadOnly() ? "message.life_sanctuary.shelter_index_unavailable"
                    : "message.life_sanctuary.shelter_not_found"), true);
            return InteractionResultHolder.fail(stack);
        }
        var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); root.put("ShelterTarget", found.get().save());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(found.get().location()), false));
        player.displayClientMessage(Component.translatable("message.life_sanctuary.shelter_bound", getName(stack)), true);
        return InteractionResultHolder.success(stack);
    }
}
