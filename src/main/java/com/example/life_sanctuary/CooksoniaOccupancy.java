package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 独立于实际地形的不可变占位层；按坐标和单位 ID 建索引，查询不扫描整个棋盘。 */
public final class CooksoniaOccupancy {
    public enum Kind { HEAD, STEM, GAMETOPHYTE }
    public record Entry(UUID id, UUID plantId, Kind kind, Optional<CooksoniaHead.Heading> stemHeading) {
        /** 无方向的旧走茎可参与成熟，但不能推测其分叉侧向。 */
        public Entry(UUID id, UUID plantId, Kind kind) {
            this(id, plantId, kind, Optional.empty());
        }
        public Entry {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(plantId);
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(stemHeading);
            if (kind != Kind.STEM && stemHeading.isPresent())
                throw new IllegalArgumentException("Only stems carry a travel heading");
        }
    }

    /** 明确的旧占位与可选新占位；空 replacement 表示删除，不使用 null 约定。 */
    public record Change(Entry expected, Optional<Entry> replacement) {
        public Change {
            java.util.Objects.requireNonNull(expected);
            java.util.Objects.requireNonNull(replacement);
        }
    }
    private final int size;
    private final Map<CooksoniaBoard.Pos, Entry> entries;
    private final Map<UUID, CooksoniaBoard.Pos> positions;
    private final Set<UUID> stemPlants;

    public CooksoniaOccupancy(int size, Map<CooksoniaBoard.Pos, Entry> entries) {
        if (size <= 0) throw new IllegalArgumentException("Positive occupancy board size required");
        Math.multiplyExact(size, size);
        this.size = size;
        this.entries = Map.copyOf(entries);
        var index = new HashMap<UUID, CooksoniaBoard.Pos>();
        var stemOwners = new HashSet<UUID>();
        this.entries.forEach((pos, entry) -> {
            if (!contains(pos) || index.putIfAbsent(entry.id(), pos) != null)
                throw new IllegalArgumentException("Invalid or duplicated board occupant");
            if (entry.kind() == Kind.STEM) stemOwners.add(entry.plantId());
        });
        positions = Map.copyOf(index);
        stemPlants = Set.copyOf(stemOwners);
    }

    public int size() { return size; }
    public Map<CooksoniaBoard.Pos, Entry> entries() { return entries; }
    public Optional<Entry> at(CooksoniaBoard.Pos pos) { return Optional.ofNullable(entries.get(pos)); }
    public Optional<CooksoniaBoard.Pos> positionOf(UUID id) { return Optional.ofNullable(positions.get(id)); }
    public Set<UUID> stemPlants() { return stemPlants; }
    private boolean contains(CooksoniaBoard.Pos pos) {
        return pos.x() >= 0 && pos.y() >= 0 && pos.x() < size && pos.y() < size;
    }

    /** 只登记明确给出的单位，不能覆盖已有头、走茎或配子体。 */
    public Optional<CooksoniaOccupancy> place(CooksoniaBoard.Pos pos, Entry entry) {
        java.util.Objects.requireNonNull(entry);
        if (!contains(pos) || entries.containsKey(pos) || positions.containsKey(entry.id())) return Optional.empty();
        var next = new HashMap<>(entries);
        next.put(pos, entry);
        return Optional.of(new CooksoniaOccupancy(size, next));
    }

    /**
     * 萌发／枯死等成批替换：全部旧值匹配且最终 ID 不冲突才返回新层。
     * 批量成功只复制一次，不在每个配子体转换时重复复制整个占位表。
     */
    public Optional<CooksoniaOccupancy> changeAll(Map<CooksoniaBoard.Pos, Change> changes) {
        var patch = Map.copyOf(changes);
        if (patch.isEmpty()) return Optional.of(this);
        var ids = new HashSet<>(positions.keySet());
        for (var entry : patch.entrySet()) {
            if (!entry.getValue().expected().equals(entries.get(entry.getKey()))) return Optional.empty();
            ids.remove(entry.getValue().expected().id());
        }
        for (var change : patch.values()) {
            if (change.replacement().isPresent() && !ids.add(change.replacement().get().id())) return Optional.empty();
        }
        var next = new HashMap<>(entries);
        for (var entry : patch.entrySet()) {
            if (entry.getValue().replacement().isPresent()) next.put(entry.getKey(), entry.getValue().replacement().get());
            else next.remove(entry.getKey());
        }
        return Optional.of(new CooksoniaOccupancy(size, next));
    }

    /** 四邻移动与原格留下同株走茎一起提出；资源／朝向／地形检查不由占位层代办。 */
    public Optional<CooksoniaOccupancy> moveHead(CooksoniaBoard.Pos from, CooksoniaBoard.Pos to, Entry expected, UUID stemId) {
        java.util.Objects.requireNonNull(expected);
        java.util.Objects.requireNonNull(stemId);
        if (!contains(from) || !contains(to) || expected.kind() != Kind.HEAD || !expected.equals(entries.get(from))
                || entries.containsKey(to) || positions.containsKey(stemId)
                || Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) != 1) return Optional.empty();
        var next = new HashMap<>(entries);
        var heading = to.x() > from.x() ? CooksoniaHead.Heading.RIGHT
                : to.x() < from.x() ? CooksoniaHead.Heading.LEFT
                : to.y() > from.y() ? CooksoniaHead.Heading.DOWN : CooksoniaHead.Heading.UP;
        next.put(from, new Entry(stemId, expected.plantId(), Kind.STEM, Optional.of(heading)));
        next.put(to, expected);
        return Optional.of(new CooksoniaOccupancy(size, next));
    }
}
