package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 按维度保存地下系统，所有归属索引只由权威系统记录重建，不扫描世界或自动补生。 */
public final class PlantRhizomeSystems extends SavedData {
    private static final Factory<PlantRhizomeSystems> FACTORY = new Factory<>(PlantRhizomeSystems::new, PlantRhizomeSystems::load);
    private final Map<UUID, PlantRhizomeSystem> systems = new HashMap<>();
    private final Map<BlockPos, UUID> nodes = new HashMap<>();
    private final Map<BlockPos, UUID> origins = new HashMap<>();
    private final Map<UUID, UUID> children = new HashMap<>();
    private final Map<BlockPos, UUID> shootBases = new HashMap<>();
    private final Map<BlockPos, UUID> attachments = new HashMap<>();
    private CompoundTag preserved;
    private UUID applyingStructure;
    private PlantRhizomeSystem applyingBefore, applyingAfter;
    private Map<UUID, RhizomeOverwrite.Transfer> applyingTransfers = Map.of();
    private RhizomeNaturalStructurePlan applyingNatural;
    private final java.util.Set<UUID> recoveryRequired = new HashSet<>();
    private final Map<UUID, RhizomeShootTimer> shootTimers = new HashMap<>();
    private final PlantGrowthScheduler shootScheduler = new PlantGrowthScheduler(32);

    public Optional<RhizomeShootTimer> shootTimer(UUID id) {
        var timer = shootTimers.get(id);
        return timer == null ? Optional.empty() : Optional.of(new RhizomeShootTimer(
                shootScheduler.currentElapsed(id, timer.elapsedTicks())));
    }

    private static boolean canRegrowShoots(PlantRhizomeSystem system) {
        return system.source().canExpand() && !system.rhizomes().isEmpty() && system.freeShootSlots() > 0;
    }

    private void updateShootTimer(PlantRhizomeSystem before, PlantRhizomeSystem next) {
        if (needsRecovery(next.id())) return;
        if (!canRegrowShoots(next)) {
            shootTimers.remove(next.id());
            shootScheduler.cancel(next.id());
        } else if (!shootTimers.containsKey(next.id())
                || (before != null && next.shoots().size() > before.shoots().size())) {
            shootTimers.put(next.id(), new RhizomeShootTimer(0));
            shootScheduler.updateSystem(next.id(), next.origin(), RhizomeShootTimer.TARGET_TICKS, 0);
        }
    }

    public void tickShoots(ServerLevel level) {
        if (this != get(level)) throw new IllegalArgumentException("Wrong dimension rhizome store");
        if (isReadOnly() || isApplyingStructure()) return;
        shootScheduler.tickSystems(level, this::setDirty, id -> {
            var system = systems.get(id);
            if (system == null || needsRecovery(id) || !canRegrowShoots(system)) {
                shootScheduler.cancel(id);
                return;
            }
            var timer = shootTimer(id).orElse(null);
            if (timer == null || !timer.ready()) return;
            var handler = RhizomeShootHandlers.find(system.species());
            if (handler == null) return;
            try {
                handler.onReady(level, this, system);
            } catch (RuntimeException failure) {
                if (systems.containsKey(id)) requireRecovery(id);
                org.slf4j.LoggerFactory.getLogger(PlantRhizomeSystems.class).error("Rhizome shoot callback failed for " + id, failure);
            }
        });
    }

    public boolean needsRecovery(UUID id) { return recoveryRequired.contains(id); }
    void requireRecovery(UUID id) {
        if (isReadOnly() || !systems.containsKey(id)) throw new IllegalStateException("Unknown recovery system");
        shootTimer(id).ifPresent(timer -> shootTimers.put(id, timer));
        shootScheduler.cancel(id);
        recoveryRequired.add(id);
        setDirty();
    }

    public boolean isApplyingStructure(UUID id) { return id.equals(applyingStructure) || applyingTransfers.containsKey(id)
            || applyingNatural != null && applyingNatural.population().systems().containsKey(id); }
    /** 新放入的地下茎尚未建立坐标索引时，回调可用此入口避免提前注销或掉落。 */
    public boolean isApplyingStructure() { return applyingStructure != null; }
    private void notApplying() {
        if (applyingStructure != null) throw new IllegalStateException("Reentrant rhizome mutation during placement");
    }

