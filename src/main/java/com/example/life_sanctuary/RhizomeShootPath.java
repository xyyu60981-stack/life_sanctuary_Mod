package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 地下茎向地表出苗的整条竖向路径预检；不以成年植株空间作为出苗条件。 */
public final class RhizomeShootPath {
    private RhizomeShootPath() {}
    public enum Reason {
        READY, READ_ONLY, RECOVERY_REQUIRED, STALE_SYSTEM, SOURCE_DISABLED, NO_SHOOT_SLOT, ROOT_NOT_REGISTERED,
        COLUMN_IN_USE, INVALID_COLUMN, OUTSIDE_BUILD_HEIGHT, OUTSIDE_WORLD_BORDER, CHUNK_NOT_TICKING,
        WRONG_ROOT, OTHER_STRUCTURE, BLOCK_ENTITY, PATH_BLOCKED, INSUFFICIENT_HEADROOM
    }

    public record Path(PlantRhizomeSystem system, BlockPos root, BlockPos surface, BlockPos shootBase,
            List<BlockPos> risingPath, List<BlockPos> thinStems, boolean waterloggedShoot,
            Map<BlockPos, BlockState> observed) {
        public Path {
            root = root.immutable();
            surface = surface.immutable();
            shootBase = shootBase.immutable();
            risingPath = risingPath.stream().map(BlockPos::immutable).toList();
            thinStems = thinStems.stream().map(BlockPos::immutable).toList();
            Map<BlockPos, BlockState> copied = new LinkedHashMap<>();
            observed.forEach((pos, state) -> copied.put(pos.immutable(), Objects.requireNonNull(state)));
            observed = Map.copyOf(copied);
        }
    }
    public record Result(Reason reason, Optional<BlockPos> position, Optional<Path> path) {
        public boolean ready() { return reason == Reason.READY; }
    }

