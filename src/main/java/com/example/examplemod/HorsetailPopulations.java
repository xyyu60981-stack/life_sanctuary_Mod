package com.example.examplemod;

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

/** Per-dimension source labels and player-created population records. */
public final class HorsetailPopulations extends SavedData {
    private static final String DATA_NAME = "life_sanctuary_horsetail_populations";
    private static final String SPECIES = "equisetum_arvense";
    private static final int REGION_SIZE = 16;
    private static final int POPULATION_LIMIT = 15;
    private static final Factory<HorsetailPopulations> FACTORY = new Factory<>(HorsetailPopulations::new, HorsetailPopulations::load);

    private final Map<BlockPos, Member> members = new HashMap<>();
    private final Map<BlockPos, Integer> remainingSpread = new HashMap<>();
    private final Map<BlockPos, Integer> failedSpreadAttempts = new HashMap<>();
    private final Set<PlantPopulationTag> playerPopulations = new HashSet<>();

    public record Member(PlantSource source, PlantPopulationTag population) {}

    public static HorsetailPopulations get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Member member(BlockPos pos) { return members.get(pos); }

    /** Unrecorded world-generated horsetails are static by default. */
    public PlantSource sourceOf(BlockPos pos) {
        Member member = members.get(pos);
        return member == null ? PlantSource.STATIC : member.source();
    }

    public PlantPopulationTag markPlayerPlaced(ServerLevel level, BlockPos pos) {
        PlantPopulationTag tag = PlantPopulationTag.fromBase(SPECIES, PlantBasePosition.singleBlock(pos), REGION_SIZE);
        playerPopulations.add(tag);
        members.put(pos.immutable(), new Member(PlantSource.PLAYER, tag));
        setDirty();
        var state = level.getBlockState(pos);
        if (state.is(ExampleMod.EQUISETUM_ARVENSE_GAMETOPHYTE.get())) {
            level.scheduleTick(pos, state.getBlock(), 24000 + level.random.nextInt(48001));
        } else if (state.is(ExampleMod.EQUISETUM_ARVENSE_SPROUTS.get())) {
            level.scheduleTick(pos, state.getBlock(), 24000);
        }
        return tag;
    }

    public void transition(BlockPos pos, Member member) {
        if (member != null) {
            members.put(pos.immutable(), member);
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        boolean changed = members.remove(pos) != null;
        changed |= remainingSpread.remove(pos) != null;
        changed |= failedSpreadAttempts.remove(pos) != null;
        if (changed) setDirty();
    }

    public int countMembers(PlantPopulationTag tag) {
        return (int) members.values().stream().filter(member -> tag.equals(member.population())).count();
    }

    public boolean canAutoExpand(PlantPopulationTag tag) {
        return playerPopulations.contains(tag) && countMembers(tag) < POPULATION_LIMIT;
    }

    public boolean hasPlayerPopulation(PlantPopulationTag tag) { return playerPopulations.contains(tag); }

    /** Performs one loaded-chunk spread attempt for a player-grown adult. */
    public boolean trySpreadOnce(ServerLevel level, BlockPos parentBase) {
        Member parent = members.get(parentBase);
        if (parent == null || parent.source() != PlantSource.PLAYER || !canAutoExpand(parent.population())) return false;
        if (remainingSpread.getOrDefault(parentBase, 0) <= 0) return false;
        if (!level.getChunkSource().hasChunk(parentBase.getX() >> 4, parentBase.getZ() >> 4)) return false;
        var target = HorsetailSpreadPlanner.findTarget(level, parentBase);
        if (target.isEmpty()) return false;
        BlockPos targetPos = target.get();
        if (!level.getBlockState(targetPos).isAir() && !level.getBlockState(targetPos).canBeReplaced()) return false;
        level.setBlock(targetPos, ExampleMod.EQUISETUM_ARVENSE_SPROUTS.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        members.put(targetPos.immutable(), new Member(PlantSource.PLAYER, parent.population()));
        remainingSpread.computeIfPresent(parentBase, (key, value) -> value <= 1 ? null : value - 1);
        level.scheduleTick(targetPos, ExampleMod.EQUISETUM_ARVENSE_SPROUTS.get(), 24000);
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
                data.members.put(BlockPos.of(entry.getLong("Position")), new Member(PlantSource.valueOf(entry.getString("Source")), population));
            } catch (IllegalArgumentException ignored) {
                // Unknown source labels cannot grant growth or expansion.
            }
        }
        ListTag spreads = tag.getList("SpreadQuotas", Tag.TAG_COMPOUND);
        for (int i = 0; i < spreads.size(); i++) {
            var entry = spreads.getCompound(i);
            data.remainingSpread.put(BlockPos.of(entry.getLong("Position")), entry.getInt("Remaining"));
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
