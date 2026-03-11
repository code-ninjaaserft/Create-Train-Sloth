package dev.elved.createtrainsloth.menu;

import dev.elved.createtrainsloth.block.entity.StationHubBlockEntity;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class StationHubMenu extends AbstractContainerMenu {

    public static final int BUTTON_PREVIOUS_STATION = 0;
    public static final int BUTTON_NEXT_STATION = 1;
    public static final int BUTTON_REMOVE_STATION = 2;
    public static final int BUTTON_TOGGLE_DEPOT = 3;

    private final BlockPos blockPos;
    private final Level level;
    @Nullable
    private final StationHubBlockEntity blockEntity;
    private final ContainerData data;

    public StationHubMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public StationHubMenu(int containerId, Inventory inventory, StationHubBlockEntity blockEntity) {
        this(containerId, inventory, blockEntity.getBlockPos(), blockEntity);
    }

    private StationHubMenu(int containerId, Inventory inventory, BlockPos blockPos) {
        this(containerId, inventory, blockPos, resolveBlockEntity(inventory.player.level(), blockPos));
    }

    private StationHubMenu(int containerId, Inventory inventory, BlockPos blockPos, @Nullable StationHubBlockEntity blockEntity) {
        super(TrainSlothRegistries.STATION_HUB_MENU.get(), containerId);
        this.blockPos = blockPos;
        this.level = inventory.player.level();
        this.blockEntity = blockEntity;
        this.data = new ContainerData() {
            @Override
            public int get(int index) {
                if (StationHubMenu.this.blockEntity == null) {
                    return 0;
                }
                return switch (index) {
                    case 0 -> StationHubMenu.this.blockEntity.syncedPlatforms().size();
                    case 1 -> StationHubMenu.this.blockEntity.selectedPlatformIndex();
                    case 2 -> StationHubMenu.this.blockEntity.syncedFreePlatforms();
                    case 3 -> StationHubMenu.this.blockEntity.syncedSoonFreePlatforms();
                    case 4 -> StationHubMenu.this.blockEntity.syncedBlockedPlatforms();
                    case 5 -> StationHubMenu.this.blockEntity.syncedDepotHub() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 6;
            }
        };
        addDataSlots(data);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }

        if (player.level() != level) {
            return false;
        }

        return player.distanceToSqr(
            blockPos.getX() + 0.5D,
            blockPos.getY() + 0.5D,
            blockPos.getZ() + 0.5D
        ) <= 64.0D;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (level.isClientSide || blockEntity == null) {
            return false;
        }

        if (id == BUTTON_PREVIOUS_STATION) {
            return blockEntity.cyclePlatformSelection(-1);
        }
        if (id == BUTTON_NEXT_STATION) {
            return blockEntity.cyclePlatformSelection(1);
        }
        if (id == BUTTON_REMOVE_STATION) {
            return blockEntity.removeSelectedPlatform();
        }
        if (id == BUTTON_TOGGLE_DEPOT) {
            return blockEntity.toggleDepotHub();
        }

        return false;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    @Nullable
    public StationHubBlockEntity blockEntity() {
        return blockEntity;
    }

    public int platformCount() {
        return data.get(0);
    }

    public String hubName() {
        if (blockEntity == null) {
            return "-";
        }
        return blockEntity.syncedHubName();
    }

    public String selectedPlatformName() {
        if (blockEntity == null) {
            return "-";
        }
        return blockEntity.selectedPlatformName();
    }

    public int freePlatforms() {
        return data.get(2);
    }

    public int soonFreePlatforms() {
        return data.get(3);
    }

    public int blockedPlatforms() {
        return data.get(4);
    }

    public boolean isDepotHub() {
        return data.get(5) > 0;
    }

    @Nullable
    private static StationHubBlockEntity resolveBlockEntity(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof StationHubBlockEntity stationHubBlockEntity) {
            return stationHubBlockEntity;
        }
        return null;
    }
}
