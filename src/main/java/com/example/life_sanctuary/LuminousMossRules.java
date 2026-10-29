package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** One base is one individual throughout seedling, adult and capsule appearances. */
public final class LuminousMossRules {
    private LuminousMossRules() {}
    public static final ResourceLocation SPECIES = ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "lichen_photobiont");
    public static final TagKey<Block> SUBSTRATES = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "luminous_moss_substrates"));
    public static final String SEEDLING = "SEEDLING", ADULT = "ADULT";
    public static final int REGION = 16, LIMIT = 4, MINIMUM_NATURAL = 2, MIN_GROWTH = 48_000, MAX_GROWTH = 72_000;

    @SuppressWarnings("deprecation") // Vanilla compost registration map, accessed only during queued setup.
    public static void setup() {
        PlantSpeciesRegistry.register(new PlantSpeciesDefinition(SPECIES, Map.of(
                SEEDLING, PlantSpeciesDefinition.StageDefinition.growing(ADULT, MIN_GROWTH, MAX_GROWTH),
                ADULT, PlantSpeciesDefinition.StageDefinition.adult()),
                Optional.of(new PlantSpeciesDefinition.PopulationRules(REGION, LIMIT))));
        PlantSpeciesRegistry.registerSpreadRule(SPECIES, new PlantSpreadAdmission(Set.of(SEEDLING, ADULT), true));
        PlantSporeTraits.register(SPECIES, new PlantSporeTraits.Profile(PlantSporeTraits.Group.BRYOPHYTE, false));
        PlantGrowthHandlers.register(SPECIES, LuminousMossRules::mature);
        ReproductiveBagEffects.registerSpore(SPECIES.toString(), (level, support, label) ->
                plant(level, support.above(), PlantSource.PLAYER, PlantOrigin.PLAYER_CULTIVATED));
        net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.put(LifeSanctuaryMod.LUMINOUS_MOSS_ITEM.get(), 0.6F);
        LuminousMossCultivation.setup();
    }

    public static String population(BlockPos base) {
        return PlantPopulationTag.fromBase(SPECIES.getPath(), base, REGION).serializedName();
    }

    public static int naturalCount(net.minecraft.util.RandomSource random) {
        return MINIMUM_NATURAL + random.nextInt(LIMIT - MINIMUM_NATURAL + 1);
    }

    public static ReproductiveBagUseResult plant(ServerLevel level, BlockPos pos, PlantSource source, PlantOrigin origin) {
        return establish(level, pos, source, origin, false);
    }

    /** Called only by the real stage-tagged moss item after its completed-phase validation. */
    static ReproductiveBagUseResult plantCultivatedAdult(ServerLevel level, BlockPos pos) {
        return establish(level, pos, PlantSource.ONE_TIME, PlantOrigin.ONE_TIME_CULTIVATION, true);
    }

    private static ReproductiveBagUseResult establish(ServerLevel level, BlockPos pos, PlantSource source, PlantOrigin origin,
            boolean cultivatedAdult) {
        if (!level.getServer().isSameThread() || level.isOutsideBuildHeight(pos)
                || !level.getWorldBorder().isWithinBounds(pos)
                || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
            return ReproductiveBagUseResult.rejected();
        var store = PlantIndividuals.get(level);
        BlockState old = level.getBlockState(pos);
        var target = LifeSanctuaryMod.LUMINOUS_MOSS.get().defaultBlockState().setValue(LuminousMossBlock.AGE, cultivatedAdult ? 1 : 0);
        if (store.isReadOnly() || store.isApplyingStructure() || !old.isAir() || !target.canSurvive(level, pos)
                || store.atBase(pos).isPresent() || store.atPart(pos).isPresent()
                || HorsetailPopulations.get(level).member(pos) != null
                || store.populationCounts(SPECIES, population(pos)).total() >= LIMIT)
            return ReproductiveBagUseResult.rejected();
        // Climate is sampled once on establishment. Dry inhibits expansion, not this growth phase.
        Set<String> reasons = !source.isStatic() && PlantEnvironment.isCold(level, pos) ? Set.of("cold") : Set.of();
        long duration = source.isStatic() || cultivatedAdult ? 0 : MIN_GROWTH + level.random.nextInt(MAX_GROWTH - MIN_GROWTH + 1);
        var child = new PlantIndividualState(UUID.randomUUID(), SPECIES, pos, source, cultivatedAdult ? ADULT : SEEDLING, population(pos),
                false, false, duration, 0, reasons, Map.of(LuminousMossCycles.QUOTA, 0), origin,
                Map.of(LuminousMossCycles.DRY, !source.isStatic() && PlantEnvironment.isDry(level, pos) ? 1 : 0));
        var plan = new PlantStructurePlan(child, Map.of(pos, new PlantStructurePlan.Change(old, target, "moss",
                PlantStructurePlan.Replacement.AIR_ONLY)), Map.of(pos.below(), level.getBlockState(pos.below())));
        try {
            store.create(child);
            var result = PlantStructureCommitter.apply(level, store, plan, child);
            if (result.status() == PlantStructureCommitter.Status.COMMITTED) return ReproductiveBagUseResult.consumed(false);
            if ((result.status() == PlantStructureCommitter.Status.REJECTED || result.status() == PlantStructureCommitter.Status.ROLLED_BACK)
                    && store.partsOf(child.id()).isEmpty() && store.find(child.id()).filter(child::equals).isPresent()
                    && store.remove(child.id(), child.base())) return ReproductiveBagUseResult.rejected();
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Luminous moss establishment failed at " + pos, failure);
            if (store.find(child.id()).isEmpty()) return ReproductiveBagUseResult.rejected();
        }
        PlantLifecycle.setPauseReason(store, child.id(), "moss_birth_error", true);
        return ReproductiveBagUseResult.recovery();
    }

    private static void mature(ServerLevel level, PlantIndividuals store, PlantIndividualState plant) {
        BlockState actual = level.getBlockState(plant.base());
        if (!actual.is(LifeSanctuaryMod.LUMINOUS_MOSS.get())) {
            PlantLifecycle.setPauseReason(store, plant.id(), "moss_structure_error", true);
            return;
        }
        // Older records may predate the one-time climate cache. Initialize only that missing cache.
        if (!plant.source().isStatic() && !plant.reproduction().containsKey(LuminousMossCycles.DRY)) {
            var budgets = new java.util.HashMap<>(plant.reproduction());
            budgets.put(LuminousMossCycles.DRY, PlantEnvironment.isDry(level, plant.base()) ? 1 : 0);
            var reasons = new java.util.HashSet<>(plant.environmentReasons());
            if (PlantEnvironment.isCold(level, plant.base())) reasons.add("cold"); else reasons.remove("cold");
            store.replace(plant, plant.withReproduction(budgets).withEnvironmentReasons(reasons));
            return;
        }
        if (LuminousMossState.check(plant, actual.getValue(LuminousMossBlock.AGE), store.partsOf(plant.id()))
                != LuminousMossState.Problem.NONE) {
            PlantLifecycle.setPauseReason(store, plant.id(), "moss_state_error", true);
            return;
        }
        if (plant.stage().equals(ADULT)) { LuminousMossCycles.ready(level, store, plant); return; }
        if (!plant.stage().equals(SEEDLING) || !store.partsOf(plant.id()).equals(Map.of(plant.base(), "moss"))) return;
        BlockState current = level.getBlockState(plant.base());
        if (!current.is(LifeSanctuaryMod.LUMINOUS_MOSS.get()) || current.getValue(LuminousMossBlock.AGE) != 0
                || !current.canSurvive(level, plant.base())) return;
        var target = new PlantStageStructure(ADULT, Map.of(plant.base(), new PlantStageStructure.Part(
                current.setValue(LuminousMossBlock.AGE, 1), "moss", PlantStructurePlan.Replacement.AIR_ONLY)));
        var result = PlantStageGrowth.advance(level, plant, target, Map.of(plant.base(), current,
                plant.base().below(), level.getBlockState(plant.base().below())), level.random);
        if (result.status() == PlantStructureCommitter.Status.COMMITTED)
            store.find(plant.id()).ifPresent(adult -> LuminousMossCycles.schedule(level, adult, false));
    }

    /** Worldgen defers identity registration to the loaded server thread; natural plants stay static. */
    public static void initializeStatic(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) return;
        var store = PlantIndividuals.get(level);
        var state = level.getBlockState(pos);
        if (!state.is(LifeSanctuaryMod.LUMINOUS_MOSS.get()) || !state.canSurvive(level, pos)
                || store.isReadOnly() || store.isApplyingStructure() || store.atBase(pos).isPresent()
                || store.atPart(pos).isPresent() || HorsetailPopulations.get(level).member(pos) != null
                || store.populationCounts(SPECIES, population(pos)).total() >= LIMIT) return;
        int age = state.getValue(LuminousMossBlock.AGE);
        var plant = new PlantIndividualState(UUID.randomUUID(), SPECIES, pos, PlantSource.STATIC,
                age == 0 ? SEEDLING : ADULT, population(pos), false, false, 0, 0, Set.of(),
                Map.of(LuminousMossCycles.QUOTA, age == 2 ? 1 : 0), PlantOrigin.NATURAL);
        store.create(plant);
        store.attachParts(plant.id(), Map.of(pos, "moss"));
    }
}
