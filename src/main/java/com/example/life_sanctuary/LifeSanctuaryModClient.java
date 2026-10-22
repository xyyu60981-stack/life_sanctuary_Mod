package com.example.life_sanctuary;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = LifeSanctuaryMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = LifeSanctuaryMod.MODID, value = Dist.CLIENT)
public class LifeSanctuaryModClient {
    public LifeSanctuaryModClient(ModContainer container, IEventBus modEventBus) {
        modEventBus.addListener(LifeSanctuaryModClient::registerCollectorTooltip);
        modEventBus.addListener(LifeSanctuaryModClient::registerCollectorScreen);
        modEventBus.addListener(CollectorKeyHandler::register);
        NeoForge.EVENT_BUS.addListener(CollectorKeyHandler::tick);
        NeoForge.EVENT_BUS.addListener(CollectorKeyHandler::screenKey);
        NeoForge.EVENT_BUS.addListener(CollectorHoverController::afterRender);
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerCollectorTooltip(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(CollectorTooltip.class, ClientCollectorTooltip::new);
    }

    private static void registerCollectorScreen(RegisterMenuScreensEvent event) {
        event.register(LifeSanctuaryMod.COLLECTOR_HOVER_MENU.get(), CollectorHoverScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        LifeSanctuaryMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        LifeSanctuaryMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