    /** 一次自然种群的系统锁；现有世界结构回调可识别整组内的每个系统。 */
    void beginNaturalStructure(RhizomeNaturalStructurePlan plan) {
        notApplying();
        validateNaturalRegistration(plan);
        applyingNatural = plan;
        applyingStructure = plan.population().systems().keySet().iterator().next();
    }

    private void validateNaturalRegistration(RhizomeNaturalStructurePlan plan) {
        if (isReadOnly()) throw new IllegalStateException("Rhizome data unavailable");
        // 仅构造本次新增索引，不复制已有世界的整套系统。
        var staged = new PlantRhizomeSystems();
        for (var system : plan.population().systems().values()) {
            if (systems.containsKey(system.id()) || !fits(system))
                throw new IllegalStateException("Natural rhizome system already occupied");
            staged.systems.put(system.id(), system);
            staged.index(system);
        }
        for (var system : plan.population().systems().values())
            if (!staged.fits(system)) throw new IllegalStateException("Natural systems conflict within batch");
    }

    void commitNaturalStructure(RhizomeNaturalStructurePlan plan) {
        if (applyingNatural != plan || !isApplyingStructure()) throw new IllegalStateException("Natural batch lock lost");
        validateNaturalRegistration(plan);
        for (var system : plan.population().systems().values()) {
            systems.put(system.id(), system);
            index(system);
        }
        // STATIC 无补生任务，不创建计时器，也不为其分配调度项。
        setDirty();
    }

    void rollbackNaturalStructure(RhizomeNaturalStructurePlan plan) {
        if (applyingNatural != plan) throw new IllegalStateException("Natural batch lock lost");
        for (var system : plan.population().systems().values()) {
            if (!systems.remove(system.id(), system)) continue;
            unindex(system);
        }
        setDirty();
    }

    void endNaturalStructure() {
        applyingNatural = null;
        applyingStructure = null;
    }

    /** 与个体结构锁成对使用，写世界期间禁止回调改变地下系统。 */
    void beginShootStructure(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        beginShootStructure(expected, next, Map.of());
    }

    void beginShootStructure(PlantRhizomeSystem expected, PlantRhizomeSystem next, Map<UUID, RhizomeOverwrite.Transfer> transfers) {
        notApplying();
        if (!canReplace(expected, next, false, false) || !validateTransfers(expected, next, transfers))
            throw new IllegalStateException("Stale or conflicting rhizome proposal");
        applyingStructure = expected.id();
        applyingBefore = expected;
        applyingAfter = next;
        applyingTransfers = Map.copyOf(transfers);
    }

    /** 批量路径必须逐步重放原末端预算，不能只相信调用者提供的最终模型。 */
    void beginHorizontalStructure(PlantRhizomeSystem expected, PlantRhizomeSystem next,
            String arm, java.util.List<Direction> directions) {
        beginHorizontalStructure(expected, next, arm, directions, Map.of());
    }

    void beginHorizontalStructure(PlantRhizomeSystem expected, PlantRhizomeSystem next,
            String arm, java.util.List<Direction> directions, Map<UUID, RhizomeOverwrite.Transfer> transfers) {
        notApplying();
        if (directions.isEmpty() || directions.size() > 10) throw new IllegalArgumentException("Invalid horizontal path length");
        var replay = expected;
        for (Direction direction : directions)
            replay = replay.extendTip(arm, direction).orElseThrow(() -> new IllegalArgumentException("Invalid horizontal path"));
        if (!replay.equals(next) || !canReplace(expected, next, true, false) || !validateTransfers(expected, next, transfers))
            throw new IllegalStateException("Stale or conflicting horizontal proposal");
        applyingStructure = expected.id();
        applyingBefore = expected;
        applyingAfter = next;
        applyingTransfers = Map.copyOf(transfers);
    }

