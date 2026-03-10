package dev.elved.createtrainsloth.menu;

import dev.elved.createtrainsloth.block.entity.InterlockingBlockEntity;
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

public class StellwerkMenu extends AbstractContainerMenu {

    public static final int BUTTON_TOGGLE_AUTOROUTING = 0;
    public static final int BUTTON_LOCK_SECTION_BASE = 1_000;
    public static final int BUTTON_UNLOCK_SECTION_BASE = 2_000;
    public static final int BUTTON_SECTION_INDEX_LIMIT = 9_999;

    private final BlockPos blockPos;
    private final Level level;
    @Nullable
    private final InterlockingBlockEntity blockEntity;
    private final ContainerData data;

    public StellwerkMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public StellwerkMenu(int containerId, Inventory inventory, InterlockingBlockEntity blockEntity) {
        this(containerId, inventory, blockEntity.getBlockPos(), blockEntity);
    }

    private StellwerkMenu(int containerId, Inventory inventory, BlockPos blockPos) {
        this(containerId, inventory, blockPos, resolveBlockEntity(inventory.player.level(), blockPos));
    }

    private StellwerkMenu(
        int containerId,
        Inventory inventory,
        BlockPos blockPos,
        @Nullable InterlockingBlockEntity blockEntity
    ) {
        super(TrainSlothRegistries.STELLWERK_MENU.get(), containerId);
        this.blockPos = blockPos;
        this.level = inventory.player.level();
        this.blockEntity = blockEntity;
        this.data = new ContainerData() {
            @Override
            public int get(int index) {
                if (StellwerkMenu.this.blockEntity == null) {
                    return 0;
                }
                return switch (index) {
                    case 0 -> StellwerkMenu.this.blockEntity.autoRoutingEnabled() ? 1 : 0;
                    case 1 -> StellwerkMenu.this.blockEntity.snapshot().nodes().size();
                    case 2 -> StellwerkMenu.this.blockEntity.snapshot().sections().size();
                    case 3 -> countSectionsWithState("FREE");
                    case 4 -> countSectionsWithState("RESERVED");
                    case 5 -> countSectionsWithState("OCCUPIED");
                    case 6 -> countSectionsWithState("BLOCKED");
                    case 7 -> StellwerkMenu.this.blockEntity.snapshot().trains().size();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 8;
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

        if (id == BUTTON_TOGGLE_AUTOROUTING) {
            blockEntity.toggleAutoRouting();
            return true;
        }

        if (id >= BUTTON_LOCK_SECTION_BASE && id <= BUTTON_LOCK_SECTION_BASE + BUTTON_SECTION_INDEX_LIMIT) {
            return blockEntity.toggleLockForSection(id - BUTTON_LOCK_SECTION_BASE);
        }

        if (id >= BUTTON_UNLOCK_SECTION_BASE && id <= BUTTON_UNLOCK_SECTION_BASE + BUTTON_SECTION_INDEX_LIMIT) {
            return blockEntity.unlockSection(id - BUTTON_UNLOCK_SECTION_BASE);
        }

        return false;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    @Nullable
    public InterlockingBlockEntity blockEntity() {
        return blockEntity;
    }

    public boolean autoRoutingEnabled() {
        return data.get(0) > 0;
    }

    public int nodeCount() {
        return data.get(1);
    }

    public int sectionCount() {
        return data.get(2);
    }

    public int freeCount() {
        return data.get(3);
    }

    public int reservedCount() {
        return data.get(4);
    }

    public int occupiedCount() {
        return data.get(5);
    }

    public int blockedCount() {
        return data.get(6);
    }

    public int trainCount() {
        return data.get(7);
    }

    @Nullable
    private static InterlockingBlockEntity resolveBlockEntity(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof InterlockingBlockEntity interlockingBlockEntity) {
            return interlockingBlockEntity;
        }
        return null;
    }

    private int countSectionsWithState(String stateName) {
        if (blockEntity == null) {
            return 0;
        }
        int count = 0;
        for (var section : blockEntity.snapshot().sections()) {
            if (section.state().name().equals(stateName)) {
                count++;
            }
        }
        return count;
    }
}
