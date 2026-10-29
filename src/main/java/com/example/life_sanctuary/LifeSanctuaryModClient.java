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
        modEventBus.addListener(LuminousMossOverlayModel::register);
        modEventBus.addListener(LuminousMossOverlayModel::bake);
        modEventBus.addListener(LifeSanctuaryModClient::registerCollectorTooltip);
        modEventBus.addListener(LifeSanctuaryModClient::registerCollectorScreen);
        modEventBus.addListener(LifeSanctuaryModClient::registerDisplayRenderer);
        modEventBus.addListener(CycadSeedColors::blocks);
        modEventBus.addListener(CycadSeedColors::items);
        modEventBus.addListener(CollectorKeyHandler::register);
        NeoForge.EVENT_BUS.addListener(CollectorKeyHandler::tick);
        NeoForge.EVENT_BUS.addListener(CollectorKeyHandler::screenKey);
        NeoForge.EVENT_BUS.addListener(CollectorHoverController::afterRender);
        NeoForge.EVENT_BUS.addListener(HandheldClient::use);
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerCollectorTooltip(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(CollectorTooltip.class, ClientCollectorTooltip::new);
    }
    private static void registerDisplayRenderer(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(LifeSanctuaryMod.DISPLAY_CABINET_ENTITY.get(), DisplayCabinetRenderer::new);
    }

    private static void registerCollectorScreen(RegisterMenuScreensEvent event) {
        event.register(LifeSanctuaryMod.COLLECTOR_HOVER_MENU.get(), CollectorHoverScreen<CollectorItemMenu>::new);
        event.register(LifeSanctuaryMod.COLLECTOR_ITEM_MENU.get(), CollectorHoverScreen<CollectorItemMenu>::new);
        event.register(LifeSanctuaryMod.COLLECTOR_BLOCK_MENU.get(), CollectorHoverScreen<CollectorBlockMenu>::new);
        event.register(LifeSanctuaryMod.COLLECTOR_FILTER_MENU.get(), CollectorFilterScreen::new);
        event.register(LifeSanctuaryMod.CULTIVATION_MENU.get(), CultivationCabinetScreen::new);
        event.register(LifeSanctuaryMod.FOSSIL_SLICER_MENU.get(), FossilSlicerScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            HandheldClient.setup();
            CycadSeedColors.properties();
            net.minecraft.client.renderer.item.ItemProperties.register(LifeSanctuaryMod.SPORE_BAG.get(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "spore_form"),
                    (stack, level, holder, seed) -> SporeBagContents.form(stack).map(form -> switch (form) {
                        case NORMAL -> 0F; case MEGA -> 1F; case MICRO -> 2F; case MIXED -> 3F;
                    }).orElse(0F));
            for (var item : java.util.List.of(LifeSanctuaryMod.SHELTER_COMPASS.get(), LifeSanctuaryMod.SKYLIGHT_SHELTER_COMPASS.get())) {
                net.minecraft.client.renderer.item.ItemProperties.register(item, net.minecraft.resources.ResourceLocation.withDefaultNamespace("angle"),
                        new net.minecraft.client.renderer.item.CompassItemPropertyFunction((level, stack, entity) -> {
                            var tracker = stack.get(net.minecraft.core.component.DataComponents.LODESTONE_TRACKER);
                            return tracker == null ? null : tracker.target().orElse(null);
                        }));
            }
        });
        // Some client setup code
        LifeSanctuaryMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        LifeSanctuaryMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
