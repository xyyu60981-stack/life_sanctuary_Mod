package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 播种合法性不读取中间路径；隐藏目标按未知地面允许，不能通过提示探测隐藏干燥。 */
public final class CooksoniaSporePreflight {
    private CooksoniaSporePreflight() {}
    public enum Status { ALLOWED, UNKNOWN_HEAD, DEAD_HEAD, NO_SPORES, OUTSIDE_BOARD, OUT_OF_RANGE, OCCUPIED, FORBIDDEN_GROUND }

    public static Status inspect(CooksoniaGameState source, UUID headId, CooksoniaBoard.Pos target) {
        Objects.requireNonNull(source);
        var basic = basics(source.board().size(), source.heads(), source.spores(), headId, target);
        if (basic != Status.ALLOWED) return basic;
        if (source.occupancy().at(target).isPresent()) return Status.OCCUPIED;
        var cell = source.board().at(target);
        return ground(cell.revealed() ? Optional.of(cell.terrain()) : Optional.empty());
    }

    /** 未来客户端高亮只使用可见 Content，不要求取得完整权威棋盘。 */
    public static Status inspect(CooksoniaView.Content view, UUID headId, CooksoniaBoard.Pos target) {
        Objects.requireNonNull(view);
        var basic = basics(view.size(), view.heads(), view.spores(), headId, target);
        if (basic != Status.ALLOWED) return basic;
        if (view.occupants().containsKey(target)) return Status.OCCUPIED;
        return ground(view.at(target).ground().map(CooksoniaView.KnownGround::terrain));
    }

    private static Status basics(int size, Map<UUID, CooksoniaHead> heads, int spores, UUID headId, CooksoniaBoard.Pos target) {
        Objects.requireNonNull(headId);
        Objects.requireNonNull(target);
        var head = heads.get(headId);
        if (head == null) return Status.UNKNOWN_HEAD;
        if (!head.alive()) return Status.DEAD_HEAD;
        if (spores == 0) return Status.NO_SPORES;
        if (target.x() < 0 || target.y() < 0 || target.x() >= size || target.y() >= size) return Status.OUTSIDE_BOARD;
        if (Math.abs(target.x() - head.pos().x()) > 2 || Math.abs(target.y() - head.pos().y()) > 2) return Status.OUT_OF_RANGE;
        return Status.ALLOWED;
    }

    private static Status ground(Optional<CooksoniaBoard.Terrain> known) {
        if (known.isEmpty()) return Status.ALLOWED;
        return switch (known.get()) {
            case NORMAL, COLD, PEAT -> Status.ALLOWED;
            default -> Status.FORBIDDEN_GROUND;
        };
    }
}
