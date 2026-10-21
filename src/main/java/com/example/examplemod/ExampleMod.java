package com.example.examplemod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(ExampleMod.MODID)
public class ExampleMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "life_sanctuary";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final TagKey<Block> HORSETAIL_SUBSTRATE = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "horsetail_substrate"));
    public static final TagKey<Block> NATURAL_PLANT_EXCLUSION = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "natural_plant_exclusion"));
    public static final TagKey<Biome> DRY_BIOMES = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MODID, "dry"));
    public static final TagKey<Biome> WET_BIOMES = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MODID, "wet"));
    public static final TagKey<Biome> COLD_BIOMES = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MODID, "cold"));
    public static final TagKey<Biome> HOT_BIOMES = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MODID, "hot"));
    // Create a Deferred Register to hold Blocks which will all be registered under the "examplemod" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "examplemod" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "examplemod" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "examplemod:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "examplemod:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // First Life Sanctuary material: a simple peat block used as the foundation for later plant systems.
    public static final DeferredBlock<Block> PEAT = BLOCKS.registerSimpleBlock("peat", BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BROWN)
            .strength(0.5F)
            .sound(SoundType.GRAVEL));
    public static final DeferredItem<BlockItem> PEAT_ITEM = ITEMS.registerSimpleBlockItem("peat", PEAT);

    // First plant registration. Growth and harvesting rules will be added after the basic asset loop is verified.
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE = BLOCKS.registerBlock("equisetum_arvense", properties -> new EquisetumArvenseBlock(properties
            .mapColor(MapColor.PLANT)
            .noOcclusion()
            .instabreak()
            .sound(SoundType.GRASS)));
    public static final DeferredItem<HorsetailPlantItem> EQUISETUM_ARVENSE_ITEM = ITEMS.register("equisetum_arvense", () -> new HorsetailPlantItem(EQUISETUM_ARVENSE.get(), new Item.Properties()));
    public static final DeferredBlock<Block> DEAD_EQUISETALES = BLOCKS.registerBlock("dead_equisetales", properties -> new DeadEquisetalesBlock(properties.mapColor(MapColor.COLOR_BROWN).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<BlockItem> DEAD_EQUISETALES_ITEM = ITEMS.registerSimpleBlockItem("dead_equisetales", DEAD_EQUISETALES);
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE_GAMETOPHYTE = BLOCKS.registerBlock("equisetum_arvense_gametophyte", properties -> new EquisetumArvenseGametophyteBlock(properties.mapColor(MapColor.PLANT).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<HorsetailPlantItem> EQUISETUM_ARVENSE_GAMETOPHYTE_ITEM = ITEMS.register("equisetum_arvense_gametophyte", () -> new HorsetailPlantItem(EQUISETUM_ARVENSE_GAMETOPHYTE.get(), new Item.Properties()));
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE_SPROUTS = BLOCKS.registerBlock("equisetum_arvense_sprouts", properties -> new EquisetumArvenseSproutsBlock(properties.mapColor(MapColor.PLANT).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<HorsetailPlantItem> EQUISETUM_ARVENSE_SPROUTS_ITEM = ITEMS.register("equisetum_arvense_sprouts", () -> new HorsetailPlantItem(EQUISETUM_ARVENSE_SPROUTS.get(), new Item.Properties()));
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE_SPORE_STEM = BLOCKS.registerSimpleBlock("equisetum_arvense_spore_stem", BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).noOcclusion().instabreak().sound(SoundType.GRASS));
    public static final DeferredItem<Item> EQUISETUM_ARVENSE_SPORE_STEM_ITEM = ITEMS.register("equisetum_arvense_spore_stem", () -> new BlockItem(EQUISETUM_ARVENSE_SPORE_STEM.get(), new Item.Properties().food(new FoodProperties.Builder().nutrition(2).saturationModifier(0.0F).build())));
    public static final DeferredItem<Item> EQUISETUM_CONGEE = ITEMS.register("equisetum_congee", () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
            .nutrition(3).saturationModifier(5.0F / 6.0F)
            .effect(() -> new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 30 * 20, 0), 1.0F).build())));

    // Creates a new food item with the id "examplemod:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "examplemod:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.life_sanctuary")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get());// Add the example item to the tab. For your own tabs, this method is preferred over the event
            output.accept(PEAT_ITEM);
            output.accept(EQUISETUM_ARVENSE_ITEM);
            output.accept(DEAD_EQUISETALES_ITEM);
            output.accept(EQUISETUM_ARVENSE_GAMETOPHYTE_ITEM);
            output.accept(EQUISETUM_ARVENSE_SPROUTS_ITEM);
            output.accept(EQUISETUM_ARVENSE_SPORE_STEM_ITEM);
            output.accept(EQUISETUM_CONGEE);
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (ExampleMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new NaturalPopulationGenerationHandler());

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
            event.accept(PEAT_ITEM);
            event.accept(EQUISETUM_ARVENSE_ITEM);
            event.accept(DEAD_EQUISETALES_ITEM);
            event.accept(EQUISETUM_ARVENSE_GAMETOPHYTE_ITEM);
            event.accept(EQUISETUM_ARVENSE_SPROUTS_ITEM);
            event.accept(EQUISETUM_ARVENSE_SPORE_STEM_ITEM);
            event.accept(EQUISETUM_CONGEE);
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
