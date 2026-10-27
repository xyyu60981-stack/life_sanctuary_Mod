package com.example.life_sanctuary;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

/** A cutting's temporary crown cycle; rooted lower stem remains the original owned stem. */
public record PlantCuttingBud(String stage, String role, BlockPos position, long targetTicks,
        long elapsedTicks, boolean present, boolean failed) {
    public static final int MINIMUM_TICKS = 12000;
    public static final int MAXIMUM_TICKS = 24000;

    public PlantCuttingBud {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(role);
        position = position.immutable();
        if (stage.isBlank() || role.isBlank() || targetTicks < MINIMUM_TICKS || targetTicks > MAXIMUM_TICKS
                || elapsedTicks < 0 || elapsedTicks > targetTicks || present && (failed || elapsedTicks != targetTicks))
            throw new IllegalArgumentException("Invalid cutting bud cycle");
    }

    public static PlantCuttingBud waiting(String stage, String role, BlockPos position, RandomSource random) {
        return new PlantCuttingBud(stage, role, position,
                MINIMUM_TICKS + random.nextInt(MAXIMUM_TICKS - MINIMUM_TICKS + 1), 0, false, false);
    }

    public boolean ready() { return !present && !failed && elapsedTicks == targetTicks; }
    public PlantCuttingBud elapsed(long elapsed) {
        return new PlantCuttingBud(stage, role, position, targetTicks, elapsed, present, failed);
    }
    public PlantCuttingBud grown() {
        if (!ready()) throw new IllegalStateException("Cutting bud is not due");
        return new PlantCuttingBud(stage, role, position, targetTicks, targetTicks, true, false);
    }
    public PlantCuttingBud fail() {
        if (present) throw new IllegalStateException("Existing bud has no growth callback");
        return new PlantCuttingBud(stage, role, position, targetTicks, elapsedTicks, false, true);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("Stage", stage); tag.putString("Role", role); tag.putLong("Position", position.asLong());
        tag.putLong("TargetTicks", targetTicks); tag.putLong("ElapsedTicks", elapsedTicks);
        tag.putBoolean("Present", present); tag.putBoolean("Failed", failed);
        return tag;
    }
    public static PlantCuttingBud read(CompoundTag tag) {
        for (String key : new String[]{"Stage", "Role"})
            if (!tag.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing cutting " + key);
        for (String key : new String[]{"Position", "TargetTicks", "ElapsedTicks"})
            if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("Missing cutting " + key);
        for (String key : new String[]{"Present", "Failed"})
            if (!tag.contains(key, Tag.TAG_BYTE) || tag.getByte(key) < 0 || tag.getByte(key) > 1)
                throw new IllegalArgumentException("Invalid cutting " + key);
        return new PlantCuttingBud(tag.getString("Stage"), tag.getString("Role"), BlockPos.of(tag.getLong("Position")),
                tag.getLong("TargetTicks"), tag.getLong("ElapsedTicks"), tag.getBoolean("Present"), tag.getBoolean("Failed"));
    }
}
