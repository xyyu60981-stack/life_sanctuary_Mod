package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

/** Separate biomass clock reuses the shared active-chunk scheduler, not the individual's stage timer. */
public final class LargeLeafMossGrowth extends SavedData {
    private static final Factory<LargeLeafMossGrowth> FACTORY = new Factory<>(LargeLeafMossGrowth::new, LargeLeafMossGrowth::load);
    private record Timer(BlockPos pos, int density, long target, long elapsed) {}
    private final Map<UUID, Timer> timers = new HashMap<>();
    private final PlantGrowthScheduler scheduler = new PlantGrowthScheduler();
    private CompoundTag preserved;
    private boolean bound;
    private final Set<UUID> suspended = new java.util.HashSet<>();
    public static LargeLeafMossGrowth get(ServerLevel level) {
        var data = level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_large_leaf_moss");
        if (!data.bound && data.preserved == null) {
            data.bound = true;
            var store = PlantIndividuals.get(level);
            if (!store.isReadOnly()) for (var id : java.util.List.copyOf(data.timers.keySet())) store.find(id)
                    .filter(plant -> plant.species().equals(LargeLeafMossRules.SPECIES)).ifPresent(plant -> {
                        if (!plant.canBePruned()) store.replace(plant, plant.withPruningCapability(true));
                        if (plant.pruned()) data.suspend(id);
                    });
        }
        return data;
    }
    public void refresh(ServerLevel level, BlockPos pos) {
        if (preserved != null || !level.getServer().isSameThread()) return;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LargeLeafMossBlock)) return;
        var store = PlantIndividuals.get(level); if (store.isReadOnly() || store.isApplyingStructure()) return;
        var plant = store.atBase(pos).orElse(null);
        if (plant != null && !plant.species().equals(LargeLeafMossRules.SPECIES)) return;
        if (plant == null) {
            boolean cultivated = state.getValue(LargeLeafMossBlock.CULTIVATED);
            plant = individual(pos, cultivated ? PlantSource.PLAYER : PlantSource.STATIC, cultivated ? PlantOrigin.PLAYER_CULTIVATED : PlantOrigin.NATURAL);
            store.create(plant); store.attachParts(plant.id(), Map.of(pos, "moss"));
        }
        if (!plant.canBePruned()) { var next = plant.withPruningCapability(true); if (!store.replace(plant, next)) return; plant = next; }
        boolean dry = LargeLeafMossBlock.dry(level, pos);
        if (state.getValue(LargeLeafMossBlock.CURLED) != dry) level.setBlock(pos, state.setValue(LargeLeafMossBlock.CURLED, dry), Block.UPDATE_ALL);
        if (plant.pruned()) { suspend(plant.id()); return; }
        if (!plant.source().canExpand() || !plant.environmentReasons().isEmpty() || dry) { cancel(plant.id()); return; }
        int density = state.getValue(LargeLeafMossBlock.COUNT);
        var timer = timers.get(plant.id());
        if (timer == null || timer.density() != density) schedule(plant, density, level);
        else if (suspended.remove(plant.id())) scheduler.updateSystem(plant.id(), timer.pos(), timer.target(), 0);
    }
    private static PlantIndividualState individual(BlockPos pos, PlantSource source, PlantOrigin origin) {
        return new PlantIndividualState(UUID.randomUUID(), LargeLeafMossRules.SPECIES, pos, source, LargeLeafMossRules.STAGE,
                LargeLeafMossRules.population(pos), true, false, 0, 0, Set.of(), Map.of(), origin);
    }
    private void schedule(PlantIndividualState plant, int density, ServerLevel level) {
        var timer = new Timer(plant.base(), density, LargeLeafMossRules.nextDelay(density, level.random), 0);
        timers.put(plant.id(), timer); scheduler.updateSystem(plant.id(), timer.pos(), timer.target(), 0); setDirty();
        suspended.remove(plant.id());
    }
    private void suspend(UUID id) {
        scheduler.cancel(id); boolean changed = suspended.add(id);
        var timer = timers.get(id);
        if (timer != null && timer.elapsed() != 0) { timers.put(id, new Timer(timer.pos(), timer.density(), timer.target(), 0)); setDirty(); }
        else if (timer != null && changed) setDirty();
    }
    /** Called by the shared store for all pruning paths, including future revival-powder adapters. */
    void pruningChanged(ServerLevel level, PlantIndividualState before, PlantIndividualState next) {
        if (preserved != null) return;
        if (next.pruned()) { suspend(next.id()); return; }
        var timer = timers.get(next.id());
        if (timer != null && suspended.remove(next.id())) scheduler.updateSystem(next.id(), timer.pos(), timer.target(), 0);
        // Do not write world blocks or trigger neighbor callbacks inside PlantIndividuals.replace.
        if (level.getChunkSource().hasChunk(next.base().getX() >> 4, next.base().getZ() >> 4)) {
            var block = level.getBlockState(next.base()).getBlock();
            if (block instanceof LargeLeafMossBlock) level.scheduleTick(next.base(), block, 1);
        }
    }
    private void cancel(UUID id) { scheduler.cancel(id); suspended.remove(id); if (timers.remove(id) != null) setDirty(); }
    public void remove(ServerLevel level, BlockPos pos) {
        if (preserved != null) return;
        var store = PlantIndividuals.get(level); if (store.isReadOnly()) return;
        store.atBase(pos).filter(plant -> plant.species().equals(LargeLeafMossRules.SPECIES)).ifPresent(plant -> {
            cancel(plant.id()); store.remove(plant.id(), plant.base());
        });
    }
    public void tick(ServerLevel level) {
        if (preserved == null) scheduler.tickSystems(level, this::setDirty, id -> ready(level, id));
    }
    private void ready(ServerLevel level, UUID id) {
        var timer = timers.get(id); if (timer == null) { scheduler.cancel(id); return; }
        var store = PlantIndividuals.get(level); if (store.isReadOnly()) return;
        var plant = store.find(id).orElse(null); var state = level.getBlockState(timer.pos());
        if (plant == null || !plant.species().equals(LargeLeafMossRules.SPECIES) || !plant.base().equals(timer.pos())
                || !(state.getBlock() instanceof LargeLeafMossBlock)) { cancel(id); return; }
        if (plant.pruned()) { suspend(id); return; }
        if (!plant.environmentReasons().isEmpty()) { cancel(id); return; }
        if (state.getValue(LargeLeafMossBlock.COUNT) != timer.density()) { refresh(level, timer.pos()); return; }
        if (!plant.source().canExpand() || LargeLeafMossBlock.dry(level, timer.pos())) { refresh(level, timer.pos()); return; }
        if (state.getValue(LargeLeafMossBlock.COUNT) < 3) {
            level.setBlock(timer.pos(), state.setValue(LargeLeafMossBlock.COUNT, state.getValue(LargeLeafMossBlock.COUNT) + 1), Block.UPDATE_ALL);
        } else spread(level, store, plant, state);
        // Notifications may destroy this plant: never leave a stale timer occupying a scheduler slot.
        var current = store.find(id).orElse(null); var actual = level.getBlockState(timer.pos());
        if (current != null && actual.getBlock() instanceof LargeLeafMossBlock) schedule(current, actual.getValue(LargeLeafMossBlock.COUNT), level);
        else cancel(id);
    }
    private void spread(ServerLevel level, PlantIndividuals store, PlantIndividualState parent, net.minecraft.world.level.block.state.BlockState state) {
        var rule = PlantSpeciesRegistry.spreadRule(LargeLeafMossRules.SPECIES).orElseThrow();
        if (rule.occupied(store.populationCounts(parent.species(), parent.population())) >= LargeLeafMossRules.LIMIT) return;
        var directions = new java.util.ArrayList<Direction>(java.util.List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        for (int i = directions.size() - 1; i > 0; i--) java.util.Collections.swap(directions, i, level.random.nextInt(i + 1));
        for (var direction : directions) {
            var support = parent.base().below().relative(direction); var pos = support.above();
            if (!LargeLeafMossRules.population(pos).equals(parent.population()) || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            var before = level.getBlockState(pos);
            if (!before.isAir() || !level.getBlockState(support).is(net.minecraft.tags.BlockTags.DIRT) || LargeLeafMossBlock.dry(level, pos)) continue;
            var child = individual(pos, PlantSource.PLAYER, PlantOrigin.AUTOMATIC_SPREAD);
            var after = state.setValue(LargeLeafMossBlock.COUNT, 1).setValue(LargeLeafMossBlock.CULTIVATED, true).setValue(LargeLeafMossBlock.CURLED, false);
            var plan = new PlantStructurePlan(child, Map.of(pos, new PlantStructurePlan.Change(before, after, "moss", PlantStructurePlan.Replacement.AIR_ONLY)),
                    Map.of(support, level.getBlockState(support)));
            var result = PlantOffspringPlacement.place(level, parent, plan);
            if (result.placed()) refresh(level, pos);
            if (result.placed() || result.needsRecovery()) return;
        }
    }
    public static LargeLeafMossGrowth load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new LargeLeafMossGrowth();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.contains("Timers", Tag.TAG_LIST)) throw new IllegalArgumentException();
            var list = tag.getList("Timers", Tag.TAG_COMPOUND); if (list.size() != ((ListTag) tag.get("Timers")).size()) throw new IllegalArgumentException();
            var positions = new java.util.HashSet<BlockPos>();
            for (var value : list) {
                var entry = (CompoundTag) value;
                if (!entry.hasUUID("Id") || !entry.contains("Pos", Tag.TAG_LONG) || !entry.contains("Density", Tag.TAG_INT)
                        || !entry.contains("Target", Tag.TAG_LONG) || !entry.contains("Elapsed", Tag.TAG_LONG)) throw new IllegalArgumentException();
                var timer = new Timer(BlockPos.of(entry.getLong("Pos")), entry.getInt("Density"), entry.getLong("Target"), entry.getLong("Elapsed"));
                if (timer.density() < 1 || timer.density() > 3 || timer.target() < LargeLeafMossRules.DAY || timer.target() > 2L * LargeLeafMossRules.DAY
                        || timer.elapsed() < 0 || timer.elapsed() > timer.target() || timer.density() == 3 && timer.target() != LargeLeafMossRules.DAY
                        || !positions.add(timer.pos()) || data.timers.putIfAbsent(entry.getUUID("Id"), timer) != null) throw new IllegalArgumentException();
            }
            data.timers.forEach((id, timer) -> data.scheduler.updateSystem(id, timer.pos(), timer.target(), timer.elapsed()));
        } catch (RuntimeException failure) { data.timers.clear(); data.preserved = tag.copy(); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1); var list = new ListTag();
        timers.forEach((id, timer) -> { var entry = new CompoundTag(); entry.putUUID("Id", id); entry.putLong("Pos", timer.pos().asLong());
            entry.putInt("Density", timer.density()); entry.putLong("Target", timer.target()); entry.putLong("Elapsed", scheduler.currentElapsed(id, timer.elapsed())); list.add(entry); });
        tag.put("Timers", list); return tag;
    }
}
