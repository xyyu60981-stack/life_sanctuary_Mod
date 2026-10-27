package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 可持久化的纯进度数据；不包含棋盘、会话身份、工具或未完成回合。 */
public record CooksoniaProgress(Map<String, Record> levels) {
    public record Record(boolean cleared, long highScore) {
        public Record {
            if (highScore < 0) throw new IllegalArgumentException("Negative Cooksonia high score");
        }
    }

    public CooksoniaProgress {
        levels = Map.copyOf(levels);
        for (var id : levels.keySet()) requireId(id);
    }

    public static CooksoniaProgress empty() { return new CooksoniaProgress(Map.of()); }
    public Record at(String id) {
        requireId(id);
        return levels.getOrDefault(id, new Record(false, 0L));
    }

    /** 只接受宿主从权威会话取出的结果，不是网络提交分数的接口。 */
    CooksoniaProgress observe(String id, long score, boolean cleared) {
        requireId(id);
        if (score < 0) throw new IllegalArgumentException("Negative Cooksonia score");
        var previous = at(id);
        var next = new Record(previous.cleared() || cleared, Math.max(previous.highScore(), score));
        if (next.equals(previous)) return this;
        var records = new HashMap<>(levels);
        records.put(id, next);
        return new CooksoniaProgress(records);
    }

    static void requireId(String id) {
        Objects.requireNonNull(id);
        if (id.isBlank() || !id.equals(id.strip())) throw new IllegalArgumentException("Invalid Cooksonia level key");
    }
}
