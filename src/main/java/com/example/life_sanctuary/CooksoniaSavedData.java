package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** 全服按玩家 UUID 保存；固定放主世界，跨维度不生成另一份奖励周期。 */
public final class CooksoniaSavedData extends SavedData {
    private static final Factory<CooksoniaSavedData> FACTORY = new Factory<>(CooksoniaSavedData::new, CooksoniaSavedData::load);
    private final Thread owningThread = Thread.currentThread();
    private final Map<UUID, CooksoniaSaveCodec.ReadResult> players = new HashMap<>();
    private CompoundTag preserved;

    public static CooksoniaSavedData get(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) throw new IllegalStateException("Cooksonia storage accessed outside the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_cooksonia_players");
    }

    public boolean isReadOnly() { checkThread(); return preserved != null; }

    /** 只有真正缺失的玩家记录才返回 empty；坏档／未来版本有 Preserved，不能当新玩家。 */
    public Optional<CooksoniaSaveCodec.ReadResult> read(UUID playerId) {
        checkThread();
        if (preserved != null) throw new IllegalStateException("Cooksonia root save is preserved and read-only");
        return Optional.ofNullable(players.get(Objects.requireNonNull(playerId)));
    }

    /** 对旧记录作对象身份比较，拒绝旧宿主覆盖新宿主已提交的奖励账本。 */
    public boolean update(UUID playerId, Optional<CooksoniaSaveCodec.ReadResult> expected, CooksoniaSaveCodec.Loaded next) {
        checkThread();
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(next);
        var current = players.get(playerId);
        if (preserved != null || current != expected.orElse(null) || current instanceof CooksoniaSaveCodec.Preserved) return false;
        if (current instanceof CooksoniaSaveCodec.Loaded loaded && loaded.progress() == next.progress() && loaded.rewards() == next.rewards())
            return true;
        // Loaded 已校验跨模型关系；只保存不可变对象，不在每次提交时编码／解码 NBT。
        players.put(playerId, next);
        setDirty();
        return true;
    }

    public static CooksoniaSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        Objects.requireNonNull(tag);
        var data = new CooksoniaSavedData();
        try {
            CooksoniaSaveCodec.require(tag, "Version", Tag.TAG_INT);
            if (tag.getInt("Version") != 1) throw new IllegalArgumentException("Unsupported Cooksonia root save version");
            CooksoniaSaveCodec.require(tag, "Players", Tag.TAG_LIST);
            var entries = tag.getList("Players", Tag.TAG_COMPOUND);
            if (entries.size() != ((ListTag) tag.get("Players")).size()) throw new IllegalArgumentException("Invalid Cooksonia players list");
            for (var entryTag : entries) {
                var entry = (CompoundTag) entryTag;
                if (!entry.hasUUID("Player")) throw new IllegalArgumentException("Missing Cooksonia player UUID");
                CooksoniaSaveCodec.require(entry, "Data", Tag.TAG_COMPOUND);
                if (data.players.putIfAbsent(entry.getUUID("Player"), CooksoniaSaveCodec.read(entry.getCompound("Data"))) != null)
                    throw new IllegalArgumentException("Duplicate Cooksonia player UUID");
            }
        } catch (RuntimeException failure) {
            data.players.clear();
            data.preserved = tag.copy();
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        checkThread();
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1);
        var entries = new ListTag();
        players.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(player -> {
            var entry = new CompoundTag();
            entry.putUUID("Player", player.getKey());
            entry.put("Data", CooksoniaSaveCodec.write(player.getValue()));
            entries.add(entry);
        });
        tag.put("Players", entries);
        return tag;
    }

    private void checkThread() {
        if (Thread.currentThread() != owningThread) throw new IllegalStateException("Cooksonia storage accessed outside its owning thread");
    }
}
