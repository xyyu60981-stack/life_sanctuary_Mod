package com.example.life_sanctuary;

import java.util.Optional;
import java.util.UUID;

/** 头独立保存生命和朝向，只通过 plantId 关联同株资源；生命零的倒下动画由后续会话处理。 */
public record CooksoniaHead(UUID id, UUID plantId, CooksoniaBoard.Pos pos, int health, Optional<Heading> heading) {
    public enum Heading {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);
        private final int dx;
        private final int dy;
        Heading(int dx, int dy) { this.dx = dx; this.dy = dy; }
        public Heading opposite() {
            return switch (this) { case UP -> DOWN; case RIGHT -> LEFT; case DOWN -> UP; case LEFT -> RIGHT; };
        }
        public Heading right() {
            return switch (this) { case UP -> RIGHT; case RIGHT -> DOWN; case DOWN -> LEFT; case LEFT -> UP; };
        }
        public Heading left() {
            return switch (this) { case UP -> LEFT; case LEFT -> DOWN; case DOWN -> RIGHT; case RIGHT -> UP; };
        }
        public CooksoniaBoard.Pos step(CooksoniaBoard.Pos pos) {
            return new CooksoniaBoard.Pos(Math.addExact(pos.x(), dx), Math.addExact(pos.y(), dy));
        }
    }

    public CooksoniaHead {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(plantId);
        java.util.Objects.requireNonNull(pos);
        java.util.Objects.requireNonNull(heading);
        if (health < 0) throw new IllegalArgumentException("Negative Cooksonia head health");
    }

    /** 孢子新生头没有朝向；生命由正式关卡参数提供，不设置默认值。 */
    public static CooksoniaHead seeded(UUID id, UUID plantId, CooksoniaBoard.Pos pos, int configuredHealth) {
        if (configuredHealth <= 0) throw new IllegalArgumentException("Positive initial head health required");
        return new CooksoniaHead(id, plantId, pos, configuredHealth, Optional.empty());
    }

    /** 分叉新头固定一生命，不平分原头生命；这里只构建头，不扣分叉或占地。 */
    public static CooksoniaHead branched(UUID id, UUID plantId, CooksoniaBoard.Pos pos, Heading direction) {
        return new CooksoniaHead(id, plantId, pos, 1, Optional.of(direction));
    }

    public boolean alive() { return health > 0; }
    public boolean mayMove(Heading direction) {
        java.util.Objects.requireNonNull(direction);
        return alive() && heading.map(current -> direction != current.opposite()).orElse(true);
    }

    /** 受伤至零仍完成合法移动；目标边界／地形／占位与资源代价由统一操作预检。 */
    public Optional<CooksoniaHead> moved(Heading direction, int nextHealth) {
        if (nextHealth < 0 || nextHealth > health || !mayMove(direction)) return Optional.empty();
        return Optional.of(new CooksoniaHead(id, plantId, direction.step(pos), nextHealth, Optional.of(direction)));
    }
}
