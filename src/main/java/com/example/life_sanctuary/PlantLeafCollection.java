package com.example.life_sanctuary;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;

/** 独立可育叶的孢子装袋与扣次；种子/其他采集产物不得伪装成孢子袋。 */
public final class PlantLeafCollection {
    private PlantLeafCollection() {}
    public enum Result { COLLECTED, REJECTED, RECOVERY_REQUIRED }

    /** 命中部件与切叶共用叶身份解析；库存规则、扣次与失败冻结仍走既有采集事务。 */
    public static Result collectSporeAt(ServerPlayer player, InteractionHand hand, BlockPos hitPart) {
        var level = player.serverLevel();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var target = PlantLeafTarget.at(PlantIndividuals.get(level), hitPart).orElse(null);
        return target == null ? Result.REJECTED : collectSpore(player, hand, target.individual(), target.key());
    }

    /** 普通玩家使用手持/装备采集箱；模拟玩家沿用现有手持空袋和库存输出规则。 */
    public static Result collectSpore(ServerPlayer player, InteractionHand hand, PlantIndividualState expected, String key) {
        if (!CollectorSporeHarvestAccess.canInteract(player, hand)) return Result.REJECTED;
        var level = player.serverLevel();
        var leaves = PlantIndividuals.get(level).leavesOf(expected.id()).orElse(null);
        return leaves == null ? Result.REJECTED : collectSpore(level, expected, leaves, key,
                new CollectorSporeHarvestAccess(player, hand), level.random);
    }

    /**
     * 自定义库存适配器必须原子交换：false 表示库存完全未动，true 表示已交付恰好一袋。
     * 回调不能修改世界或植株；抛错后的库存状态未知，不自动退款，保留暂扣并暂停诊断。
     */
    public static Result collectSpore(ServerLevel level, PlantIndividualState expected, PlantLeafData leaves,
            String key, SporeHarvestAccess access, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(access);
        Objects.requireNonNull(random);
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || expected.pruned()
                || !PlantHarvestEligibility.canCollectSporeOrSeed(expected)
                || expected.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))
                || !store.find(expected.id()).filter(expected::equals).isPresent()
                || !store.leavesOf(expected.id()).filter(leaves::equals).isPresent()) return Result.REJECTED;
        var leaf = leaves.leaves().leaves().get(key);
        var definition = PlantSpeciesRegistry.find(expected.species()).orElse(null);
        if (leaf == null || !leaf.canCollect() || definition == null || !available(level, leaf.anchor())
                || !store.partRole(expected.id(), leaf.anchor()).filter(leaf.role()::equals).isPresent()) return Result.REJECTED;
        var before = level.getBlockState(leaf.anchor());
        if (before.isAir() || before.hasBlockEntity()) return Result.REJECTED;

        PlantLeafData reserved = null;
        Result result;
        store.beginStructure(expected);
        try {
            reserved = store.reserveLeafCollection(expected, leaves, key);
            if (!access.exchangeSporeBag(PlantSpeciesRegistry.legacyId(definition), leaf.anchor())) {
                store.restoreLeafCollection(expected, reserved, leaves);
                result = Result.REJECTED;
            } else {
                if (!available(level, leaf.anchor()) || !level.getBlockState(leaf.anchor()).equals(before))
                    throw new IllegalStateException("Leaf changed during inventory exchange");
                store.finishLeafCollection(expected, reserved, key, random);
                result = Result.COLLECTED;
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(PlantLeafCollection.class).error("Leaf spore collection failed for " + expected.id(), failure);
            result = reserved == null ? Result.REJECTED : Result.RECOVERY_REQUIRED;
        } finally {
            store.endStructure();
        }
        if (result == Result.RECOVERY_REQUIRED) {
            try {
                store.failLeafTimers(expected.id());
                store.find(expected.id()).ifPresent(current -> {
                    var reasons = new java.util.HashSet<>(current.environmentReasons());
                    reasons.add("leaf_collection_error");
                    store.replace(current, current.withEnvironmentReasons(reasons));
                });
            } catch (RuntimeException recoveryFailure) {
                org.slf4j.LoggerFactory.getLogger(PlantLeafCollection.class).error("Cannot freeze failed leaf collection", recoveryFailure);
            }
        }
        return result;
    }

    private static boolean available(ServerLevel level, BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().hasChunk(x, z)
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z));
    }
}
