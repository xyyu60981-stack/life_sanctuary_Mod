package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 合法播种一次生成完整新快照，不消耗某株行动／分叉，也不推进回合。 */
public final class CooksoniaSporeSowing {
    private CooksoniaSporeSowing() {}
    public enum Status { SOWN, ILLEGAL_TARGET, ID_IN_USE }

    public record Result(Status status, CooksoniaGameState state, Optional<CooksoniaSporePreflight.Status> rejection) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(state);
            Objects.requireNonNull(rejection);
            if ((status == Status.ILLEGAL_TARGET) != rejection.isPresent()
                    || rejection.filter(value -> value == CooksoniaSporePreflight.Status.ALLOWED).isPresent())
                throw new IllegalArgumentException("Invalid Cooksonia sowing result");
        }
        public boolean sown() { return status == Status.SOWN; }
    }

    /** unitId／futurePlantId 由服务器提供，不能让客户端指定或复用其他单位身份。 */
    public static Result sow(CooksoniaGameState source, UUID headId, CooksoniaBoard.Pos target,
            UUID unitId, UUID futurePlantId) {
        Objects.requireNonNull(unitId);
        Objects.requireNonNull(futurePlantId);
        var check = CooksoniaSporePreflight.inspect(source, headId, target);
        if (check != CooksoniaSporePreflight.Status.ALLOWED)
            return new Result(Status.ILLEGAL_TARGET, source, Optional.of(check));
        if (unitId.equals(futurePlantId) || source.plants().containsKey(unitId) || source.plants().containsKey(futurePlantId)
                || source.occupancy().positionOf(unitId).isPresent() || source.occupancy().positionOf(futurePlantId).isPresent())
            return new Result(Status.ID_IN_USE, source, Optional.empty());
        for (var existing : source.gametophytes().values()) {
            if (existing.futurePlantId().equals(unitId) || existing.futurePlantId().equals(futurePlantId))
                return new Result(Status.ID_IN_USE, source, Optional.empty());
        }
        var terrain = source.board().at(target).terrain();
        boolean peat = terrain == CooksoniaBoard.Terrain.PEAT;
        var kind = terrain == CooksoniaBoard.Terrain.COLD ? CooksoniaPlant.Kind.TUNDRA : CooksoniaPlant.Kind.COOKSONIA;
        var gametophyte = new CooksoniaGametophyte(unitId, futurePlantId, target, kind, source.turn(), 0, 0, peat);
        var occupancy = source.occupancy().place(target,
                new CooksoniaOccupancy.Entry(unitId, futurePlantId, CooksoniaOccupancy.Kind.GAMETOPHYTE)).orElseThrow();
        var gametophytes = new HashMap<>(source.gametophytes());
        gametophytes.put(unitId, gametophyte);
        long score = Math.addExact(source.score(), peat ? 500L : 0L);
        // 泥炭分数立即领取；行动／分叉加成归新植株，保存在配子体上直到萌发。
        var next = new CooksoniaGameState(source.board().revealLanding(target), occupancy, source.plants(), source.heads(),
                gametophytes, source.spores() - 1, source.waits(), source.pickaxes(), score, source.turn(), source.cleared(), source.producedSpores());
        return new Result(Status.SOWN, next, Optional.empty());
    }
}
