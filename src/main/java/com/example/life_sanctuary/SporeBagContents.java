package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** One bag item: explicit macro/micro metadata, with legacy unspecified tree bags treated as mixed. */
public final class SporeBagContents {
    private SporeBagContents() {}
    public enum Form { NORMAL, MEGA, MICRO, MIXED }
    public static boolean allowed(PlantSporeTraits.Profile profile, Form form) {
        return profile.differentiatedSpores() ? form == Form.MIXED || !profile.allMonoecious() && (form == Form.MEGA || form == Form.MICRO) : form == Form.NORMAL;
    }
    public static Optional<Form> read(CompoundTag root, PlantSporeTraits.Profile profile) {
        if (!root.contains("SporeForm")) return Optional.of(profile.differentiatedSpores() ? Form.MIXED : Form.NORMAL);
        if (!root.contains("SporeForm", Tag.TAG_STRING)) return Optional.empty();
        try { var form = Form.valueOf(root.getString("SporeForm")); return allowed(profile, form) ? Optional.of(form) : Optional.empty(); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    public static Optional<Form> form(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof SporeBagItem)) return Optional.empty();
        var species = PlantSporeTraits.species(SporeBagItem.species(stack));
        return species.flatMap(id -> read(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(), PlantSporeTraits.find(id)));
    }
    public static void write(ItemStack stack, Form form) {
        var species = PlantSporeTraits.species(SporeBagItem.species(stack)).orElseThrow();
        if (!(stack.getItem() instanceof SporeBagItem) || !allowed(PlantSporeTraits.find(species), form)) throw new IllegalArgumentException("Spore form does not match species");
        var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        // Canonical defaults merge with old bags, without an unnecessary extra data component field.
        if (form == Form.NORMAL || form == Form.MIXED) root.remove("SporeForm"); else root.putString("SporeForm", form.name());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
    public static boolean cultivatable(Form form) { return form == Form.NORMAL || form == Form.MIXED; }
    public static boolean cultivatable(ItemStack stack) { return form(stack).map(SporeBagContents::cultivatable).orElse(false); }
    public record Ingredient(ResourceLocation species, Form form) {
        public Ingredient { java.util.Objects.requireNonNull(species); java.util.Objects.requireNonNull(form); }
    }
    /** Quantity is fixed by the user: one mega bag plus one micro bag yields two mixed bags. */
    public static Optional<ResourceLocation> mixedSpecies(Ingredient a, Ingredient b) {
        if (!a.species().equals(b.species())) return Optional.empty();
        return (a.form() == Form.MEGA && b.form() == Form.MICRO || a.form() == Form.MICRO && b.form() == Form.MEGA)
                ? Optional.of(a.species()) : Optional.empty();
    }
}
