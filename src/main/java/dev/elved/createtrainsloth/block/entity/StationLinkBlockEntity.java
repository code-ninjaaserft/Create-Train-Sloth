package dev.elved.createtrainsloth.block.entity;

import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class StationLinkBlockEntity extends BlockEntity {

    private static final String TAG_HUB_ID = "HubId";
    private static final String TAG_STATION_NAME = "StationName";

    private String hubId = "";
    private String stationName = "";

    public StationLinkBlockEntity(BlockPos pos, BlockState blockState) {
        super(TrainSlothRegistries.STATION_LINK_BLOCK_ENTITY.get(), pos, blockState);
    }

    public void bind(String hubIdValue, String stationNameValue) {
        hubId = hubIdValue == null ? "" : hubIdValue.trim();
        stationName = stationNameValue == null ? "" : stationNameValue.trim().toLowerCase();
        setChangedAndSync();
    }

    public boolean hasBinding() {
        return !hubId.isBlank() && !stationName.isBlank();
    }

    public String hubId() {
        return hubId;
    }

    public String stationName() {
        return stationName;
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_HUB_ID, hubId);
        tag.putString(TAG_STATION_NAME, stationName);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        hubId = tag.getString(TAG_HUB_ID);
        stationName = tag.getString(TAG_STATION_NAME);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString(TAG_HUB_ID, hubId);
        tag.putString(TAG_STATION_NAME, stationName);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        hubId = tag.getString(TAG_HUB_ID);
        stationName = tag.getString(TAG_STATION_NAME);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