    /**
     * surface 是物种判定的实际地表方块，不是任意地下空洞的顶面；与 root 必须同列。
     * 从 root 上方到 surface 只允许空气、水方块或苔藓可转换方块；地表上方两格只允许空气/水。
     * risingPath 全部转换为地下茎；两格水时另以细茎抬高幼丛，水面以上须有两格空气。
     * 最多支持两格水，不预检成年体高度、侧枝或树冠。
     * validRoot 为只读的真实地下茎方块判定；本方法不加载区块、不写方块、不占幼丛名额。
     */
    public static Result inspect(ServerLevel level, PlantRhizomeSystems systems, PlantRhizomeSystem expected,
            BlockPos root, BlockPos surface, Predicate<BlockState> validRoot) {
        Objects.requireNonNull(validRoot);
        if (systems != PlantRhizomeSystems.get(level)) throw new IllegalArgumentException("Wrong dimension rhizome store");
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly()) return rejected(Reason.READ_ONLY, null);
        if (systems.needsRecovery(expected.id())) return rejected(Reason.RECOVERY_REQUIRED, root);
        if (!expected.equals(systems.find(expected.id()).orElse(null))) return rejected(Reason.STALE_SYSTEM, root);
        if (!expected.source().canExpand()) return rejected(Reason.SOURCE_DISABLED, root);
        if (expected.freeShootSlots() == 0) return rejected(Reason.NO_SHOOT_SLOT, root);
        if (!expected.rhizomes().contains(root)) return rejected(Reason.ROOT_NOT_REGISTERED, root);
        if (expected.shoots().values().stream().anyMatch(shoot -> shoot.attachment().equals(root)))
            return rejected(Reason.COLUMN_IN_USE, root);
        if (root.getX() != surface.getX() || root.getZ() != surface.getZ() || surface.getY() < root.getY())
            return rejected(Reason.INVALID_COLUMN, surface);
        if (level.isOutsideBuildHeight(root) || level.isOutsideBuildHeight(surface))
            return rejected(Reason.OUTSIDE_BUILD_HEIGHT, surface);
        BlockPos head = surface.above(2);
        if (level.isOutsideBuildHeight(head)) return rejected(Reason.OUTSIDE_BUILD_HEIGHT, head);
        if (!level.getWorldBorder().isWithinBounds(root)) return rejected(Reason.OUTSIDE_WORLD_BORDER, root);
        int chunkX = root.getX() >> 4, chunkZ = root.getZ() >> 4;
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)
                || !level.getChunkSource().isPositionTicking(ChunkPos.asLong(chunkX, chunkZ)))
            return rejected(Reason.CHUNK_NOT_TICKING, root);

        // 所有位置同列，端点已确认构建高度/区块可用，之后才开始读取任何世界方块。
        Map<BlockPos, BlockState> observed = new LinkedHashMap<>();
        BlockState rootState = level.getBlockState(root);
        if (!validRoot.test(rootState)) return rejected(Reason.WRONG_ROOT, root);
        observed.put(root.immutable(), rootState);
        List<BlockPos> path = new ArrayList<>();
        List<BlockPos> thinStems = List.of();
        BlockPos shootBase = surface.above();
        boolean waterloggedShoot = false;
        for (int y = root.getY() + 1; y <= head.getY(); y++) {
            BlockPos pos = new BlockPos(root.getX(), y, root.getZ());
            if (level.isOutsideBuildHeight(pos)) return rejected(Reason.OUTSIDE_BUILD_HEIGHT, pos);
            // 空气中的身份锚点或损坏残留索引也不能被另一株借用。
            if (individuals.atPart(pos).isPresent() || individuals.atBase(pos).isPresent())
                return rejected(Reason.OTHER_STRUCTURE, pos);
            BlockState state = level.getBlockState(pos);
            if (state.hasBlockEntity()) return rejected(Reason.BLOCK_ENTITY, pos);
            observed.put(pos, state);
            if (y <= surface.getY()) {
                if (!RhizomeOverwrite.canConvert(systems, expected, pos, state)) return rejected(Reason.PATH_BLOCKED, pos);
                path.add(pos);
            } else if (systems.atRhizome(pos).isPresent() || systems.atOrigin(pos).isPresent()
                    || systems.atAttachment(pos).isPresent() || systems.atShootBase(pos).isPresent()) {
                return rejected(Reason.OTHER_STRUCTURE, pos);
            } else if (!openSpace(state)) {
                return rejected(Reason.INSUFFICIENT_HEADROOM, pos);
            }
            if (y == surface.getY() + 2) {
                BlockState first = observed.get(surface.above());
                if (first.is(Blocks.WATER) && state.is(Blocks.WATER)) {
                    // 仅两格水可抬高；继续检查的两格必须全为空气，第三格水也拒绝。
                    head = surface.above(4);
                    thinStems = List.of(surface.above(), surface.above(2));
                    shootBase = surface.above(3);
                } else if (!RhizomePathRules.isAirLike(state)) {
                    return rejected(Reason.INSUFFICIENT_HEADROOM, pos);
                } else {
                    waterloggedShoot = first.is(Blocks.WATER) && first.getFluidState().isSource();
                }
            } else if (y > surface.getY() + 2 && !RhizomePathRules.isAirLike(state)) {
                return rejected(Reason.INSUFFICIENT_HEADROOM, pos);
            }
        }
        Map<BlockPos, BlockState> decorationReads = new LinkedHashMap<>(observed);
        decorationReads.remove(root);
        var partners = RhizomeDecorationCleanup.partners(level, decorationReads);
        if (partners.isEmpty()) return rejected(Reason.PATH_BLOCKED, null);
        observed.putAll(partners.get());
        if (!expected.equals(systems.find(expected.id()).orElse(null))) return rejected(Reason.STALE_SYSTEM, root);
        return new Result(Reason.READY, Optional.empty(), Optional.of(new Path(expected, root, surface,
                shootBase, path, thinStems, waterloggedShoot, observed)));
    }

    private static boolean openSpace(BlockState state) { return RhizomePathRules.isAirLike(state) || state.is(Blocks.WATER); }
    private static Result rejected(Reason reason, BlockPos pos) {
        return new Result(reason, Optional.ofNullable(pos).map(BlockPos::immutable), Optional.empty());
    }
}
