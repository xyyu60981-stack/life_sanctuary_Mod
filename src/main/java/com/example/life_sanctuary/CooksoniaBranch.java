package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 分叉的完整纯提案；只扣本株分叉，不扣行动，也不推进任何成长计时。 */
public final class CooksoniaBranch {
    private CooksoniaBranch() {}
    public enum Status { BRANCHED, ILLEGAL_TARGET, ID_IN_USE }
    public record Result(Status status, CooksoniaGameState state, Optional<CooksoniaBranchPreflight.Status> rejection) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(state);
            Objects.requireNonNull(rejection);
            if ((status == Status.ILLEGAL_TARGET) != rejection.isPresent()
                    || rejection.filter(value -> value == CooksoniaBranchPreflight.Status.ALLOWED).isPresent())
                throw new IllegalArgumentException("Invalid Cooksonia branch result");
        }
        public boolean branched() { return status == Status.BRANCHED; }
    }

    public static Result apply(CooksoniaGameState source, UUID stemId, CooksoniaHead.Heading direction, UUID headId) {
        Objects.requireNonNull(headId);
        var check = CooksoniaBranchPreflight.inspect(source, stemId, direction);
        if (check != CooksoniaBranchPreflight.Status.ALLOWED)
            return new Result(Status.ILLEGAL_TARGET, source, Optional.of(check));
        if (source.occupancy().positionOf(headId).isPresent())
            return new Result(Status.ID_IN_USE, source, Optional.empty());
        var stemPos = source.occupancy().positionOf(stemId).orElseThrow();
        var stem = source.occupancy().at(stemPos).orElseThrow();
        var target = direction.step(stemPos);
        var terrain = source.board().at(target).terrain();
        var plant = source.plants().get(stem.plantId()).spendBranch().orElseThrow();
        var head = CooksoniaHead.branched(headId, plant.id(), target, direction);
        boolean harmful = terrain == CooksoniaBoard.Terrain.DRY
                || terrain == CooksoniaBoard.Terrain.COLD && plant.kind() != CooksoniaPlant.Kind.TUNDRA;
        if (harmful) head = new CooksoniaHead(head.id(), head.plantId(), head.pos(), 0, head.heading());
        boolean peat = terrain == CooksoniaBoard.Terrain.PEAT;
        if (peat) plant = plant.peatBonus().orElseThrow();
        var occupied = source.occupancy().place(target,
                new CooksoniaOccupancy.Entry(headId, plant.id(), CooksoniaOccupancy.Kind.HEAD)).orElseThrow();
        var plants = new HashMap<>(source.plants());
        plants.put(plant.id(), plant);
        var heads = new HashMap<>(source.heads());
        heads.put(headId, head);
        // 原植株必有活头，因此新头落地死亡不注销原株或移除走茎。
        var next = new CooksoniaGameState(source.board().revealLanding(target), occupied, plants, heads,
                source.gametophytes(), source.spores(), source.waits(), source.pickaxes(),
                Math.addExact(source.score(), peat ? 500L : 0L), source.turn(), source.cleared(), source.producedSpores());
        return new Result(Status.BRANCHED, next, Optional.empty());
    }
}
