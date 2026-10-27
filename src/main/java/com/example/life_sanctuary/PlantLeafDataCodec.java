package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 严格 NBT 编解码；缺字段、错误类型和重复键一律拒绝，不猜测额度或计时。 */
final class PlantLeafDataCodec {
    private PlantLeafDataCodec() {}

    static PlantLeafData read(CompoundTag saved, int version) {
        require(saved, "LeafLimit", Tag.TAG_INT);
        require(saved, "FertileLimit", Tag.TAG_INT);
        Map<String, PlantLeafState> leaves = new HashMap<>();
        var entries = compounds(saved, "Leaves");
        for (int i = 0; i < entries.size(); i++) {
            var leaf = entries.getCompound(i);
            require(leaf, "Key", Tag.TAG_STRING);
            require(leaf, "Anchor", Tag.TAG_LONG);
            require(leaf, "Role", Tag.TAG_STRING);
            require(leaf, "RemainingCollections", Tag.TAG_INT);
            var state = new PlantLeafState(BlockPos.of(leaf.getLong("Anchor")), leaf.getString("Role"),
                    flag(leaf, "Present"), flag(leaf, "Fertile"), leaf.getInt("RemainingCollections"));
            if (leaves.putIfAbsent(leaf.getString("Key"), state) != null) throw new IllegalArgumentException("Duplicate leaf key");
        }
        Optional<PlantLeafTimer> regrowth = Optional.empty();
        if (flag(saved, "HasRegrowth")) {
            require(saved, "Regrowth", Tag.TAG_COMPOUND);
            regrowth = Optional.of(readTimer(saved.getCompound("Regrowth"), PlantLeafTimer.Kind.REGROWTH));
        } else if (saved.contains("Regrowth")) throw new IllegalArgumentException("Unexpected leaf regrowth timer");
        Map<String, PlantLeafTimer> shedding = new HashMap<>();
        var timers = compounds(saved, "Shedding");
        for (int i = 0; i < timers.size(); i++) {
            var timer = timers.getCompound(i);
            require(timer, "Key", Tag.TAG_STRING);
            require(timer, "Timer", Tag.TAG_COMPOUND);
            if (shedding.putIfAbsent(timer.getString("Key"), readTimer(timer.getCompound("Timer"), PlantLeafTimer.Kind.SHEDDING)) != null)
                throw new IllegalArgumentException("Duplicate leaf shedding timer");
        }
        var allowedRoles = new java.util.HashSet<String>();
        if (version >= 12) {
            var roles = compounds(saved, "AllowedRoles");
            for (int i = 0; i < roles.size(); i++) {
                var role = roles.getCompound(i);
                require(role, "Role", Tag.TAG_STRING);
                if (!allowedRoles.add(role.getString("Role"))) throw new IllegalArgumentException("Duplicate allowed leaf role");
            }
        } else if (saved.contains("AllowedRoles")) throw new IllegalArgumentException("Unexpected legacy allowed roles");
        var crowns = new HashMap<String, PlantLeafSet.Crown>();
        var crownTimers = new HashMap<String, PlantLeafTimer>();
        if (version >= 13) {
            for (var value : compounds(saved, "Crowns")) {
                var entry = (CompoundTag) value;
                require(entry, "Key", Tag.TAG_STRING);
                require(entry, "Center", Tag.TAG_LONG);
                var roles = new java.util.HashSet<String>();
                for (var roleTag : compounds(entry, "Roles")) {
                    var role = (CompoundTag) roleTag;
                    require(role, "Role", Tag.TAG_STRING);
                    if (!roles.add(role.getString("Role"))) throw new IllegalArgumentException("Duplicate crown role");
                }
                if (crowns.putIfAbsent(entry.getString("Key"), new PlantLeafSet.Crown(BlockPos.of(entry.getLong("Center")), roles)) != null)
                    throw new IllegalArgumentException("Duplicate crown key");
            }
            for (var value : compounds(saved, "CrownRegrowth")) {
                var entry = (CompoundTag) value;
                require(entry, "Key", Tag.TAG_STRING);
                require(entry, "Timer", Tag.TAG_COMPOUND);
                if (crownTimers.putIfAbsent(entry.getString("Key"), readTimer(entry.getCompound("Timer"), PlantLeafTimer.Kind.REGROWTH)) != null)
                    throw new IllegalArgumentException("Duplicate crown timer");
            }
        } else if (saved.contains("Crowns") || saved.contains("CrownRegrowth")) throw new IllegalArgumentException("Unexpected legacy crown metadata");
        return new PlantLeafData(new PlantLeafSet(saved.getInt("LeafLimit"), saved.getInt("FertileLimit"), leaves, allowedRoles, crowns),
                new PlantLeafTimers(regrowth, shedding, crownTimers));
    }

