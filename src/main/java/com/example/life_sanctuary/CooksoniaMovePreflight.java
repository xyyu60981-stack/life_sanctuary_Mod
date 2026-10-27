package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 只读移动预检，供按钮提示和未来统一结算共用；成功预检不等于已经执行动作。
 * 不扣资源、不揭示隐藏格、不推进回合，也不访问随机源或 Minecraft 世界。
 */
public final class CooksoniaMovePreflight {
    private CooksoniaMovePreflight() {}

    public enum Status {
        GROUND_MOVE, UNKNOWN_HEAD, DEAD_HEAD, REVERSE_DIRECTION, OUTSIDE_BOARD,
        OCCUPIED, COBBLESTONE, RIVER, NEEDS_WATER_RESOLUTION
    }

    /**
     * 绑定预检时的整局对象，后续执行必须重新预检或核对对象身份，不能套用旧报价。
     * 两类伤害分开记录；生命不足时截到零，仍允许完成落点效果。
     */
    public record Cost(CooksoniaGameState source, UUID headId, CooksoniaHead.Heading direction,
            CooksoniaBoard.Pos target, int actionsSpent, int exhaustionDamage, int terrainDamage,
            int remainingHealth, boolean consumesPeat) {
        public Cost {
            Objects.requireNonNull(source);
            Objects.requireNonNull(headId);
            Objects.requireNonNull(direction);
            Objects.requireNonNull(target);
            if (actionsSpent < 0 || actionsSpent > 1 || exhaustionDamage != 1 - actionsSpent
                    || terrainDamage < 0 || terrainDamage > 1 || remainingHealth < 0)
                throw new IllegalArgumentException("Invalid Cooksonia move cost");
        }

        public boolean matches(CooksoniaGameState current) { return source == current; }
        public long peatScore() { return consumesPeat ? 500L : 0L; }
    }

    public record Result(Status status, Optional<Cost> cost) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(cost);
            if ((status == Status.GROUND_MOVE) != cost.isPresent())
                throw new IllegalArgumentException("Move status and cost disagree");
        }
    }

    public static Result inspect(CooksoniaGameState state, UUID headId, CooksoniaHead.Heading direction) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(headId);
        Objects.requireNonNull(direction);
        var head = state.heads().get(headId);
        if (head == null) return withoutCost(Status.UNKNOWN_HEAD);
        if (!head.alive()) return withoutCost(Status.DEAD_HEAD);
        if (!head.mayMove(direction)) return withoutCost(Status.REVERSE_DIRECTION);
        var target = direction.step(head.pos());
        if (!state.board().contains(target)) return withoutCost(Status.OUTSIDE_BOARD);
        if (state.occupancy().at(target).isPresent()) return withoutCost(Status.OCCUPIED);
        var terrain = state.board().at(target).terrain();
        if (terrain == CooksoniaBoard.Terrain.COBBLESTONE) return withoutCost(Status.COBBLESTONE);
        if (terrain == CooksoniaBoard.Terrain.RIVER) return withoutCost(Status.RIVER);
        // 一次水要与后续水路机制共同结算；暂不擅自规定可进入或永久阻挡。
        if (terrain == CooksoniaBoard.Terrain.ONE_SHOT_WATER) return withoutCost(Status.NEEDS_WATER_RESOLUTION);

        var plant = state.plants().get(head.plantId());
        int actionsSpent = plant.actions() > 0 ? 1 : 0;
        int exhaustionDamage = 1 - actionsSpent;
        boolean extreme = terrain == CooksoniaBoard.Terrain.DRY
                || terrain == CooksoniaBoard.Terrain.COLD && plant.kind() != CooksoniaPlant.Kind.TUNDRA;
        int terrainDamage = extreme ? 1 : 0;
        int remainingHealth = Math.max(0, head.health() - exhaustionDamage - terrainDamage);
        // 使用实际地形计算，隐藏寒冷／干燥也不能绕过伤害；预检本身不改变可见性。
        return new Result(Status.GROUND_MOVE, Optional.of(new Cost(state, headId, direction, target,
                actionsSpent, exhaustionDamage, terrainDamage, remainingHealth,
                terrain == CooksoniaBoard.Terrain.PEAT)));
    }

    private static Result withoutCost(Status status) { return new Result(status, Optional.empty()); }
}
