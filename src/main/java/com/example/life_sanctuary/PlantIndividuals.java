package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension individual store. No world scans, chunk loads, timer advancement or automatic
 * registration occur during reads. Unknown species/stages remain preserved for optional modules.
 */
public final class PlantIndividuals extends SavedData {
    private static final int VERSION = 12;
    private static final Factory<PlantIndividuals> FACTORY = new Factory<>(PlantIndividuals::new, PlantIndividuals::load);
    private final Map<UUID, PlantIndividualState> individuals = new HashMap<>();
    private final Map<BlockPos, UUID> byBase = new HashMap<>();
    private final Map<BlockPos, UUID> partOwners = new HashMap<>();
    private final Map<UUID, Map<BlockPos, String>> parts = new HashMap<>();
    private final PlantGrowthScheduler growth = new PlantGrowthScheduler();
    private final PlantGrowthScheduler withering = new PlantGrowthScheduler(32);
    private final Map<UUID, PlantWitheringTimer> witheringTimers = new HashMap<>();
    private final Map<UUID, Map<String, PlantFlowerState>> flowers = new HashMap<>();
    private final Map<UUID, PlantFlowerRound> flowerRounds = new HashMap<>();
    private final Map<UUID, PlantLeafData> leafData = new HashMap<>();
    private final Map<UUID, PlantLeafCounts> leafCounts = new HashMap<>();
    private final Map<UUID, PlantLeafPartIndex> leafPartIndices = new HashMap<>();
    private final PlantLeafScheduler leafScheduler = new PlantLeafScheduler();
    private final PlantPollenIndex pollenIndex = new PlantPollenIndex();
    private final PlantPopulationIndex populationIndex = new PlantPopulationIndex();
    private CompoundTag preservedUnsupportedData;
    private UUID applyingStructure;
    private RhizomeNaturalStructurePlan applyingNatural;
    private Map<UUID, Map<BlockPos, String>> applyingNaturalParts = Map.of();

