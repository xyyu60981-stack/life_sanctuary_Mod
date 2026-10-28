package com.example.life_sanctuary;

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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(LifeSanctuaryMod.MODID)
public class LifeSanctuaryMod {
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
    // Registry for Life Sanctuary blocks.
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Registry for Life Sanctuary items.
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<net.minecraft.world.level.levelgen.feature.Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>, LavaLakeRemnantFeature> LAVA_LAKE_REMNANTS =
            FEATURES.register("lava_lake_remnants", LavaLakeRemnantFeature::new);
    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>, CoalCoreFeature> COAL_CORE =
            FEATURES.register("coal_core", CoalCoreFeature::new);
    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>, LargeLeafMossFeature> LARGE_LEAF_MOSS_PATCH =
            FEATURES.register("large_leaf_moss_patch", LargeLeafMossFeature::new);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<CultivationCabinetMenu>> CULTIVATION_MENU =
            MENUS.register("cultivation_cabinet", () -> IMenuTypeExtension.create((id, inventory, buffer) ->
                    new CultivationCabinetMenu(id, inventory, buffer.readBlockPos(), buffer.readVarInt())));
    public static final DeferredHolder<MenuType<?>, MenuType<FossilSlicerMenu>> FOSSIL_SLICER_MENU =
            MENUS.register("fossil_slicer", () -> IMenuTypeExtension.create((id, inventory, buffer) -> new FossilSlicerMenu(id, inventory, buffer.readBlockPos())));
    public static final DeferredHolder<MenuType<?>, MenuType<CollectorItemMenu>> COLLECTOR_HOVER_MENU =
            MENUS.register("collector_hover", () -> IMenuTypeExtension.create((id, inventory, buffer) ->
                    new CollectorItemMenu(id, inventory, buffer.readVarInt(), buffer.readVarInt())));
    public static final DeferredHolder<MenuType<?>, MenuType<CollectorBlockMenu>> COLLECTOR_BLOCK_MENU =
            MENUS.register("collector_block", () -> IMenuTypeExtension.create((id, inventory, buffer) ->
                    new CollectorBlockMenu(id, inventory, buffer.readBlockPos(), buffer.readVarInt())));
    public static final DeferredHolder<MenuType<?>, MenuType<CollectorFilterMenu>> COLLECTOR_FILTER_MENU =
            MENUS.register("collector_filter", () -> IMenuTypeExtension.create((id, inventory, buffer) -> new CollectorFilterMenu(id, inventory)));
    public static final DeferredHolder<MenuType<?>, MenuType<CollectorItemMenu>> COLLECTOR_ITEM_MENU =
            MENUS.register("collector_item", () -> IMenuTypeExtension.create((id, inventory, buffer) ->
                    new CollectorItemMenu(id, inventory, buffer.readVarInt(), buffer.readVarInt(), true)));
    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<CollectorUpgradeRecipe>> COLLECTOR_UPGRADE =
            RECIPE_SERIALIZERS.register("collector_upgrade", () -> new SimpleCraftingRecipeSerializer<>(CollectorUpgradeRecipe::new));
    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<CycadSeedPeelingRecipe>> CYCAD_SEED_PEELING =
            RECIPE_SERIALIZERS.register("cycad_seed_peeling", () -> new SimpleCraftingRecipeSerializer<>(CycadSeedPeelingRecipe::new));
    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<MixedSporeRecipe>> MIXED_SPORES =
            RECIPE_SERIALIZERS.register("mixed_spores", () -> new SimpleCraftingRecipeSerializer<>(MixedSporeRecipe::new));
    // Registry for Life Sanctuary creative tabs.
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // First Life Sanctuary material: a simple peat block used as the foundation for later plant systems.
    public static final DeferredBlock<Block> PEAT = BLOCKS.registerBlock("peat", properties -> new PeatBlock(properties
            .mapColor(MapColor.COLOR_BROWN)
            .strength(0.5F)
            .sound(SoundType.GRAVEL)));
    public static final DeferredItem<BlockItem> PEAT_ITEM = ITEMS.registerSimpleBlockItem("peat", PEAT);
    public static final DeferredBlock<PeatFarmlandBlock> PEAT_FARMLAND = BLOCKS.registerBlock("peat_farmland",
            properties -> new PeatFarmlandBlock(properties.strength(0.6F).sound(SoundType.GRAVEL).randomTicks()));
    public static final DeferredItem<BlockItem> PEAT_FARMLAND_ITEM = ITEMS.registerSimpleBlockItem("peat_farmland", PEAT_FARMLAND);

