package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;

/** Authoritative real structure starts, with world-wide stable numbering per shelter category. */
public final class ShelterLocations extends SavedData {
    public static final TagKey<Structure> SHELTERS = TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "shelters"));
    public static final TagKey<Structure> SKYLIGHT = TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "skylight_shelters"));
    private static final Factory<ShelterLocations> FACTORY = new Factory<>(ShelterLocations::new, ShelterLocations::load);
    private record Key(ResourceLocation structure, ResourceLocation dimension, long startChunk) {}
    public record Target(ResourceLocation structure, GlobalPos location, long startChunk, long number, boolean skylight) {
        public Target { java.util.Objects.requireNonNull(structure); java.util.Objects.requireNonNull(location); if (number < 1) throw new IllegalArgumentException("Invalid shelter number"); }
        private Key key() { return new Key(structure, location.dimension().location(), startChunk); }
        public CompoundTag save() {
            var tag = new CompoundTag(); tag.putString("Structure", structure.toString());
            tag.put("Location", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, location).getOrThrow());
            tag.putLong("StartChunk", startChunk); tag.putLong("Number", number); tag.putBoolean("Skylight", skylight); return tag;
        }
        public static Target read(CompoundTag tag) {
            if (!tag.contains("Structure", Tag.TAG_STRING) || !tag.contains("Location", Tag.TAG_COMPOUND)
                    || !tag.contains("StartChunk", Tag.TAG_LONG) || !tag.contains("Number", Tag.TAG_LONG) || !tag.contains("Skylight", Tag.TAG_BYTE))
                throw new IllegalArgumentException("Incomplete shelter target");
            return new Target(ResourceLocation.parse(tag.getString("Structure")), GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.get("Location")).getOrThrow(),
                    tag.getLong("StartChunk"), tag.getLong("Number"), tag.getBoolean("Skylight"));
        }
    }
    private final Map<Key, Target> targets = new HashMap<>();
    private long ordinary, skylight;
    private CompoundTag preserved;
    public static ShelterLocations get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Shelter index requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_shelters");
    }
    public boolean isReadOnly() { return preserved != null; }
    /** Generation/locating adapters pass a real start; tags decide category, never an arbitrary item coordinate. */
    public Optional<Target> observe(ServerLevel level, StructureStart start) {
        if (!level.getServer().isSameThread() || preserved != null || !start.isValid()) return Optional.empty();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var id = registry.getKey(start.getStructure());
        if (id == null) return Optional.empty();
        var holder = registry.getHolderOrThrow(registry.getResourceKey(start.getStructure()).orElseThrow());
        boolean sky = holder.is(SKYLIGHT);
        if (!sky && !holder.is(SHELTERS)) return Optional.empty();
        var key = new Key(id, level.dimension().location(), start.getChunkPos().toLong());
        var existing = targets.get(key);
        if (existing != null) return Optional.of(existing);
        long counter = sky ? skylight : ordinary;
        if (counter == Long.MAX_VALUE) return Optional.empty();
        var target = new Target(id, GlobalPos.of(level.dimension(), start.getBoundingBox().getCenter()), key.startChunk(), counter + 1, sky);
        targets.put(key, target); if (sky) skylight++; else ordinary++; setDirty(); return Optional.of(target);
    }
    public Optional<Target> nearest(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            BlockPos origin, boolean sky, Set<GlobalPos> excluded) {
        if (preserved != null) return Optional.empty();
        return targets.values().stream().filter(target -> target.skylight() == sky && target.location().dimension().equals(dimension)
                && !excluded.contains(target.location())).min(java.util.Comparator.<Target>comparingDouble(target -> distance(origin, target.location().pos()))
                .thenComparingLong(Target::number));
    }
    private static double distance(BlockPos from, BlockPos to) { double x = (double) from.getX() - to.getX(), z = (double) from.getZ() - to.getZ(); return x * x + z * z; }
    public static String letters(long number) {
        if (number < 1) throw new IllegalArgumentException("Invalid shelter number");
        var result = new StringBuilder();
        while (number > 0) { number--; result.append((char) ('A' + number % 26)); number /= 26; }
        return result.reverse().toString();
    }
    public static ShelterLocations load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new ShelterLocations();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.contains("Ordinary", Tag.TAG_LONG)
                    || !tag.contains("Skylight", Tag.TAG_LONG) || !tag.contains("Targets", Tag.TAG_LIST)) throw new IllegalArgumentException("Unknown shelter index");
            data.ordinary = tag.getLong("Ordinary"); data.skylight = tag.getLong("Skylight");
            if (data.ordinary < 0 || data.skylight < 0) throw new IllegalArgumentException("Invalid counters");
            var list = tag.getList("Targets", Tag.TAG_COMPOUND);
            if (list.size() != ((ListTag) tag.get("Targets")).size()) throw new IllegalArgumentException("Invalid target list");
            var numbers = new java.util.HashSet<String>();
            for (var entry : list) {
                var target = Target.read((CompoundTag) entry);
                if (target.number() > (target.skylight() ? data.skylight : data.ordinary)
                        || !numbers.add(target.skylight() + ":" + target.number()) || data.targets.putIfAbsent(target.key(), target) != null)
                    throw new IllegalArgumentException("Duplicate shelter identity");
            }
        } catch (RuntimeException failure) { data.targets.clear(); data.preserved = tag.copy(); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1); tag.putLong("Ordinary", ordinary); tag.putLong("Skylight", skylight);
        var entries = new ListTag(); targets.values().stream().sorted(java.util.Comparator.comparing(Target::skylight).thenComparingLong(Target::number))
                .forEach(target -> entries.add(target.save())); tag.put("Targets", entries); return tag;
    }
}