    public static PlantIndividuals get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_plant_individuals");
    }

    public boolean isReadOnly() { return preservedUnsupportedData != null; }
    public boolean isApplyingStructure(UUID id) { return id.equals(applyingStructure)
            || applyingNatural != null && applyingNatural.plants().containsKey(id); }
    /** 新部件尚未提交归属索引时，方块回调仍可识别当前结构事务。 */
    public boolean isApplyingStructure() { return applyingStructure != null; }

    void beginStructure(PlantIndividualState expected) {
        writable();
        if (!expected.equals(find(expected.id()).orElse(null)))
            throw new IllegalStateException("Stale structure snapshot");
        applyingStructure = expected.id();
    }

    void endStructure() { applyingStructure = null; }

    /** 整组自然结构专用；只准备索引，不在世界写入前暴露半个种群。 */
    void beginNaturalStructure(RhizomeNaturalStructurePlan plan) {
        writable();
        validateNaturalRegistration(plan);
        Map<UUID, Map<BlockPos, String>> prepared = new HashMap<>();
        plan.plants().forEach((id, structure) -> {
            Map<BlockPos, String> roles = new HashMap<>();
            structure.changes().forEach((pos, change) -> roles.put(pos, change.role()));
            // 存储内部部件表须可变，供未来 attachParts/detachPart 使用；不向外暴露此表。
            prepared.put(id, roles);
        });
        applyingNaturalParts = Map.copyOf(prepared);
        applyingNatural = plan;
        applyingStructure = plan.population().plants().keySet().iterator().next();
    }

    private void validateNaturalRegistration(RhizomeNaturalStructurePlan plan) {
        if (isReadOnly()) throw new IllegalStateException("Plant data unavailable");
        for (var structure : plan.plants().values()) {
            var state = structure.expectedIndividual();
            if (individuals.containsKey(state.id()) || byBase.containsKey(state.base())
                    || partOwners.containsKey(state.base())) throw new IllegalStateException("Natural identity already occupied");
            for (var pos : structure.changes().keySet())
                if (partOwners.containsKey(pos) || byBase.containsKey(pos))
                    throw new IllegalStateException("Natural structure already occupied");
        }
    }

    void commitNaturalStructure(RhizomeNaturalStructurePlan plan) {
        if (applyingNatural != plan || !isApplyingStructure()) throw new IllegalStateException("Natural batch lock lost");
        validateNaturalRegistration(plan);
        // STATIC 不进入成长/枯死队列；没有花结构时也无需建立花粉索引。
        for (var state : plan.population().plants().values()) {
            individuals.put(state.id(), state);
            byBase.put(state.base(), state.id());
            var roles = applyingNaturalParts.get(state.id());
            parts.put(state.id(), roles);
            roles.keySet().forEach(pos -> partOwners.put(pos, state.id()));
            populationIndex.add(state);
        }
        setDirty();
    }

    /** 仅撤销当前锁内新登记的整组身份；不能作为任意删除活体的公共入口。 */
    void rollbackNaturalStructure(RhizomeNaturalStructurePlan plan) {
        if (applyingNatural != plan) throw new IllegalStateException("Natural batch lock lost");
        for (var state : plan.population().plants().values()) {
            if (!individuals.remove(state.id(), state)) continue;
            byBase.remove(state.base(), state.id());
            var roles = parts.remove(state.id());
            if (roles != null) roles.keySet().forEach(pos -> partOwners.remove(pos, state.id()));
            populationIndex.remove(state);
        }
        setDirty();
    }

    void endNaturalStructure() {
        applyingNatural = null;
        applyingNaturalParts = Map.of();
        applyingStructure = null;
    }

    void markNaturalRecovery(RhizomeNaturalStructurePlan plan) {
        if (applyingNatural != plan) throw new IllegalStateException("Natural batch lock lost");
        for (var original : plan.population().plants().values()) {
            var state = individuals.get(original.id());
            if (state == null) continue;
            var reasons = new HashSet<>(state.environmentReasons());
            reasons.add("structure_commit_error");
            individuals.put(state.id(), state.withEnvironmentReasons(reasons));
        }
        setDirty();
    }

    /** 地下系统没有个体身份，仍须在其世界写入期间阻止个体索引被回调占用。 */
    void beginRhizomeStructure(UUID systemId) {
        writable();
        applyingStructure = java.util.Objects.requireNonNull(systemId);
    }

    /** Called only after all world writes have succeeded while the store is locked. */
    void commitStructure(PlantStructurePlan plan, PlantIndividualState next,
            PlantReproductionDebit debit) {
        commitStructure(plan, next, debit, null);
    }

    void commitStructure(PlantStructurePlan plan, PlantIndividualState next,
            PlantReproductionDebit debit, PlantLeafStructureChange leafChange) {
        if (!isApplyingStructure(next.id())
                || !plan.expectedIndividual().equals(find(next.id()).orElse(null)))
            throw new IllegalStateException("Structure commit lost its owner");
        PlantIndividualState parentAfter = null;
        if (debit != null) {
            if (debit.expected().id().equals(next.id())
                    || !debit.expected().equals(find(debit.expected().id()).orElse(null)))
                throw new IllegalStateException("Reproduction parent changed before commit");
            parentAfter = debit.after();
        }
        Map<BlockPos, String> updated = new HashMap<>(parts.getOrDefault(next.id(), Map.of()));
        plan.changes().forEach((pos, change) -> {
            if (change.role().isEmpty()) updated.remove(pos);
            else updated.put(pos, change.role());
        });
        if (leafChange != null && !leavesOf(next.id()).equals(leafChange.expected()))
            throw new IllegalStateException("Stale leaf structure snapshot");
        var updatedLeaves = leafChange == null ? leavesOf(next.id()).map(value -> value.afterParts(updated)).orElse(null)
                : leafChange.afterParts(next, updated);
        if (updatedLeaves != null) updatedLeaves.validateOwner(next, updated);
        PlantWitheringTimer budLossTimer = null;
        if (leafChange instanceof PlantBudLossChange loss) {
            if (witheringTimers.containsKey(next.id()) || PlantWithering.isWithering(plan.expectedIndividual()))
                throw new IllegalStateException("Cannot reset existing bud loss process");
            budLossTimer = new PlantWitheringTimer(loss.duration(), 0, false);
        }
        // Ownership was checked before locking; public mutations are rejected during world callbacks.
        Map<BlockPos, String> old = parts.remove(next.id());
        if (old != null) old.keySet().forEach(pos -> partOwners.remove(pos, next.id()));
        if (!updated.isEmpty()) {
            parts.put(next.id(), updated);
            updated.keySet().forEach(pos -> partOwners.put(pos, next.id()));
        }
        populationIndex.changed(plan.expectedIndividual(), next);
        individuals.put(next.id(), next);
        if (updatedLeaves != null) putLeafData(next.id(), updatedLeaves);
        leafScheduler.update(next, updatedLeaves);
        growth.update(next);
        if (budLossTimer != null) {
            witheringTimers.put(next.id(), budLossTimer);
            withering.updateWithering(next, budLossTimer);
        }
        refreshPollenIndex(next.id());
        // Both records share this SavedData payload. No world/species callbacks between these writes.
        // Only the budget changes: the parent's existing timer and population index remain valid.
        if (parentAfter != null) individuals.put(parentAfter.id(), parentAfter);
        setDirty();
    }
    public Optional<PlantIndividualState> find(UUID id) {
        PlantIndividualState state = individuals.get(id);
        return state == null ? Optional.empty() : Optional.of(growth.current(state));
    }

    public void tickGrowth(ServerLevel level) {
        if (!isReadOnly()) {
            growth.tick(level, this);
            withering.tickWithering(level, this);
            leafScheduler.tick(level, this);
        }
    }

    /** 重复触发不刷新已保存的独立倒计时，包括已到期或失败的任务。 */
    public PlantLifecycle.Result beginWithering(PlantIndividualState expected, long duration) {
        if (duration <= 0) throw new IllegalArgumentException("Withering duration must be positive");
        if (isReadOnly() || !expected.equals(find(expected.id()).orElse(null))) return PlantLifecycle.Result.REJECTED;
        writable();
        if (witheringTimers.containsKey(expected.id())) return PlantLifecycle.Result.UNCHANGED;
        var reasons = new HashSet<>(expected.environmentReasons());
        reasons.add(PlantWithering.BUD_CLUSTER_DESTROYED);
        var next = expected.withEnvironmentReasons(reasons);
        if (!replace(expected, next)) return PlantLifecycle.Result.REJECTED;
        var timer = new PlantWitheringTimer(duration, 0, false);
        witheringTimers.put(next.id(), timer);
        withering.updateWithering(next, timer);
        setDirty();
        return PlantLifecycle.Result.CHANGED;
    }

    public Optional<PlantWitheringTimer> witheringTimer(UUID id) {
        var timer = witheringTimers.get(id);
        return timer == null ? Optional.empty() : Optional.of(new PlantWitheringTimer(timer.targetTicks(),
                withering.currentElapsed(id, timer.elapsedTicks()), timer.failed()));
    }

    /** 回调失败后停止重试，保留到期快照与诊断，等待显式恢复。 */
    void failWithering(UUID id) {
        writable();
        var timer = witheringTimer(id).orElse(null);
        if (timer == null) return;
        witheringTimers.put(id, new PlantWitheringTimer(timer.targetTicks(), timer.elapsedTicks(), true));
        withering.cancel(id);
        find(id).ifPresent(state -> {
            var reasons = new HashSet<>(state.environmentReasons());
            reasons.add("withering_callback_error");
            replace(state, state.withEnvironmentReasons(reasons));
        });
        setDirty();
    }

    /** Counts registered individuals only; does not discover unregistered natural scenery. */
    public PlantPopulationCounts populationCounts(ResourceLocation species, String population) {
        if (isReadOnly()) throw new IllegalStateException("Population data is unavailable in read-only recovery mode");
        return populationIndex.snapshot(species, population);
    }
    public Optional<PlantIndividualState> atBase(BlockPos pos) {
        UUID id = byBase.get(pos);
        return id == null ? Optional.empty() : find(id);
    }

    /** Base is an identity anchor; structural positions are registered explicitly. */
    public Optional<PlantIndividualState> atPart(BlockPos pos) {
        UUID id = partOwners.get(pos);
        return id == null ? Optional.empty() : find(id);
    }

    public Map<BlockPos, String> partsOf(UUID id) {
        return Map.copyOf(parts.getOrDefault(id, Map.of()));
    }

    /** 不可变叶快照；没有记录表示尚未接入，不代表可自动领取全新次数。 */
    public Optional<PlantLeafData> leavesOf(UUID id) { return Optional.ofNullable(leafScheduler.current(id, leafData.get(id))); }

    public Optional<PlantLeafCounts> leafCounts(UUID id) { return Optional.ofNullable(leafCounts.get(id)); }

    Optional<String> leafKeyAtPart(UUID id, BlockPos pos) {
        var index = leafPartIndices.get(id);
        var role = partRole(id, pos).orElse(null);
        return index == null || role == null ? Optional.empty() : index.keyForRole(role);
    }

    private void putLeafData(UUID id, PlantLeafData next) {
        var previous = leafData.get(id);
        // 仅计时变化时共用同一不可变叶账本，避免再次遍历历史键。
        if (previous == null || previous.leaves() != next.leaves()) {
            leafCounts.put(id, PlantLeafCounts.from(next.leaves()));
            leafPartIndices.put(id, new PlantLeafPartIndex(next.leaves()));
        }
        leafData.put(id, next);
    }

    /** 仅供物种结构接入层首次初始化；重复初始化拒绝，不提供任意覆盖资源的公开入口。 */
    boolean initializeLeaves(PlantIndividualState expected, PlantLeafData initial) {
        writable();
        if (!expected.equals(find(expected.id()).orElse(null)) || leafData.containsKey(expected.id())) return false;
        initial.validateOwner(expected, parts.getOrDefault(expected.id(), Map.of()));
        putLeafData(expected.id(), initial);
        leafScheduler.update(expected, initial);
        setDirty();
        return true;
    }

    private void synchronizeLeafParts(UUID id) {
        var current = leavesOf(id).orElse(null);
        if (current == null) return;
        var next = current.afterParts(parts.getOrDefault(id, Map.of()));
        if (!next.equals(current)) {
            putLeafData(id, next);
            leafScheduler.update(find(id).orElseThrow(), next);
        }
    }

    /** 叶回调异常时保留当前活动进度并冻结本株全部叶任务，不重试半完成的世界操作。 */
    void failLeafTimers(UUID id) {
        writable();
        var current = leavesOf(id).orElse(null);
        if (current == null) return;
        var shedding = new HashMap<String, PlantLeafTimer>();
        current.timers().shedding().forEach((key, timer) -> shedding.put(key, timer.freezeForRecovery()));
        var timers = new PlantLeafTimers(current.timers().regrowth().map(PlantLeafTimer::freezeForRecovery), shedding);
        putLeafData(id, new PlantLeafData(current.leaves(), timers));
        leafScheduler.cancel(id);
        setDirty();
    }

    /** 库存交换期间暂扣一次，持有结构锁，公共回调不能重入修改植株。 */
    PlantLeafData reserveLeafCollection(PlantIndividualState expected, PlantLeafData before, String key) {
        requireLeafCollectionLock(expected);
        if (!PlantHarvestEligibility.canCollectSporeOrSeed(expected))
            throw new IllegalArgumentException("Spore collection requires a fully adult stage");
        if (!leavesOf(expected.id()).filter(before::equals).isPresent()) throw new IllegalStateException("Stale leaf collection");
        var old = before.leaves().leaves().get(key);
        if (old == null) throw new IllegalArgumentException("Unknown leaf resource");
        var nextLeaf = old.collect().orElseThrow();
        var next = before.leaves().replace(key, old, nextLeaf).orElseThrow();
        var reserved = new PlantLeafData(next, before.timers());
        reserved.validateOwner(expected, parts.getOrDefault(expected.id(), Map.of()));
        putLeafData(expected.id(), reserved);
        return reserved;
    }

    void restoreLeafCollection(PlantIndividualState expected, PlantLeafData reserved, PlantLeafData before) {
        requireLeafCollectionLock(expected);
        if (!leavesOf(expected.id()).filter(reserved::equals).isPresent()) throw new IllegalStateException("Leaf reservation changed");
        putLeafData(expected.id(), before);
    }

    void finishLeafCollection(PlantIndividualState expected, PlantLeafData reserved, String key,
            net.minecraft.util.RandomSource random) {
        requireLeafCollectionLock(expected);
        if (!leavesOf(expected.id()).filter(reserved::equals).isPresent()) throw new IllegalStateException("Leaf reservation changed");
        var timers = reserved.timers();
        if (reserved.leaves().leaves().get(key).exhausted() && expected.source().canProgress())
            timers = timers.startShedding(reserved.leaves(), key, expected.source(), random).orElseThrow();
        var next = new PlantLeafData(reserved.leaves(), timers);
        next.validateOwner(expected, parts.getOrDefault(expected.id(), Map.of()));
        putLeafData(expected.id(), next);
        leafScheduler.update(expected, next);
        setDirty();
    }

    private void requireLeafCollectionLock(PlantIndividualState expected) {
        if (isReadOnly() || !isApplyingStructure(expected.id()) || !expected.equals(find(expected.id()).orElse(null)))
            throw new IllegalStateException("Leaf collection lock or identity lost");
    }

    public Optional<String> partRole(UUID id, BlockPos pos) {
        return Optional.ofNullable(parts.getOrDefault(id, Map.of()).get(pos));
    }

    public Optional<PlantFlowerState> flower(UUID id, String key) {
        return Optional.ofNullable(flowers.getOrDefault(id, Map.of()).get(key));
    }

    public Optional<PlantFlowerRound> flowerRound(UUID id) { return Optional.ofNullable(flowerRounds.get(id)); }

    boolean flowerRoundActive(UUID id) {
        var round = flowerRounds.get(id);
        return round == null || round.allowsPollenSupply();
    }

    boolean acceptsPollenInRound(UUID id) {
        var round = flowerRounds.get(id);
        return round == null || round.allowsPollenReception();
    }

    /** 当前窗口未结束时不能另开编号清除失败；重复进入同一窗口不重置失败记录。 */
    public boolean beginFlowerRound(PlantIndividualState expected, long roundId) {
        var next = new PlantFlowerRound(roundId, true, false);
        if (isReadOnly() || !expected.equals(find(expected.id()).orElse(null))) return false;
        writable();
        var current = flowerRounds.get(expected.id());
        if (current != null && current.active()) return current.id() == roundId;
        if (current != null && roundId <= current.id()) return false;
        flowerRounds.put(expected.id(), next);
        refreshPollenIndex(expected.id());
        setDirty();
        return true;
    }

    /** 仅真实雌性受粉判定失败才调用；只关闭本株受粉侧，雄花/完全花供粉不受影响。 */
    public boolean failFlowerRound(PlantIndividualState expected, long roundId, String flowerKey) {
        if (isReadOnly() || !expected.equals(find(expected.id()).orElse(null))) return false;
        writable();
        var round = flowerRounds.get(expected.id());
        var flower = flower(expected.id(), flowerKey).orElse(null);
        if (round == null || round.id() != roundId || !round.active() || round.failed()
                || expected.pruned() || !expected.environmentReasons().isEmpty() || flower == null || !flower.receivesPollen()
                || !partRole(expected.id(), flower.position()).filter(flower.role()::equals).isPresent()) return false;
        flowerRounds.put(expected.id(), new PlantFlowerRound(roundId, true, true));
        setDirty();
        return true;
    }

    /** 种群窗口结束时调用；物种须先成功移除该株本轮待清理花结构，再提交此状态。 */
    public boolean endFlowerRound(PlantIndividualState expected, long roundId) {
        if (isReadOnly() || !expected.equals(find(expected.id()).orElse(null))) return false;
        writable();
        var round = flowerRounds.get(expected.id());
        if (round == null || round.id() != roundId) return false;
        if (!round.active()) return true;
        var states = flowers.get(expected.id());
        if (states != null) states.replaceAll((key, flower) -> flower.phase() == PlantFlowerState.Phase.FLOWERING
                ? flower.withPhase(PlantFlowerState.Phase.CLOSED, 0) : flower);
        flowerRounds.put(expected.id(), new PlantFlowerRound(roundId, false, round.failed()));
        refreshPollenIndex(expected.id());
        setDirty();
        return true;
    }

    /** 额外可用性判断只读；用于自然授粉时排除花所在区块未活动的结构。 */
    boolean hasPollen(UUID id, java.util.function.Predicate<BlockPos> available) {
        var state = individuals.get(id);
        if (state == null || state.pruned() || !state.environmentReasons().isEmpty() || !flowerRoundActive(id)) return false;
        return flowers.getOrDefault(id, Map.of()).values().stream().anyMatch(flower -> flower.providesPollen()
                && partRole(id, flower.position()).filter(flower.role()::equals).isPresent()
                && available.test(flower.position()));
    }

    private void refreshPollenIndex(UUID id) {
        var state = individuals.get(id);
        if (state == null) pollenIndex.remove(id);
        else pollenIndex.update(state, hasPollen(id, ignored -> true));
    }

    Set<UUID> cycadPollenCandidates(ResourceLocation species, BlockPos center) {
        return isReadOnly() ? Set.of() : pollenIndex.cycadCandidates(species, center);
    }

    /** 明确登记一个真实生殖结构；已有稳定键不能重复初始化。 */
    public boolean initializeFlower(PlantIndividualState expected, String key, PlantFlowerState flower) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Empty flower key");
        java.util.Objects.requireNonNull(flower);
        if (isReadOnly() || !expected.equals(find(expected.id()).orElse(null))) return false;
        writable();
        if (!partRole(expected.id(), flower.position()).filter(flower.role()::equals).isPresent()
                || flowers.getOrDefault(expected.id(), Map.of()).containsKey(key)) return false;
        if (flowers.getOrDefault(expected.id(), Map.of()).values().stream().anyMatch(existing ->
                existing.position().equals(flower.position()) && existing.role().equals(flower.role()))) return false;
        if (expected.pruned() && !flower.equals(flower.pruned())) return false;
        if (flower.phase() == PlantFlowerState.Phase.FLOWERING && !flowerRoundActive(expected.id())) return false;
        flowers.computeIfAbsent(expected.id(), ignored -> new HashMap<>()).put(key, flower);
        refreshPollenIndex(expected.id());
        setDirty();
        return true;
    }

    public boolean replaceFlower(PlantIndividualState expected, String key, PlantFlowerState before, PlantFlowerState next) {
        if (isReadOnly() || !expected.equals(find(expected.id()).orElse(null))
                || !flower(expected.id(), key).filter(before::equals).isPresent()) return false;
        writable();
        if (!before.permits(next)) throw new IllegalArgumentException("Invalid flower transition");
        if (!before.equals(next)) {
            if (next.phase() == PlantFlowerState.Phase.FLOWERING && !flowerRoundActive(expected.id())) return false;
            if (before.phase() == PlantFlowerState.Phase.FLOWERING
                    && (next.phase() == PlantFlowerState.Phase.FERTILIZED || next.phase() == PlantFlowerState.Phase.SEEDS_READY)
                    && !acceptsPollenInRound(expected.id())) return false;
        }
        if (!partRole(expected.id(), before.position()).filter(before.role()::equals).isPresent()) return false;
        if (!before.equals(next) && next.phase() != PlantFlowerState.Phase.CLOSED
                && (expected.pruned() || !expected.environmentReasons().isEmpty())) return false;
        if (!before.equals(next)) {
            flowers.get(expected.id()).put(key, next);
            refreshPollenIndex(expected.id());
            setDirty();
        }
        return true;
    }

    /** Validate the entire batch before changing any index. Existing same-owner bindings are idempotent. */
    public void attachParts(UUID id, Map<BlockPos, String> additions) {
        writable();
        if (!individuals.containsKey(id)) throw new IllegalArgumentException("Unknown individual");
        Map<BlockPos, String> normalized = new HashMap<>();
        additions.forEach((pos, role) -> {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("Empty structure role");
            BlockPos key = pos.immutable();
            UUID owner = partOwners.get(key);
            UUID anchorOwner = byBase.get(key);
            if ((owner != null && !owner.equals(id)) || (anchorOwner != null && !anchorOwner.equals(id)))
                throw new IllegalArgumentException("Structure belongs to another individual");
            normalized.put(key, role);
        });
        if (normalized.isEmpty()) return;
        Map<BlockPos, String> owned = parts.computeIfAbsent(id, ignored -> new HashMap<>());
        boolean changed = false;
        for (var entry : normalized.entrySet()) {
            changed |= !entry.getValue().equals(owned.put(entry.getKey(), entry.getValue()));
            partOwners.put(entry.getKey(), id);
        }
        if (changed) {
            synchronizeLeafParts(id);
            refreshPollenIndex(id);
            setDirty();
        }
    }

    /** Detaching a leaf/root never deletes the individual or replenishes any harvest quota. */
    public boolean detachPart(UUID expectedOwner, BlockPos pos) {
        writable();
        if (!expectedOwner.equals(partOwners.get(pos))) return false;
        partOwners.remove(pos);
        Map<BlockPos, String> owned = parts.get(expectedOwner);
        owned.remove(pos);
        if (owned.isEmpty()) parts.remove(expectedOwner);
        synchronizeLeafParts(expectedOwner);
        refreshPollenIndex(expectedOwner);
        setDirty();
        return true;
    }

    /** Creation cannot overwrite an existing identity or occupied base. */
    public void create(PlantIndividualState state) {
        writable();
        if (individuals.containsKey(state.id()) || byBase.containsKey(state.base())
                || partOwners.containsKey(state.base()))
            throw new IllegalArgumentException("Individual or base already registered");
        individuals.put(state.id(), state);
        populationIndex.add(state);
        byBase.put(state.base(), state.id());
        growth.update(state);
        setDirty();
    }

    /** Optimistic replacement rejects stale snapshots; moving a plant requires an explicit later API. */
    public boolean replace(PlantIndividualState expected, PlantIndividualState next) {
        writable();
        if (witheringTimers.containsKey(expected.id()) && !PlantWithering.isWithering(next))
            throw new IllegalArgumentException("Cannot clear an active withering process through ordinary replacement");
        if (!expected.id().equals(next.id()) || !expected.species().equals(next.species())
                || !expected.base().equals(next.base()))
            throw new IllegalArgumentException("Cannot replace identity, species or base");
        if (!expected.equals(find(expected.id()).orElse(null))) return false;
        var currentLeaves = leavesOf(next.id()).orElse(null);
        if (currentLeaves != null) currentLeaves.validateOwner(next, parts.getOrDefault(next.id(), Map.of()));
        if (!expected.equals(next)) {
            if (currentLeaves != null) putLeafData(next.id(), currentLeaves);
            populationIndex.changed(expected, next);
            individuals.put(next.id(), next);
            growth.update(next);
            leafScheduler.update(next, currentLeaves);
            if (!expected.pruned() && next.pruned() && flowers.containsKey(next.id()))
                flowers.get(next.id()).replaceAll((key, flower) -> flower.pruned());
            if (expected.pruned() != next.pruned() || !expected.environmentReasons().equals(next.environmentReasons()))
                refreshPollenIndex(next.id());
            setDirty();
        }
        return true;
    }

    /** Both keys are required so a stale removal cannot delete a new plant at a reused position. */
    public boolean remove(UUID id, BlockPos base) {
        writable();
        PlantIndividualState current = individuals.get(id);
        if (current == null || !current.base().equals(base)) return false;
        individuals.remove(id);
        populationIndex.remove(current);
        growth.cancel(id);
        withering.cancel(id);
        witheringTimers.remove(id);
        flowers.remove(id);
        flowerRounds.remove(id);
        leafData.remove(id);
        leafCounts.remove(id);
        leafPartIndices.remove(id);
        leafScheduler.cancel(id);
        pollenIndex.remove(id);
        byBase.remove(base, id);
        Map<BlockPos, String> owned = parts.remove(id);
        if (owned != null) owned.keySet().forEach(pos -> partOwners.remove(pos, id));
        setDirty();
        return true;
    }

    private void writable() {
        if (isReadOnly()) throw new IllegalStateException("Unsupported or damaged plant data; migration required");
        if (applyingStructure != null) throw new IllegalStateException("Reentrant plant mutation during structure placement");
    }

    /**
     * Validated state-only split/merge. Server-thread only; no callbacks or world changes occur here.
     * Different sources/stages/pause states require a future explicit species policy.
     */
    public boolean replaceGroup(PlantIndividualReplacement proposal) {
        writable();
        Set<UUID> oldIds = new HashSet<>();
        Map<String, Integer> oldHarvests = new HashMap<>();
        Map<String, Integer> oldReproduction = new HashMap<>();
        var first = proposal.expected().getFirst();
        long target = 0;
        long remaining = 0;
        Set<BlockPos> oldParts = new HashSet<>();
        for (var old : proposal.expected()) {
            if (leafData.containsKey(old.id()))
                throw new IllegalArgumentException("Independent leaves require explicit split/merge inheritance");
            if (flowers.containsKey(old.id()) || flowerRounds.containsKey(old.id()))
                throw new IllegalArgumentException("Flower structures require explicit split/merge inheritance");
            if (witheringTimers.containsKey(old.id()) || PlantWithering.isWithering(old))
                throw new IllegalArgumentException("Withering individuals cannot split or merge");
            if (!oldIds.add(old.id()) || !old.equals(find(old.id()).orElse(null))) return false;
            requireCompatible(first, old);
            collectHarvests(oldHarvests, old.harvests());
            collectHarvests(oldReproduction, old.reproduction());
            target = Math.max(target, old.targetTicks());
            remaining = Math.max(remaining, old.remainingTicks());
            oldParts.addAll(parts.getOrDefault(old.id(), Map.of()).keySet());
        }
        Set<UUID> newIds = new HashSet<>();
        Set<BlockPos> newBases = new HashSet<>();
        Map<BlockPos, UUID> newOwners = new HashMap<>();
        Map<String, Integer> newHarvests = new HashMap<>();
        Map<String, Integer> newReproduction = new HashMap<>();
        for (var next : proposal.replacements()) {
            requireCompatible(first, next);
            if (!newIds.add(next.id()) || (!oldIds.contains(next.id()) && individuals.containsKey(next.id()))
                    || !newBases.add(next.base())) throw new IllegalArgumentException("Duplicate replacement identity/base");
            if (next.targetTicks() != target || next.remainingTicks() != remaining)
                throw new IllegalArgumentException("Replacement changed inherited timer");
            var nextParts = proposal.structures().get(next.id());
            if (nextParts == null) throw new IllegalArgumentException("Missing replacement structure");
            if (proposal.expected().size() == 1 && proposal.replacements().size() == 1
                    && !first.runnerBudget().map(value -> value.withStarts(value.survivingStarts(
                            nextParts.keySet()))).equals(next.runnerBudget()))
                throw new IllegalArgumentException("Trimming cannot reset runner budget");
            if (proposal.expected().size() == 1 && next.runnerBudget().isPresent()
                    && !next.runnerBudget().get().usedStarts().equals(first.runnerBudget().orElseThrow()
                            .survivingStarts(nextParts.keySet())))
                throw new IllegalArgumentException("Cut cannot refresh surviving runner starts");
            if (proposal.replacements().size() > 1 && next.runnerBudget().isPresent()
                    && (next.runnerBudget().get().extraLength() != 0
                        || next.runnerBudget().get().peatRewardsUsed() != 0))
                throw new IllegalArgumentException("True splits must discard extra runner length");
            if (proposal.expected().size() > 1)
                next.runnerBudget().ifPresent(budget -> requireMergedRunnerBudget(proposal.expected(), budget));
            UUID baseOwner = byBase.get(next.base());
            UUID partOwner = partOwners.get(next.base());
            if ((baseOwner != null && !oldIds.contains(baseOwner)) || (partOwner != null && !oldIds.contains(partOwner)))
                throw new IllegalArgumentException("Replacement base occupied");
            collectHarvests(newHarvests, next.harvests());
            collectHarvests(newReproduction, next.reproduction());
            for (BlockPos pos : nextParts.keySet()) {
                if (!oldParts.contains(pos) || newOwners.putIfAbsent(pos, next.id()) != null)
                    throw new IllegalArgumentException("Structure is new or shared between replacements");
            }
        }
        if (!proposal.structures().keySet().equals(newIds)) throw new IllegalArgumentException("Extra structure records");
        for (var next : proposal.replacements()) {
            UUID owner = newOwners.get(next.base());
            if (owner != null && !owner.equals(next.id())) throw new IllegalArgumentException("Base overlaps another part");
        }
        collectHarvests(newHarvests, proposal.retiredHarvests());
        if (!oldHarvests.equals(newHarvests)) throw new IllegalArgumentException("Resource inheritance is not conserved");
        collectHarvests(newReproduction, proposal.retiredReproduction());
        if (!oldReproduction.equals(newReproduction))
            throw new IllegalArgumentException("Reproduction inheritance is not conserved");
        // All checks completed: the following operations cannot invoke species/world callbacks.
        for (var old : proposal.expected()) {
            populationIndex.remove(old);
            growth.cancel(old.id());
            individuals.remove(old.id());
            byBase.remove(old.base());
            Map<BlockPos, String> owned = parts.remove(old.id());
            if (owned != null) owned.keySet().forEach(pos -> partOwners.remove(pos, old.id()));
        }
        for (var next : proposal.replacements()) {
            populationIndex.add(next);
            individuals.put(next.id(), next);
            byBase.put(next.base(), next.id());
            Map<BlockPos, String> owned = new HashMap<>(proposal.structures().get(next.id()));
            if (!owned.isEmpty()) parts.put(next.id(), owned);
            owned.keySet().forEach(pos -> partOwners.put(pos, next.id()));
            growth.update(next);
        }
        setDirty();
        return true;
    }

    /** Shared by proposal builders and the final store validation; never converts mixed origins. */
    static void requireMergedRunnerBudget(java.util.List<PlantIndividualState> originals,
            RunnerGrowthAdmission.Budget budget) {
        int length = 0;
        int extra = 0;
        int chances = 0;
        int peatUsed = 0;
        Map<BlockPos, net.minecraft.core.Direction> starts = new HashMap<>();
        for (var original : originals) {
            var old = original.runnerBudget().orElseThrow();
            length = Math.max(length, old.maximumLength());
            extra = Math.max(extra, old.extraLength());
            chances = Math.max(chances, old.branchChances());
            peatUsed = Math.max(peatUsed, old.peatRewardsUsed());
            old.usedStarts().forEach((pos, direction) -> {
                var previous = starts.putIfAbsent(pos, direction);
                if (previous != null && previous != direction)
                    throw new IllegalArgumentException("Conflicting runner start history");
            });
        }
        if (budget.maximumLength() > length || budget.extraLength() > extra || budget.branchChances() > chances
                || budget.peatRewardsUsed() < peatUsed || !budget.usedStarts().equals(starts))
            throw new IllegalArgumentException("Merging cannot accumulate runner allowances");
    }

    /** Shared by proposal builders and the final store validation; never converts mixed origins. */
    static void requireCompatible(PlantIndividualState first, PlantIndividualState other) {
        if (!first.species().equals(other.species()) || !first.stage().equals(other.stage())
                || first.source() != other.source() || first.origin() != other.origin()
                || !first.population().equals(other.population())
                || first.canBePruned() != other.canBePruned() || first.pruned() != other.pruned()
                || !first.environmentReasons().equals(other.environmentReasons())
                || first.runnerBudget().isPresent() != other.runnerBudget().isPresent())
            throw new IllegalArgumentException("Split/merge needs an explicit policy for different states");
    }

    private static void collectHarvests(Map<String, Integer> target, Map<String, Integer> values) {
        values.forEach((key, count) -> {
            if (key == null || key.isBlank() || count == null || count < 0 || target.putIfAbsent(key, count) != null)
                throw new IllegalArgumentException("Invalid or duplicated resource key");
        });
    }

    private static PlantIndividuals load(CompoundTag tag, HolderLookup.Provider registries) {
        PlantIndividuals data = new PlantIndividuals();
        try {
            int version = tag.getInt("Version");
            if (!tag.contains("Version", Tag.TAG_INT) || version < 1 || version > VERSION
                    || !tag.contains("Individuals", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported plant data version");
            ListTag entries = tag.getList("Individuals", Tag.TAG_COMPOUND);
            if (entries.size() != ((ListTag) tag.get("Individuals")).size())
                throw new IllegalArgumentException("Invalid individual list type");
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                for (String key : new String[]{"Species", "Stage", "Source", "Population"})
                    if (!entry.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing " + key);
                for (String key : new String[]{"Base", "TargetTicks", "ElapsedTicks"})
                    if (!entry.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("Missing " + key);
                if (!entry.hasUUID("Id") || !entry.contains("Harvests", Tag.TAG_COMPOUND)
                        || !entry.contains("EnvironmentReasons", Tag.TAG_LIST)
                        || !entry.contains("CanBePruned", Tag.TAG_BYTE) || !entry.contains("Pruned", Tag.TAG_BYTE))
                    throw new IllegalArgumentException("Incomplete individual");
                Map<String, Integer> harvests = new HashMap<>();
                CompoundTag quotas = entry.getCompound("Harvests");
                for (String key : quotas.getAllKeys()) {
                    if (!quotas.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Invalid quota");
                    harvests.put(key, quotas.getInt(key));
                }
                HashSet<String> reasons = new HashSet<>();
                Map<String, Integer> reproduction = new HashMap<>();
                if (version >= 4) {
                    if (!entry.contains("Reproduction", Tag.TAG_COMPOUND))
                        throw new IllegalArgumentException("Missing reproduction budgets");
                    CompoundTag budgets = entry.getCompound("Reproduction");
                    for (String key : budgets.getAllKeys()) {
                        if (!budgets.contains(key, Tag.TAG_INT))
                            throw new IllegalArgumentException("Invalid reproduction budget");
                        reproduction.put(key, budgets.getInt(key));
                    }
                }
                ListTag savedReasons = entry.getList("EnvironmentReasons", Tag.TAG_STRING);
                if (savedReasons.size() != ((ListTag) entry.get("EnvironmentReasons")).size())
                    throw new IllegalArgumentException("Invalid environment reason list");
                for (int j = 0; j < savedReasons.size(); j++) reasons.add(savedReasons.getString(j));
                if (version >= 3 && !entry.contains("Origin", Tag.TAG_STRING))
                    throw new IllegalArgumentException("Missing origin");
                Optional<RunnerGrowthAdmission.Budget> runnerBudget = Optional.empty();
                if (version >= 5) {
                    if (!entry.contains("HasRunnerBudget", Tag.TAG_BYTE))
                        throw new IllegalArgumentException("Missing runner budget presence flag");
                    if (entry.getBoolean("HasRunnerBudget")) {
                        if (!entry.contains("RunnerBudget", Tag.TAG_COMPOUND))
                            throw new IllegalArgumentException("Missing runner budget");
                        CompoundTag budget = entry.getCompound("RunnerBudget");
                        for (String key : new String[]{"BaseLength", "ExtraLength", "BranchChances"})
                            if (!budget.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Missing " + key);
                        if (version >= 6 && !budget.contains("PeatRewardsUsed", Tag.TAG_INT))
                            throw new IllegalArgumentException("Missing peat reward history");
                        Map<BlockPos, net.minecraft.core.Direction> starts = new HashMap<>();
                        if (version >= 7) {
                            if (!budget.contains("UsedStarts", Tag.TAG_LIST))
                                throw new IllegalArgumentException("Missing runner start history");
                            ListTag savedStarts = budget.getList("UsedStarts", Tag.TAG_COMPOUND);
                            if (savedStarts.size() != ((ListTag) budget.get("UsedStarts")).size())
                                throw new IllegalArgumentException("Invalid runner start history");
                            for (int k = 0; k < savedStarts.size(); k++) {
                                CompoundTag start = savedStarts.getCompound(k);
                                if (!start.contains("Position", Tag.TAG_LONG) || !start.contains("Direction", Tag.TAG_STRING))
                                    throw new IllegalArgumentException("Incomplete runner start history");
                                var direction = net.minecraft.core.Direction.valueOf(start.getString("Direction"));
                                if (starts.putIfAbsent(BlockPos.of(start.getLong("Position")), direction) != null)
                                    throw new IllegalArgumentException("Duplicate runner start history");
                            }
                        }
                        // 旧版本尚未发放此奖励，因此迁移为零；新版本缺字段视为损坏。
                        runnerBudget = Optional.of(new RunnerGrowthAdmission.Budget(budget.getInt("BaseLength"),
                                budget.getInt("ExtraLength"), budget.getInt("BranchChances"),
                                version >= 6 ? budget.getInt("PeatRewardsUsed") : 0, starts));
                    } else if (entry.contains("RunnerBudget")) {
                        throw new IllegalArgumentException("Unexpected runner budget");
                    }
                }
                PlantIndividualState state = new PlantIndividualState(entry.getUUID("Id"),
                        ResourceLocation.parse(entry.getString("Species")), BlockPos.of(entry.getLong("Base")),
                        PlantSource.valueOf(entry.getString("Source")), entry.getString("Stage"),
                        entry.getString("Population"), entry.getBoolean("CanBePruned"), entry.getBoolean("Pruned"),
                        entry.getLong("TargetTicks"), entry.getLong("ElapsedTicks"), reasons, harvests,
                        version >= 3 ? PlantOrigin.valueOf(entry.getString("Origin")) : PlantOrigin.UNKNOWN,
                        reproduction, runnerBudget);
                if (data.individuals.putIfAbsent(state.id(), state) != null
                        || data.byBase.putIfAbsent(state.base(), state.id()) != null)
                    throw new IllegalArgumentException("Duplicate individual or base");
                if (version >= 8) {
                    if (!entry.contains("HasWitheringTimer", Tag.TAG_BYTE))
                        throw new IllegalArgumentException("Missing withering timer presence flag");
                    if (entry.getBoolean("HasWitheringTimer")) {
                        if (!entry.contains("WitheringTimer", Tag.TAG_COMPOUND) || !PlantWithering.isWithering(state))
                            throw new IllegalArgumentException("Invalid withering timer owner");
                        CompoundTag saved = entry.getCompound("WitheringTimer");
                        if (!saved.contains("TargetTicks", Tag.TAG_LONG) || !saved.contains("ElapsedTicks", Tag.TAG_LONG)
                                || !saved.contains("Failed", Tag.TAG_BYTE))
                            throw new IllegalArgumentException("Incomplete withering timer");
                        data.witheringTimers.put(state.id(), new PlantWitheringTimer(saved.getLong("TargetTicks"),
                                saved.getLong("ElapsedTicks"), saved.getBoolean("Failed")));
                    } else if (entry.contains("WitheringTimer")) {
                        throw new IllegalArgumentException("Unexpected withering timer");
                    }
                }
                if (version >= 9) {
                    if (!entry.contains("Flowers", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing flowers");
                    ListTag savedFlowers = entry.getList("Flowers", Tag.TAG_COMPOUND);
                    if (savedFlowers.size() != ((ListTag) entry.get("Flowers")).size())
                        throw new IllegalArgumentException("Invalid flowers list");
                    Map<String, PlantFlowerState> restored = new HashMap<>();
                    Set<Map.Entry<BlockPos, String>> flowerAnchors = new HashSet<>();
                    for (int j = 0; j < savedFlowers.size(); j++) {
                        CompoundTag saved = savedFlowers.getCompound(j);
                        for (String key : new String[]{"Key", "Role", "Sex", "Phase"})
                            if (!saved.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing flower " + key);
                        if (!saved.contains("Position", Tag.TAG_LONG) || !saved.contains("Seeds", Tag.TAG_INT))
                            throw new IllegalArgumentException("Incomplete flower state");
                        var flower = new PlantFlowerState(BlockPos.of(saved.getLong("Position")), saved.getString("Role"),
                                PlantFlowerState.Sex.valueOf(saved.getString("Sex")),
                                PlantFlowerState.Phase.valueOf(saved.getString("Phase")), saved.getInt("Seeds"));
                        if (saved.getString("Key").isBlank() || restored.putIfAbsent(saved.getString("Key"), flower) != null
                                || !flowerAnchors.add(Map.entry(flower.position(), flower.role()))
                                || (state.pruned() && !flower.equals(flower.pruned())))
                            throw new IllegalArgumentException("Invalid or duplicate flower state");
                    }
                    if (!restored.isEmpty()) data.flowers.put(state.id(), restored);
                }
                if (version >= 10) {
                    if (!entry.contains("HasFlowerRound", Tag.TAG_BYTE))
                        throw new IllegalArgumentException("Missing flower round presence flag");
                    if (entry.getBoolean("HasFlowerRound")) {
                        if (!entry.contains("FlowerRound", Tag.TAG_COMPOUND))
                            throw new IllegalArgumentException("Missing flower round");
                        CompoundTag saved = entry.getCompound("FlowerRound");
                        if (!saved.contains("Id", Tag.TAG_LONG) || !saved.contains("Active", Tag.TAG_BYTE)
                                || !saved.contains("Failed", Tag.TAG_BYTE))
                            throw new IllegalArgumentException("Incomplete flower round");
                        data.flowerRounds.put(state.id(), new PlantFlowerRound(saved.getLong("Id"),
                                saved.getBoolean("Active"), saved.getBoolean("Failed")));
                        if (!saved.getBoolean("Active") && data.flowers.getOrDefault(state.id(), Map.of()).values()
                                .stream().anyMatch(flower -> flower.phase() == PlantFlowerState.Phase.FLOWERING))
                            throw new IllegalArgumentException("Closed flower round still has active flowers");
                    } else if (entry.contains("FlowerRound")) {
                        throw new IllegalArgumentException("Unexpected flower round");
                    }
                }
            }
            // Load identities first so cross-individual anchor collisions are detected regardless of order.
            if (version >= 2) {
                for (int i = 0; i < entries.size(); i++) {
                    CompoundTag entry = entries.getCompound(i);
                    if (!entry.contains("Parts", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing parts");
                    ListTag savedParts = entry.getList("Parts", Tag.TAG_COMPOUND);
                    if (savedParts.size() != ((ListTag) entry.get("Parts")).size())
                        throw new IllegalArgumentException("Invalid parts list");
                    Map<BlockPos, String> restored = new HashMap<>();
                    for (int j = 0; j < savedParts.size(); j++) {
                        CompoundTag part = savedParts.getCompound(j);
                        if (!part.contains("Position", Tag.TAG_LONG) || !part.contains("Role", Tag.TAG_STRING))
                            throw new IllegalArgumentException("Incomplete part");
                        if (restored.putIfAbsent(BlockPos.of(part.getLong("Position")), part.getString("Role")) != null)
                            throw new IllegalArgumentException("Duplicate part position");
                    }
                    data.attachParts(entry.getUUID("Id"), restored);
                }
            }
            // V1 has no structural positions: never infer them by scanning or claim adjacent blocks.
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                if (version >= 11) {
                    if (!entry.contains("HasLeafData", Tag.TAG_BYTE) || entry.getByte("HasLeafData") < 0
                            || entry.getByte("HasLeafData") > 1)
                        throw new IllegalArgumentException("Missing or invalid leaf data presence flag");
                    if (entry.getBoolean("HasLeafData")) {
                        if (!entry.contains("LeafData", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing leaf data");
                        var state = data.individuals.get(entry.getUUID("Id"));
                        var leaves = PlantLeafDataCodec.read(entry.getCompound("LeafData"), version);
                        leaves.validateOwner(state, data.parts.getOrDefault(state.id(), Map.of()));
                        data.putLeafData(state.id(), leaves);
                    } else if (entry.contains("LeafData")) throw new IllegalArgumentException("Unexpected leaf data");
                } else if (entry.contains("HasLeafData") || entry.contains("LeafData")) {
                    throw new IllegalArgumentException("Unexpected leaf data in legacy version");
                }
            }
            data.setDirty(version < VERSION);
            data.individuals.values().forEach(data.growth::update);
            data.witheringTimers.forEach((id, timer) -> data.withering.updateWithering(data.individuals.get(id), timer));
            data.individuals.values().forEach(data.populationIndex::add);
            data.individuals.keySet().forEach(data::refreshPollenIndex);
            data.leafData.forEach((id, leaves) -> data.leafScheduler.update(data.individuals.get(id), leaves));
        } catch (IllegalArgumentException exception) {
            // Preserve the entire original payload rather than silently refreshing resources.
            data.individuals.clear();
            data.byBase.clear();
            data.parts.clear();
            data.partOwners.clear();
            data.witheringTimers.clear();
            data.flowers.clear();
            data.flowerRounds.clear();
            data.leafData.clear();
            data.leafCounts.clear();
            data.leafPartIndices.clear();
            data.pollenIndex.clear();
            data.setDirty(false);
            data.preservedUnsupportedData = tag.copy();
            org.slf4j.LoggerFactory.getLogger(PlantIndividuals.class)
                    .error("Plant individual data opened read-only; original payload preserved", exception);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (isReadOnly()) return preservedUnsupportedData.copy();
        tag.putInt("Version", VERSION);
        ListTag entries = new ListTag();
        for (PlantIndividualState stored : individuals.values()) {
            PlantIndividualState state = growth.current(stored);
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", state.id());
            entry.putString("Species", state.species().toString());
            entry.putLong("Base", state.base().asLong());
            entry.putString("Source", state.source().name());
            entry.putString("Origin", state.origin().name());
            entry.putString("Stage", state.stage());
            entry.putString("Population", state.population());
            entry.putBoolean("CanBePruned", state.canBePruned());
            entry.putBoolean("Pruned", state.pruned());
            entry.putLong("TargetTicks", state.targetTicks());
            entry.putLong("ElapsedTicks", state.elapsedTicks());
            ListTag reasons = new ListTag();
            state.environmentReasons().stream().sorted().forEach(reason -> reasons.add(StringTag.valueOf(reason)));
            entry.put("EnvironmentReasons", reasons);
            var leaves = leavesOf(state.id()).orElse(null);
            entry.putBoolean("HasLeafData", leaves != null);
            if (leaves != null) {
                leaves.validateOwner(state, parts.getOrDefault(state.id(), Map.of()));
                entry.put("LeafData", PlantLeafDataCodec.write(leaves));
            }
            ListTag savedFlowers = new ListTag();
            flowers.getOrDefault(state.id(), Map.of()).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
                PlantFlowerState flower = value.getValue();
                CompoundTag saved = new CompoundTag();
                saved.putString("Key", value.getKey());
                saved.putLong("Position", flower.position().asLong());
                saved.putString("Role", flower.role());
                saved.putString("Sex", flower.sex().name());
                saved.putString("Phase", flower.phase().name());
                saved.putInt("Seeds", flower.seeds());
                savedFlowers.add(saved);
            });
            entry.put("Flowers", savedFlowers);
            var round = flowerRounds.get(state.id());
            entry.putBoolean("HasFlowerRound", round != null);
            if (round != null) {
                CompoundTag saved = new CompoundTag();
                saved.putLong("Id", round.id());
                saved.putBoolean("Active", round.active());
                saved.putBoolean("Failed", round.failed());
                entry.put("FlowerRound", saved);
            }
            var witheringTimer = witheringTimer(state.id());
            entry.putBoolean("HasWitheringTimer", witheringTimer.isPresent());
            witheringTimer.ifPresent(timer -> {
                CompoundTag saved = new CompoundTag();
                saved.putLong("TargetTicks", timer.targetTicks());
                saved.putLong("ElapsedTicks", timer.elapsedTicks());
                saved.putBoolean("Failed", timer.failed());
                entry.put("WitheringTimer", saved);
            });
            CompoundTag quotas = new CompoundTag();
            state.harvests().forEach(quotas::putInt);
            entry.put("Harvests", quotas);
            CompoundTag reproduction = new CompoundTag();
            state.reproduction().forEach(reproduction::putInt);
            entry.put("Reproduction", reproduction);
            entry.putBoolean("HasRunnerBudget", state.runnerBudget().isPresent());
            state.runnerBudget().ifPresent(budget -> {
                CompoundTag savedBudget = new CompoundTag();
                savedBudget.putInt("BaseLength", budget.baseLength());
                savedBudget.putInt("ExtraLength", budget.extraLength());
                savedBudget.putInt("BranchChances", budget.branchChances());
                savedBudget.putInt("PeatRewardsUsed", budget.peatRewardsUsed());
                ListTag starts = new ListTag();
                budget.usedStarts().entrySet().stream().sorted(java.util.Comparator.comparingLong(value -> value.getKey().asLong()))
                        .forEach(value -> {
                            CompoundTag start = new CompoundTag();
                            start.putLong("Position", value.getKey().asLong());
                            start.putString("Direction", value.getValue().name());
                            starts.add(start);
                        });
                savedBudget.put("UsedStarts", starts);
                entry.put("RunnerBudget", savedBudget);
            });
            ListTag savedParts = new ListTag();
            for (var part : parts.getOrDefault(state.id(), Map.of()).entrySet()) {
                CompoundTag saved = new CompoundTag();
                saved.putLong("Position", part.getKey().asLong());
                saved.putString("Role", part.getValue());
                savedParts.add(saved);
            }
            entry.put("Parts", savedParts);
            entries.add(entry);
        }
        tag.put("Individuals", entries);
        return tag;
    }
}
