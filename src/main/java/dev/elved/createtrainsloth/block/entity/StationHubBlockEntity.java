package dev.elved.createtrainsloth.block.entity;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSectionState;
import dev.elved.createtrainsloth.menu.StationHubMenu;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationHubLocator;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class StationHubBlockEntity extends BlockEntity implements MenuProvider {

    private static final long SYNC_INTERVAL_TICKS = 20L;
    private static final String TAG_PLATFORMS = "Platforms";
    private static final String TAG_HUB_NAME = "HubName";
    private static final String TAG_SELECTED_INDEX = "SelectedIndex";
    private static final String TAG_FREE_COUNT = "FreeCount";
    private static final String TAG_SOON_FREE_COUNT = "SoonFreeCount";
    private static final String TAG_BLOCKED_COUNT = "BlockedCount";

    private final List<String> syncedPlatforms = new ArrayList<>();
    private String syncedHubName = "";
    private int selectedPlatformIndex = 0;
    private int syncedFreePlatforms = 0;
    private int syncedSoonFreePlatforms = 0;
    private int syncedBlockedPlatforms = 0;
    private long lastSyncTick = Long.MIN_VALUE;

    public StationHubBlockEntity(BlockPos pos, BlockState blockState) {
        super(TrainSlothRegistries.STATION_HUB_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, StationHubBlockEntity blockEntity) {
        if (level == null || level.isClientSide()) {
            return;
        }

        if (blockEntity.refreshHubData(level, false)) {
            blockEntity.setChangedAndSync();
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.create_train_sloth.station_hub_block");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new StationHubMenu(containerId, playerInventory, this);
    }

    public List<String> syncedPlatforms() {
        return List.copyOf(syncedPlatforms);
    }

    public String syncedHubName() {
        return syncedHubName == null || syncedHubName.isBlank() ? hubId().value() : syncedHubName;
    }

    public int selectedPlatformIndex() {
        return selectedPlatformIndex;
    }

    public int syncedFreePlatforms() {
        return syncedFreePlatforms;
    }

    public int syncedSoonFreePlatforms() {
        return syncedSoonFreePlatforms;
    }

    public int syncedBlockedPlatforms() {
        return syncedBlockedPlatforms;
    }

    public String selectedPlatformName() {
        if (syncedPlatforms.isEmpty() || selectedPlatformIndex < 0 || selectedPlatformIndex >= syncedPlatforms.size()) {
            return "-";
        }
        return syncedPlatforms.get(selectedPlatformIndex);
    }

    public boolean cyclePlatformSelection(int delta) {
        if (syncedPlatforms.isEmpty()) {
            return false;
        }
        selectedPlatformIndex = Math.floorMod(selectedPlatformIndex + delta, syncedPlatforms.size());
        setChangedAndSync();
        return true;
    }

    public boolean removeSelectedPlatform() {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String selected = selectedPlatformName();
        if ("-".equals(selected)) {
            return false;
        }

        StationHubRegistry registry = CreateTrainSlothMod.runtime().stationHubRegistry();
        if (registry == null) {
            return false;
        }

        boolean removed = registry.removePlatform(hubId(), selected);
        if (!removed) {
            return false;
        }

        refreshHubData(level, true);
        setChangedAndSync();
        return true;
    }

    public boolean renamePlatform(String oldName, String newName) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String oldValue = oldName == null ? "" : oldName.trim();
        String newValue = newName == null ? "" : newName.trim();
        if (oldValue.isBlank() || newValue.isBlank()) {
            return false;
        }

        StationHubRegistry registry = CreateTrainSlothMod.runtime().stationHubRegistry();
        if (registry == null) {
            return false;
        }

        StationHubId hubId = hubId();
        if (!registry.removePlatform(hubId, oldValue)) {
            return false;
        }

        boolean added = registry.addPlatform(hubId, newValue);
        if (!added) {
            registry.addPlatform(hubId, oldValue);
            return false;
        }

        refreshHubData(level, true);
        int renamedIndex = syncedPlatforms.indexOf(newValue.toLowerCase());
        if (renamedIndex >= 0) {
            selectedPlatformIndex = renamedIndex;
        }
        setChangedAndSync();
        return true;
    }

    public StationHubId hubId() {
        if (level == null) {
            return new StationHubId("hub_unknown");
        }
        return StationHubLocator.idFor(level.dimension(), worldPosition);
    }

    public void refreshNow() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (refreshHubData(level, true)) {
            setChangedAndSync();
        }
    }

    private boolean refreshHubData(Level level, boolean force) {
        if (!force && level.getGameTime() - lastSyncTick < SYNC_INTERVAL_TICKS) {
            return false;
        }
        lastSyncTick = level.getGameTime();

        StationHubRegistry registry = CreateTrainSlothMod.runtime().stationHubRegistry();
        if (registry == null) {
            boolean changed = !syncedPlatforms.isEmpty()
                || !syncedHubName.isBlank()
                || syncedFreePlatforms != 0
                || syncedSoonFreePlatforms != 0
                || syncedBlockedPlatforms != 0;
            syncedPlatforms.clear();
            syncedHubName = "";
            syncedFreePlatforms = 0;
            syncedSoonFreePlatforms = 0;
            syncedBlockedPlatforms = 0;
            if (clampSelection()) {
                changed = true;
            }
            return changed;
        }

        StationHubId hubId = hubId();
        StationHub hub = registry.findHub(hubId).orElseGet(() -> registry.createHub(hubId, StationHubLocator.displayNameFor(worldPosition)));

        List<String> updatedPlatforms = new ArrayList<>(hub.platformStationNames());
        updatedPlatforms.sort(Comparator.naturalOrder());
        String updatedHubName = hub.displayName();
        int updatedFree = 0;
        int updatedSoonFree = 0;
        int updatedBlocked = 0;
        for (String platform : updatedPlatforms) {
            StellwerkSectionState stationState = InterlockingBlockEntity.lookupPublishedStationState(level, platform);
            if (stationState == null || stationState == StellwerkSectionState.FREE) {
                updatedFree++;
            } else if (stationState == StellwerkSectionState.BLOCKED) {
                updatedBlocked++;
            } else {
                updatedSoonFree++;
            }
        }

        boolean changed = !syncedPlatforms.equals(updatedPlatforms)
            || !syncedHubName.equals(updatedHubName)
            || syncedFreePlatforms != updatedFree
            || syncedSoonFreePlatforms != updatedSoonFree
            || syncedBlockedPlatforms != updatedBlocked;
        if (changed) {
            syncedPlatforms.clear();
            syncedPlatforms.addAll(updatedPlatforms);
            syncedHubName = updatedHubName;
            syncedFreePlatforms = updatedFree;
            syncedSoonFreePlatforms = updatedSoonFree;
            syncedBlockedPlatforms = updatedBlocked;
        }

        if (clampSelection()) {
            changed = true;
        }

        return changed;
    }

    private boolean clampSelection() {
        int previous = selectedPlatformIndex;
        if (syncedPlatforms.isEmpty()) {
            selectedPlatformIndex = 0;
        } else {
            selectedPlatformIndex = Math.max(0, Math.min(selectedPlatformIndex, syncedPlatforms.size() - 1));
        }
        return previous != selectedPlatformIndex;
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
        writeSyncData(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readSyncData(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        writeSyncData(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        readSyncData(tag);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void writeSyncData(CompoundTag tag) {
        ListTag platformTags = new ListTag();
        for (String station : syncedPlatforms) {
            platformTags.add(StringTag.valueOf(station));
        }
        tag.put(TAG_PLATFORMS, platformTags);
        tag.putString(TAG_HUB_NAME, syncedHubName);
        tag.putInt(TAG_SELECTED_INDEX, selectedPlatformIndex);
        tag.putInt(TAG_FREE_COUNT, syncedFreePlatforms);
        tag.putInt(TAG_SOON_FREE_COUNT, syncedSoonFreePlatforms);
        tag.putInt(TAG_BLOCKED_COUNT, syncedBlockedPlatforms);
    }

    private void readSyncData(CompoundTag tag) {
        syncedPlatforms.clear();
        for (Tag element : tag.getList(TAG_PLATFORMS, Tag.TAG_STRING)) {
            syncedPlatforms.add(element.getAsString());
        }

        syncedHubName = tag.getString(TAG_HUB_NAME);
        selectedPlatformIndex = tag.getInt(TAG_SELECTED_INDEX);
        syncedFreePlatforms = tag.getInt(TAG_FREE_COUNT);
        syncedSoonFreePlatforms = tag.getInt(TAG_SOON_FREE_COUNT);
        syncedBlockedPlatforms = tag.getInt(TAG_BLOCKED_COUNT);
        clampSelection();
    }
}
