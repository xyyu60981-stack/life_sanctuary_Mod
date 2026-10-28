package com.example.life_sanctuary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

/** One item per shared appearance; completed-stage species data supplies the actual plant identity. */
public final class SharedGametophyteItem extends Item {
    private record Key(ResourceLocation species, String phase) {}
    private final GametophyteAppearance appearance;
    private final Map<Key, CultivatedGametophyteUse.Planner> planners = new ConcurrentHashMap<>();
    public SharedGametophyteItem(Properties properties, GametophyteAppearance appearance) {
        super(properties);
        this.appearance = java.util.Objects.requireNonNull(appearance);
    }
    private SporeBagContents.Form form() {
        return switch (appearance) {
            case FEMALE_LYCOPOD -> SporeBagContents.Form.MEGA;
            case MALE_LYCOPOD -> SporeBagContents.Form.MICRO;
            case MIXED_LYCOPOD -> SporeBagContents.Form.MIXED;
            default -> SporeBagContents.Form.NORMAL;
        };
    }
    /** Register after the real stage-tagged output and cultivated phase mapping. */
    public void registerPlacement(ResourceLocation species, String completedPhase, CultivatedGametophyteUse.Planner planner) {
        java.util.Objects.requireNonNull(planner);
        if (!GametophyteAppearance.forSpecies(species, form()).filter(appearance::equals).isPresent()
                || !CultivatedGametophyteMapping.matchesItem(species, completedPhase, this))
            throw new IllegalArgumentException("Shared gametophyte item does not match its species or cultivated phase");
        if (planners.putIfAbsent(new Key(species, completedPhase), planner) != null)
            throw new IllegalArgumentException("Duplicate shared gametophyte placement");
    }
    @Override public String getDescriptionId() {
        return "block.life_sanctuary." + appearance.name().toLowerCase(java.util.Locale.ROOT) + "_gametophyte";
    }
    public java.util.Optional<ResourceLocation> species(ItemStack stack) {
        if (!stack.is(this)) return java.util.Optional.empty();
        return CultivatedStageData.read(stack).filter(data ->
                GametophyteAppearance.forSpecies(data.species(), form()).filter(appearance::equals).isPresent())
                .map(CultivatedStageData::species);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var completed = CultivatedStageData.read(context.getItemInHand()).orElse(null);
        if (completed == null || GametophyteAppearance.forSpecies(completed.species(), form()).filter(appearance::equals).isEmpty())
            return InteractionResult.PASS;
        var planner = planners.get(new Key(completed.species(), completed.phase()));
        return planner == null ? InteractionResult.PASS : CultivatedGametophyteUse.use(context, planner);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        var completed = CultivatedStageData.read(stack).orElse(null);
        if (completed == null || GametophyteAppearance.forSpecies(completed.species(), form()).filter(appearance::equals).isEmpty()) return;
        var species = completed.species();
        var name = Component.translatableWithFallback("species." + species.getNamespace() + "." + species.getPath(), species.toString());
        tooltip.add(Component.translatable("tooltip.life_sanctuary.gametophyte.species", name).withStyle(ChatFormatting.GRAY));
    }
}
