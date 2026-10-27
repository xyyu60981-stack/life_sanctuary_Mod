package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 正式关卡的明确顺序与稳定进度键；不创建默认关卡，也不按文件遍历顺序推断编号。 */
public final class CooksoniaLevels {
    public record Entry(String id, CooksoniaLevel level) {
        public Entry {
            CooksoniaProgress.requireId(id);
            Objects.requireNonNull(level);
        }
    }

    private final List<Entry> entries;
    private final Map<String, Integer> indices;
    private final CooksoniaToolRules tools;

    public CooksoniaLevels(List<Entry> entries) {
        this(entries, CooksoniaToolRules.none());
    }

    public CooksoniaLevels(List<Entry> entries, CooksoniaToolRules tools) {
        this.entries = List.copyOf(entries);
        var positions = new HashMap<String, Integer>();
        for (int index = 0; index < this.entries.size(); index++) {
            if (positions.putIfAbsent(this.entries.get(index).id(), index) != null)
                throw new IllegalArgumentException("Duplicate Cooksonia level key");
        }
        this.indices = Map.copyOf(positions);
        this.tools = Objects.requireNonNull(tools);
        tools.validate(this);
    }

    public List<Entry> entries() { return entries; }
    public CooksoniaToolRules tools() { return tools; }
    public Optional<Entry> find(String id) {
        CooksoniaProgress.requireId(id);
        var index = indices.get(id);
        return index == null ? Optional.empty() : Optional.of(entries.get(index));
    }

    /** 空目录没有可玩关卡；未知存档键不参与本目录解锁。 */
    public boolean unlocked(CooksoniaProgress progress, String id) {
        Objects.requireNonNull(progress);
        CooksoniaProgress.requireId(id);
        var index = indices.get(id);
        if (index == null) return false;
        for (int previous = 0; previous < index; previous++) {
            if (!progress.at(entries.get(previous).id()).cleared()) return false;
        }
        return true;
    }
}
