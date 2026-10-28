package com.example.life_sanctuary;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Administrator diagnostics only; no chunk loading, inventory changes or timer updates. */
public final class GametophyteCommands {
    private GametophyteCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("life_sanctuary").requires(source -> source.hasPermission(2))
                .then(Commands.literal("gametophyte").then(Commands.literal("inspect")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            var source = context.getSource();
                            var pos = BlockPosArgument.getBlockPos(context, "pos");
                            if (!source.getLevel().getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.gametophyte.unloaded"));
                                return 0;
                            }
                            var status = GametophyteStatus.at(source.getLevel(), pos).orElse(null);
                            if (status == null) {
                                source.sendFailure(Component.translatable("command.life_sanctuary.gametophyte.unknown"));
                                return 0;
                            }
                            source.sendSuccess(() -> Component.literal(status.toTag().toString()), false);
                            return 1;
                        })))));
    }
}
