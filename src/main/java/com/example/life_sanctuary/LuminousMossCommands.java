package com.example.life_sanctuary;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Administrator inspection only: no chunk loads, environment refresh, timer changes or quota repair. */
public final class LuminousMossCommands {
    private LuminousMossCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("life_sanctuary").requires(source -> source.hasPermission(2))
                .then(Commands.literal("moss").then(Commands.literal("inspect")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            var source = context.getSource(); var level = source.getLevel();
                            var pos = BlockPosArgument.getBlockPos(context, "pos");
                            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.gametophyte.unloaded")); return 0;
                            }
                            var store = PlantIndividuals.get(level);
                            if (store.isReadOnly()) {
                                source.sendFailure(Component.translatable("jade.life_sanctuary.gametophyte.recovery_required")); return 0;
                            }
                            var state = level.getBlockState(pos);
                            var plant = store.atBase(pos).filter(p -> p.species().equals(LuminousMossRules.SPECIES)).orElse(null);
                            if (plant == null) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.moss.unknown")); return 0;
                            }
                            var data = new CompoundTag(); data.putUUID("Individual", plant.id());
                            int appearance = state.is(LifeSanctuaryMod.LUMINOUS_MOSS.get()) ? state.getValue(LuminousMossBlock.AGE) : -1;
                            data.putString("Population", plant.population()); data.putString("Source", plant.source().name());
                            data.putString("Stage", plant.stage()); data.putInt("Appearance", appearance);
                            data.putLong("RemainingTicks", plant.remainingTicks());
                            data.putInt("CapsuleQuota", plant.harvests().getOrDefault(LuminousMossCycles.QUOTA, -1));
                            data.putInt("CapsulePending", plant.reproduction().getOrDefault(LuminousMossCycles.PENDING, 0));
                            data.putInt("DryCache", plant.reproduction().getOrDefault(LuminousMossCycles.DRY, -1));
                            data.putString("Consistency", LuminousMossState.check(plant, appearance, store.partsOf(plant.id())).name());
                            data.putString("PauseReasons", plant.environmentReasons().stream().sorted().toList().toString());
                            source.sendSuccess(() -> Component.literal(data.toString()), false); return 1;
                        })))));
    }
}
