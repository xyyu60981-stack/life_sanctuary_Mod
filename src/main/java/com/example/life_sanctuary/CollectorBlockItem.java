package com.example.life_sanctuary;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Holder;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class CollectorBlockItem extends BlockItem implements Equipable {
    private static final ItemAttributeModifiers ARMOR = ItemAttributeModifiers.builder()
            .add(Attributes.ARMOR, new AttributeModifier(
                    ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "collector_armor"),
                    5.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.LEGS).build();

    // Store armor in the supported attribute component instead of the deprecated no-argument getter.
    // No durability component: equipment wear cannot destroy the stored contents.
    public CollectorBlockItem(Block block, Properties properties) { super(block, properties.attributes(ARMOR)); }

    @Override public EquipmentSlot getEquipmentSlot() { return EquipmentSlot.LEGS; }

    @Override public boolean isEnchantable(ItemStack stack) {
        return stack.getCount() == 1 && !stack.isEnchanted();
    }

    @Override public int getEnchantmentValue(ItemStack stack) { return 9; } // Iron armor enchantability.

    @Override public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return enchantment.is(Enchantments.PROTECTION) || enchantment.is(Enchantments.FIRE_PROTECTION)
                || enchantment.is(Enchantments.BLAST_PROTECTION) || enchantment.is(Enchantments.PROJECTILE_PROTECTION);
    }

    @Override public boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> enchantment) {
        return supportsEnchantment(stack, enchantment);
    }

    @Override public boolean canBeHurtBy(ItemStack stack, DamageSource source) {
        if (!source.is(DamageTypeTags.IS_FIRE)) return true;
        var enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var enchantment : enchantments.keySet()) {
            if (enchantment.is(Enchantments.FIRE_PROTECTION) && enchantments.getLevel(enchantment) > 0) return false;
        }
        return true;
    }

    @Override public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        return Optional.of(new CollectorTooltip(
                stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY),
                CollectorInventory.summarize(stack)));
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context,
                                          List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        var summary = CollectorInventory.summarize(stack);
        tooltip.add(Component.translatable("tooltip.life_sanctuary.collector.slots",
                summary.occupiedSlots(), summary.capacity()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.life_sanctuary.collector.controls").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.life_sanctuary.collector.hover").withStyle(ChatFormatting.DARK_GRAY));
        // Occupied slots alone cannot determine harvest capacity: consuming the last
        // empty bag or merging with an existing output may still permit collection.
    }

    private void open(Level level, Player player, InteractionHand hand) {
        ItemStack box = player.getItemInHand(hand);
        if (!level.isClientSide && !(player instanceof FakePlayer) && box.getCount() == 1) {
            player.openMenu(new SimpleMenuProvider((id, inventory, user) ->
                    new CollectorItemMenu(id, inventory, hand, box), box.getHoverName()));
            level.playSound(null, player.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 0.5F, 1.0F);
        }
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof FakePlayer) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (player.isShiftKeyDown()) {
            return swapWithEquipmentSlot(this, level, player, hand);
        }
        open(level, player, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        // Fake players collect through the plant's block interaction, never place a box on failure.
        if (player == null || player instanceof FakePlayer) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            ItemStack box = context.getItemInHand();
            // Do not truncate oversized data supplied by commands when placing a smaller box.
            if (box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).getSlots()
                    > CollectorInventory.capacity(box)) return InteractionResult.FAIL;
            return super.useOn(context);
        }
        open(context.getLevel(), player, context.getHand());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
}
