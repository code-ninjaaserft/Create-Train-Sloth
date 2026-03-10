package dev.elved.createtrainsloth.block.entity;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSchematicBuilder;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSchematicSnapshot;
import dev.elved.createtrainsloth.menu.StellwerkMenu;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
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
import java.util.LinkedHashSet;
import java.util.Set;

public class InterlockingBlockEntity extends BlockEntity implements MenuProvider {

    private static final long SNAPSHOT_INTERVAL_TICKS = 20L;
    private static final String TAG_AUTO_ROUTING = "AutoRoutingEnabled";
    private static final String TAG_LOCKED_SECTIONS = "LockedSections";
    private static final String TAG_SNAPSHOT = "SchematicSnapshot";

    private final Set<String> lockedSectionIds = new LinkedHashSet<>();
    private final StellwerkSchematicBuilder schematicBuilder = new StellwerkSchematicBuilder();
    private StellwerkSchematicSnapshot schematicSnapshot = StellwerkSchematicSnapshot.empty();
    private boolean autoRoutingEnabled = true;
    private long lastSnapshotTick = Long.MIN_VALUE;

    public InterlockingBlockEntity(BlockPos pos, BlockState blockState) {
        super(TrainSlothRegistries.INTERLOCKING_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, InterlockingBlockEntity blockEntity) {
        if (level == null || level.isClientSide()) {
            return;
        }

        if (blockEntity.refreshSchematicIfDue(level, false)) {
            blockEntity.setChangedAndSync();
        }
        if (CreateTrainSlothMod.runtime().interlockingControlService() != null) {
            CreateTrainSlothMod.runtime().interlockingControlService()
                .heartbeat(level, pos, blockEntity.autoRoutingEnabled, Set.copyOf(blockEntity.lockedSectionIds));
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.create_train_sloth.interlocking_block");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new StellwerkMenu(containerId, playerInventory, this);
    }

    public StellwerkSchematicSnapshot snapshot() {
        return schematicSnapshot;
    }

    public boolean autoRoutingEnabled() {
        return autoRoutingEnabled;
    }

    public void toggleAutoRouting() {
        autoRoutingEnabled = !autoRoutingEnabled;
        markUpdated();
    }

    public boolean toggleLockForSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= schematicSnapshot.sections().size()) {
            return false;
        }

        String sectionId = schematicSnapshot.sections().get(sectionIndex).id();
        if (sectionId == null || sectionId.isBlank()) {
            return false;
        }

        if (lockedSectionIds.contains(sectionId)) {
            lockedSectionIds.remove(sectionId);
        } else {
            lockedSectionIds.add(sectionId);
        }
        markUpdated();
        return true;
    }

    public boolean unlockSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= schematicSnapshot.sections().size()) {
            return false;
        }

        String sectionId = schematicSnapshot.sections().get(sectionIndex).id();
        if (!lockedSectionIds.remove(sectionId)) {
            return false;
        }

        markUpdated();
        return true;
    }

    public Set<String> lockedSectionIds() {
        return Set.copyOf(lockedSectionIds);
    }

    private boolean refreshSchematicIfDue(Level level, boolean force) {
        if (!force && level.getGameTime() - lastSnapshotTick < SNAPSHOT_INTERVAL_TICKS) {
            return false;
        }
        schematicSnapshot = schematicBuilder.build(level, lockedSectionIds());
        lastSnapshotTick = level.getGameTime();
        return true;
    }

    private void markUpdated() {
        if (level != null && !level.isClientSide()) {
            refreshSchematicIfDue(level, true);
        }

        setChangedAndSync();
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
        tag.putBoolean(TAG_AUTO_ROUTING, autoRoutingEnabled);
        ListTag locked = new ListTag();
        for (String section : lockedSectionIds) {
            locked.add(StringTag.valueOf(section));
        }
        tag.put(TAG_LOCKED_SECTIONS, locked);
        tag.put(TAG_SNAPSHOT, schematicSnapshot.toTag());
    }

    private void readSyncData(CompoundTag tag) {
        autoRoutingEnabled = !tag.contains(TAG_AUTO_ROUTING) || tag.getBoolean(TAG_AUTO_ROUTING);
        lockedSectionIds.clear();
        for (Tag element : tag.getList(TAG_LOCKED_SECTIONS, Tag.TAG_STRING)) {
            lockedSectionIds.add(element.getAsString());
        }

        if (tag.contains(TAG_SNAPSHOT, Tag.TAG_COMPOUND)) {
            schematicSnapshot = StellwerkSchematicSnapshot.fromTag(tag.getCompound(TAG_SNAPSHOT));
        } else {
            schematicSnapshot = StellwerkSchematicSnapshot.empty();
        }
    }
}
