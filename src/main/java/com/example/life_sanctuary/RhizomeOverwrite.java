package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 异系统地下茎可被转化；同系统节点必须避让，逻辑身份及其余关联不合并。 */
final class RhizomeOverwrite {
    private RhizomeOverwrite() {}
    record Transfer(PlantRhizomeSystem before, PlantRhizomeSystem after) {}

    static boolean canConvert(PlantRhizomeSystems store, PlantRhizomeSystem own, BlockPos pos, BlockState state) {
        var physical = store.atRhizome(pos).orElse(null);
        if (store.atShootBase(pos).isPresent()) return false;
        if (physical == null) return store.atOrigin(pos).isEmpty() && store.atAttachment(pos).isEmpty()
                && RhizomePathRules.canConvert(state);
        if (physical.id().equals(own.id()) || store.needsRecovery(physical.id())
                || !(state.getBlock() instanceof RhizomeBlock) || state.hasBlockEntity()
                || (!state.getFluidState().isEmpty() && !state.getFluidState().is(FluidTags.WATER))) return false;
        return true;
    }

    static boolean ownsAttachment(ServerLevel level, PlantRhizomeSystem own, BlockPos pos) {
        var store = PlantRhizomeSystems.get(level);
        var physical = store.atRhizome(pos).orElse(null);
        if (physical != null) return physical.id().equals(own.id()) && own.rhizomes().contains(pos);
        if (!own.isFiberAnchor(pos)) return false;
        var plant = PlantIndividuals.get(level).atPart(pos).orElse(null);
        return plant == null || RhizomeIndividualIdentity.resolve(level, plant)
                .map(identity -> identity.individualId().equals(own.id())).orElse(false);
    }

    static Map<UUID, Transfer> transfers(PlantRhizomeSystems store, PlantRhizomeSystem own,
            Map<BlockPos, RhizomeShootStructure.Part> parts) {
        Map<UUID, Set<BlockPos>> positions = new HashMap<>();
        parts.forEach((pos, part) -> {
            if (part.kind() != RhizomeShootStructure.Kind.RHIZOME) return;
            store.atRhizome(pos).filter(owner -> !owner.id().equals(own.id())).ifPresent(owner ->
                    positions.computeIfAbsent(owner.id(), ignored -> new HashSet<>()).add(pos));
        });
        Map<UUID, Transfer> result = new HashMap<>();
        positions.forEach((id, replaced) -> {
            var before = store.find(id).orElseThrow();
            Set<BlockPos> lateral = new HashSet<>(replaced);
            lateral.retainAll(before.rhizomes());
            Set<BlockPos> rising = new HashSet<>(replaced);
            rising.retainAll(before.risingRhizomes());
            result.put(id, new Transfer(before, before.removeRhizomes(lateral).removeRisingRhizomes(rising)));
        });
        return Map.copyOf(result);
    }

    static void requireRecovery(PlantRhizomeSystems store, UUID own, Map<UUID, Transfer> transfers) {
        store.requireRecovery(own);
        transfers.keySet().forEach(store::requireRecovery);
    }
}
