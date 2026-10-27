package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** 阶段与整株结构一次提交；不能先改阶段、再逐格生成树干或叶片。 */
public final class PlantStageGrowth {
    private PlantStageGrowth() {}

    /**
     * 目标形状由物种一次选择并提供；空间不足保留原阶段、成熟计时和全部额度。
     * 生态与完整形状合法性须由物种检查，相关世界读取加入 observed 供提交时复核。
     * 此入口不用于自然静止植株；自然多方块生成继续走一次性生成接口。
     */
    public static PlantStructureCommitter.Result advance(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure target, Map<BlockPos, BlockState> observed, RandomSource random) {
        return advance(level, expected, target, observed, PlantStageResources.NONE, random);
    }

    /** 新结构首次额度与阶段、世界结构一起写入；旧接口默认不新增任何额度。 */
    public static PlantStructureCommitter.Result advance(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure target, Map<BlockPos, BlockState> observed,
            PlantStageResources resources, RandomSource random) {
        return advanceInternal(level, expected, target, observed, resources, null, random);
    }

    /** 首次叶冠与下一阶段一次提交；已有叶账本必须使用保持资源的普通阶段入口。 */
    public static PlantStructureCommitter.Result advanceWithInitialCrown(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure target, Map<BlockPos, BlockState> observed, PlantStageResources resources,
            PlantInitialCrown crown, RandomSource random) {
        return advanceInternal(level, expected, target, observed, resources, Objects.requireNonNull(crown), random);
    }

    /** 完整候选叶冠逐方向筛选：至多跳过两个方向，三个及以上受阻保持原阶段。 */
    public static PlantStructureCommitter.Result advanceWithDirectionalInitialCrown(ServerLevel level,
            PlantIndividualState expected, PlantStageStructure fullTarget, Map<BlockPos, BlockState> observed,
            PlantStageResources resources, PlantInitialCrown fullCrown, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (PlantIndividuals.get(level).isWaitingForCuttingBud(expected.id()) || !HeterosporeTransitions.stageReady(level, expected)) return rejected();
        var selection = PlantCrownSpace.select(level, expected, fullTarget, fullCrown, observed);
        if (selection.selection().isEmpty()) return rejected();
        var selected = selection.selection().get();
        return advanceWithInitialCrown(level, expected, selected.structure(), observed, resources, selected.crown(), random);
    }

    private static PlantStructureCommitter.Result advanceInternal(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure target, Map<BlockPos, BlockState> observed, PlantStageResources resources,
            PlantInitialCrown crown, RandomSource random) {
        return advanceInternal(level, expected, target, observed, resources, crown, random, false);
    }

    /** 已有营养叶冠首次进入成年：重查全部方向，保留旧历史但只为成年新叶首次分配次数。 */
    public static PlantStructureCommitter.Result advanceToAdultCrown(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure fullTarget, Map<BlockPos, BlockState> observed, PlantStageResources resources,
            PlantInitialCrown fullCrown, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (PlantIndividuals.get(level).isWaitingForCuttingBud(expected.id()) || !HeterosporeTransitions.stageReady(level, expected)) return rejected();
        var selection = PlantCrownSpace.selectAdult(level, expected, fullTarget, fullCrown, observed);
        if (selection.selection().isEmpty()) return rejected();
        var selected = selection.selection().get();
        return advanceInternal(level, expected, selected.structure(), observed, resources, selected.crown(), random, true);
    }

