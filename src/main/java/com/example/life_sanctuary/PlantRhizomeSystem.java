package com.example.life_sanctuary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * 芦木地下系统的不可变模型。切断可产生多个片段，但仍共享一个系统身份和幼丛名额。
 * 横向节点和竖向地下茎分别保存；细茎及幼丛仍由个体结构接口处理。
 */
public record PlantRhizomeSystem(UUID id, ResourceLocation species, String population, BlockPos origin,
        PlantSource source, int shootLimit, Set<BlockPos> rhizomes, Map<UUID, Shoot> shoots,
        Optional<RhizomeGrowthState> growth, Set<BlockPos> risingRhizomes) {
    public PlantRhizomeSystem(UUID id, ResourceLocation species, String population, BlockPos origin,
            PlantSource source, int shootLimit, Set<BlockPos> rhizomes, Map<UUID, Shoot> shoots,
            Optional<RhizomeGrowthState> growth) {
        this(id, species, population, origin, source, shootLimit, rhizomes, shoots, growth, Set.of());
    }
    public PlantRhizomeSystem(UUID id, ResourceLocation species, String population, BlockPos origin,
            PlantSource source, int shootLimit, Set<BlockPos> rhizomes, Map<UUID, Shoot> shoots) {
        this(id, species, population, origin, source, shootLimit, rhizomes, shoots, Optional.empty());
    }
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    public record Shoot(BlockPos attachment, BlockPos base) {
        public Shoot {
            attachment = attachment.immutable();
            base = base.immutable();
            if (base.getX() != attachment.getX() || base.getZ() != attachment.getZ() || base.getY() <= attachment.getY())
                throw new IllegalArgumentException("Rhizome shoot must rise vertically to the surface");
        }
    }

    public PlantRhizomeSystem {
        Objects.requireNonNull(id);
        Objects.requireNonNull(species);
        Objects.requireNonNull(source);
        Objects.requireNonNull(growth);
        origin = origin.immutable();
        if (population == null || population.isBlank() || source == PlantSource.ONE_TIME || shootLimit < 2 || shootLimit > 3)
            throw new IllegalArgumentException("Invalid rhizome system metadata");
        Set<BlockPos> copied = new HashSet<>();
        for (BlockPos pos : rhizomes) copied.add(pos.immutable());
        rhizomes = Set.copyOf(copied);
        risingRhizomes = risingRhizomes.stream().map(BlockPos::immutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
        shoots = Map.copyOf(shoots);
        if (shoots.containsKey(id)) throw new IllegalArgumentException("Primary plant cannot occupy a descendant slot");
        if (shoots.size() > shootLimit) throw new IllegalArgumentException("Too many shoots in rhizome system");
        Set<Integer> rootLayers = new HashSet<>();
        rootLayers.add(origin.getY());
        growth.ifPresent(state -> state.branches().values().forEach(branch -> rootLayers.add(branch.root().getY())));
        int lowestRoot = rootLayers.stream().mapToInt(Integer::intValue).min().orElseThrow();
        for (BlockPos pos : rhizomes)
            if (!sameRegion(origin, pos) || !rootLayers.contains(pos.getY()))
                throw new IllegalArgumentException("Rhizome outside fixed region or underground layer");
        for (BlockPos pos : risingRhizomes)
            if (!sameRegion(origin, pos) || pos.getY() <= lowestRoot || pos.equals(origin) || rhizomes.contains(pos))
                throw new IllegalArgumentException("Rising rhizome outside region or above-ground-root column");
        Set<BlockPos> occupiedColumns = new HashSet<>();
        for (Shoot shoot : shoots.values()) {
            if (!sameRegion(origin, shoot.attachment()) || !rootLayers.contains(shoot.attachment().getY())
                    || !occupiedColumns.add(shoot.attachment()) || risingRhizomes.contains(shoot.base()))
                throw new IllegalArgumentException("Invalid or duplicate rhizome shoot attachment");
        }
        if (growth.isPresent()) {
            Set<BlockPos> branchRoots = new HashSet<>();
            branchRoots.add(origin);
            if (growth.get().branches().containsKey(id)) throw new IllegalArgumentException("Primary plant cannot be its own branch");
            for (var branch : growth.get().branches().values())
                if (!sameRegion(origin, branch.root()) || !branchRoots.add(branch.root())
                        || rhizomes.contains(branch.root()) || risingRhizomes.contains(branch.root()))
                    throw new IllegalArgumentException("Invalid or duplicate branch root");
            for (var entry : growth.get().allTips().entrySet()) {
                var tip = entry.getValue();
                if (!sameRegion(origin, tip.position()) || tip.position().getY() != growth.get().rootFor(entry.getKey(), origin).getY())
                    throw new IllegalArgumentException("Rhizome growth tip outside region/layer");
            }
        }
    }

    public int regionX() { return Math.floorDiv(origin.getX(), 32); }
    public int regionZ() { return Math.floorDiv(origin.getZ(), 32); }
    /** 名额统计全部关联地上植株；它们共享一个生物个体 ID，长到 C/D 后仍然占额。 */
    public int freeShootSlots() { return shootLimit - shoots.size(); }
    public boolean containsRegion(BlockPos pos) { return sameRegion(origin, pos); }

    public Set<BlockPos> roots() {
        Set<BlockPos> roots = new HashSet<>();
        roots.add(origin);
        growth.ifPresent(state -> state.branches().values().forEach(branch -> roots.add(branch.root())));
        return Set.copyOf(roots);
    }

    /** 历史分支根位也保留身份引用；查询不复制 roots 集合。 */
    public boolean isRoot(BlockPos pos) {
        return origin.equals(pos) || growth.map(state -> state.branches().values().stream()
                .anyMatch(branch -> branch.root().equals(pos))).orElse(false);
    }

    /** 选路与模型提交共用本系统占位约束，不读取世界、不构建候选系统。 */
    boolean canExtendInto(BlockPos pos) {
        return containsRegion(pos) && !rhizomes.contains(pos) && !risingRhizomes.contains(pos)
                && !isRoot(pos) && !isFiberAnchor(pos);
    }

    /** C 转换后的中央纤维可承接旧末端的 D 延伸，不把同源纤维当成异物或另一个个体。 */
    public boolean isFiberAnchor(BlockPos pos) {
        if (pos.equals(origin)) return true;
        if (growth.isEmpty()) return false;
        for (var entry : shoots.entrySet()) {
            var branch = growth.get().branches().get(entry.getKey());
            if (branch != null && pos.getX() == branch.root().getX() && pos.getZ() == branch.root().getZ()
                    && pos.getY() >= branch.root().getY() && pos.getY() <= Math.max(branch.root().getY(), entry.getValue().base().getY() - 1))
                return true;
        }
        return false;
    }

    public Set<BlockPos> fiberAnchors() {
        Set<BlockPos> result = new HashSet<>();
        result.add(origin);
        if (growth.isPresent()) for (var entry : shoots.entrySet()) {
            var branch = growth.get().branches().get(entry.getKey());
            if (branch == null) continue;
            result.add(branch.root());
            for (int y = branch.root().getY(); y < entry.getValue().base().getY(); y++)
                result.add(new BlockPos(branch.root().getX(), y, branch.root().getZ()));
        }
        return Set.copyOf(result);
    }


    private static boolean sameRegion(BlockPos origin, BlockPos pos) {
        return Math.floorDiv(origin.getX(), 32) == Math.floorDiv(pos.getX(), 32)
                && Math.floorDiv(origin.getZ(), 32) == Math.floorDiv(pos.getZ(), 32);
    }

    /** 单格延伸的纯提案，空间/土壤/含水与实际写入由适配器统一预检。 */
    public Optional<PlantRhizomeSystem> extend(BlockPos attachment, Direction direction) {
        if (growth.isEmpty()) return Optional.empty();
        var arms = growth.get().allTips().entrySet().stream().filter(entry -> entry.getValue().position().equals(attachment)).toList();
        return arms.size() == 1 ? extendTip(arms.getFirst().getKey(), direction) : Optional.empty();
    }

    public Optional<PlantRhizomeSystem> extendTip(String arm, Direction direction) {
        if (!source.canExpand() || growth.isEmpty()) return Optional.empty();
        var tip = growth.get().tip(arm);
        if (tip == null) return Optional.empty();
        BlockPos attachment = tip.position();
        if (!rhizomes.contains(attachment) && !isFiberAnchor(attachment))
            return Optional.empty();
        var nextGrowth = growth.get().advance(arm, direction);
        if (nextGrowth.isEmpty()) return Optional.empty();
        BlockPos candidate = attachment.relative(direction);
        if (!canExtendInto(candidate)) return Optional.empty();
        Set<BlockPos> next = new HashSet<>(rhizomes);
        next.add(candidate);
        return Optional.of(new PlantRhizomeSystem(id, species, population, origin, source, shootLimit,
                next, shoots, Optional.of(nextGrowth.get()), risingRhizomes));
    }

    /** 明确初始化/阶段提案，不能在失败重试或加载时重抽。 */
    public PlantRhizomeSystem withGrowth(RhizomeGrowthState growth) {
        return new PlantRhizomeSystem(id, species, population, origin, source, shootLimit, rhizomes, shoots, Optional.of(growth), risingRhizomes);
    }

    /** 每株到 D 只追加自己的两端长度；重复调用不抽数，不给其他同源植株追加。 */
    public Optional<PlantRhizomeSystem> beginD(UUID plantId, net.minecraft.util.RandomSource random) {
        if (!source.canExpand() || growth.isEmpty()) return Optional.empty();
        return (plantId.equals(id) ? growth.get().beginD(random) : growth.get().beginBranchD(plantId, random))
                .map(this::withGrowth);
    }

    /** 同源地上植株首次到 C：增加一对独立预算，个体身份、固定范围和共享名额均不变。 */
    public Optional<PlantRhizomeSystem> establishBranch(UUID plantId, BlockPos root, net.minecraft.util.RandomSource random) {
        if (growth.isEmpty() || growth.get().branches().containsKey(plantId) || !shoots.containsKey(plantId)) return Optional.empty();
        var shoot = shoots.get(plantId);
        if (!source.canExpand() || !containsRegion(root) || roots().contains(root)
                || root.getX() != shoot.base().getX() || root.getZ() != shoot.base().getZ()
                || root.getY() > shoot.base().getY()) return Optional.empty();
        return establishBranch(plantId, root, RhizomeGrowthState.initialC(root, random));
    }

    Optional<PlantRhizomeSystem> establishBranch(UUID plantId, BlockPos root, RhizomeGrowthState budget) {
        var shoot = shoots.get(plantId);
        if (!source.canExpand() || growth.isEmpty() || shoot == null || growth.get().branches().containsKey(plantId)
                || plantId.equals(id) || !containsRegion(root) || roots().contains(root)
                || root.getX() != shoot.base().getX() || root.getZ() != shoot.base().getZ()
                || root.getY() > shoot.base().getY() || budget.dStarted() || !budget.branches().isEmpty()
                || budget.tips().values().stream().anyMatch(tip -> !tip.position().equals(root)
                        || tip.remaining() < 3 || tip.remaining() > 5)
                || budget.tips().get("positive").direction() != budget.tips().get("negative").direction().getOpposite())
            return Optional.empty();
        Map<UUID, RhizomeGrowthState.Branch> branches = new HashMap<>(growth.get().branches());
        branches.put(plantId, new RhizomeGrowthState.Branch(root, budget));
        Set<BlockPos> lateral = new HashSet<>(rhizomes);
        Set<BlockPos> rising = new HashSet<>(risingRhizomes);
        java.util.function.Predicate<BlockPos> convertedFiber = pos -> pos.getX() == root.getX()
                && pos.getZ() == root.getZ() && pos.getY() >= root.getY() && pos.getY() < shoot.base().getY();
        lateral.removeIf(convertedFiber);
        rising.removeIf(convertedFiber);
        return Optional.of(new PlantRhizomeSystem(id, species, population, origin, source, shootLimit,
                lateral, shoots, Optional.of(new RhizomeGrowthState(growth.get().dStarted(), growth.get().tips(), branches)), rising));
    }

    /** 路径通过且方块成功落地后才保存本提案；竖向段不消耗横向末端长度。 */
    public Optional<PlantRhizomeSystem> claimShootWithPath(PlantIndividualState child, RhizomeShootPath.Path path) {
        if (!equals(path.system()) || !child.base().equals(path.shootBase())) return Optional.empty();
        return claimShoot(child, path.root()).map(claimed -> {
            Set<BlockPos> rising = new HashSet<>(risingRhizomes);
            rising.addAll(path.risingPath());
            return new PlantRhizomeSystem(id, species, population, origin, source, shootLimit,
                    rhizomes, claimed.shoots(), growth, rising);
        });
    }

    /** 切断竖向段不移动连接、不释放幼丛名额，也不补回已经移除的方块。 */
    public PlantRhizomeSystem removeRisingRhizomes(Set<BlockPos> removed) {
        if (!risingRhizomes.containsAll(removed)) throw new IllegalArgumentException("Unknown rising rhizome removal");
        Set<BlockPos> next = new HashSet<>(risingRhizomes);
        next.removeAll(removed);
        return new PlantRhizomeSystem(id, species, population, origin, source, shootLimit, rhizomes, shoots, growth, next);
    }

    /** 切断只移除实际地下茎，不拆出新的系统、不重新抽名额、不提前释放现存幼丛。 */
    public PlantRhizomeSystem removeRhizomes(Set<BlockPos> removed) {
        if (!rhizomes.containsAll(removed)) throw new IllegalArgumentException("Unknown rhizome removal");
        Set<BlockPos> next = new HashSet<>(rhizomes);
        next.removeAll(removed);
        return copy(next, shoots);
    }

    /** 计划子体占位；返回新快照不实际占额，最终须与子体生成在统一事务中提交。 */
    public Optional<PlantRhizomeSystem> claimShoot(PlantIndividualState child, BlockPos attachment) {
        if (!source.canExpand() || freeShootSlots() == 0 || !rhizomes.contains(attachment)
                || shoots.containsKey(child.id()) || !species.equals(child.species()) || !population.equals(child.population())
                || child.origin() != PlantOrigin.AUTOMATIC_SPREAD || child.source().isStatic()
                || child.base().getX() != attachment.getX() || child.base().getZ() != attachment.getZ()
                || child.base().getY() <= attachment.getY()
                || shoots.values().stream().anyMatch(shoot -> shoot.attachment().equals(attachment))) return Optional.empty();
        Shoot shoot = new Shoot(attachment, child.base());
        Map<UUID, Shoot> next = new HashMap<>(shoots);
        next.put(child.id(), shoot);
        return Optional.of(copy(rhizomes, next));
    }

    /** 仅在对应个体确已注销时调用；长到 C、剪止或地下茎断裂均不释放名额。 */
    public PlantRhizomeSystem releaseShoot(UUID child) {
        if (!shoots.containsKey(child)) return this;
        Map<UUID, Shoot> next = new HashMap<>(shoots);
        next.remove(child);
        return copy(rhizomes, next);
    }

    /** 仅结构变化或诊断时调用；片段共享本记录，返回值不能各自注册成新系统。 */
    public List<Set<BlockPos>> components() {
        Set<BlockPos> unseen = new HashSet<>(rhizomes);
        List<Set<BlockPos>> result = new ArrayList<>();
        for (BlockPos first : rhizomes.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
            if (!unseen.remove(first)) continue;
            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> pending = new ArrayDeque<>();
            pending.add(first);
            while (!pending.isEmpty()) {
                BlockPos current = pending.removeFirst();
                component.add(current);
                for (Direction direction : HORIZONTAL) {
                    BlockPos next = current.relative(direction);
                    if (unseen.remove(next)) pending.addLast(next);
                }
            }
            result.add(Set.copyOf(component));
        }
        return List.copyOf(result);
    }

    private PlantRhizomeSystem copy(Set<BlockPos> positions, Map<UUID, Shoot> shoots) {
        return new PlantRhizomeSystem(id, species, population, origin, source, shootLimit, positions, shoots, growth, risingRhizomes);
    }
}
