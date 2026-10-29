package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Bounded per-box product filter. Empty bags are supplies, not collected products. */
public record CollectorFilter(Mode mode, List<Optional<CollectorFilter.Rule>> entries) {
    public static final String KEY = "CollectorFilter";
    public static final int SIZE = 18;
    private static final com.google.common.cache.Cache<CustomData, CollectorFilter> CACHE =
            com.google.common.cache.CacheBuilder.newBuilder().weakKeys().maximumSize(512).build();
    public enum Mode { OFF, BLACKLIST, WHITELIST, BROKEN }
    public record Rule(ResourceLocation item, String species) {
        public Rule {
            java.util.Objects.requireNonNull(item); java.util.Objects.requireNonNull(species);
            if (item.equals(ResourceLocation.withDefaultNamespace("air")) || item.toString().length() > 256 || species.length() > 128
                    || !species.isEmpty() && (species.isBlank() || ResourceLocation.tryParse(species) == null))
                throw new IllegalArgumentException("Invalid collector filter entry");
        }
        public boolean matches(ResourceLocation incoming, String incomingSpecies) {
            return item.equals(incoming) && (species.isEmpty() || sameSpecies(species, incomingSpecies));
        }
        private boolean equivalent(Rule other) {
            return item.equals(other.item) && (species.isEmpty() && other.species.isEmpty()
                    || !species.isEmpty() && !other.species.isEmpty() && sameSpecies(species, other.species));
        }
        public ItemStack display() {
            var registered = BuiltInRegistries.ITEM.getOptional(item).filter(value -> value != Items.AIR);
            var stack = new ItemStack(registered.orElse(Items.BARRIER));
            if (registered.isEmpty()) stack.set(DataComponents.CUSTOM_NAME, Component.literal(item.toString()));
            else if (!species.isEmpty()) {
                SpeciesTaggedItemData.write(stack, species);
                if (stack.getItem() instanceof SharedGametophyteItem)
                    stack.set(DataComponents.CUSTOM_NAME, stack.getHoverName().copy().append(" [" + species + "]"));
            }
            return stack;
        }
        static Rule from(ItemStack sample, boolean anySpecies) {
            var species = speciesOf(sample);
            return new Rule(BuiltInRegistries.ITEM.getKey(sample.getItem()), anySpecies || species == null ? "" : species);
        }
    }
    public CollectorFilter {
        java.util.Objects.requireNonNull(mode); entries = List.copyOf(entries);
        if (entries.size() != SIZE || entries.stream().flatMap(Optional::stream).distinct().count() != entries.stream().filter(Optional::isPresent).count())
            throw new IllegalArgumentException("Invalid collector filter slots");
    }
    public static CollectorFilter empty() { return new CollectorFilter(Mode.OFF, java.util.Collections.nCopies(SIZE, Optional.empty())); }
    private static boolean sameSpecies(String first, String second) {
        var species = PlantSporeTraits.species(first).orElse(null);
        return species != null && PlantSporeTraits.species(second).filter(species::equals).isPresent();
    }
    private static CollectorFilter broken() { return new CollectorFilter(Mode.BROKEN, empty().entries()); }
    public boolean allows(ResourceLocation item, String species) {
        if (mode == Mode.OFF) return true;
        if (mode == Mode.BROKEN) return false;
        boolean match = entries.stream().flatMap(Optional::stream).anyMatch(rule -> rule.matches(item, species));
        return mode == Mode.WHITELIST ? match : !match;
    }
    private static String speciesOf(ItemStack stack) {
        return stack.getItem() instanceof SharedGametophyteItem shared
                ? shared.species(stack).map(Object::toString).orElse(null) : SpeciesTaggedItemData.read(stack);
    }
    public CollectorFilter cycleMode() {
        return mode == Mode.BROKEN ? this : new CollectorFilter(Mode.values()[(mode.ordinal() + 1) % 3], entries);
    }
    public CollectorFilter set(int index, Optional<Rule> rule) {
        if (mode == Mode.BROKEN || index < 0 || index >= SIZE) return this;
        var next = new ArrayList<>(entries);
        if (rule.isPresent()) for (int i = 0; i < SIZE; i++)
            if (next.get(i).filter(rule.get()::equivalent).isPresent()) next.set(i, Optional.empty());
        next.set(index, rule); return new CollectorFilter(mode, next);
    }
    public CollectorFilter move(int from, int to) {
        if (mode == Mode.BROKEN || from < 0 || from >= SIZE || to < 0 || to >= SIZE) return this;
        var next = new ArrayList<>(entries); java.util.Collections.swap(next, from, to); return new CollectorFilter(mode, next);
    }
    /** Missing configuration means legacy behavior. Malformed data rejects products until explicit reset. */
    public static CollectorFilter read(CompoundTag custom) {
        if (!custom.contains(KEY)) return empty();
        try {
            if (!custom.contains(KEY, Tag.TAG_COMPOUND)) return broken();
            var saved = custom.getCompound(KEY);
            if (!saved.contains("Version", Tag.TAG_INT) || saved.getInt("Version") != 1 || !saved.contains("Mode", Tag.TAG_STRING)
                    || !saved.contains("Entries", Tag.TAG_LIST)) return broken();
            var mode = Mode.valueOf(saved.getString("Mode"));
            if (mode == Mode.BROKEN) return broken();
            var list = saved.getList("Entries", Tag.TAG_COMPOUND);
            if (list.size() != ((ListTag) saved.get("Entries")).size() || list.size() > SIZE) return broken();
            var entries = new ArrayList<>(empty().entries());
            for (var raw : list) {
                var entry = (CompoundTag) raw;
                if (!entry.contains("Slot", Tag.TAG_INT) || !entry.contains("Item", Tag.TAG_STRING) || !entry.contains("Species", Tag.TAG_STRING)) return broken();
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= SIZE || entries.get(slot).isPresent()) return broken();
                entries.set(slot, Optional.of(new Rule(ResourceLocation.parse(entry.getString("Item")), entry.getString("Species"))));
            }
            return new CollectorFilter(mode, entries);
        } catch (IllegalArgumentException error) { return broken(); }
    }
    public CompoundTag writeInto(CompoundTag original) {
        if (mode == Mode.BROKEN) throw new IllegalStateException("Cannot overwrite damaged filter without explicit reset");
        var root = original.copy();
        if (mode == Mode.OFF && entries.stream().noneMatch(Optional::isPresent)) { root.remove(KEY); return root; }
        var saved = new CompoundTag(); saved.putInt("Version", 1); saved.putString("Mode", mode.name());
        var list = new ListTag();
        for (int i = 0; i < SIZE; i++) if (entries.get(i).isPresent()) {
            var rule = entries.get(i).orElseThrow(); var entry = new CompoundTag();
            entry.putInt("Slot", i); entry.putString("Item", rule.item().toString()); entry.putString("Species", rule.species()); list.add(entry);
        }
        saved.put("Entries", list); root.put(KEY, saved); return root;
    }
    public static CollectorFilter read(CustomData custom) {
        var cached = CACHE.getIfPresent(custom);
        if (cached != null) return cached;
        var filter = read(custom.copyTag()); CACHE.put(custom, filter); return filter;
    }
    public static CollectorFilter read(ItemStack box) { return read(box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)); }
    public void write(ItemStack box) {
        var root = writeInto(box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
        if (root.isEmpty()) box.remove(DataComponents.CUSTOM_DATA); else box.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
    public static boolean isSupply(ItemStack stack) {
        return ReproductiveBagKind.SPORE.isEmptyBag(stack) || ReproductiveBagKind.POLLEN.isEmptyBag(stack);
    }
    public boolean allows(ItemStack stack) {
        return stack.isEmpty() || isSupply(stack) || allows(BuiltInRegistries.ITEM.getKey(stack.getItem()), speciesOf(stack));
    }
}
