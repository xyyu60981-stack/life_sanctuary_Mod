package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** 死体种子账本；不再占用活体个体或种群名额，不令未成熟种子在死亡后继续成熟。 */
public final class PlantSeedSalvage {
    private PlantSeedSalvage() {}
    public record Key(UUID sourcePlant, String flowerKey) {
        public Key {
            Objects.requireNonNull(sourcePlant);
            if (flowerKey == null || flowerKey.isBlank()) throw new IllegalArgumentException("Invalid salvaged seed key");
        }
    }
    public record Allocation(ResourceLocation species, BlockPos position, int seeds) {
        public Allocation {
            Objects.requireNonNull(species); position = position.immutable();
            if (seeds <= 0) throw new IllegalArgumentException("No mature seeds to salvage");
        }
    }
    public record Entry(Key key, ResourceLocation species, BlockPos position, int seeds, ResourceLocation corpseBlock) {
        public Entry {
            Objects.requireNonNull(key); Objects.requireNonNull(species); Objects.requireNonNull(corpseBlock);
            position = position.immutable();
            if (seeds <= 0 || corpseBlock.getNamespace().equals("minecraft")
                    && Set.of("air", "cave_air", "void_air", "water", "lava", "bubble_column").contains(corpseBlock.getPath()))
                throw new IllegalArgumentException("Salvaged seeds need a remaining corpse block");
        }
    }

    /** 优先留在原种子位置，否则附在基底或仍存在的最低死体部件；没有死体时不制造悬空采集点。 */
    public static Map<Key, Allocation> plan(PlantIndividualState owner, Map<String, PlantFlowerState> remainingFlowers,
            Set<BlockPos> corpsePositions) {
        var solid = Set.copyOf(corpsePositions);
        if (solid.isEmpty()) return Map.of();
        var fallback = solid.contains(owner.base()) ? owner.base() : solid.stream().min(java.util.Comparator
                .<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).orElseThrow();
        var rescued = new HashMap<Key, Allocation>();
        remainingFlowers.forEach((key, flower) -> {
            if (flower.phase() == PlantFlowerState.Phase.SEEDS_READY)
                rescued.put(new Key(owner.id(), key), new Allocation(owner.species(), solid.contains(flower.position()) ? flower.position() : fallback, flower.seeds()));
        });
        return Map.copyOf(rescued);
    }

    static CompoundTag write(Entry entry) {
        var saved = new CompoundTag();
        saved.putUUID("SourcePlant", entry.key().sourcePlant());
        saved.putString("Flower", entry.key().flowerKey());
        saved.putString("Species", entry.species().toString());
        saved.putLong("Position", entry.position().asLong());
        saved.putInt("Seeds", entry.seeds());
        saved.putString("CorpseBlock", entry.corpseBlock().toString());
        return saved;
    }
    static Entry read(CompoundTag saved) {
        if (!saved.hasUUID("SourcePlant") || !saved.contains("Flower", Tag.TAG_STRING) || !saved.contains("Species", Tag.TAG_STRING)
                || !saved.contains("Position", Tag.TAG_LONG) || !saved.contains("Seeds", Tag.TAG_INT) || !saved.contains("CorpseBlock", Tag.TAG_STRING))
            throw new IllegalArgumentException("Incomplete salvaged seed record");
        var species = ResourceLocation.tryParse(saved.getString("Species"));
        var corpse = ResourceLocation.tryParse(saved.getString("CorpseBlock"));
        if (species == null || corpse == null) throw new IllegalArgumentException("Invalid salvaged seed identifiers");
        return new Entry(new Key(saved.getUUID("SourcePlant"), saved.getString("Flower")), species,
                BlockPos.of(saved.getLong("Position")), saved.getInt("Seeds"), corpse);
    }
}
