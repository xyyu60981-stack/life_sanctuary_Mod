package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** One actual exhibit, no ticking inventory or automation capability. */
public final class DisplayCabinetBlockEntity extends BlockEntity {
    private ItemStack exhibit = ItemStack.EMPTY;
    private MapItemSavedData mapPreview;
    private byte[] mapColors;
    private byte mapScale;
    private boolean mapLocked;
    public DisplayCabinetBlockEntity(BlockPos pos, BlockState state) {
        super(LifeSanctuaryMod.DISPLAY_CABINET_ENTITY.get(), pos, state);
    }
    public ItemStack exhibit() { return exhibit; }
    public MapItemSavedData mapPreview() {
        if (mapPreview == null && mapColors != null && level != null && level.isClientSide) {
            mapPreview = MapItemSavedData.createForClient(mapScale, mapLocked, level.dimension());
            mapPreview.colors = mapColors; mapColors = null;
        }
        return mapPreview;
    }
    public boolean insert(ItemStack source, boolean creative) {
        if (level == null || level.isClientSide || !level.getServer().isSameThread() || source.isEmpty() || !exhibit.isEmpty()) return false;
        exhibit = source.copyWithCount(1);
        if (!creative) source.shrink(1);
        changed(); return true;
    }
    public ItemStack take() {
        if (level == null || level.isClientSide || !level.getServer().isSameThread()) return ItemStack.EMPTY;
        var result = exhibit; exhibit = ItemStack.EMPTY; mapPreview = null; mapColors = null;
        if (!result.isEmpty()) changed();
        return result;
    }
    private void changed() {
        setChanged(); level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!exhibit.isEmpty()) tag.put("Exhibit", exhibit.save(registries));
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        exhibit = tag.contains("Exhibit", Tag.TAG_COMPOUND) ? ItemStack.parseOptional(registries, tag.getCompound("Exhibit")) : ItemStack.EMPTY;
        mapPreview = null; mapColors = null;
        if (exhibit.has(DataComponents.MAP_ID)
                && tag.contains("MapColors", Tag.TAG_BYTE_ARRAY)) {
            var colors = tag.getByteArray("MapColors");
            if (colors.length == 128 * 128) {
                mapScale = (byte) Math.max(0, Math.min(4, tag.getByte("MapScale")));
                mapLocked = tag.getBoolean("MapLocked"); mapColors = colors;
            }
        }
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = saveCustomOnly(registries);
        if (level != null && !level.isClientSide) {
            var id = exhibit.get(DataComponents.MAP_ID);
            var map = id == null ? null : MapItem.getSavedData(id, level);
            if (map != null) {
                tag.putByteArray("MapColors", map.colors.clone()); tag.putByte("MapScale", map.scale); tag.putBoolean("MapLocked", map.locked);
            }
        }
        return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
