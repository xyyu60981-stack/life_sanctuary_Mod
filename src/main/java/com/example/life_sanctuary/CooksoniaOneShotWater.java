package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 破石后单个一次水的有界流路；不访问单位层，水可穿过所有植物而不改动它们。 */
public final class CooksoniaOneShotWater {
    public static final int MAX_PATH_CELLS = 5;
    private CooksoniaOneShotWater() {}

    public enum Status { READY, INVALID_SOURCE, INVALID_OUTLET, INVALID_SOURCE_LAYOUT }
    public enum Stop { LENGTH_LIMIT, BLOCKED, RIVER }

    public record Result(Status status, Optional<Plan> plan) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(plan);
            if ((status == Status.READY) != plan.isPresent())
                throw new IllegalArgumentException("Water planning status and result disagree");
        }
    }

    /** 原地面身份、路径及结果一起保留；未来动画展示路径，不能让客户端回传结算结果。 */
    public static final class Plan {
        private final CooksoniaBoard source;
        private final CooksoniaBoard.Pos water;
        private final List<CooksoniaBoard.Pos> path;
        private final Set<CooksoniaBoard.Pos> wetCells;
        private final Stop stop;

        private Plan(CooksoniaBoard source, List<CooksoniaBoard.Pos> path, Set<CooksoniaBoard.Pos> wetCells,
                Stop stop, CooksoniaBoard.Pos water) {
            this.source = source;
            this.path = List.copyOf(path);
            this.wetCells = Set.copyOf(wetCells);
            this.stop = stop;
            this.water = water;
        }

        public boolean matches(CooksoniaBoard current) { return source == current; }
        /** 只供服务器构建播放前视图，不得将整个 Plan 发给客户端。 */
        public CooksoniaBoard sourceBoard() { return source; }
        /** 单水源便捷结算；多源调用方应合并 wetCells 后使用 Board.finishOneShotWaters。 */
        public CooksoniaBoard board() { return source.finishOneShotWater(water, wetCells); }
        public List<CooksoniaBoard.Pos> path() { return path; }
        public Set<CooksoniaBoard.Pos> wetCells() { return wetCells; }
        public Stop stop() { return stop; }
    }

    /**
     * afterBreaking 是破石地面提案，openedCell 是刚被破坏的圆石位置；费用与结果须一起提交。
     * 只处理与 source 四邻相接的普通出口，不将对角破石当作开口。
     */
    public static Result plan(CooksoniaBoard afterBreaking, CooksoniaBoard.Pos source, CooksoniaBoard.Pos openedCell) {
        return plan(afterBreaking, source, openedCell, Set.of(source));
    }

    /** 同次释放的水源不互相触发或阻挡，全部路径必须对同一个 afterBreaking 规划。 */
    public static Result plan(CooksoniaBoard afterBreaking, CooksoniaBoard.Pos source, CooksoniaBoard.Pos openedCell,
            Set<CooksoniaBoard.Pos> releasedSources) {
        Objects.requireNonNull(afterBreaking);
        Objects.requireNonNull(source);
        Objects.requireNonNull(openedCell);
        var released = Set.copyOf(releasedSources);
        if (!released.contains(source)) return withoutPlan(Status.INVALID_SOURCE);
        for (var pos : released) {
            if (!afterBreaking.contains(pos) || afterBreaking.at(pos).terrain() != CooksoniaBoard.Terrain.ONE_SHOT_WATER)
                return withoutPlan(Status.INVALID_SOURCE);
        }
        if (!afterBreaking.contains(source) || afterBreaking.at(source).terrain() != CooksoniaBoard.Terrain.ONE_SHOT_WATER)
            return withoutPlan(Status.INVALID_SOURCE);
        if (!afterBreaking.contains(openedCell) || !afterBreaking.cardinalNeighbors(source).contains(openedCell)
                || afterBreaking.at(openedCell).terrain() != CooksoniaBoard.Terrain.NORMAL)
            return withoutPlan(Status.INVALID_OUTLET);

        var direction = initialDirection(source, openedCell);
        var path = new ArrayList<CooksoniaBoard.Pos>(MAX_PATH_CELLS);
        var visited = new HashSet<CooksoniaBoard.Pos>();
        path.add(source);
        path.add(openedCell);
        visited.addAll(path);
        var current = openedCell;
        Stop stop = Stop.LENGTH_LIMIT;
        while (path.size() < MAX_PATH_CELLS) {
            boolean advanced = false;
            // 前方畅通就直行；受阻才右转，再尝试左转。不后退、不反复循环计格。
            var choices = List.of(direction, direction.right(), direction.left());
            for (var candidate : choices) {
                var next = candidate.step(current);
                if (!afterBreaking.contains(next) || visited.contains(next)
                        || afterBreaking.at(next).terrain() == CooksoniaBoard.Terrain.COBBLESTONE) continue;
                var terrain = afterBreaking.at(next).terrain();
                if (terrain == CooksoniaBoard.Terrain.RIVER) {
                    stop = Stop.RIVER;
                    return ready(afterBreaking, source, path, stop);
                }
                // 本次已触发的源点即将消失，可通过；未触发水源按设定应被圆石封闭。
                if (terrain == CooksoniaBoard.Terrain.ONE_SHOT_WATER && !released.contains(next))
                    return withoutPlan(Status.INVALID_SOURCE_LAYOUT);
                current = next;
                direction = candidate;
                path.add(next);
                visited.add(next);
                advanced = true;
                break;
            }
            if (!advanced) {
                stop = Stop.BLOCKED;
                break;
            }
        }
        return ready(afterBreaking, source, path, stop);
    }

    private static Result ready(CooksoniaBoard board, CooksoniaBoard.Pos source, List<CooksoniaBoard.Pos> path, Stop stop) {
        var wet = new HashSet<CooksoniaBoard.Pos>();
        for (var pos : path) {
            wet.add(pos);
            wet.addAll(board.cardinalNeighbors(pos));
        }
        return new Result(Status.READY, Optional.of(new Plan(board, path, wet, stop, source)));
    }

    private static CooksoniaHead.Heading initialDirection(CooksoniaBoard.Pos from, CooksoniaBoard.Pos to) {
        if (to.x() > from.x()) return CooksoniaHead.Heading.RIGHT;
        if (to.x() < from.x()) return CooksoniaHead.Heading.LEFT;
        return to.y() > from.y() ? CooksoniaHead.Heading.DOWN : CooksoniaHead.Heading.UP;
    }

    private static Result withoutPlan(Status status) { return new Result(status, Optional.empty()); }
}