    void commitShootStructure(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        if (!isApplyingStructure(expected.id()) || !expected.equals(applyingBefore) || !next.equals(applyingAfter)
                || !expected.equals(systems.get(expected.id())) || needsRecovery(expected.id())
                || !validateTransfers(expected, next, applyingTransfers))
            throw new IllegalStateException("Rhizome placement lost its owner");
        if (applyingTransfers.isEmpty()) install(expected, next);
        else {
            unindex(expected);
            applyingTransfers.values().forEach(transfer -> unindex(transfer.before()));
            systems.put(next.id(), next);
            index(next);
            applyingTransfers.values().forEach(transfer -> {
                systems.put(transfer.after().id(), transfer.after());
                index(transfer.after());
                updateShootTimer(transfer.before(), transfer.after());
            });
            updateShootTimer(expected, next);
            setDirty();
        }
    }

    void endShootStructure() { applyingStructure = null; applyingBefore = null; applyingAfter = null; applyingTransfers = Map.of(); }

    public static PlantRhizomeSystems get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_rhizome_systems");
    }
    public boolean isReadOnly() { return preserved != null; }
    public Optional<PlantRhizomeSystem> find(UUID id) { return Optional.ofNullable(systems.get(id)); }
    public Optional<PlantRhizomeSystem> atRhizome(BlockPos pos) { return owner(nodes.get(pos)); }
    public Optional<PlantRhizomeSystem> atOrigin(BlockPos pos) { return owner(origins.get(pos)); }
    public Optional<PlantRhizomeSystem> atShootBase(BlockPos pos) { return owner(shootBases.get(pos)); }
    public Optional<PlantRhizomeSystem> atAttachment(BlockPos pos) { return owner(attachments.get(pos)); }
    public Optional<PlantRhizomeSystem> forShoot(UUID child) { return owner(children.get(child)); }
    private Optional<PlantRhizomeSystem> owner(UUID id) { return id == null ? Optional.empty() : find(id); }

    /** 新系统必须有地下茎；原点可为中央纤维结构，切断后也不移动原点。 */
    public boolean create(PlantRhizomeSystem system) {
        notApplying();
        if (isReadOnly() || systems.containsKey(system.id()) || system.rhizomes().isEmpty() || !fits(system)) return false;
        systems.put(system.id(), system);
        index(system);
        updateShootTimer(null, system);
        setDirty();
        return true;
    }

    /** 中央纤维根已实际落地后的专用入口；尚无横向节点时只登记一次预算，不伪造节点。 */
    boolean createInitialRoot(PlantRhizomeSystem system) {
        notApplying();
        if (isReadOnly() || systems.containsKey(system.id()) || system.source() != PlantSource.PLAYER
                || !system.rhizomes().isEmpty() || !system.risingRhizomes().isEmpty() || !system.shoots().isEmpty()
                || system.growth().isEmpty() || system.growth().get().dStarted() || !fits(system)) return false;
        if (!system.growth().get().branches().isEmpty()) return false;
        var tips = system.growth().get().tips();
        var positive = tips.get("positive");
        var negative = tips.get("negative");
        if (tips.values().stream().anyMatch(tip -> !tip.position().equals(system.origin())
                || tip.remaining() < 3 || tip.remaining() > 5)
                || positive.direction() != negative.direction().getOpposite()) return false;
        systems.put(system.id(), system);
        index(system);
        // 没有横向地下茎时不启动幼丛补生；第一条路径落地后再由 install 启动。
        setDirty();
        return true;
    }

    /** 状态提案一次验证后更新；不写方块、不替代世界/个体生成事务。 */
    public boolean replace(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        notApplying();
        if (!canReplace(expected, next)) return false;
        if (!expected.equals(next)) install(expected, next);
        return true;
    }

    private boolean canReplace(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        return canReplace(expected, next, false);
    }

    private boolean canReplace(PlantRhizomeSystem expected, PlantRhizomeSystem next, boolean growthProven) {
        return canReplace(expected, next, growthProven, true);
    }

    private boolean canReplace(PlantRhizomeSystem expected, PlantRhizomeSystem next, boolean growthProven, boolean checkFit) {
        if (isReadOnly() || needsRecovery(expected.id()) || !expected.equals(systems.get(expected.id()))) return false;
        if (!expected.shoots().keySet().containsAll(next.shoots().keySet())
                && !shootTimer(expected.id()).map(RhizomeShootTimer::ready).orElse(false)) return false;
        if (!expected.id().equals(next.id()) || !expected.species().equals(next.species())
                || !expected.population().equals(next.population()) || !expected.origin().equals(next.origin())
                || expected.source() != next.source() || expected.shootLimit() != next.shootLimit())
            throw new IllegalArgumentException("Cannot reset rhizome identity, region, source or shoot limit");
        if (expected.source().isStatic() && (!expected.rhizomes().containsAll(next.rhizomes())
                || !expected.risingRhizomes().containsAll(next.risingRhizomes())
                || !expected.shoots().keySet().containsAll(next.shoots().keySet())))
            throw new IllegalArgumentException("Static rhizome systems cannot expand");
        expected.shoots().forEach((id, shoot) -> {
            if (next.shoots().containsKey(id) && !next.shoots().get(id).equals(shoot))
                throw new IllegalArgumentException("Cannot move an existing rhizome shoot link");
        });
        requireRisingTransition(expected, next);
        if (!growthProven) requireGrowthTransition(expected, next);
        return !checkFit || fits(next);
    }

    private void install(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        unindex(expected);
        systems.put(next.id(), next);
        index(next);
        updateShootTimer(expected, next);
        setDirty();
    }

    private static void requireRisingTransition(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        var added = new HashSet<>(next.risingRhizomes());
        added.removeAll(expected.risingRhizomes());
        if (added.isEmpty()) return;
        var newShoots = new HashSet<>(next.shoots().keySet());
        newShoots.removeAll(expected.shoots().keySet());
        if (!expected.source().canExpand() || newShoots.size() != 1
                || !next.shoots().keySet().containsAll(expected.shoots().keySet())
                || !next.risingRhizomes().containsAll(expected.risingRhizomes())
                || !next.rhizomes().equals(expected.rhizomes()) || !next.growth().equals(expected.growth()))
            throw new IllegalArgumentException("Rising additions require one new shoot without lateral growth");
        var shoot = next.shoots().get(newShoots.iterator().next());
        if (!expected.rhizomes().contains(shoot.attachment()))
            throw new IllegalArgumentException("Rising path must attach to an existing lateral node");
        int highest = added.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        long length = (long) highest - shoot.attachment().getY();
        if (length != added.size() || highest >= shoot.base().getY()
                || added.stream().anyMatch(pos -> pos.getX() != shoot.attachment().getX()
                        || pos.getZ() != shoot.attachment().getZ()))
            throw new IllegalArgumentException("Rising path must be a complete vertical column below the shoot");
    }

    /** 只有单段成功提案消耗一格，或一次性启动 D；普通切断/幼丛变化不能刷新预算。 */
    private static void requireGrowthTransition(PlantRhizomeSystem expected, PlantRhizomeSystem next) {
        if (expected.growth().equals(next.growth())) {
            if (!expected.rhizomes().containsAll(next.rhizomes()))
                throw new IllegalArgumentException("Rhizome addition requires a stored growth budget");
            return;
        }
        if (expected.growth().isEmpty() || next.growth().isEmpty() || !expected.source().canExpand())
            throw new IllegalArgumentException("Cannot initialize/reset rhizome growth through replacement");
        var before = expected.growth().get();
        var after = next.growth().get();
        for (var entry : after.branches().entrySet()) {
            UUID plantId = entry.getKey();
            var branch = entry.getValue();
            if (!before.branches().containsKey(plantId)) {
                if (expected.establishBranch(plantId, branch.root(), branch.growth()).filter(next::equals).isPresent()) return;
            } else {
                var old = before.branches().get(plantId);
                if (old.root().equals(branch.root()) && validD(old.growth(), branch.growth())) {
                    var replaced = new HashMap<>(before.branches());
                    replaced.put(plantId, branch);
                    var candidate = expected.withGrowth(new RhizomeGrowthState(before.dStarted(), before.tips(), replaced));
                    if (candidate.equals(next)) return;
                }
            }
        }
        if (!before.dStarted() && after.dStarted() && expected.rhizomes().equals(next.rhizomes())
                && expected.shoots().equals(next.shoots()) && expected.risingRhizomes().equals(next.risingRhizomes())) {
            boolean valid = before.tips().entrySet().stream().allMatch(entry -> {
                var tip = entry.getValue();
                var changed = after.tips().get(entry.getKey());
                int extra = changed.remaining() - tip.remaining();
                return tip.position().equals(changed.position()) && tip.direction() == changed.direction() && extra >= 2 && extra <= 5;
            });
            if (valid && before.branches().equals(after.branches())) return;
        }
        for (String arm : before.allTips().keySet()) {
            var tip = after.tip(arm);
            if (tip != null && !before.tip(arm).equals(tip)
                    && expected.extendTip(arm, tip.direction()).filter(next::equals).isPresent()) return;
        }
        throw new IllegalArgumentException("Invalid rhizome growth budget transition");
    }

    private static boolean validD(RhizomeGrowthState before, RhizomeGrowthState after) {
        return !before.dStarted() && after.dStarted() && before.branches().equals(after.branches())
                && before.tips().entrySet().stream().allMatch(entry -> {
                    var old = entry.getValue();
                    var tip = after.tips().get(entry.getKey());
                    int extra = tip.remaining() - old.remaining();
                    return old.position().equals(tip.position()) && old.direction() == tip.direction() && extra >= 2 && extra <= 5;
                });
    }

    /** 仅明确清空所有地下茎与幼丛关联后注销，不能直接忘记活系统以刷新名额。 */
    public boolean removeEmpty(PlantRhizomeSystem expected) {
        notApplying();
        if (isReadOnly() || needsRecovery(expected.id()) || !expected.equals(systems.get(expected.id()))
                || !expected.rhizomes().isEmpty() || !expected.risingRhizomes().isEmpty()
                || !expected.shoots().isEmpty()) return false;
        unindex(expected);
        systems.remove(expected.id());
        shootTimers.remove(expected.id());
        shootScheduler.cancel(expected.id());
        setDirty();
        return true;
    }

    private static boolean other(UUID owner, UUID id) { return owner != null && !owner.equals(id); }

    private boolean fits(PlantRhizomeSystem system) {
        return fits(system, Map.of());
    }

    private boolean foreignNode(BlockPos pos, UUID id, Map<UUID, RhizomeOverwrite.Transfer> transfers) {
        UUID owner = nodes.get(pos);
        if (!other(owner, id)) return false;
        var transfer = transfers.get(owner);
        return transfer == null || transfer.after().rhizomes().contains(pos) || transfer.after().risingRhizomes().contains(pos);
    }

    private boolean fits(PlantRhizomeSystem system, Map<UUID, RhizomeOverwrite.Transfer> transfers) {
        UUID id = system.id();
        if (other(children.get(id), id)) return false;
        // 原点/连接是稳定历史引用，覆盖后可仍指向异系统现拥有的物理节点；不把引用当作合并。
        for (BlockPos root : system.roots()) if (other(origins.get(root), id) || other(shootBases.get(root), id)) return false;
        for (BlockPos pos : system.rhizomes()) if (foreignNode(pos, id, transfers) || other(shootBases.get(pos), id)) return false;
        for (BlockPos pos : system.risingRhizomes()) if (foreignNode(pos, id, transfers) || other(shootBases.get(pos), id)) return false;
        for (var entry : system.shoots().entrySet()) {
            if (other(children.get(entry.getKey()), id) || (systems.containsKey(entry.getKey()) && !entry.getKey().equals(id))
                    || other(attachments.get(entry.getValue().attachment()), id) || other(shootBases.get(entry.getValue().attachment()), id)
                    || foreignNode(entry.getValue().base(), id, transfers) || other(origins.get(entry.getValue().base()), id)
                    || other(shootBases.get(entry.getValue().base()), id) || other(attachments.get(entry.getValue().base()), id)) return false;
        }
        return true;
    }

    private boolean validateTransfers(PlantRhizomeSystem expected, PlantRhizomeSystem next,
            Map<UUID, RhizomeOverwrite.Transfer> transfers) {
        var added = new HashSet<>(next.rhizomes());
        added.addAll(next.risingRhizomes());
        added.removeAll(expected.rhizomes());
        added.removeAll(expected.risingRhizomes());
        for (var entry : transfers.entrySet()) {
            var transfer = entry.getValue();
            var before = transfer.before();
            if (!entry.getKey().equals(before.id()) || before.id().equals(expected.id())
                    || !before.equals(systems.get(before.id())) || needsRecovery(before.id())) return false;
            var lateral = new HashSet<>(before.rhizomes());
            lateral.retainAll(added);
            var rising = new HashSet<>(before.risingRhizomes());
            rising.retainAll(added);
            if (lateral.isEmpty() && rising.isEmpty()) return false;
            var derived = before.removeRhizomes(lateral).removeRisingRhizomes(rising);
            if (!derived.equals(transfer.after()) || !canReplace(before, derived)) return false;
        }
        return fits(next, transfers);
    }

    private void index(PlantRhizomeSystem system) {
        UUID id = system.id();
        system.roots().forEach(root -> origins.put(root, id));
        system.rhizomes().forEach(pos -> nodes.put(pos, id));
        system.risingRhizomes().forEach(pos -> nodes.put(pos, id));
        system.shoots().forEach((child, shoot) -> {
            children.put(child, id);
            shootBases.put(shoot.base(), id);
            attachments.put(shoot.attachment(), id);
        });
    }
    private void unindex(PlantRhizomeSystem system) {
        UUID id = system.id();
        system.roots().forEach(root -> origins.remove(root, id));
        system.rhizomes().forEach(pos -> nodes.remove(pos, id));
        system.risingRhizomes().forEach(pos -> nodes.remove(pos, id));
        system.shoots().forEach((child, shoot) -> {
            children.remove(child, id);
            shootBases.remove(shoot.base(), id);
            attachments.remove(shoot.attachment(), id);
        });
    }

    private static PlantRhizomeSystems load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new PlantRhizomeSystems();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") < 1 || tag.getInt("Version") > 6
                    || !tag.contains("Systems", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported rhizome system data");
            int version = tag.getInt("Version");
            ListTag entries = tag.getList("Systems", Tag.TAG_COMPOUND);
            if (entries.size() != ((ListTag) tag.get("Systems")).size()) throw new IllegalArgumentException("Invalid systems list");
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                if (!entry.hasUUID("Id") || !entry.contains("Origin", Tag.TAG_LONG)
                        || !entry.contains("ShootLimit", Tag.TAG_INT) || !entry.contains("Rhizomes", Tag.TAG_LONG_ARRAY)
                        || !entry.contains("Shoots", Tag.TAG_LIST)) throw new IllegalArgumentException("Incomplete rhizome system");
                for (String key : new String[]{"Species", "Population", "Source"})
                    if (!entry.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing rhizome " + key);
                var positions = new HashSet<BlockPos>();
                for (long value : entry.getLongArray("Rhizomes"))
                    if (!positions.add(BlockPos.of(value))) throw new IllegalArgumentException("Duplicate rhizome position");
                var rising = new HashSet<BlockPos>();
                if (version >= 3) {
                    if (!entry.contains("RisingRhizomes", Tag.TAG_LONG_ARRAY))
                        throw new IllegalArgumentException("Missing rising rhizomes");
                    for (long value : entry.getLongArray("RisingRhizomes"))
                        if (!rising.add(BlockPos.of(value))) throw new IllegalArgumentException("Duplicate rising rhizome");
                }
                ListTag shoots = entry.getList("Shoots", Tag.TAG_COMPOUND);
                if (shoots.size() != ((ListTag) entry.get("Shoots")).size()) throw new IllegalArgumentException("Invalid shoot list");
                Map<UUID, PlantRhizomeSystem.Shoot> links = new HashMap<>();
                for (int j = 0; j < shoots.size(); j++) {
                    CompoundTag shoot = shoots.getCompound(j);
                    if (!shoot.hasUUID("Child") || !shoot.contains("Attachment", Tag.TAG_LONG) || !shoot.contains("Base", Tag.TAG_LONG))
                        throw new IllegalArgumentException("Incomplete rhizome shoot");
                    if (links.putIfAbsent(shoot.getUUID("Child"), new PlantRhizomeSystem.Shoot(
                            BlockPos.of(shoot.getLong("Attachment")), BlockPos.of(shoot.getLong("Base")))) != null)
                        throw new IllegalArgumentException("Duplicate rhizome child");
                }
                ResourceLocation species = ResourceLocation.tryParse(entry.getString("Species"));
                if (species == null) throw new IllegalArgumentException("Invalid rhizome species id");
                Optional<RhizomeGrowthState> growth = Optional.empty();
                if (version >= 2) {
                    if (!entry.contains("HasGrowth", Tag.TAG_BYTE)) throw new IllegalArgumentException("Missing growth presence flag");
                    if (entry.getBoolean("HasGrowth")) {
                        if (!entry.contains("Growth", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing growth state");
                        growth = Optional.of(readGrowth(entry.getCompound("Growth"), version >= 6, false));
                    } else if (entry.contains("Growth")) throw new IllegalArgumentException("Unexpected growth state");
                }
                var system = new PlantRhizomeSystem(entry.getUUID("Id"), species,
                        entry.getString("Population"), BlockPos.of(entry.getLong("Origin")),
                        PlantSource.valueOf(entry.getString("Source")), entry.getInt("ShootLimit"), positions, links, growth, rising);
                // 历史原点可能已被切掉、节点可能全部为空，不能按新建规则重掷或补建。
                if (data.systems.containsKey(system.id()) || !data.fits(system))
                    throw new IllegalArgumentException("Conflicting rhizome system ownership");
                data.systems.put(system.id(), system);
                data.index(system);
                if (version >= 4) {
                    if (!entry.contains("RecoveryRequired", Tag.TAG_BYTE))
                        throw new IllegalArgumentException("Missing rhizome recovery flag");
                    if (entry.getBoolean("RecoveryRequired")) data.recoveryRequired.add(system.id());
                }
                if (version >= 5) {
                    if (!entry.contains("HasShootTimer", Tag.TAG_BYTE)) throw new IllegalArgumentException("Missing shoot timer flag");
                    if (entry.getBoolean("HasShootTimer")) {
                        if (!entry.contains("ShootElapsed", Tag.TAG_LONG) || !canRegrowShoots(system))
                            throw new IllegalArgumentException("Invalid shoot timer owner");
                        data.shootTimers.put(system.id(), new RhizomeShootTimer(entry.getLong("ShootElapsed")));
                    } else if (entry.contains("ShootElapsed") || (canRegrowShoots(system) && !data.needsRecovery(system.id())))
                        throw new IllegalArgumentException("Inconsistent shoot timer presence");
                }
            }
            for (var system : data.systems.values()) {
                if (version < 5) data.updateShootTimer(null, system);
                else if (!data.needsRecovery(system.id())) {
                    var timer = data.shootTimers.get(system.id());
                    if (timer != null) data.shootScheduler.updateSystem(system.id(), system.origin(),
                            RhizomeShootTimer.TARGET_TICKS, timer.elapsedTicks());
                }
            }
            data.setDirty(version < 6);
        } catch (IllegalArgumentException exception) {
            data.systems.clear(); data.nodes.clear(); data.origins.clear(); data.children.clear();
            data.shootBases.clear(); data.attachments.clear();
            data.recoveryRequired.clear();
            data.shootTimers.keySet().forEach(data.shootScheduler::cancel);
            data.shootTimers.clear();
            data.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(PlantRhizomeSystems.class)
                    .error("Rhizome systems opened read-only; original payload preserved", exception);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (isReadOnly()) return preserved.copy();
        tag.putInt("Version", 6);
        ListTag entries = new ListTag();
        systems.values().stream().sorted(java.util.Comparator.comparing(PlantRhizomeSystem::id)).forEach(system -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", system.id());
            entry.putBoolean("RecoveryRequired", needsRecovery(system.id()));
            var timer = shootTimer(system.id());
            entry.putBoolean("HasShootTimer", timer.isPresent());
            timer.ifPresent(value -> entry.putLong("ShootElapsed", value.elapsedTicks()));
            entry.putString("Species", system.species().toString());
            entry.putString("Population", system.population());
            entry.putLong("Origin", system.origin().asLong());
            entry.putString("Source", system.source().name());
            entry.putInt("ShootLimit", system.shootLimit());
            entry.putLongArray("Rhizomes", system.rhizomes().stream().mapToLong(BlockPos::asLong).sorted().toArray());
            entry.putLongArray("RisingRhizomes", system.risingRhizomes().stream().mapToLong(BlockPos::asLong).sorted().toArray());
            ListTag shoots = new ListTag();
            system.shoots().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
                CompoundTag shoot = new CompoundTag();
                shoot.putUUID("Child", value.getKey());
                shoot.putLong("Attachment", value.getValue().attachment().asLong());
                shoot.putLong("Base", value.getValue().base().asLong());
                shoots.add(shoot);
            });
            entry.put("Shoots", shoots);
            entry.putBoolean("HasGrowth", system.growth().isPresent());
            system.growth().ifPresent(growth -> entry.put("Growth", writeGrowth(growth)));
            entries.add(entry);
        });
        tag.put("Systems", entries);
        return tag;
    }

    private static RhizomeGrowthState readGrowth(CompoundTag saved, boolean branchFormat, boolean nested) {
        if (!branchFormat && saved.contains("Branches")) throw new IllegalArgumentException("Growth branches require version 6");
        if (!saved.contains("DStarted", Tag.TAG_BYTE) || !saved.contains("Tips", Tag.TAG_LIST))
            throw new IllegalArgumentException("Incomplete rhizome growth state");
        ListTag tips = saved.getList("Tips", Tag.TAG_COMPOUND);
        if (tips.size() != ((ListTag) saved.get("Tips")).size()) throw new IllegalArgumentException("Invalid tips list");
        Map<String, RhizomeGrowthState.Tip> restored = new HashMap<>();
        for (int i = 0; i < tips.size(); i++) {
            var tip = tips.getCompound(i);
            if (!tip.contains("Key", Tag.TAG_STRING) || !tip.contains("Position", Tag.TAG_LONG)
                    || !tip.contains("Direction", Tag.TAG_STRING) || !tip.contains("Remaining", Tag.TAG_INT))
                throw new IllegalArgumentException("Incomplete rhizome tip");
            if (restored.putIfAbsent(tip.getString("Key"), new RhizomeGrowthState.Tip(BlockPos.of(tip.getLong("Position")),
                    Direction.valueOf(tip.getString("Direction")), tip.getInt("Remaining"))) != null)
                throw new IllegalArgumentException("Duplicate tip key");
        }
        Map<UUID, RhizomeGrowthState.Branch> branches = new HashMap<>();
        if (branchFormat) {
            if (!saved.contains("Branches", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing growth branches");
            ListTag entries = saved.getList("Branches", Tag.TAG_COMPOUND);
            if (entries.size() != ((ListTag) saved.get("Branches")).size() || (nested && !entries.isEmpty()))
                throw new IllegalArgumentException("Invalid or nested growth branches");
            for (int i = 0; i < entries.size(); i++) {
                var branch = entries.getCompound(i);
                if (!branch.hasUUID("Plant") || !branch.contains("Root", Tag.TAG_LONG) || !branch.contains("Growth", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Incomplete growth branch");
                if (branches.putIfAbsent(branch.getUUID("Plant"), new RhizomeGrowthState.Branch(
                        BlockPos.of(branch.getLong("Root")), readGrowth(branch.getCompound("Growth"), true, true))) != null)
                    throw new IllegalArgumentException("Duplicate growth branch");
            }
        }
        return new RhizomeGrowthState(saved.getBoolean("DStarted"), restored, branches);
    }

    private static CompoundTag writeGrowth(RhizomeGrowthState growth) {
        var saved = new CompoundTag();
        saved.putBoolean("DStarted", growth.dStarted());
        ListTag tips = new ListTag();
        growth.tips().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            var tip = new CompoundTag();
            tip.putString("Key", value.getKey());
            tip.putLong("Position", value.getValue().position().asLong());
            tip.putString("Direction", value.getValue().direction().name());
            tip.putInt("Remaining", value.getValue().remaining());
            tips.add(tip);
        });
        saved.put("Tips", tips);
        ListTag branches = new ListTag();
        growth.branches().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            var branch = new CompoundTag();
            branch.putUUID("Plant", value.getKey());
            branch.putLong("Root", value.getValue().root().asLong());
            branch.put("Growth", writeGrowth(value.getValue().growth()));
            branches.add(branch);
        });
        saved.put("Branches", branches);
        return saved;
    }
}
