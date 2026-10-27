package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 镐操作的纯结算；合法操作一次构建完整新快照，不推进回合或配子体进度。 */
public final class CooksoniaPickaxe {
    private CooksoniaPickaxe() {}

    public enum Status { APPLIED, UNKNOWN_HEAD, DEAD_HEAD, NO_PICKAXES, OUTSIDE_BOARD, OUT_OF_RANGE, OCCUPIED, NOT_COBBLESTONE, INVALID_WATER_LAYOUT }

    /** 非 APPLIED 时 state 就是输入的原快照，工具选择取消等鼠标表现由 UI 处理。 */
    public record Result(Status status, CooksoniaGameState state, List<CooksoniaOneShotWater.Plan> flows) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(state);
            flows = List.copyOf(flows);
            if (status != Status.APPLIED && !flows.isEmpty())
                throw new IllegalArgumentException("Rejected pickaxe action cannot produce water flows");
        }
        public Result(Status status, CooksoniaGameState state) { this(status, state, List.of()); }
        public boolean applied() { return status == Status.APPLIED; }
    }

    /**
     * target 是棋盘坐标，周围 3×3 包含对角格；不使用四邻或移动朝向限制。
     * 会话仍须核对当前快照身份、权限与操作冷却后，才提交本次返回值。
     */
    public static Result apply(CooksoniaGameState source, UUID headId, CooksoniaBoard.Pos target) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(headId);
        Objects.requireNonNull(target);
        var head = source.heads().get(headId);
        if (head == null) return new Result(Status.UNKNOWN_HEAD, source);
        if (!head.alive()) return new Result(Status.DEAD_HEAD, source);
        if (source.pickaxes() == 0) return new Result(Status.NO_PICKAXES, source);
        if (!source.board().contains(target)) return new Result(Status.OUTSIDE_BOARD, source);
        if (Math.abs(target.x() - head.pos().x()) > 1 || Math.abs(target.y() - head.pos().y()) > 1)
            return new Result(Status.OUT_OF_RANGE, source);
        if (source.occupancy().at(target).isPresent()) return new Result(Status.OCCUPIED, source);
        var board = source.board().breakCobblestone(target);
        if (board.isEmpty()) return new Result(Status.NOT_COBBLESTONE, source);

        var openedSources = new ArrayList<CooksoniaBoard.Pos>();
        for (var neighbor : source.board().cardinalNeighbors(target)) {
            if (source.board().at(neighbor).terrain() == CooksoniaBoard.Terrain.ONE_SHOT_WATER) openedSources.add(neighbor);
        }
        var flows = new ArrayList<CooksoniaOneShotWater.Plan>();
        var ground = board.get();
        var releasedSources = new HashSet<>(openedSources);
        var wet = new HashSet<CooksoniaBoard.Pos>();
        // 全部对同一破石地面独立规划，不把前一个水路的湿润／源点消失传给后一个。
        for (var waterSource : openedSources) {
            var water = CooksoniaOneShotWater.plan(ground, waterSource, target, releasedSources);
            if (water.plan().isEmpty()) return new Result(Status.INVALID_WATER_LAYOUT, source);
            var flow = water.plan().orElseThrow();
            flows.add(flow);
            wet.addAll(flow.wetCells());
        }
        if (!openedSources.isEmpty()) {
            ground = ground.finishOneShotWaters(releasedSources, wet);
        }

        var plant = source.plants().get(head.plantId());
        Map<UUID, CooksoniaHead> heads = source.heads();
        CooksoniaPlant nextPlant;
        if (plant.actions() > 0) {
            nextPlant = plant.spendAction().orElseThrow();
        } else {
            var injured = new CooksoniaHead(head.id(), head.plantId(), head.pos(), head.health() - 1, head.heading());
            heads = new HashMap<>(source.heads());
            heads.put(injured.id(), injured);
            nextPlant = plant;
            if (!injured.alive()) {
                boolean anotherLiveHead = false;
                for (var other : source.heads().values()) {
                    if (!other.id().equals(headId) && other.plantId().equals(plant.id()) && other.alive()) {
                        anotherLiveHead = true;
                        break;
                    }
                }
                if (!anotherLiveHead) nextPlant = plant.retire();
            }
        }
        var plants = new HashMap<>(source.plants());
        plants.put(nextPlant.id(), nextPlant);
        // 即使扣至零生命也先完成破石；零生命头暂保留，倒下动画及之后占位另行处理。
        var occupancy = nextPlant.retired() ? CooksoniaRetirement.withoutRetiredStems(source.occupancy(), plants) : source.occupancy();
        var next = new CooksoniaGameState(ground, occupancy, plants, heads, source.gametophytes(),
                source.spores(), source.waits(), source.pickaxes() - 1, source.score(), source.turn(), source.cleared(), source.producedSpores());
        return new Result(Status.APPLIED, next, flows);
    }
}
