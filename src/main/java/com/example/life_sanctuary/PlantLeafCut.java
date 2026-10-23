package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** 手工破坏整片独立叶的入口；不是采集孢子，也不是剪止整株。 */
public final class PlantLeafCut {
    private PlantLeafCut() {}
    public record Result(PlantStructureCommitter.Result commit, Map<BlockPos, BlockState> removed) {
        public Result { removed = Map.copyOf(removed); }
    }

    /** 命中叶柄／叶片任一登记子部件时切除所属整片叶；不会把中心芽丛当作叶。 */
    public static Result cutAt(ServerLevel level, BlockPos hitPart, PlantStructureSupports supports,
            Map<BlockPos, BlockState> observed, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var store = PlantIndividuals.get(level);
        var target = PlantLeafTarget.at(store, hitPart).orElse(null);
        if (target == null) return new Result(new PlantStructureCommitter.Result(
                PlantStructureCommitter.Status.REJECTED, java.util.Optional.empty()), Map.of());
        var leaves = store.leavesOf(target.individual().id()).orElseThrow();
        return cut(level, target.individual(), leaves, target.key(), supports, observed, random);
    }

    /**
     * 在原版实际破坏/掉落前拦截并调用。只有 COMMITTED 的 removed 可用于物种掉落处理，
     * 共享层不生成草杆、不按部件数猜测掉落量，也不能再执行一次原版整片掉落。
     */
    public static Result cut(ServerLevel level, PlantIndividualState expected, PlantLeafData leaves, String key,
            PlantStructureSupports supports, Map<BlockPos, BlockState> observed, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var leaf = leaves.leaves().leaves().get(key);
        Map<BlockPos, BlockState> removed = new LinkedHashMap<>();
        if (leaf != null) PlantIndividuals.get(level).partsOf(expected.id()).forEach((pos, role) -> {
            if (role.equals(leaf.role()) || role.startsWith(leaf.role() + "/")) {
                var before = observed.get(pos);
                if (before != null) removed.put(pos, before);
            }
        });
        var commit = PlantLeafShedding.cut(level, expected, leaves, key, supports, observed, random);
        return new Result(commit, commit.status() == PlantStructureCommitter.Status.COMMITTED ? removed : Map.of());
    }
}