    /** 幼苗／亚成体之间的换冠，不为营养叶分配可采孢子或种子额度。 */
    public static PlantStructureCommitter.Result advanceWithJuvenileCrown(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure fullTarget, Map<BlockPos, BlockState> observed, PlantStageResources resources,
            PlantInitialCrown fullCrown, RandomSource random) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (PlantIndividuals.get(level).isWaitingForCuttingBud(expected.id()) || !HeterosporeTransitions.stageReady(level, expected)
                || PlantHarvestEligibility.isFullyAdult(expected.species(), fullTarget.stage())) return rejected();
        var selection = PlantCrownSpace.selectGrowing(level, expected, fullTarget, fullCrown, observed);
        if (selection.selection().isEmpty()) return rejected();
        var selected = selection.selection().get();
        return advanceInternal(level, expected, selected.structure(), observed, resources, selected.crown(), random, true);
    }

    private static PlantStructureCommitter.Result advanceInternal(ServerLevel level, PlantIndividualState expected,
            PlantStageStructure target, Map<BlockPos, BlockState> observed, PlantStageResources resources,
            PlantInitialCrown crown, RandomSource random, boolean replaceCrown) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(random);
        Objects.requireNonNull(resources);
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly() || store.isApplyingStructure() || PlantIndividuals.get(level).isWaitingForCuttingBud(expected.id()) || !HeterosporeTransitions.stageReady(level, expected)
                || !expected.equals(store.find(expected.id()).orElse(null))) return rejected();
        var definition = PlantSpeciesRegistry.find(expected.species()).orElse(null);
        if (definition == null) return rejected();
        var current = definition.stages().get(expected.stage());
        if (current == null || current.transition().isEmpty()
                || !current.transition().get().nextStage().equals(target.stage())
                || target.stage().equals(expected.stage())) return rejected();

        if (target.parts().values().stream().anyMatch(part -> !CycadSeedAppearance.matchesStage(part.state(), expected.species(), target.stage())))
            return rejected();
        var owned = store.partsOf(expected.id());
        var leaves = store.leavesOf(expected.id());
        var plan = target.plan(expected, owned, observed);
        // 在抽取计时或开始写世界前，拒绝重复键、旧角色和不在目标结构中的额度。
        var initialized = resources.initialize(expected, plan, owned);
        if (crown != null && !PlantHarvestEligibility.permitsInitialLeaves(crown.leaves(), expected.species(), target.stage()))
            return rejected();
        if (replaceCrown) {
            if (leaves.isEmpty() || !PlantGrowingCrownChange.eligible(expected, leaves.get())
                    || !PlantGrowingCrownChange.permitsTarget(expected.species(), target.stage(), crown.leaves())
                    || !crown.valid(initialized, PlantGrowingCrownChange.nonLeafParts(leaves.get(), owned), plan)) return rejected();
            // 先合并校验新旧键与额度，不能写世界后才发现资源身份重复。
            var targetRoles = new java.util.HashMap<BlockPos, String>();
            target.parts().forEach((pos, part) -> targetRoles.put(pos, part.role()));
            if (!PlantGrowingCrownChange.coversOldLeafShapes(leaves.get(), crown.leaves(), targetRoles)) return rejected();
            PlantGrowingCrownChange.merge(leaves.get(), crown.leaves()).validateOwner(initialized, targetRoles);
        } else {
            if (crown != null && (leaves.isPresent() || !crown.valid(initialized, owned, plan))) return rejected();
            if (leaves.isPresent() && !PlantLeafStageGuard.preserves(leaves.get(), owned, plan, initialized)) return rejected();
        }
        var preflight = PlantStructurePreflight.check(level, store, plan);
        if (!preflight.ready())
            return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.of(preflight));
        if (!PlantGrowthSpace.check(expected, target.stage(), owned.keySet(), observed).ready()) return rejected();
        // 先通过完整空间预检再抽下一阶段计时；常规阻挡重试不会提前刷新计时。
        long duration = 0;
        var nextTransition = definition.stages().get(target.stage()).transition();
        if (nextTransition.isPresent()) {
            var timing = nextTransition.get();
            duration = timing.minimumTicks() + random.nextInt(timing.maximumTicks() - timing.minimumTicks() + 1);
        }
        if (!owned.equals(store.partsOf(expected.id())) || !leaves.equals(store.leavesOf(expected.id()))) return rejected();
        return PlantStructureCommitter.applyStageGrowth(level, store, plan,
                expected.beginStage(target.stage(), duration), resources, crown, random);
    }

    private static PlantStructureCommitter.Result rejected() {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.empty());
    }
}
