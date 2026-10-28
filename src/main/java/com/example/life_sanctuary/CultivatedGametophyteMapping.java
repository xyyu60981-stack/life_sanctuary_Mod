package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Setup-time mapping from a completed cabinet phase to a fresh world timeline. */
public final class CultivatedGametophyteMapping {
    private CultivatedGametophyteMapping() {}
    @FunctionalInterface public interface TimelineFactory { HeterosporeCycle startNextStage(RandomSource random); }
    private record Key(ResourceLocation species, String phase) {}
    private record Rule(Item item, TimelineFactory factory, String homosporousStage) {}
    private static final Map<Key, Rule> RULES = new ConcurrentHashMap<>();

    public static void register(ResourceLocation species, String completedPhase, Item item, TimelineFactory factory) {
        java.util.Objects.requireNonNull(species); java.util.Objects.requireNonNull(item); java.util.Objects.requireNonNull(factory);
        var recipe = CultivationRecipes.find(species).orElseThrow();
        if (!PlantSporeTraits.find(species).differentiatedSpores() || HeterosporeAdapters.find(species) == null
                || completedPhase == null || recipe.phase(completedPhase).isEmpty())
            throw new IllegalArgumentException("Missing species, adapter or cultivation phase");
        var phase = recipe.phase(completedPhase).orElseThrow();
        if (!CultivationProducts.stageTaggedItem(species, phase.output(), item))
            throw new IllegalArgumentException("Completed phase requires the same registered stage-tagged output item");
        if (RULES.putIfAbsent(new Key(species, completedPhase), new Rule(item, factory, null)) != null)
            throw new IllegalArgumentException("Duplicate cultivated phase mapping");
    }

    /** Same real item/stage proof as mixed products, with the species' ordinary fresh growth timer. */
    public static void registerHomosporous(ResourceLocation species, String completedPhase, Item item, String worldStage) {
        java.util.Objects.requireNonNull(species); java.util.Objects.requireNonNull(item);
        var recipe = CultivationRecipes.find(species).orElseThrow();
        var phase = recipe.phase(completedPhase).orElseThrow();
        PlantSpeciesRegistry.find(species).orElseThrow().requireTransition(worldStage);
        if (!GametophyteIdentity.isRegisteredStage(species, worldStage)
                || SharedGametophyteBlocks.find(species, SporeBagContents.Form.NORMAL).isEmpty()
                || PlantGrowthHandlers.find(species) == null
                || !CultivationProducts.stageTaggedItem(species, phase.output(), item))
            throw new IllegalArgumentException("Missing real homosporous stage, growth handler or stage-tagged product");
        if (RULES.putIfAbsent(new Key(species, completedPhase), new Rule(item, null, worldStage)) != null)
            throw new IllegalArgumentException("Duplicate cultivated phase mapping");
    }

    static Optional<String> homosporousStage(ItemStack stack, CultivatedStageData completed) {
        var rule = RULES.get(new Key(completed.species(), completed.phase()));
        return rule != null && stack.is(rule.item()) ? Optional.ofNullable(rule.homosporousStage()) : Optional.empty();
    }

    /** Invoke only on the server after permission and target checks; never trust the component alone. */
    public static Optional<HeterosporeCycle> start(ItemStack stack, ResourceLocation expectedSpecies, RandomSource random) {
        java.util.Objects.requireNonNull(expectedSpecies); java.util.Objects.requireNonNull(random);
        var tag = CultivatedStageData.read(stack).orElse(null);
        if (tag == null || !expectedSpecies.equals(tag.species()) || !accepts(stack, tag)) return Optional.empty();
        var rule = RULES.get(new Key(expectedSpecies, tag.phase()));
        if (rule.factory() == null) return Optional.empty();
        var cycle = java.util.Objects.requireNonNull(rule.factory().startNextStage(random));
        if (cycle.kind() != HeterosporeGametophyteRules.Kind.MIXED || cycle.action() == HeterosporeCycle.Action.SEEDLING
                || cycle.attempts() != 0 || cycle.outcome() != HeterosporeCycle.Outcome.ACTIVE || cycle.terminalTarget() != 0
                || cycle.age() != (cycle.mature() ? HeterosporeGametophyteRules.MATURITY_TICKS : 0))
            throw new IllegalStateException("Invalid fresh mixed-gametophyte timeline");
        return Optional.of(cycle);
    }

    /** Cheap item/phase gate before a species planner reads the world or any random duration is sampled. */
    static boolean matchesItem(ResourceLocation species, String phase, Item item) {
        var rule = RULES.get(new Key(species, phase));
        return rule != null && rule.item() == item;
    }

    static boolean accepts(ItemStack stack, CultivatedStageData completed) {
        var rule = RULES.get(new Key(completed.species(), completed.phase()));
        return rule != null && stack.is(rule.item());
    }
}
