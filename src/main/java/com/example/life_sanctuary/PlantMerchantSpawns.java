package com.example.life_sanctuary;

import java.util.ArrayDeque;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Keep the spawner's original entity reference and vanilla lifetime/target setup intact. */
public final class PlantMerchantSpawns {
    private static final String CHOICE = "life_sanctuary:plant_merchant";
    private final ArrayDeque<TraderLlama> companions = new ArrayDeque<>();
    public static boolean isPlantMerchant(WanderingTrader trader) { return trader.getPersistentData().getBoolean(CHOICE); }
    @SubscribeEvent
    public void finalizeSpawn(FinalizeSpawnEvent event) {
        if (event.isSpawnCancelled() || event.getSpawnType() != MobSpawnType.EVENT) return;
        if (event.getEntity() instanceof TraderLlama llama) {
            companions.addLast(llama); return;
        }
        if (!(event.getEntity() instanceof WanderingTrader trader) || trader.getType() != EntityType.WANDERING_TRADER
                || trader.getClass() != WanderingTrader.class || trader.getPersistentData().contains(CHOICE)) return;
        var biome = event.getLevel().getBiome(trader.blockPosition());
        var key = biome.unwrapKey();
        boolean sanctuary = key.isPresent() && key.get().location().getNamespace().equals(LifeSanctuaryMod.MODID);
        boolean vanilla = key.isPresent() && key.get().location().getNamespace().equals("minecraft")
                && (biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA));
        boolean selected = PlantMerchantRules.choose(sanctuary, vanilla, vanilla && !sanctuary ? trader.getRandom().nextInt(100) : 0);
        trader.getPersistentData().putBoolean(CHOICE, selected);
        if (!selected) return;
        // AbstractVillager.overrideOffers is a no-op on server NPCs; edit this entity's real offer list.
        var offers = trader.getOffers();
        offers.clear(); offers.addAll(PlantMerchantTrades.create(trader));
        if (!trader.hasCustomName()) trader.setCustomName(Component.translatable("entity.life_sanctuary.plant_merchant"));
    }
    @SubscribeEvent
    public void afterTick(ServerTickEvent.Post event) {
        // The vanilla spawner attaches the leash after the spawn event returns. Inspect once, then forget.
        while (!companions.isEmpty()) {
            var llama = companions.removeFirst();
            if (llama.level().getServer() == event.getServer() && !llama.isRemoved()
                    && llama.getLeashHolder() instanceof WanderingTrader trader && isPlantMerchant(trader)) {
                llama.dropLeash(false, false); llama.discard();
            }
        }
    }
    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) { companions.clear(); }
}
