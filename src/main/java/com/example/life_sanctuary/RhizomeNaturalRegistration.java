package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 供整组世界事务使用的包内元数据登记会话。锁住两个存储后，调用者才可写入世界。
 * 本类不写方块、不消耗生成凭据、不负责世界回滚，不能独立替代完整放置器。
 */
final class RhizomeNaturalRegistration {
    private RhizomeNaturalRegistration() {}

    /** 必须传入刚完成预检的结果；打开时仍重检，以免复用跨 tick 的过期快照。 */
    static Session open(ServerLevel level, RhizomeNaturalPreflight.Checked checked) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var current = RhizomeNaturalPreflight.check(level, checked.plan());
        if (!current.ready() || !current.checked().orElseThrow().equals(checked))
            throw new IllegalStateException("Natural preflight expired");
        return new Session(level, checked);
    }

    static final class Session implements AutoCloseable {
        private final ServerLevel level;
        private final RhizomeNaturalPreflight.Checked checked;
        private final PlantIndividuals individuals;
        private final PlantRhizomeSystems systems;
        private boolean closed;
        private boolean committed;

        private Session(ServerLevel level, RhizomeNaturalPreflight.Checked checked) {
            this.level = level;
            this.checked = checked;
            individuals = PlantIndividuals.get(level);
            systems = PlantRhizomeSystems.get(level);
            individuals.beginNaturalStructure(checked.plan());
            try {
                systems.beginNaturalStructure(checked.plan());
            } catch (RuntimeException exception) {
                individuals.endNaturalStructure();
                throw exception;
            }
        }

        /**
         * 全部实际方块与依赖符合目标后才登记。两个存储更新间不调用世界或物种方法。
         * 返回 false 表示方块/区块已改变，调用者须按自己的世界事务回滚。
         */
        boolean commit() {
            requireOpen();
            if (committed) throw new IllegalStateException("Natural batch already committed");
            if (!placedStatesMatch()) return false;
            registerMetadata();
            return true;
        }

        /** 世界无法完整回滚时保留整组拟占位并冻结，不能当作正常生成成功。 */
        void retainForRecovery() {
            requireOpen();
            if (!committed) registerMetadata();
            individuals.markNaturalRecovery(checked.plan());
            checked.plan().population().systems().keySet().forEach(systems::requireRecovery);
        }

        private void registerMetadata() {
            try {
                individuals.commitNaturalStructure(checked.plan());
                systems.commitNaturalStructure(checked.plan());
                committed = true;
            } catch (RuntimeException exception) {
                // 此处没有世界回调；只撤回本会话新登记的数据，不碰既有植株。
                systems.rollbackNaturalStructure(checked.plan());
                individuals.rollbackNaturalStructure(checked.plan());
                throw exception;
            }
        }

        private boolean placedStatesMatch() {
            Map<BlockPos, BlockState> targets = new HashMap<>(checked.expectedStates());
            checked.plan().plants().values().forEach(plan -> plan.changes().forEach(
                    (pos, change) -> targets.put(pos, change.replacement())));
            checked.plan().underground().forEach((pos, change) -> targets.put(pos, change.replacement()));
            checked.decorationCleanup().keySet().forEach(pos -> targets.put(pos, Blocks.AIR.defaultBlockState()));
            var chunks = new java.util.HashSet<Long>();
            // 全量可用性检查先于读取；头部清理和未修改依赖同样不能遗漏。
            for (var pos : targets.keySet()) {
                if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
                int x = pos.getX() >> 4, z = pos.getZ() >> 4;
                long chunk = ChunkPos.asLong(x, z);
                if (chunks.add(chunk) && (!level.getChunkSource().hasChunk(x, z)
                        || !level.getChunkSource().isPositionTicking(chunk))) return false;
            }
            for (var entry : targets.entrySet())
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) return false;
            return true;
        }

        private void requireOpen() {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
            if (closed) throw new IllegalStateException("Natural batch session closed");
        }

        /** 未提交时仅解锁；世界写入若已开始，调用者必须在关闭之前完成世界回滚。 */
        @Override public void close() {
            requireOpen();
            systems.endNaturalStructure();
            individuals.endNaturalStructure();
            closed = true;
        }
    }
}
