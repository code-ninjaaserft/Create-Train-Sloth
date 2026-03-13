package dev.elved.createtrainsloth.menu;

import dev.elved.createtrainsloth.block.entity.LineManagerComputerBlockEntity;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class LineManagerComputerMenu extends AbstractContainerMenu {

    public static final int BUTTON_GENERATE_LINES = 0;
    public static final int BUTTON_LINE_PREV = 1;
    public static final int BUTTON_LINE_NEXT = 2;
    public static final int BUTTON_TOGGLE_MANUAL_TRAIN_COUNT = 3;
    public static final int BUTTON_MANUAL_TRAIN_COUNT_DEC = 4;
    public static final int BUTTON_MANUAL_TRAIN_COUNT_INC = 5;

    private final BlockPos blockPos;
    private final Level level;
    @Nullable
    private final LineManagerComputerBlockEntity blockEntity;
    private final ContainerData data;

    public LineManagerComputerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public LineManagerComputerMenu(int containerId, Inventory inventory, LineManagerComputerBlockEntity blockEntity) {
        this(containerId, inventory, blockEntity.getBlockPos(), blockEntity);
    }

    private LineManagerComputerMenu(int containerId, Inventory inventory, BlockPos blockPos) {
        this(containerId, inventory, blockPos, resolveBlockEntity(inventory.player.level(), blockPos));
    }

    private LineManagerComputerMenu(
        int containerId,
        Inventory inventory,
        BlockPos blockPos,
        @Nullable LineManagerComputerBlockEntity blockEntity
    ) {
        super(TrainSlothRegistries.LINE_MANAGER_COMPUTER_MENU.get(), containerId);
        this.blockPos = blockPos;
        this.level = inventory.player.level();
        this.blockEntity = blockEntity;
        this.data = new ContainerData() {
            @Override
            public int get(int index) {
                if (LineManagerComputerMenu.this.blockEntity == null) {
                    return 0;
                }
                return switch (index) {
                    case 0 -> LineManagerComputerMenu.this.blockEntity.syncedLineIds().size();
                    case 1 -> LineManagerComputerMenu.this.blockEntity.selectedLineIndex();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 2;
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

        if (id == BUTTON_GENERATE_LINES) {
            return blockEntity.generateLinesFromHubs();
        }
        if (id == BUTTON_LINE_PREV) {
            return blockEntity.cycleLineSelection(-1);
        }
        if (id == BUTTON_LINE_NEXT) {
            return blockEntity.cycleLineSelection(1);
        }
        if (id == BUTTON_TOGGLE_MANUAL_TRAIN_COUNT) {
            return blockEntity.toggleSelectedLineManualTrainCount();
        }
        if (id == BUTTON_MANUAL_TRAIN_COUNT_DEC) {
            return blockEntity.adjustSelectedLineManualTrainCount(-1);
        }
        if (id == BUTTON_MANUAL_TRAIN_COUNT_INC) {
            return blockEntity.adjustSelectedLineManualTrainCount(1);
        }
        return false;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    @Nullable
    public LineManagerComputerBlockEntity blockEntity() {
        return blockEntity;
    }

    public int lineCount() {
        return data.get(0);
    }

    public String selectedLineLabel() {
        if (blockEntity == null) {
            return "-";
        }
        return blockEntity.selectedLineLabel();
    }

    public List<String> selectedLineStations() {
        if (blockEntity == null) {
            return List.of();
        }
        return blockEntity.selectedLineStations();
    }

    public String selectedLineName() {
        if (blockEntity == null) {
            return "-";
        }
        return blockEntity.selectedLineName();
    }

    public String selectedServiceClass() {
        if (blockEntity == null) {
            return "RE";
        }
        return blockEntity.selectedServiceClass();
    }

    public int selectedLineAssignedTrainCount() {
        if (blockEntity == null) {
            return 0;
        }
        return blockEntity.selectedLineAssignedTrainCount();
    }

    public int selectedLineRecommendedTrainCount() {
        if (blockEntity == null) {
            return 0;
        }
        return blockEntity.selectedLineRecommendedTrainCount();
    }

    public int selectedLineTargetTrainCount() {
        if (blockEntity == null) {
            return 0;
        }
        return blockEntity.selectedLineTargetTrainCount();
    }

    public boolean selectedLineUsesManualTrainCount() {
        return blockEntity != null && blockEntity.selectedLineUsesManualTrainCount();
    }

    public List<String> selectedAllowedDepotHubs() {
        if (blockEntity == null) {
            return List.of();
        }
        return blockEntity.selectedAllowedDepotHubs();
    }

    @Nullable
    private static LineManagerComputerBlockEntity resolveBlockEntity(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            return lineManagerComputerBlockEntity;
        }
        return null;
    }
}
