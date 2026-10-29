package com.example.life_sanctuary.compat.jade;

import com.example.life_sanctuary.*;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Discovered only by Jade. No mod constructor or client-only Minecraft class references. */
@WailaPlugin
public final class LifeSanctuaryJadePlugin implements IWailaPlugin {
    @Override public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(MarchantiaJadeProvider.INSTANCE, MarchantiaBlock.class);
        registration.registerBlockDataProvider(StarMossJadeProvider.INSTANCE, StarMossBlock.class);
        registration.registerBlockDataProvider(LuminousMossJadeProvider.INSTANCE, LuminousMossBlock.class);
        registration.registerBlockDataProvider(CollectorJadeProvider.INSTANCE, CollectorBlock.class);
        registration.registerBlockDataProvider(CultivationCabinetJadeProvider.INSTANCE, CultivationCabinetBlock.class);
        registration.registerBlockDataProvider(Provider.INSTANCE, SharedGametophyteBlock.class);
        registration.registerBlockDataProvider(Provider.INSTANCE, EquisetumArvenseGametophyteBlock.class);
        registration.registerBlockDataProvider(HorsetailProvider.INSTANCE, EquisetumArvenseBlock.class);
        registration.registerBlockDataProvider(HorsetailProvider.INSTANCE, EquisetumArvenseSproutsBlock.class);
    }
    @Override public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(MarchantiaJadeProvider.INSTANCE, MarchantiaBlock.class);
        registration.registerBlockComponent(StarMossJadeProvider.INSTANCE, StarMossBlock.class);
        registration.registerBlockComponent(LuminousMossJadeProvider.INSTANCE, LuminousMossBlock.class);
        registration.registerBlockComponent(CollectorJadeProvider.INSTANCE, CollectorBlock.class);
        registration.registerBlockComponent(CultivationCabinetJadeProvider.INSTANCE, CultivationCabinetBlock.class);
        registration.registerBlockComponent(Provider.INSTANCE, SharedGametophyteBlock.class);
        registration.registerBlockComponent(Provider.INSTANCE, EquisetumArvenseGametophyteBlock.class);
        registration.registerBlockComponent(HorsetailProvider.INSTANCE, EquisetumArvenseBlock.class);
        registration.registerBlockComponent(HorsetailProvider.INSTANCE, EquisetumArvenseSproutsBlock.class);
    }
    private enum HorsetailProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;
        private static final String KEY = "LifeSanctuaryHorsetail";
        @Override public ResourceLocation getUid() { return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "horsetail_status"); }
        @Override public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel level)) return;
            var pos = accessor.getPosition();
            var state = accessor.getBlockState();
            var base = state.getBlock() instanceof EquisetumArvenseBlock ? EquisetumArvenseBlock.base(state, pos) : pos;
            if (!level.getChunkSource().hasChunk(base.getX() >> 4, base.getZ() >> 4)) return;
            var data = HorsetailPopulations.get(level);
            if (data.member(base) == null) return;
            var lower = level.getBlockState(base);
            if (state.getBlock() instanceof EquisetumArvenseBlock
                    && (!(lower.getBlock() instanceof EquisetumArvenseBlock)
                        || !EquisetumArvenseBlock.hasMatchingHalves(level, base, lower))) return;
            var payload = data.statusAt(base).toTag();
            payload.putInt("Version", 1);
            payload.putBoolean("HasSporeStem", lower.getBlock() instanceof EquisetumArvenseBlock
                    && data.stageOf(base) == PlantStage.ADULT
                    && lower.getValue(EquisetumArvenseBlock.PART) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    && lower.getValue(EquisetumArvenseBlock.SPORE_STEM));
            payload.putLong("Position", pos.asLong());
            tag.put(KEY, payload);
        }
        @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            var root = accessor.getServerData();
            if (!root.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return;
            var data = root.getCompound(KEY);
            if (data.getInt("Version") != 1 || !data.contains("Position", net.minecraft.nbt.Tag.TAG_LONG)
                    || data.getLong("Position") != accessor.getPosition().asLong()) return;
            String status = switch (data.getString("GrowthStatus")) {
                case "STATIC" -> "static";
                case "PAUSED_COLD" -> "paused";
                case "CLIMATE_ALLOWS_GROWTH" -> "active";
                default -> "unknown";
            };
            if (data.getBoolean("Pruned") && !status.equals("static")) status = "paused";
            tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte." + status));
            if (data.getString("GrowthStatus").equals("PAUSED_COLD"))
                tooltip.add(Component.translatable("jade.life_sanctuary.reason.cold"));
            if (data.getBoolean("Pruned")) tooltip.add(Component.translatable("jade.life_sanctuary.horsetail.pruned"));
            if (data.getBoolean("HasSporeStem")) tooltip.add(Component.translatable("jade.life_sanctuary.horsetail.harvests",
                    Math.max(0, data.getInt("RemainingSporeHarvests"))));
        }
    }
    private enum Provider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;
        private static final String KEY = "LifeSanctuaryGametophyte";
        @Override public ResourceLocation getUid() { return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "gametophyte_status"); }
        @Override public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel level)) return;
            GametophyteStatus.at(level, accessor.getPosition()).ifPresent(status -> {
                var data = status.toTag();
                data.remove("Cycle");
                // Tooltip consumers only need a bounded subset of known environmental causes.
                data.remove("Reasons");
                for (var reason : java.util.List.of("cold", "dry", "hot", "lack_water"))
                    data.putBoolean("Reason_" + reason, status.reasons().contains(reason));
                data.putLong("Position", accessor.getPosition().asLong());
                tag.put(KEY, data);
            });
        }
        @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            var root = accessor.getServerData();
            if (!root.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return;
            var data = root.getCompound(KEY);
            if (data.getInt("Version") != 1 || !data.contains("Position", net.minecraft.nbt.Tag.TAG_LONG)
                    || data.getLong("Position") != accessor.getPosition().asLong()) return;
            var species = ResourceLocation.tryParse(data.getString("Species"));
            if (species == null) return;
            var name = Component.translatableWithFallback("species." + species.getNamespace() + "." + species.getPath(), species.toString());
            tooltip.add(Component.translatable("tooltip.life_sanctuary.gametophyte.species", name).withStyle(ChatFormatting.GRAY));
            String growth = data.getString("Growth").toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("static", "unknown", "active", "paused", "recovery_required").contains(growth)) return;
            tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte." + growth));
            for (var reason : java.util.List.of("cold", "dry", "hot"))
                if (data.getBoolean("Reason_" + reason)) tooltip.add(Component.translatable("jade.life_sanctuary.reason." + reason));
            if (data.getBoolean("WaterKnown") && data.contains("Water", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var water = data.getCompound("Water");
                if (water.getBoolean("LacksWater")) {
                    long ticks = Math.max(0, water.getLong("RemainingDeclineTicks"));
                    tooltip.add((ticks == 0 ? Component.translatable("jade.life_sanctuary.gametophyte.waiting_decline")
                            : Component.translatable("jade.life_sanctuary.gametophyte.lacks_water", ticks / 20 + (ticks % 20 == 0 ? 0 : 1)))
                            .withStyle(ChatFormatting.YELLOW));
                }
            } else if (data.getBoolean("Reason_lack_water")) {
                tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.water_unknown").withStyle(ChatFormatting.YELLOW));
            }
        }
    }
}
