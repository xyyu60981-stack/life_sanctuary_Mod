package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension source labels and player-created population records.
 *
 * <p>This is saved data rather than block metadata because population limits,
 * spread quotas and lifecycle stages must survive chunk unloads and world
 * reloads. Unregistered positions are treated as {@link PlantSource#STATIC}
 * so natural scenery remains inert until a later natural-generation pass
 * explicitly registers it.</p>
 */
public final class HorsetailPopulations extends SavedData {
    private static final String DATA_NAME = "life_sanctuary_horsetail_populations";
    private static final String SPECIES = PlantSpeciesRegistry.legacyId(PlantSpeciesRegistry.HORSETAIL);
    private static final int REGION_SIZE = PlantSpeciesRegistry.HORSETAIL.population().orElseThrow().horizontalSize();
    private static final int POPULATION_LIMIT = PlantSpeciesRegistry.HORSETAIL.population().orElseThrow().maximumIndividuals();
    private static final Factory<HorsetailPopulations> FACTORY = new Factory<>(HorsetailPopulations::new, HorsetailPopulations::load);

    private final Map<BlockPos, Member> members = new HashMap<>();
    private final Map<PlantPopulationTag, Integer> memberCounts = new HashMap<>();
    private final Map<BlockPos, PlantStage> stages = new HashMap<>();
    private final Set<BlockPos> pruned = new HashSet<>();
    /** Capability flag; most species never enter the pruning workflow. */
    private final Set<BlockPos> prunable = new HashSet<>();
    private final Map<BlockPos, Long> maturityTicks = new HashMap<>();
    private final Map<BlockPos, PlantEnvironmentState> environmentStates = new HashMap<>();
    private final Map<BlockPos, PlantClimate> climates = new HashMap<>();
    /** Remaining harvest count for each reproductive structure. */
    private final Map<BlockPos, Integer> remainingSporeHarvests = new HashMap<>();
    private final Set<BlockPos> peatHarvestBonus = new HashSet<>();
    private final Map<BlockPos, Integer> remainingSpread = new HashMap<>();
    private final Map<BlockPos, Integer> failedSpreadAttempts = new HashMap<>();
    private final Set<PlantPopulationTag> playerPopulations = new HashSet<>();

    public record Member(PlantSource source, PlantPopulationTag population) {}

    public static HorsetailPopulations get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Member member(BlockPos pos) { return members.get(pos); }

    /** Read-only O(1) inspection. Does not initialize climate, schedule ticks or dirty SavedData. */
    public HorsetailStatus statusAt(BlockPos pos) {
        Member member = members.get(pos);
        PlantSource source = member == null ? PlantSource.STATIC : member.source();
        return new HorsetailStatus(java.util.Optional.ofNullable(stages.get(pos)), source,
                java.util.Optional.ofNullable(member == null ? null : member.population()),
                canBePruned(pos), isPruned(pos), remainingSporeHarvests.getOrDefault(pos, 0),
                maturityTicks(pos), java.util.Optional.ofNullable(source.isStatic() ? null : climates.get(pos)));
    }

    /** Returns the saved lifecycle stage, or {@code null} for legacy records. */
    public PlantStage stageOf(BlockPos pos) { return stages.get(pos); }

    /** Updates a stage without changing the plant's source or population. */
    public void setStage(BlockPos pos, PlantStage stage) {
        stages.put(pos.immutable(), stage);
        setDirty();
    }

    public boolean isPruned(BlockPos pos) { return pruned.contains(pos); }

    public boolean canBePruned(BlockPos pos) { return prunable.contains(pos); }

    public void setCanBePruned(BlockPos pos, boolean value) {
        BlockPos key = pos.immutable();
        if (value) prunable.add(key); else {
            prunable.remove(key);
            pruned.remove(key);
        }
        setDirty();
    }

    public void setPruned(BlockPos pos, boolean value) {
        BlockPos key = pos.immutable();
        if (value) pruned.add(key); else pruned.remove(key);
        setDirty();
    }

    public long maturityTicks(BlockPos pos) { return maturityTicks.getOrDefault(pos, 0L); }

    public void setMaturityTicks(BlockPos pos, long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("maturity ticks cannot be negative");
        maturityTicks.put(pos.immutable(), ticks);
        setDirty();
    }

    public PlantEnvironmentState environmentState(BlockPos pos) {
        return environmentStates.getOrDefault(pos, PlantEnvironmentState.UNKNOWN);
    }

    public void setEnvironmentState(BlockPos pos, PlantEnvironmentState state) {
        environmentStates.put(pos.immutable(), state == null ? PlantEnvironmentState.UNKNOWN : state);
        setDirty();
    }

    /** Resolves UNKNOWN from the loaded biome and stores the result for later decisions. */
    public PlantEnvironmentState refreshEnvironmentState(ServerLevel level, BlockPos pos) {
        if (sourceOf(pos).isStatic()) return PlantEnvironmentState.UNKNOWN;
        PlantClimate climate = climateOf(level, pos);
        PlantEnvironmentState resolved;
        if (climate.dry()) resolved = PlantEnvironmentState.DRY;
        else if (climate.cold()) resolved = PlantEnvironmentState.COLD;
        else if (climate.hot()) resolved = PlantEnvironmentState.HOT;
        else resolved = PlantEnvironmentState.SUITABLE;
        if (environmentState(pos) != resolved) setEnvironmentState(pos, resolved);
        return resolved;
    }

    /** Null for static scenery. Legacy single-value caches are re-evaluated once. */
    public PlantClimate climateOf(ServerLevel level, BlockPos pos) {
        if (sourceOf(pos).isStatic()) return null;
        PlantClimate cached = climates.get(pos);
        if (cached != null) return cached;
        PlantClimate climate = new PlantClimate(PlantEnvironment.isDry(level, pos),
                PlantEnvironment.isCold(level, pos), PlantEnvironment.isHot(level, pos));
        climates.put(pos.immutable(), climate);
        setDirty();
        return climate;
    }

    public boolean canGrowInClimate(ServerLevel level, BlockPos pos) {
        PlantClimate climate = climateOf(level, pos);
        return climate != null && !climate.cold();
    }

    /** Same plant, new stage: preserve per-individual data rather than deleting it in onRemove. */
    public static boolean isLivingHorsetail(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())
                || state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get())
                || state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_GAMETOPHYTE.get());
    }

    /** Sets the remaining harvest count when a reproductive structure matures. */
    public void setSporeHarvestCount(BlockPos pos, int count) {
        if (count < 0) throw new IllegalArgumentException("spore harvest count cannot be negative");
        remainingSporeHarvests.put(pos.immutable(), count);
        setDirty();
    }

    /** Initializes one newly placed reproductive structure without touching other positions. */
    public void initializeSporeStem(ServerLevel level, BlockPos pos) {
        BlockPos key = pos.immutable();
        if (!remainingSporeHarvests.containsKey(key)) {
            var bonus = PeatPlantInteraction.capture(level, pos, level.getBlockState(pos));
            remainingSporeHarvests.put(key, PeatPlantInteraction.initialHarvests(1, bonus));
            if (bonus == PeatPlantInteraction.Bonus.SPORE_HARVEST) peatHarvestBonus.add(key);
            setDirty();
        }
    }

    public boolean hasPeatHarvestBonus(BlockPos pos) { return peatHarvestBonus.contains(pos); }

    /**
     * Atomically consumes the reproductive opportunity at this single position.
     *
     * @return {@code true} while this position still has a harvest remaining
     */
    public boolean tryHarvestSporeStem(BlockPos pos) {
        int remaining = remainingSporeHarvests.getOrDefault(pos, 0);
        if (remaining <= 0) return false;
        // Keep zero as an initialized/exhausted marker so peat cannot refill a spent structure.
        remainingSporeHarvests.put(pos.immutable(), remaining - 1);
        setDirty();
        return true;
    }

    /**
     * Harvest entry point for the future player/collection-box interaction.
     * Inventory consumption happens only after the reproductive quota check
     * succeeds, so an invalid or exhausted harvest never consumes a bag.
     */
    public boolean tryHarvestSporeStem(BlockPos pos, SporeHarvestAccess access) {
        if (!mayGenerateSporeStem(pos)) return false;
        if (!access.exchangeSporeBag("equisetum_arvense", pos)) return false;
        return tryHarvestSporeStem(pos);
    }

    /** Reproductive structures must not be regenerated after a successful harvest. */
    public boolean mayGenerateSporeStem(BlockPos pos) {
        return remainingSporeHarvests.getOrDefault(pos, 0) > 0;
    }

    /** Unrecorded world-generated horsetails are static by default. */
    public PlantSource sourceOf(BlockPos pos) {
        Member member = members.get(pos);
        return member == null ? PlantSource.STATIC : member.source();
    }

    public PlantPopulationTag markPlayerPlaced(ServerLevel level, BlockPos pos) {
        return markPlaced(level, pos, PlantSource.PLAYER);
    }

    /** Registers a one-time cultivation result without granting expansion rights. */
    public PlantPopulationTag markOneTimePlaced(ServerLevel level, BlockPos pos) {
        return markPlaced(level, pos, PlantSource.ONE_TIME);
    }

    private PlantPopulationTag markPlaced(ServerLevel level, BlockPos pos, PlantSource source) {
        PlantPopulationTag tag = PlantPopulationTag.fromBase(SPECIES, PlantBasePosition.singleBlock(pos), REGION_SIZE);
        if (source.canExpand()) playerPopulations.add(tag);
        putMember(pos, new Member(source, tag));
        setDirty();
        pruned.remove(pos);
        prunable.remove(pos);
        maturityTicks.put(pos.immutable(), 0L);
        environmentStates.put(pos.immutable(), PlantEnvironmentState.UNKNOWN);
        climates.remove(pos);
        var state = level.getBlockState(pos);
        if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_GAMETOPHYTE.get())) {
            stages.put(pos.immutable(), PlantStage.GAMETOPHYTE);
            level.scheduleTick(pos, state.getBlock(), HorsetailSpreadRules.GAMETOPHYTE_TO_SPROUTS_MIN_TICKS
                    + level.random.nextInt(HorsetailSpreadRules.GAMETOPHYTE_TO_SPROUTS_MAX_TICKS
                    - HorsetailSpreadRules.GAMETOPHYTE_TO_SPROUTS_MIN_TICKS + 1));
        } else if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get())) {
            stages.put(pos.immutable(), PlantStage.SPROUTS);
            level.scheduleTick(pos, state.getBlock(), HorsetailSpreadRules.SPROUTS_TO_ADULT_TICKS);
        } else if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())) {
            stages.put(pos.immutable(), PlantStage.ADULT);
            // Capture only once the adult actually carries a reproductive structure.
            if (state.getValue(EquisetumArvenseBlock.SPORE_STEM)) initializeSporeStem(level, pos);
        }
        refreshEnvironmentState(level, pos);
        PlantWaterMonitoring.get(level).register(level, pos, source, tag);
        setDirty();
        return tag;
    }

    public void transition(BlockPos pos, Member member) {
        if (member != null) {
            putMember(pos, member);
            setDirty();
        }
    }

    private void putMember(BlockPos pos, Member member) {
        BlockPos key = pos.immutable();
        Member previous = members.put(key, member);
        if (previous != null) memberCounts.computeIfPresent(previous.population(), (tag, count) -> count <= 1 ? null : count - 1);
        memberCounts.merge(member.population(), 1, Integer::sum);
    }

    public void remove(BlockPos pos) {
        Member removed = members.remove(pos);
        boolean changed = removed != null;
        if (removed != null) memberCounts.computeIfPresent(removed.population(), (tag, count) -> count <= 1 ? null : count - 1);
        changed |= stages.remove(pos) != null;
        changed |= pruned.remove(pos);
        changed |= prunable.remove(pos);
        changed |= maturityTicks.remove(pos) != null;
        changed |= environmentStates.remove(pos) != null;
        changed |= climates.remove(pos) != null;
        changed |= remainingSporeHarvests.remove(pos) != null;
        changed |= peatHarvestBonus.remove(pos);
        changed |= remainingSpread.remove(pos) != null;
        changed |= failedSpreadAttempts.remove(pos) != null;
        if (changed) setDirty();
    }

    public int countMembers(PlantPopulationTag tag) {
        return memberCounts.getOrDefault(tag, 0);
    }

    public boolean canAutoExpand(PlantPopulationTag tag) {
        return playerPopulations.contains(tag) && countMembers(tag) < POPULATION_LIMIT;
    }

    public boolean hasPlayerPopulation(PlantPopulationTag tag) { return playerPopulations.contains(tag); }

    /** Performs one loaded-chunk spread attempt for a player-grown adult. */
    public boolean trySpreadOnce(ServerLevel level, BlockPos parentBase) {
        Member parent = members.get(parentBase);
        if (parent == null || !parent.source().canExpand() || !canAutoExpand(parent.population())) return false;
        if (!canGrowInClimate(level, parentBase)) return false;
        if (remainingSpread.getOrDefault(parentBase, 0) <= 0) return false;
        if (!level.getChunkSource().hasChunk(parentBase.getX() >> 4, parentBase.getZ() >> 4)) return false;
        // The planner performs a bounded 5x3x5 search; never scan the whole population.
        var target = HorsetailSpreadPlanner.findTarget(level, parentBase);
        if (target.isEmpty()) return false;
        BlockPos targetPos = target.get();
        // The planner already checked the replaceable block above its substrate.
        level.setBlock(targetPos, LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        putMember(targetPos, new Member(PlantSource.PLAYER, parent.population()));
        remainingSpread.computeIfPresent(parentBase, (key, value) -> value <= 1 ? null : value - 1);
        level.scheduleTick(targetPos, LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get(), HorsetailSpreadRules.SPROUTS_TO_ADULT_TICKS);
        setDirty();
        return true;
    }

    public int beginOrGetSpreadQuota(BlockPos parentBase, net.minecraft.util.RandomSource random) {
        return remainingSpread.computeIfAbsent(parentBase.immutable(), ignored -> 2 + random.nextInt(2));
    }

    public int remainingSpread(BlockPos parentBase) { return remainingSpread.getOrDefault(parentBase, 0); }

    public int nextSpreadRetryDelay(BlockPos parentBase, net.minecraft.util.RandomSource random) {
        int failures = failedSpreadAttempts.merge(parentBase.immutable(), 1, Integer::sum);
        int base = HorsetailSpreadRules.DELAY_MIN_TICKS
                + random.nextInt(HorsetailSpreadRules.DELAY_MAX_TICKS - HorsetailSpreadRules.DELAY_MIN_TICKS + 1);
        int extendedFailures = Math.max(0, failures - GametophyteSpreadRules.FAILED_RETRY_THRESHOLD);
        return Math.min(HorsetailSpreadRules.MAX_RETRY_DELAY_TICKS, base + extendedFailures * 200);
    }

    public void resetSpreadRetry(BlockPos parentBase) {
        if (failedSpreadAttempts.remove(parentBase) != null) setDirty();
    }

    private static HorsetailPopulations load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new HorsetailPopulations();
        ListTag populations = tag.getList("Populations", Tag.TAG_COMPOUND);
        for (int i = 0; i < populations.size(); i++) {
            var entry = populations.getCompound(i);
            data.playerPopulations.add(new PlantPopulationTag(SPECIES, entry.getInt("X"), entry.getInt("Z"), REGION_SIZE));
        }
        ListTag plants = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < plants.size(); i++) {
            var entry = plants.getCompound(i);
            PlantPopulationTag population = new PlantPopulationTag(SPECIES, entry.getInt("PopulationX"), entry.getInt("PopulationZ"), REGION_SIZE);
            try {
                BlockPos position = BlockPos.of(entry.getLong("Position"));
                data.putMember(position, new Member(PlantSource.valueOf(entry.getString("Source")), population));
                if (entry.contains("Stage")) data.stages.put(position, PlantStage.valueOf(entry.getString("Stage")));
                if (entry.getBoolean("Pruned")) data.pruned.add(position);
                if (entry.getBoolean("CanBePruned")) data.prunable.add(position);
                if (entry.contains("MaturityTicks")) data.maturityTicks.put(position, entry.getLong("MaturityTicks"));
                if (entry.contains("Environment")) data.environmentStates.put(position,
                        PlantEnvironmentState.valueOf(entry.getString("Environment")));
                if (entry.contains("Climate")) {
                    CompoundTag climate = entry.getCompound("Climate");
                    data.climates.put(position, new PlantClimate(climate.getBoolean("Dry"),
                            climate.getBoolean("Cold"), climate.getBoolean("Hot")));
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown source labels cannot grant growth or expansion.
            }
        }
        ListTag spreads = tag.getList("SpreadQuotas", Tag.TAG_COMPOUND);
        for (int i = 0; i < spreads.size(); i++) {
            var entry = spreads.getCompound(i);
            data.remainingSpread.put(BlockPos.of(entry.getLong("Position")), entry.getInt("Remaining"));
        }
        ListTag harvested = tag.getList("SporeHarvests", Tag.TAG_COMPOUND);
        for (int i = 0; i < harvested.size(); i++) {
            var entry = harvested.getCompound(i);
            data.remainingSporeHarvests.put(BlockPos.of(entry.getLong("Position")), entry.getInt("Remaining"));
            if (entry.getBoolean("PeatBonus")) data.peatHarvestBonus.add(BlockPos.of(entry.getLong("Position")));
        }
        // Older development saves only recorded whether the one-shot harvest had happened.
        ListTag legacyHarvested = tag.getList("HarvestedSporeStems", Tag.TAG_LONG);
        for (int i = 0; i < legacyHarvested.size(); i++) {
            data.remainingSporeHarvests.putIfAbsent(BlockPos.of(((net.minecraft.nbt.LongTag) legacyHarvested.get(i)).getAsLong()), 0);
        }
        ListTag failures = tag.getList("SpreadFailures", Tag.TAG_COMPOUND);
        for (int i = 0; i < failures.size(); i++) {
            var entry = failures.getCompound(i);
            data.failedSpreadAttempts.put(BlockPos.of(entry.getLong("Position")), entry.getInt("Count"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag populations = new ListTag();
        for (PlantPopulationTag population : playerPopulations) {
            var entry = new CompoundTag();
            entry.putInt("X", population.minimumX());
            entry.putInt("Z", population.minimumZ());
            populations.add(entry);
        }
        tag.put("Populations", populations);
        ListTag plants = new ListTag();
        for (var plant : members.entrySet()) {
            var entry = new CompoundTag();
            entry.putLong("Position", plant.getKey().asLong());
            entry.putString("Source", plant.getValue().source().name());
            entry.putInt("PopulationX", plant.getValue().population().minimumX());
            entry.putInt("PopulationZ", plant.getValue().population().minimumZ());
            PlantStage stage = stages.get(plant.getKey());
            if (stage != null) entry.putString("Stage", stage.name());
            if (pruned.contains(plant.getKey())) entry.putBoolean("Pruned", true);
            if (prunable.contains(plant.getKey())) entry.putBoolean("CanBePruned", true);
            Long maturity = maturityTicks.get(plant.getKey());
            if (maturity != null) entry.putLong("MaturityTicks", maturity);
            PlantEnvironmentState environment = environmentStates.get(plant.getKey());
            if (environment != null) entry.putString("Environment", environment.name());
            PlantClimate climate = climates.get(plant.getKey());
            if (climate != null) {
                CompoundTag saved = new CompoundTag();
                saved.putBoolean("Dry", climate.dry());
                saved.putBoolean("Cold", climate.cold());
                saved.putBoolean("Hot", climate.hot());
                entry.put("Climate", saved);
            }
            plants.add(entry);
        }
        tag.put("Members", plants);
        ListTag spreads = new ListTag();
        for (var spread : remainingSpread.entrySet()) {
            var entry = new CompoundTag();
            entry.putLong("Position", spread.getKey().asLong());
            entry.putInt("Remaining", spread.getValue());
            spreads.add(entry);
        }
        tag.put("SpreadQuotas", spreads);
        ListTag harvested = new ListTag();
        for (var entry : remainingSporeHarvests.entrySet()) {
            var saved = new CompoundTag();
            saved.putLong("Position", entry.getKey().asLong());
            saved.putInt("Remaining", entry.getValue());
            saved.putBoolean("PeatBonus", peatHarvestBonus.contains(entry.getKey()));
            harvested.add(saved);
        }
        tag.put("SporeHarvests", harvested);
        ListTag failures = new ListTag();
        for (var failure : failedSpreadAttempts.entrySet()) {
            var entry = new CompoundTag();
            entry.putLong("Position", failure.getKey().asLong());
            entry.putInt("Count", failure.getValue());
            failures.add(entry);
        }
        tag.put("SpreadFailures", failures);
        return tag;
    }
}