    static CompoundTag write(PlantLeafData data) {
        var saved = new CompoundTag();
        saved.putInt("LeafLimit", data.leaves().leafLimit());
        saved.putInt("FertileLimit", data.leaves().fertileLimit());
        var roles = new ListTag();
        data.leaves().allowedRoles().stream().sorted().forEach(role -> {
            var value = new CompoundTag();
            value.putString("Role", role);
            roles.add(value);
        });
        saved.put("AllowedRoles", roles);
        var leaves = new ListTag();
        data.leaves().leaves().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var leaf = entry.getValue();
            var value = new CompoundTag();
            value.putString("Key", entry.getKey());
            value.putLong("Anchor", leaf.anchor().asLong());
            value.putString("Role", leaf.role());
            value.putBoolean("Present", leaf.present());
            value.putBoolean("Fertile", leaf.fertile());
            value.putInt("RemainingCollections", leaf.remainingCollections());
            leaves.add(value);
        });
        saved.put("Leaves", leaves);
        saved.putBoolean("HasRegrowth", data.timers().regrowth().isPresent());
        data.timers().regrowth().ifPresent(timer -> saved.put("Regrowth", writeTimer(timer)));
        var shedding = new ListTag();
        data.timers().shedding().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var value = new CompoundTag();
            value.putString("Key", entry.getKey());
            value.put("Timer", writeTimer(entry.getValue()));
            shedding.add(value);
        });
        saved.put("Shedding", shedding);
        var crowns = new ListTag();
        data.leaves().crowns().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var value = new CompoundTag();
            value.putString("Key", entry.getKey());
            value.putLong("Center", entry.getValue().center().asLong());
            var crownRoles = new ListTag();
            entry.getValue().roles().stream().sorted().forEach(role -> {
                var item = new CompoundTag(); item.putString("Role", role); crownRoles.add(item);
            });
            value.put("Roles", crownRoles); crowns.add(value);
        });
        saved.put("Crowns", crowns);
        var crownTimers = new ListTag();
        data.timers().crownRegrowth().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var value = new CompoundTag(); value.putString("Key", entry.getKey());
            value.put("Timer", writeTimer(entry.getValue())); crownTimers.add(value);
        });
        saved.put("CrownRegrowth", crownTimers);
        return saved;
    }

    private static PlantLeafTimer readTimer(CompoundTag saved, PlantLeafTimer.Kind kind) {
        require(saved, "TargetTicks", Tag.TAG_LONG);
        require(saved, "ElapsedTicks", Tag.TAG_LONG);
        return new PlantLeafTimer(kind, saved.getLong("TargetTicks"), saved.getLong("ElapsedTicks"), flag(saved, "Failed"));
    }
    private static CompoundTag writeTimer(PlantLeafTimer timer) {
        var saved = new CompoundTag();
        saved.putLong("TargetTicks", timer.targetTicks());
        saved.putLong("ElapsedTicks", timer.elapsedTicks());
        saved.putBoolean("Failed", timer.failed());
        return saved;
    }
    private static ListTag compounds(CompoundTag saved, String key) {
        require(saved, key, Tag.TAG_LIST);
        var entries = saved.getList(key, Tag.TAG_COMPOUND);
        if (entries.size() != ((ListTag) saved.get(key)).size()) throw new IllegalArgumentException("Invalid leaf list " + key);
        return entries;
    }
    private static void require(CompoundTag saved, String key, int type) {
        if (!saved.contains(key, type)) throw new IllegalArgumentException("Missing or invalid leaf field " + key);
    }
    private static boolean flag(CompoundTag saved, String key) {
        require(saved, key, Tag.TAG_BYTE);
        byte value = saved.getByte(key);
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid leaf boolean " + key);
        return value == 1;
    }
}
