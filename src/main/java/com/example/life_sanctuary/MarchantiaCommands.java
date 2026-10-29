package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit administrator diagnostics; never loads terrain or changes cached plant state. */
public final class MarchantiaCommands {
    private MarchantiaCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("life_sanctuary").requires(source -> source.hasPermission(2))
                .then(Commands.literal("liverwort").then(Commands.literal("inspect")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            var source = context.getSource(); var level = source.getLevel();
                            var pos = BlockPosArgument.getBlockPos(context, "pos");
                            if (level.isOutsideBuildHeight(pos)) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.liverwort.outside")); return 0;
                            }
                            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.gametophyte.unloaded")); return 0;
                            }
                            var store = PlantIndividuals.get(level);
                            if (store.isReadOnly() || store.isApplyingStructure()) {
                                source.sendFailure(Component.translatable("jade.life_sanctuary.gametophyte.recovery_required")); return 0;
                            }
                            var plant = store.atBase(pos).filter(p -> p.species().equals(MarchantiaRules.SPECIES)).orElse(null);
                            if (plant == null) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.liverwort.unknown")); return 0;
                            }
                            var state = level.getBlockState(pos);
                            Optional<Boolean> appearance = state.is(LifeSanctuaryMod.MARCHANTIA.get())
                                    ? Optional.of(state.getValue(MarchantiaBlock.REPRODUCTIVE)) : Optional.empty();
                            var data = snapshot(plant, appearance, store.partsOf(plant.id()));
                            data.putInt("GroupMembers", store.populationCounts(plant.species(), plant.population()).total());
                            source.sendSuccess(() -> Component.literal(data.toString()), false); return 1;
                        })))));
    }
    /** A detached snapshot; absent blocks can still expose orphaned records for troubleshooting. */
    static CompoundTag snapshot(PlantIndividualState plant, Optional<Boolean> reproductive, Map<BlockPos, String> parts) {
        var data = new CompoundTag();
        data.putInt("Version", 1);
        data.putUUID("Individual", plant.id()); data.putLong("Base", plant.base().asLong());
        data.putString("Species", plant.species().toString()); data.putString("Population", plant.population());
        data.putString("Source", plant.source().name()); data.putString("Origin", plant.origin().name());
        data.putString("Stage", plant.stage());
        data.putBoolean("BlockPresent", reproductive.isPresent());
        reproductive.ifPresent(value -> data.putBoolean("Reproductive", value));
        data.putLong("TargetTicks", plant.targetTicks()); data.putLong("ElapsedTicks", plant.elapsedTicks());
        data.putLong("RemainingTicks", plant.remainingTicks());
        data.putInt("ReceptacleQuota", plant.harvests().getOrDefault(MarchantiaCycles.QUOTA, -1));
        data.putInt("ReproductionPending", plant.reproduction().getOrDefault(MarchantiaCycles.PENDING, 0));
        data.putInt("ClimateKnown", plant.reproduction().getOrDefault(MarchantiaRules.KNOWN, -1));
        data.putString("Consistency", reproductive.map(value -> MarchantiaState.check(plant, value, parts).name()).orElse("MISSING_BLOCK"));
        data.putInt("RegisteredParts", parts.size());
        data.putString("PauseReasons", plant.environmentReasons().stream().sorted().toList().toString());
        return data;
    }
}
