package com.example.life_sanctuary;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 管理员按需查询，不加载目标区块、不推进计时、不发物品，也不自动修复未知数据。 */
public final class CultivationCabinetCommands {
    private CultivationCabinetCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var cultivation = Commands.literal("cultivation")
                .then(Commands.literal("inspect").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> inspect(context.getSource(), BlockPosArgument.getBlockPos(context, "pos")))))
                .then(Commands.literal("recipes").executes(context -> recipes(context.getSource())));
        event.getDispatcher().register(Commands.literal("life_sanctuary").requires(source -> source.hasPermission(2))
                .then(cultivation));
    }

    /** 查配置而非世界运行状态，不调用 Phase.start 或构建物品栈。 */
    private static int recipes(CommandSourceStack source) {
        var registered = CultivationRecipes.snapshot();
        var plans = registered.values().stream().sorted(java.util.Comparator.comparing(
                (CultivationRecipe recipe) -> recipe.species().toString())).toList();
        long readyCount = plans.stream().filter(CultivationProducts::isReady).count();
        source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.recipes_summary",
                plans.size(), readyCount), false);
        for (var recipe : plans) {
            var missing = CultivationProducts.missingOutputs(recipe);
            source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.recipe",
                    recipe.species().toString(), recipe.id().toString(), recipe.phases().size(), missing.toString()), false);
            for (var phase : recipe.phases())
                source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.recipe_phase",
                        phase.key(), phase.output().toString(), phase.minimumNaturalTicks(), phase.maximumNaturalTicks(),
                        CultivationRules.durationFromNatural(phase.minimumNaturalTicks()),
                        CultivationRules.durationFromNatural(phase.maximumNaturalTicks())), false);
        }
        var withoutRecipe = PlantSpeciesRegistry.snapshot().keySet().stream().filter(species -> !registered.containsKey(species))
                .map(Object::toString).sorted().toList();
        if (!withoutRecipe.isEmpty()) source.sendSuccess(() -> Component.translatable(
                "command.life_sanctuary.cultivation.species_without_recipe", withoutRecipe.toString()), false);
        return plans.size();
    }

    private static int inspect(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        if (!CultivationCabinetWorldConnection.isLoaded(level, pos)) {
            source.sendFailure(Component.translatable("command.life_sanctuary.cultivation.unloaded"));
            return 0;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof CultivationCabinetBlock)
                || !(level.getBlockEntity(pos) instanceof CultivationCabinetBlockEntity own)) {
            source.sendFailure(Component.translatable("command.life_sanctuary.cultivation.not_cabinet"));
            return 0;
        }
        var state = own.getBlockState();
        var part = state.getValue(CultivationCabinetBlock.PART);
        source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.header",
                CultivationCabinetWorldConnection.displayName(level, pos), pos.toShortString(),
                state.getValue(CultivationCabinetBlock.FACING).getSerializedName(), part.getSerializedName()), false);
        var partner = CultivationCabinetWorldConnection.partner(level, pos).orElse(null);
        if (part != CultivationCabinetPairing.Part.SINGLE && partner == null)
            source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.partner_unavailable"), false);
        boolean powered = own.cachedPower().orElse(false) || partner != null && partner.cachedPower().orElse(false);
        var first = partner != null && part == CultivationCabinetPairing.Part.SECOND ? partner : own;
        var second = partner == null ? null : first == own ? partner : own;
        report(source, first, powered);
        if (second != null) report(source, second, powered);
        var contents = CultivationCabinetWorldConnection.contents(level, pos);
        source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.summary",
                contents.map(CultivationSlots::capacity).orElse(0),
                CultivationCabinetWorldConnection.comparatorSignal(level, pos)), false);
        return 1;
    }

    private static void report(CommandSourceStack source, CultivationCabinetBlockEntity cabinet, boolean powered) {
        String cache = cabinet.cachedPower().map(Object::toString).orElse("UNKNOWN");
        source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.half",
                cabinet.getBlockPos().toShortString(), cache), false);
        var snapshot = cabinet.status(powered);
        if (snapshot.readOnly()) {
            source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.unavailable",
                    snapshot.diagnostic().orElse("UNKNOWN")), false);
            return;
        }
        for (var slot : snapshot.slots()) {
            String species = slot.batch().map(batch -> batch.species().toString()).orElse("-");
            String phase = slot.batch().map(CultivationStatus.Batch::phase).orElse("-");
            long remainingTicks = slot.batch().map(batch -> batch.targetTicks() - batch.elapsedTicks()).orElse(0L);
            int products = slot.bufferedProduct().map(CultivationStatus.BufferedProduct::remaining)
                    .orElseGet(() -> slot.manualProduct().map(CultivationBatch.Product::count).orElse(0));
            source.sendSuccess(() -> Component.translatable("command.life_sanctuary.cultivation.slot", slot.index() + 1,
                    species, phase, slot.state().name(), remainingTicks, products), false);
        }
    }
}
