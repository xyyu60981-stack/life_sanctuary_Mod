package com.example.life_sanctuary;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 玩家小游戏进度 V1；不可读内容原样保留，不能按空存档重新发奖励。 */
public final class CooksoniaSaveCodec {
    private CooksoniaSaveCodec() {}
    private static final int VERSION = 1;
    public sealed interface ReadResult permits Loaded, Preserved {}
    public record Loaded(CooksoniaProgress progress, CooksoniaRewards rewards) implements ReadResult {
        public Loaded {
            Objects.requireNonNull(progress);
            Objects.requireNonNull(rewards);
            for (var id : rewards.earnedEpochs().keySet()) {
                if (!progress.at(id).cleared()) throw new IllegalArgumentException("Cooksonia reward lacks a cleared level record");
            }
        }
    }
    public record Preserved(CompoundTag data, String reason) implements ReadResult {
        public Preserved { data = data.copy(); Objects.requireNonNull(reason); }
        @Override public CompoundTag data() { return data.copy(); }
    }

    public static ReadResult read(CompoundTag saved) {
        Objects.requireNonNull(saved);
        try {
            require(saved, "Version", Tag.TAG_INT);
            if (saved.getInt("Version") != VERSION) throw new IllegalArgumentException("Unsupported Cooksonia player save version");
            require(saved, "RewardEpoch", Tag.TAG_LONG);
            require(saved, "OnlineTicks", Tag.TAG_INT);
            var records = new HashMap<String, CooksoniaProgress.Record>();
            for (var tag : compounds(saved, "Levels")) {
                var entry = (CompoundTag) tag;
                require(entry, "Level", Tag.TAG_STRING);
                require(entry, "Cleared", Tag.TAG_BYTE);
                require(entry, "HighScore", Tag.TAG_LONG);
                int flag = entry.getByte("Cleared");
                if (flag != 0 && flag != 1) throw new IllegalArgumentException("Invalid Cooksonia clear flag");
                if (records.putIfAbsent(entry.getString("Level"), new CooksoniaProgress.Record(flag == 1, entry.getLong("HighScore"))) != null)
                    throw new IllegalArgumentException("Duplicate Cooksonia level record");
            }
            var earned = new HashMap<String, Long>();
            for (var tag : compounds(saved, "Earned")) {
                var entry = (CompoundTag) tag;
                require(entry, "Level", Tag.TAG_STRING);
                require(entry, "Epoch", Tag.TAG_LONG);
                if (earned.putIfAbsent(entry.getString("Level"), entry.getLong("Epoch")) != null)
                    throw new IllegalArgumentException("Duplicate Cooksonia earned reward");
            }
            var pending = new HashSet<CooksoniaRewards.Ticket>();
            for (var tag : compounds(saved, "Pending")) {
                var entry = (CompoundTag) tag;
                require(entry, "Level", Tag.TAG_STRING);
                require(entry, "Epoch", Tag.TAG_LONG);
                if (!pending.add(new CooksoniaRewards.Ticket(entry.getString("Level"), entry.getLong("Epoch"))))
                    throw new IllegalArgumentException("Duplicate Cooksonia pending reward");
            }
            var progress = new CooksoniaProgress(records);
            for (var id : earned.keySet()) {
                if (!progress.at(id).cleared()) throw new IllegalArgumentException("Cooksonia reward lacks a cleared level record");
            }
            return new Loaded(progress, new CooksoniaRewards(saved.getLong("RewardEpoch"), saved.getInt("OnlineTicks"), earned, pending));
        } catch (RuntimeException failure) {
            return new Preserved(saved, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public static CompoundTag write(ReadResult result) {
        Objects.requireNonNull(result);
        if (result instanceof Preserved preserved) return preserved.data();
        var loaded = (Loaded) result;
        var saved = new CompoundTag();
        saved.putInt("Version", VERSION);
        saved.putLong("RewardEpoch", loaded.rewards().epoch());
        saved.putInt("OnlineTicks", loaded.rewards().onlineTicks());
        var levels = new ListTag();
        loaded.progress().levels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(record -> {
            var entry = new CompoundTag();
            entry.putString("Level", record.getKey());
            entry.putBoolean("Cleared", record.getValue().cleared());
            entry.putLong("HighScore", record.getValue().highScore());
            levels.add(entry);
        });
        saved.put("Levels", levels);
        var earned = new ListTag();
        loaded.rewards().earnedEpochs().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(record -> {
            var entry = new CompoundTag();
            entry.putString("Level", record.getKey());
            entry.putLong("Epoch", record.getValue());
            earned.add(entry);
        });
        saved.put("Earned", earned);
        var pending = new ListTag();
        loaded.rewards().pending().stream().sorted(Comparator.comparing(CooksoniaRewards.Ticket::levelId)
                .thenComparingLong(CooksoniaRewards.Ticket::epoch)).forEach(ticket -> {
            var entry = new CompoundTag();
            entry.putString("Level", ticket.levelId());
            entry.putLong("Epoch", ticket.epoch());
            pending.add(entry);
        });
        saved.put("Pending", pending);
        return saved;
    }

    private static ListTag compounds(CompoundTag saved, String key) {
        require(saved, key, Tag.TAG_LIST);
        var entries = saved.getList(key, Tag.TAG_COMPOUND);
        if (entries.size() != ((ListTag) saved.get(key)).size())
            throw new IllegalArgumentException("Invalid Cooksonia compound list: " + key);
        return entries;
    }

    static void require(CompoundTag saved, String key, int type) {
        if (!saved.contains(key, type)) throw new IllegalArgumentException("Missing or invalid Cooksonia save field: " + key);
    }
}
