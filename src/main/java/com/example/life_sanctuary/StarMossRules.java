package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Artificial groups have sixteen tiles including the founder; natural generation is not configured. */
public final class StarMossRules {
    private StarMossRules() {}
    public static final ResourceLocation SPECIES = ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "marchantia_stellatus");
    public static final String ADULT = "ADULT";
    public static final int REGION = 16, ARTIFICIAL_LIMIT = 16, NATURAL_MIN = 4, NATURAL_MAX = 8, NATURAL_GROUP_MIN = 1, NATURAL_GROUP_MAX = 4;
    @SuppressWarnings("deprecation")
    public static void setup() {
        PlantSpeciesRegistry.register(new PlantSpeciesDefinition(SPECIES, Map.of(ADULT, PlantSpeciesDefinition.StageDefinition.adult()),
                Optional.empty()));
        PlantSporeTraits.register(SPECIES, new PlantSporeTraits.Profile(PlantSporeTraits.Group.BRYOPHYTE, false));
        PlantGrowthHandlers.register(SPECIES, StarMossCycles::ready);
        ReproductiveBagEffects.registerSpore(SPECIES.toString(), (level, support, label) -> plant(level, support.above()));
        net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.put(LifeSanctuaryMod.STAR_MOSS_ITEM.get(), 0.6F);
    }
    public static String naturalPopulation(BlockPos base) { return PlantPopulationTag.fromBase(SPECIES.getPath(), base, REGION).serializedName(); }
    public static String artificialPopulation(BlockPos base, UUID founder) { return naturalPopulation(base) + "/planted/" + founder; }
    static java.util.List<BlockPos> spreadCandidates(BlockPos base, java.util.List<net.minecraft.core.Direction> directions) {
        var result = new java.util.ArrayList<BlockPos>();
        for (int dy : new int[]{0, 1, -1}) for (var direction : directions) {
            if (!direction.getAxis().isHorizontal()) throw new IllegalArgumentException("Horizontal neighbor required");
            var candidate = base.relative(direction).above(dy);
            if (naturalPopulation(candidate).equals(naturalPopulation(base))) result.add(candidate);
        }
        return java.util.List.copyOf(result);
    }
    public static boolean validPopulation(PlantIndividualState plant) {
        String prefix = naturalPopulation(plant.base());
        if (plant.source().isStatic()) return plant.population().equals(prefix);
        if (!plant.population().startsWith(prefix + "/planted/")) return false;
        try { UUID.fromString(plant.population().substring((prefix + "/planted/").length())); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    public static ReproductiveBagUseResult plant(ServerLevel level, BlockPos pos) {
        var id = UUID.randomUUID();
        return establish(level, pos, id, artificialPopulation(pos, id), PlantOrigin.PLAYER_CULTIVATED);
    }
    static ReproductiveBagUseResult spread(ServerLevel level, PlantIndividualState parent, BlockPos pos) {
        if (!validPopulation(parent) || !naturalPopulation(pos).equals(naturalPopulation(parent.base()))) return ReproductiveBagUseResult.rejected();
        return establish(level, pos, UUID.randomUUID(), parent.population(), PlantOrigin.AUTOMATIC_SPREAD);
    }
    private static ReproductiveBagUseResult establish(ServerLevel level, BlockPos pos, UUID id, String population, PlantOrigin origin) {
        if (!level.getServer().isSameThread() || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return ReproductiveBagUseResult.rejected();
        var store = PlantIndividuals.get(level); var before = level.getBlockState(pos);
        var target = LifeSanctuaryMod.STAR_MOSS.get().defaultBlockState();
        if (store.isReadOnly() || store.isApplyingStructure() || !before.isAir() || !target.canSurvive(level, pos)
                || store.atBase(pos).isPresent() || store.atPart(pos).isPresent() || HorsetailPopulations.get(level).member(pos) != null
                || store.populationCounts(SPECIES, population).total() >= ARTIFICIAL_LIMIT) return ReproductiveBagUseResult.rejected();
        var child = new PlantIndividualState(id, SPECIES, pos, PlantSource.PLAYER, ADULT, population, false, false,
                StarMossCycles.MIN_SPREAD + level.random.nextInt(StarMossCycles.MAX_SPREAD - StarMossCycles.MIN_SPREAD + 1), 0,
                Set.of(), Map.of(StarMossCycles.QUOTA, 0), origin, Map.of());
        var plan = new PlantStructurePlan(child, Map.of(pos, new PlantStructurePlan.Change(before, target, "star_moss", PlantStructurePlan.Replacement.AIR_ONLY)),
                Map.of(pos.below(), level.getBlockState(pos.below())));
        try {
            store.create(child);
            var result = PlantStructureCommitter.apply(level, store, plan, child);
            if (result.status() == PlantStructureCommitter.Status.COMMITTED) return ReproductiveBagUseResult.consumed(false);
            if ((result.status() == PlantStructureCommitter.Status.REJECTED || result.status() == PlantStructureCommitter.Status.ROLLED_BACK)
                    && store.partsOf(id).isEmpty() && store.find(id).filter(child::equals).isPresent() && store.remove(id, pos))
                return ReproductiveBagUseResult.rejected();
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("StarMoss establishment failed at " + pos, failure);
            if (store.find(id).isEmpty()) return ReproductiveBagUseResult.rejected();
        }
        PlantLifecycle.setPauseReason(store, id, "star_moss_birth_error", true);
        return ReproductiveBagUseResult.recovery();
    }
    static void initializeStatic(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) return;
        var store = PlantIndividuals.get(level); var state = level.getBlockState(pos);
        if (!state.is(LifeSanctuaryMod.STAR_MOSS.get()) || !state.canSurvive(level, pos) || store.isReadOnly() || store.isApplyingStructure()
                || store.atBase(pos).isPresent() || store.atPart(pos).isPresent() || HorsetailPopulations.get(level).member(pos) != null) return;
        var plant = new PlantIndividualState(UUID.randomUUID(), SPECIES, pos, PlantSource.STATIC, ADULT, naturalPopulation(pos),
                false, false, 0, 0, Set.of(), Map.of(StarMossCycles.QUOTA, state.getValue(StarMossBlock.REPRODUCTIVE) ? 1 : 0), PlantOrigin.NATURAL);
        store.create(plant); store.attachParts(plant.id(), Map.of(pos, "star_moss"));
    }
}
