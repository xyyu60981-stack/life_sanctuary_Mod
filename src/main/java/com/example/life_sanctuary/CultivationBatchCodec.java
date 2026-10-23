package com.example.life_sanctuary;

import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** 单槽批次 V1 编解码；不访问配方注册表、不创建任务、不补算离线进度。 */
public final class CultivationBatchCodec {
    private CultivationBatchCodec() {}
    private static final int VERSION = 1;
    public sealed interface ReadResult permits Loaded, Preserved {}
    public record Loaded(CultivationBatch batch) implements ReadResult {
        public Loaded { java.util.Objects.requireNonNull(batch); }
    }

    /** 不可执行；保存时原样写回，不能把它当作空槽重新接收输入。 */
    public record Preserved(CompoundTag data, String reason) implements ReadResult {
        public Preserved {
            data = data.copy();
            java.util.Objects.requireNonNull(reason);
        }
        @Override public CompoundTag data() { return data.copy(); }
    }

    public static ReadResult read(CompoundTag saved) {
        java.util.Objects.requireNonNull(saved);
        try {
            require(saved, "Version", Tag.TAG_INT);
            if (saved.getInt("Version") != VERSION) throw new IllegalArgumentException("Unsupported cultivation batch version");
            if (!saved.hasUUID("Id")) throw new IllegalArgumentException("Missing cultivation batch identity");
            require(saved, "PhaseIndex", Tag.TAG_INT);
            require(saved, "Recipe", Tag.TAG_COMPOUND);
            require(saved, "Progress", Tag.TAG_COMPOUND);
            var recipeTag = saved.getCompound("Recipe");
            require(recipeTag, "Id", Tag.TAG_STRING);
            require(recipeTag, "Species", Tag.TAG_STRING);
            require(recipeTag, "Phases", Tag.TAG_LIST);
            var phaseTags = recipeTag.getList("Phases", Tag.TAG_COMPOUND);
            if (phaseTags.size() != ((ListTag) recipeTag.get("Phases")).size())
                throw new IllegalArgumentException("Invalid cultivation phase list");
            var phases = new ArrayList<CultivationRecipe.Phase>();
            for (int i = 0; i < phaseTags.size(); i++) {
                var phase = phaseTags.getCompound(i);
                require(phase, "Key", Tag.TAG_STRING);
                require(phase, "Output", Tag.TAG_STRING);
                require(phase, "MinimumNaturalTicks", Tag.TAG_INT);
                require(phase, "MaximumNaturalTicks", Tag.TAG_INT);
                phases.add(new CultivationRecipe.Phase(phase.getString("Key"), ResourceLocation.parse(phase.getString("Output")),
                        phase.getInt("MinimumNaturalTicks"), phase.getInt("MaximumNaturalTicks")));
            }
            var recipe = new CultivationRecipe(ResourceLocation.parse(recipeTag.getString("Id")),
                    ResourceLocation.parse(recipeTag.getString("Species")), phases);
            var progressTag = saved.getCompound("Progress");
            require(progressTag, "Stage", Tag.TAG_STRING);
            require(progressTag, "TargetTicks", Tag.TAG_LONG);
            require(progressTag, "ElapsedTicks", Tag.TAG_LONG);
            require(progressTag, "Failed", Tag.TAG_BYTE);
            byte failed = progressTag.getByte("Failed");
            if (failed != 0 && failed != 1) throw new IllegalArgumentException("Invalid cultivation failure flag");
            var progress = new CultivationProgress(progressTag.getString("Stage"), progressTag.getLong("TargetTicks"),
                    progressTag.getLong("ElapsedTicks"), failed == 1);
            return new Loaded(new CultivationBatch(saved.getUUID("Id"), recipe, saved.getInt("PhaseIndex"), progress));
        } catch (RuntimeException failure) {
            return new Preserved(saved, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public static CompoundTag write(CultivationBatch batch) {
        var saved = new CompoundTag();
        saved.putInt("Version", VERSION);
        saved.putUUID("Id", batch.id());
        saved.putInt("PhaseIndex", batch.phaseIndex());
        var recipe = new CompoundTag();
        recipe.putString("Id", batch.recipe().id().toString());
        recipe.putString("Species", batch.recipe().species().toString());
        var phases = new ListTag();
        for (var phase : batch.recipe().phases()) {
            var entry = new CompoundTag();
            entry.putString("Key", phase.key());
            entry.putString("Output", phase.output().toString());
            entry.putInt("MinimumNaturalTicks", phase.minimumNaturalTicks());
            entry.putInt("MaximumNaturalTicks", phase.maximumNaturalTicks());
            phases.add(entry);
        }
        recipe.put("Phases", phases);
        saved.put("Recipe", recipe);
        var progress = new CompoundTag();
        progress.putString("Stage", batch.progress().stage());
        progress.putLong("TargetTicks", batch.progress().targetTicks());
        progress.putLong("ElapsedTicks", batch.progress().elapsedTicks());
        progress.putBoolean("Failed", batch.progress().failed());
        saved.put("Progress", progress);
        return saved;
    }

    public static CompoundTag write(ReadResult result) {
        java.util.Objects.requireNonNull(result);
        return result instanceof Preserved preserved ? preserved.data() : write(((Loaded) result).batch());
    }

    private static void require(CompoundTag saved, String key, int type) {
        if (!saved.contains(key, type)) throw new IllegalArgumentException("Missing or invalid cultivation field: " + key);
    }
}
