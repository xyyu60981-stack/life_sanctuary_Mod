package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension one-shot candidate ledger. Records failures as well as successes; no retry queue. */
public final class NaturalPlantGenerationAttempts extends SavedData {
    private static final Factory<NaturalPlantGenerationAttempts> FACTORY =
            new Factory<>(NaturalPlantGenerationAttempts::new, NaturalPlantGenerationAttempts::load);
    private final Map<ResourceLocation, Set<Long>> attempts = new HashMap<>();
    private CompoundTag preserved;
    public static NaturalPlantGenerationAttempts get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_natural_plant_attempts");
    }
    public boolean isReadOnly() { return preserved != null; }

    /** Server-thread only. Rule ID must be stable, never random or derived from the selected variant. */
    public boolean claim(ResourceLocation rule, BlockPos candidate) {
        java.util.Objects.requireNonNull(rule);
        java.util.Objects.requireNonNull(candidate);
        if (isReadOnly()) return false;
        if (!attempts.computeIfAbsent(rule, ignored -> new HashSet<>()).add(candidate.asLong())) return false;
        setDirty();
        return true;
    }

    public boolean contains(ResourceLocation rule, BlockPos candidate) {
        Set<Long> positions = attempts.get(rule);
        return positions != null && positions.contains(candidate.asLong());
    }

    private static NaturalPlantGenerationAttempts load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new NaturalPlantGenerationAttempts();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                    || !tag.contains("Rules", Tag.TAG_LIST)) throw new IllegalArgumentException("Unsupported ledger");
            ListTag rules = tag.getList("Rules", Tag.TAG_COMPOUND);
            if (rules.size() != ((ListTag) tag.get("Rules")).size()) throw new IllegalArgumentException("Invalid rules");
            for (int i = 0; i < rules.size(); i++) {
                CompoundTag entry = rules.getCompound(i);
                if (!entry.contains("Rule", Tag.TAG_STRING) || !entry.contains("Positions", Tag.TAG_LONG_ARRAY))
                    throw new IllegalArgumentException("Incomplete rule");
                ResourceLocation id = ResourceLocation.parse(entry.getString("Rule"));
                Set<Long> positions = new HashSet<>();
                for (long pos : entry.getLongArray("Positions")) positions.add(pos);
                if (data.attempts.putIfAbsent(id, positions) != null)
                    throw new IllegalArgumentException("Duplicate rule");
            }
        } catch (IllegalArgumentException exception) {
            data.attempts.clear();
            data.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(NaturalPlantGenerationAttempts.class)
                    .error("Natural generation ledger is read-only; original data preserved", exception);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (isReadOnly()) return preserved.copy();
        tag.putInt("Version", 1);
        ListTag rules = new ListTag();
        attempts.forEach((id, positions) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Rule", id.toString());
            entry.putLongArray("Positions", positions.stream().mapToLong(Long::longValue).sorted().toArray());
            rules.add(entry);
        });
        tag.put("Rules", rules);
        return tag;
    }
}