    // First plant registration. Growth and harvesting rules will be added after the basic asset loop is verified.
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE = BLOCKS.registerBlock("equisetum_arvense", properties -> new EquisetumArvenseBlock(properties
            .mapColor(MapColor.PLANT)
            .noOcclusion()
            .instabreak()
            .sound(SoundType.GRASS)));
    public static final DeferredBlock<Block> PLACED_EQUISETUM_ARVENSE = BLOCKS.registerBlock("placed_equisetum_arvense",
            properties -> new PlacedHorsetailBlock(properties.mapColor(MapColor.PLANT).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<CutHorsetailItem> EQUISETUM_ARVENSE_ITEM = ITEMS.register("equisetum_arvense",
            () -> new CutHorsetailItem(PLACED_EQUISETUM_ARVENSE.get(), new Item.Properties(), false));
    public static final DeferredBlock<Block> DEAD_EQUISETALES = BLOCKS.registerBlock("dead_equisetales", properties -> new DeadEquisetalesBlock(properties.mapColor(MapColor.COLOR_BROWN).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<BlockItem> DEAD_EQUISETALES_ITEM = ITEMS.registerSimpleBlockItem("dead_equisetales", DEAD_EQUISETALES);
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE_GAMETOPHYTE = BLOCKS.registerBlock("equisetum_arvense_gametophyte", properties -> new EquisetumArvenseGametophyteBlock(properties.mapColor(MapColor.PLANT).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredBlock<SharedGametophyteBlock> SPHENOPHYTE_GAMETOPHYTE = sharedGametophyte("sphenophyte_gametophyte");
    public static final DeferredBlock<SharedGametophyteBlock> FERN_GAMETOPHYTE = sharedGametophyte("fern_gametophyte");
    public static final DeferredBlock<SharedGametophyteBlock> HOMOSPOROUS_LYCOPOD_GAMETOPHYTE = sharedGametophyte("homosporous_lycopod_gametophyte");
    public static final DeferredBlock<SharedGametophyteBlock> FEMALE_LYCOPOD_GAMETOPHYTE = sharedGametophyte("female_lycopod_gametophyte");
    public static final DeferredBlock<SharedGametophyteBlock> MALE_LYCOPOD_GAMETOPHYTE = sharedGametophyte("male_lycopod_gametophyte");
    public static final DeferredBlock<SharedGametophyteBlock> MIXED_LYCOPOD_GAMETOPHYTE = sharedGametophyte("mixed_lycopod_gametophyte");
    private static DeferredBlock<SharedGametophyteBlock> sharedGametophyte(String id) {
        return BLOCKS.registerBlock(id, p -> new SharedGametophyteBlock(p.mapColor(MapColor.PLANT).instabreak().sound(SoundType.GRASS)));
    }
    public static final DeferredItem<SharedGametophyteItem> SPHENOPHYTE_GAMETOPHYTE_ITEM = sharedGametophyteItem(GametophyteAppearance.SPHENOPHYTE);
    public static final DeferredItem<SharedGametophyteItem> FERN_GAMETOPHYTE_ITEM = sharedGametophyteItem(GametophyteAppearance.FERN);
    public static final DeferredItem<SharedGametophyteItem> HOMOSPOROUS_LYCOPOD_GAMETOPHYTE_ITEM = sharedGametophyteItem(GametophyteAppearance.HOMOSPOROUS_LYCOPOD);
    public static final DeferredItem<SharedGametophyteItem> FEMALE_LYCOPOD_GAMETOPHYTE_ITEM = sharedGametophyteItem(GametophyteAppearance.FEMALE_LYCOPOD);
    public static final DeferredItem<SharedGametophyteItem> MALE_LYCOPOD_GAMETOPHYTE_ITEM = sharedGametophyteItem(GametophyteAppearance.MALE_LYCOPOD);
    public static final DeferredItem<SharedGametophyteItem> MIXED_LYCOPOD_GAMETOPHYTE_ITEM = sharedGametophyteItem(GametophyteAppearance.MIXED_LYCOPOD);
    private static DeferredItem<SharedGametophyteItem> sharedGametophyteItem(GametophyteAppearance appearance) {
        return ITEMS.register(appearance.name().toLowerCase(java.util.Locale.ROOT) + "_gametophyte",
                () -> new SharedGametophyteItem(new Item.Properties(), appearance));
    }
    public static final DeferredItem<HorsetailPlantItem> EQUISETUM_ARVENSE_GAMETOPHYTE_ITEM = ITEMS.register("equisetum_arvense_gametophyte", () -> new HorsetailPlantItem(EQUISETUM_ARVENSE_GAMETOPHYTE.get(), new Item.Properties()));
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE_SPROUTS = BLOCKS.registerBlock("equisetum_arvense_sprouts", properties -> new EquisetumArvenseSproutsBlock(properties.mapColor(MapColor.PLANT).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<HorsetailPlantItem> EQUISETUM_ARVENSE_SPROUTS_ITEM = ITEMS.register("equisetum_arvense_sprouts", () -> new HorsetailPlantItem(EQUISETUM_ARVENSE_SPROUTS.get(), new Item.Properties()));
    public static final DeferredBlock<Block> EQUISETUM_ARVENSE_SPORE_STEM = BLOCKS.registerBlock("equisetum_arvense_spore_stem", properties -> new EquisetumArvenseSporeStemBlock(properties.mapColor(MapColor.COLOR_BROWN).noOcclusion().instabreak().sound(SoundType.GRASS)));
    public static final DeferredItem<Item> EQUISETUM_ARVENSE_SPORE_STEM_ITEM = ITEMS.register("equisetum_arvense_spore_stem", () -> new CutHorsetailItem(EQUISETUM_ARVENSE_SPORE_STEM.get(), new Item.Properties().food(new FoodProperties.Builder().nutrition(2).saturationModifier(0.0F).build()), true));
    public static final DeferredItem<Item> EQUISETUM_CONGEE = ITEMS.register("equisetum_congee", () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
            .nutrition(3).saturationModifier(5.0F / 6.0F)
            .effect(() -> new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 30 * 20, 0), 1.0F).build())));

    // Collection and reproductive-material containers. Behavior is added through SporeHarvestAccess.
    public static final DeferredBlock<FossilSlicerBlock> FOSSIL_SLICER = BLOCKS.registerBlock("fossil_slicer",
            properties -> new FossilSlicerBlock(properties.strength(3.5F).sound(SoundType.METAL)));
    public static final DeferredItem<BlockItem> FOSSIL_SLICER_ITEM = ITEMS.registerSimpleBlockItem("fossil_slicer", FOSSIL_SLICER);
    public static final DeferredItem<FossilSliceItem> FOSSIL_SLICE = ITEMS.register("fossil_slice", () -> new FossilSliceItem(new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FossilSlicerBlockEntity>> FOSSIL_SLICER_ENTITY =
            BLOCK_ENTITIES.register("fossil_slicer", () -> BlockEntityType.Builder.of(FossilSlicerBlockEntity::new, FOSSIL_SLICER.get()).build(null));
    public static final DeferredBlock<DisplayCabinetBlock> DISPLAY_CABINET = BLOCKS.registerBlock("display_cabinet",
            properties -> new DisplayCabinetBlock(properties.strength(1.5F).sound(SoundType.GLASS).noOcclusion()
                    .isViewBlocking((state, level, pos) -> false).isSuffocating((state, level, pos) -> false)));
    public static final DeferredItem<BlockItem> DISPLAY_CABINET_ITEM = ITEMS.registerSimpleBlockItem("display_cabinet", DISPLAY_CABINET);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisplayCabinetBlockEntity>> DISPLAY_CABINET_ENTITY =
            BLOCK_ENTITIES.register("display_cabinet", () -> BlockEntityType.Builder.of(DisplayCabinetBlockEntity::new, DISPLAY_CABINET.get()).build(null));
    public static final DeferredItem<Item> EMPTY_SPORE_BAG = ITEMS.registerSimpleItem("empty_spore_bag");
    public static final DeferredBlock<LargeLeafMossBlock> LARGE_LEAF_MOSS = BLOCKS.registerBlock("large_leaf_moss",
            properties -> new LargeLeafMossBlock(properties.noCollission().instabreak().sound(SoundType.MOSS)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    public static final DeferredItem<BlockItem> LARGE_LEAF_MOSS_ITEM = ITEMS.registerSimpleBlockItem("large_leaf_moss", LARGE_LEAF_MOSS);
    public static final DeferredItem<HandheldItem> GAME_START = ITEMS.register("game_start", () -> new HandheldItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<ShelterCompassItem> SHELTER_COMPASS = ITEMS.register("shelter_compass",
            () -> new ShelterCompassItem(new Item.Properties().stacksTo(1), false));
    public static final DeferredItem<ShelterCompassItem> SKYLIGHT_SHELTER_COMPASS = ITEMS.register("skylight_shelter_compass",
            () -> new ShelterCompassItem(new Item.Properties().stacksTo(1), true));
    public static final DeferredItem<SporeBagItem> SPORE_BAG = ITEMS.register("spore_bag", () -> new SporeBagItem(new Item.Properties()));
    public static final DeferredItem<Item> EMPTY_POLLEN_BAG = ITEMS.registerSimpleItem("empty_pollen_bag");
    public static final DeferredItem<PollenBagItem> POLLEN_BAG = ITEMS.register("pollen_bag", () -> new PollenBagItem(new Item.Properties()));
    public static final DeferredBlock<CollectorBlock> COLLECTOR_BLOCK = BLOCKS.registerBlock("portable_collector",
            properties -> new CollectorBlock(properties.strength(2.5F).sound(SoundType.WOOD)));
    public static final DeferredBlock<CollectorBlock> COLLECTOR_PLUS_BLOCK = BLOCKS.registerBlock("portable_collector_plus",
            properties -> new CollectorBlock(properties.strength(2.5F).sound(SoundType.WOOD)));
    public static final DeferredItem<BlockItem> PORTABLE_COLLECTOR = ITEMS.register("portable_collector",
            () -> new CollectorBlockItem(COLLECTOR_BLOCK.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> PORTABLE_COLLECTOR_PLUS = ITEMS.register("portable_collector_plus",
            () -> new CollectorBlockItem(COLLECTOR_PLUS_BLOCK.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CollectorBlockEntity>> COLLECTOR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("portable_collector", () -> BlockEntityType.Builder.of(CollectorBlockEntity::new,
                    COLLECTOR_BLOCK.get(), COLLECTOR_PLUS_BLOCK.get()).build(null));

    public static final DeferredBlock<CultivationCabinetBlock> CULTIVATION_CABINET = BLOCKS.registerBlock("horizontal_light_cultivation_cabinet",
            properties -> new CultivationCabinetBlock(properties.strength(2.5F).sound(SoundType.METAL)
                    .lightLevel(state -> CultivationRules.LIGHT_LEVEL)));
    public static final DeferredItem<BlockItem> CULTIVATION_CABINET_ITEM = ITEMS.register("horizontal_light_cultivation_cabinet",
            () -> new BlockItem(CULTIVATION_CABINET.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CultivationCabinetBlockEntity>> CULTIVATION_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("horizontal_light_cultivation_cabinet", () -> BlockEntityType.Builder.of(
                    CultivationCabinetBlockEntity::new, CULTIVATION_CABINET.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> LIFE_SANCTUARY_TAB = CREATIVE_MODE_TABS.register("life_sanctuary", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.life_sanctuary")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> PEAT_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
            output.accept(PEAT_ITEM);
            output.accept(PEAT_FARMLAND_ITEM);
            output.accept(EQUISETUM_ARVENSE_ITEM);
            output.accept(DEAD_EQUISETALES_ITEM);
            output.accept(EQUISETUM_ARVENSE_GAMETOPHYTE_ITEM);
            output.accept(EQUISETUM_ARVENSE_SPROUTS_ITEM);
            output.accept(EQUISETUM_ARVENSE_SPORE_STEM_ITEM);
            output.accept(EQUISETUM_CONGEE);
            output.accept(FOSSIL_SLICER_ITEM);
            output.accept(DISPLAY_CABINET_ITEM);
            output.accept(SHELTER_COMPASS);
            output.accept(SKYLIGHT_SHELTER_COMPASS);
            output.accept(GAME_START);
            output.accept(LARGE_LEAF_MOSS_ITEM);
            output.accept(EMPTY_SPORE_BAG);
            output.accept(SPORE_BAG);
            output.accept(EMPTY_POLLEN_BAG);
            output.accept(POLLEN_BAG);
            output.accept(PORTABLE_COLLECTOR);
            output.accept(PORTABLE_COLLECTOR_PLUS);
            output.accept(CULTIVATION_CABINET_ITEM);
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public LifeSanctuaryMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerCollectorNetworking);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        FEATURES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
        MENUS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (LifeSanctuaryMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new NaturalPopulationGenerationHandler());
        NeoForge.EVENT_BUS.register(new PlantWaterTickHandler());
        NeoForge.EVENT_BUS.register(new PlantGrowthTickHandler());
        NeoForge.EVENT_BUS.register(new PeatCropHarvestHandler());
        NeoForge.EVENT_BUS.register(new CollectorPickupHandler());
        NeoForge.EVENT_BUS.register(new CollectorAdvancementHandler());
        NeoForge.EVENT_BUS.register(new CooksoniaLifecycle());
        NeoForge.EVENT_BUS.register(new PlantMerchantSpawns());
        NeoForge.EVENT_BUS.register(new ShelterDiscovery());
        NeoForge.EVENT_BUS.register(new HorsetailStemInteraction());
        NeoForge.EVENT_BUS.addListener(CultivationCabinetCommands::register);
        NeoForge.EVENT_BUS.addListener(GametophyteCommands::register);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, FOSSIL_SLICER_ENTITY.get(), (machine, side) -> machine.itemHandler(side));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CULTIVATION_BLOCK_ENTITY.get(),
                (cabinet, side) -> side == net.minecraft.core.Direction.UP ? cabinet.inputHandler
                        : side == net.minecraft.core.Direction.DOWN ? cabinet.outputHandler : null);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, COLLECTOR_BLOCK_ENTITY.get(),
                (collector, side) -> collector.itemHandler);
        event.registerItem(Capabilities.ItemHandler.ITEM,
                (stack, context) -> new CollectorItemHandler(stack),
                PORTABLE_COLLECTOR.get(), PORTABLE_COLLECTOR_PLUS.get());
    }

    private void registerCollectorNetworking(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(OpenCollectorPayload.TYPE, OpenCollectorPayload.STREAM_CODEC,
                OpenCollectorPayload::handle);
        event.registrar("1").playToServer(OpenCollectorFilterPayload.TYPE, OpenCollectorFilterPayload.STREAM_CODEC,
                OpenCollectorFilterPayload::handle);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            SharedGametophyteBlocks.register(GametophyteAppearance.SPHENOPHYTE, SPHENOPHYTE_GAMETOPHYTE.get());
            SharedGametophyteBlocks.register(GametophyteAppearance.FERN, FERN_GAMETOPHYTE.get());
            SharedGametophyteBlocks.register(GametophyteAppearance.HOMOSPOROUS_LYCOPOD, HOMOSPOROUS_LYCOPOD_GAMETOPHYTE.get());
            SharedGametophyteBlocks.register(GametophyteAppearance.FEMALE_LYCOPOD, FEMALE_LYCOPOD_GAMETOPHYTE.get());
            SharedGametophyteBlocks.register(GametophyteAppearance.MALE_LYCOPOD, MALE_LYCOPOD_GAMETOPHYTE.get());
            SharedGametophyteBlocks.register(GametophyteAppearance.MIXED_LYCOPOD, MIXED_LYCOPOD_GAMETOPHYTE.get());
        });
        event.enqueueWork(LargeLeafMossRules::setup);
        event.enqueueWork(CycadGrowthProfile.COMMON::registerDefinition);
        event.enqueueWork(TansachanaCycad.PROFILE::registerDefinition);
        event.enqueueWork(() -> PlantSporeTraits.register(PlantSpeciesRegistry.HORSETAIL.id(),
                new PlantSporeTraits.Profile(PlantSporeTraits.Group.SPHENOPHYTE, false)));
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

    }

    // Add Life Sanctuary items to the building blocks tab.
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(PEAT_ITEM);
            event.accept(PEAT_FARMLAND_ITEM);
            event.accept(EQUISETUM_ARVENSE_ITEM);
            event.accept(DEAD_EQUISETALES_ITEM);
            event.accept(EQUISETUM_ARVENSE_GAMETOPHYTE_ITEM);
            event.accept(EQUISETUM_ARVENSE_SPROUTS_ITEM);
            event.accept(EQUISETUM_ARVENSE_SPORE_STEM_ITEM);
            event.accept(EQUISETUM_CONGEE);
            event.accept(EMPTY_SPORE_BAG);
            event.accept(SPORE_BAG);
            event.accept(EMPTY_POLLEN_BAG);
            event.accept(POLLEN_BAG);
            event.accept(PORTABLE_COLLECTOR);
            event.accept(PORTABLE_COLLECTOR_PLUS);
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
