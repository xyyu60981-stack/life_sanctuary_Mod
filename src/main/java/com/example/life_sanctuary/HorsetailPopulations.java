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
    private static final String SPECIES = "equisetum_arvense";
    private static final int REGION_SIZE = 16;
    private static final int POPULATION_LIMIT = 15;
    private static final Factory<HorsetailPopulations> FACTORY = new Factory<>(HorsetailPopulations::new, HorsetailPopulations::load);

    private final Map<BlockPos, Member> members = new HashMap<>();
    private final Map<PlantPopulationTag, Integer> memberCounts = new HashMap<>();
    private final Map<BlockPos, PlantStage> stages = new HashMap<>();
    /** Remaining harvest count for each reproductive structure. */
    private final Map<BlockPos, Integer> remainingSporeHarvests = new HashMap<>();
    private final Map<BlockPos, Integer> remainingSpread = new HashMap<>();
    private final Map<BlockPos, Integer> failedSpreadAttempts = new HashMap<>();
    private final Set<PlantPopulationTag> playerPopulations = new HashSet<>();

    public record Member(PlantSource source, PlantPopulationTag population) {}

    public static HorsetailPopulations get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Member member(BlockPos pos) { return members.get(pos); }

    /** Returns the saved lifecycle stage, or {@code null} for legacy records. */
    public PlantStage stageOf(BlockPos pos) { return stages.get(pos); }

    /** Updates a stage without changing the plant's source or population. */
    public void setStage(BlockPos pos, PlantStage stage) {
        stages.put(pos.immutable(), stage);
        setDirty();
    }

    /** Sets the remaining harvest count when a reproductive structure matures. */
    public void setSporeHarvestCount(BlockPos pos, int count) {
        if (count < 0) throw new IllegalArgumentException("spore harvest count cannot be negative");
        remainingSporeHarvests.put(pos.immutable(), count);
        setDirty();
    }

    /**
     * Atomically consumes the reproductive opportunity at a position.
     *
     * @return {@code true} only for the first successful harvest
     */
    public boolean tryHarvestSporeStem(BlockPos pos) {
        int remaining = remainingSporeHarvests.getOrDefault(pos, 0);
        if (remaining <= 0) return false;
        if (remaining == 1) remainingSporeHarvests.remove(pos);
        else remainingSporeHarvests.put(pos.immutable(), remaining - 1);
        setDirty();
        return true;
    }

    /**
     * Harvest entry point for the future player/collection-box interaction.
     * Inventory consumption happens only after the reproductive quota check
     * succeeds, so an invalid or exhausted harvest never consumes a bag.
     */
    public boolean tryHarvestSporeStem(BlockPos pos, SporeHarvestAccess access) {
        if (!access.hasEmptySporeBag() || !mayGenerateSporeStem(pos)) return false;
        if (!tryHarvestSporeStem(pos)) return false;
        access.consumeEmptySporeBag();
        access.giveSporeBag("equisetum_arvense", pos);
        return true;
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
        var state = level.getBlockState(pos);
        if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_GAMETOPHYTE.get())) {
            stages.put(pos.immutable(), PlantStage.GAMETOPHYTE);
            level.scheduleTick(pos, state.getBlock(), 24000 + level.random.nextInt(48001));
        } else if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get())) {
            stages.put(pos.immutable(), PlantStage.SPROUTS);
            level.scheduleTick(pos, state.getBlock(), 24000);
        } else if (state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())) {
            stages.put(pos.immutable(), PlantStage.ADULT);
            // Horsetail has exactly one spore harvest; other species may use a larger quota.
            remainingSporeHarvests.put(pos.immutable(), 1);
        }
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
        changed |= remainingSporeHarvests.remove(pos) != null;
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
        level.scheduleTick(targetPos, LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get(), 24000);
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
