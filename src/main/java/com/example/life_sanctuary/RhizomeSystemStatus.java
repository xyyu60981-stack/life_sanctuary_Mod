package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Jade/诊断用只读快照；只查询 SavedData 和运行计时，不读方块、不加载区块。 */
public final class RhizomeSystemStatus {
    private RhizomeSystemStatus() {}
    public enum LookupReason { FOUND, NO_SYSTEM, DATA_UNAVAILABLE }
    public enum Regrowth {
        DATA_UNAVAILABLE, RECOVERY_REQUIRED, APPLYING_STRUCTURE, STATIC, INITIAL_ROOT, NO_LATERAL_RHIZOME,
        FULL, WAITING, READY, MISSING_HANDLER, INCONSISTENT_TIMER
    }
    public enum Location { LATERAL_RHIZOME, RISING_RHIZOME, ORIGIN, ATTACHMENT, LINKED_PLANT }

    public record Snapshot(UUID id, ResourceLocation species, PlantSource source, BlockPos origin,
            int regionX, int regionZ, int lateralBlocks, int risingBlocks,
            int occupiedShootSlots, int shootLimit, Optional<RhizomeGrowthState> growth,
            Optional<RhizomeShootTimer> shootTimer, boolean hasShootHandler, Regrowth regrowth) {
        public Snapshot {
            Objects.requireNonNull(id);
            Objects.requireNonNull(species);
            Objects.requireNonNull(source);
            origin = origin.immutable();
            Objects.requireNonNull(growth);
            Objects.requireNonNull(shootTimer);
            Objects.requireNonNull(regrowth);
        }
        public int freeShootSlots() { return shootLimit - occupiedShootSlots; }
        public UUID individualId() { return id; }
    }

    public record Lookup(LookupReason reason, Optional<Location> location, Optional<Snapshot> system) {}

    public static Optional<Snapshot> byId(PlantRhizomeSystems store, UUID id) {
        return store.find(id).map(system -> snapshot(store, system));
    }

    /** 地下节点、系统原点、连接位置以及地上个体任一已登记部件均可追溯。 */
    public static Lookup at(ServerLevel level, BlockPos pos) {
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly())
            return new Lookup(LookupReason.DATA_UNAVAILABLE, Optional.empty(), Optional.empty());
        var node = systems.atRhizome(pos).orElse(null);
        if (node != null) return found(systems, node, node.rhizomes().contains(pos)
                ? Location.LATERAL_RHIZOME : Location.RISING_RHIZOME);
        var origin = systems.atOrigin(pos).orElse(null);
        if (origin != null) return found(systems, origin, Location.ORIGIN);
        var attachment = systems.atAttachment(pos).orElse(null);
        if (attachment != null) return found(systems, attachment, Location.ATTACHMENT);
        var shootBase = systems.atShootBase(pos).orElse(null);
        if (shootBase != null) return found(systems, shootBase, Location.LINKED_PLANT);
        var individual = individuals.atPart(pos).orElseGet(() -> individuals.atBase(pos).orElse(null));
        if (individual != null) {
            var owner = systems.forShoot(individual.id()).orElseGet(() -> systems.find(individual.id()).orElse(null));
            if (owner != null) return found(systems, owner, Location.LINKED_PLANT);
        }
        return new Lookup(LookupReason.NO_SYSTEM, Optional.empty(), Optional.empty());
    }

    private static Lookup found(PlantRhizomeSystems store, PlantRhizomeSystem system, Location location) {
        return new Lookup(LookupReason.FOUND, Optional.of(location), Optional.of(snapshot(store, system)));
    }

    private static Snapshot snapshot(PlantRhizomeSystems store, PlantRhizomeSystem system) {
        var timer = store.shootTimer(system.id());
        boolean handler = RhizomeShootHandlers.find(system.species()) != null;
        Regrowth regrowth;
        if (store.isReadOnly()) regrowth = Regrowth.DATA_UNAVAILABLE;
        else if (store.needsRecovery(system.id())) regrowth = Regrowth.RECOVERY_REQUIRED;
        else if (store.isApplyingStructure(system.id())) regrowth = Regrowth.APPLYING_STRUCTURE;
        else if (system.source().isStatic()) regrowth = Regrowth.STATIC;
        else if (system.rhizomes().isEmpty()) regrowth = system.growth().filter(growth -> growth.allTips().entrySet().stream()
                .anyMatch(entry -> entry.getValue().position().equals(growth.rootFor(entry.getKey(), system.origin()))
                        && entry.getValue().remaining() > 0)).isPresent()
                ? Regrowth.INITIAL_ROOT : Regrowth.NO_LATERAL_RHIZOME;
        else if (system.freeShootSlots() == 0) regrowth = Regrowth.FULL;
        else if (timer.isEmpty()) regrowth = Regrowth.INCONSISTENT_TIMER;
        else if (!timer.get().ready()) regrowth = Regrowth.WAITING;
        else regrowth = handler ? Regrowth.READY : Regrowth.MISSING_HANDLER;
        return new Snapshot(system.id(), system.species(), system.source(), system.origin(),
                system.regionX(), system.regionZ(), system.rhizomes().size(), system.risingRhizomes().size(),
                system.shoots().size(), system.shootLimit(), system.growth(), timer, handler, regrowth);
    }
}
