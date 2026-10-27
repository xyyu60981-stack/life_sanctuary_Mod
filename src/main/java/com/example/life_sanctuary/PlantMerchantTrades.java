package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Independent per-trader offers; never changes the global vanilla wandering trader trade table. */
public final class PlantMerchantTrades {
    private PlantMerchantTrades() {}
    private static final Map<ResourceLocation, Supplier<MerchantOffer>> ADDITIONS = new LinkedHashMap<>();
    /** Register content once during setup; the factory must create a fresh offer on each call. */
    public static void register(ResourceLocation id, Supplier<MerchantOffer> factory) {
        Objects.requireNonNull(id); Objects.requireNonNull(factory);
        if (ADDITIONS.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("Duplicate plant merchant offer " + id);
    }
    public static MerchantOffers create(WanderingTrader trader) {
        var result = new MerchantOffers();
        int cost = Config.PLANT_MERCHANT_BAG_COST.get();
        int count = Config.PLANT_MERCHANT_BAG_COUNT.get();
        result.add(sale(new ItemStack(LifeSanctuaryMod.EMPTY_SPORE_BAG.get(), count), cost));
        result.add(sale(new ItemStack(LifeSanctuaryMod.EMPTY_POLLEN_BAG.get(), count), cost));
        result.add(sale(new ItemStack(LifeSanctuaryMod.SHELTER_COMPASS.get()), Config.PLANT_MERCHANT_COMPASS_COST.get()));
        for (var entry : ADDITIONS.entrySet()) {
            var offer = Objects.requireNonNull(entry.getValue().get(), "Null plant merchant offer " + entry.getKey());
            if (offer.getResult().isEmpty()) throw new IllegalArgumentException("Empty plant merchant offer " + entry.getKey());
            result.add(offer.copy());
        }
        return result;
    }
    public static MerchantOffer sale(ItemStack product, int emeralds) {
        if (product.isEmpty() || product.getCount() > product.getMaxStackSize() || emeralds < 1 || emeralds > 64)
            throw new IllegalArgumentException("Invalid plant merchant sale");
        return new MerchantOffer(new ItemCost(Items.EMERALD, emeralds), product.copy(), 12, 1, 0.05F);
    }
}
