package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 移动的资源／落点阶段；返回中间快照，不是完成了水路和回合推进的玩家动作。 */
public final class CooksoniaMoveLanding {
    private CooksoniaMoveLanding() {}

    public enum Status { PREPARED, ILLEGAL_MOVE, STEM_ID_IN_USE }

    /** 拒绝时 state 为原快照；只有 ILLEGAL_MOVE 提供具体预检原因。 */
    public record Result(Status status, CooksoniaGameState state, Optional<CooksoniaMovePreflight.Status> rejection) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(state);
            Objects.requireNonNull(rejection);
            if ((status == Status.ILLEGAL_MOVE) != rejection.isPresent()
                    || rejection.filter(value -> value == CooksoniaMovePreflight.Status.GROUND_MOVE).isPresent())
                throw new IllegalArgumentException("Invalid Cooksonia landing result");
        }
        public boolean prepared() { return status == Status.PREPARED; }
    }

    /**
     * stemId 由服务器为原格的新走茎提供，不接受客户端指定身份。
     * 必须继续处理此前结构的回合推进和结果判定后才可提交；会话 move 负责组合。
     */
    public static Result prepare(CooksoniaGameState source, UUID headId, CooksoniaHead.Heading direction, UUID stemId) {
        Objects.requireNonNull(stemId);
        var check = CooksoniaMovePreflight.inspect(source, headId, direction);
        if (check.cost().isEmpty()) return new Result(Status.ILLEGAL_MOVE, source, Optional.of(check.status()));
        var cost = check.cost().orElseThrow();
        var head = source.heads().get(headId);
        var entry = new CooksoniaOccupancy.Entry(head.id(), head.plantId(), CooksoniaOccupancy.Kind.HEAD);
        var occupancy = source.occupancy().moveHead(head.pos(), cost.target(), entry, stemId);
        if (occupancy.isEmpty()) return new Result(Status.STEM_ID_IN_USE, source, Optional.empty());

        var moved = head.moved(direction, cost.remainingHealth()).orElseThrow();
        var plant = source.plants().get(head.plantId());
        var nextPlant = cost.actionsSpent() == 1 ? plant.spendAction().orElseThrow() : plant;
        if (cost.consumesPeat()) nextPlant = nextPlant.peatBonus().orElseThrow();
        if (!moved.alive()) {
            boolean anotherLiveHead = false;
            for (var other : source.heads().values()) {
                if (!other.id().equals(headId) && other.plantId().equals(plant.id()) && other.alive()) {
                    anotherLiveHead = true;
                    break;
                }
            }
            // 致死移动仍消耗泥炭并记分，但不能靠泥炭给无活头的植株重新分叉。
            if (!anotherLiveHead) nextPlant = nextPlant.retire();
        }
        long score = Math.addExact(source.score(), cost.peatScore());
        var heads = new HashMap<>(source.heads());
        heads.put(moved.id(), moved);
        var plants = new HashMap<>(source.plants());
        plants.put(nextPlant.id(), nextPlant);
        var occupied = nextPlant.retired() ? CooksoniaRetirement.withoutRetiredStems(occupancy.get(), plants) : occupancy.get();
        var next = new CooksoniaGameState(source.board().revealLanding(cost.target()), occupied, plants, heads,
                source.gametophytes(), source.spores(), source.waits(), source.pickaxes(), score, source.turn(), source.cleared(), source.producedSpores());
        return new Result(Status.PREPARED, next, Optional.empty());
    }
}
