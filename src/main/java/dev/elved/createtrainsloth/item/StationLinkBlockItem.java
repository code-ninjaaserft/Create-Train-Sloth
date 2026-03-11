package dev.elved.createtrainsloth.item;

import com.simibubi.create.content.trains.station.StationBlock;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.StationLinkBlock;
import dev.elved.createtrainsloth.block.entity.StationLinkBlockEntity;
import dev.elved.createtrainsloth.block.StationHubBlock;
import dev.elved.createtrainsloth.block.entity.StationHubBlockEntity;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationHubLocator;
import dev.elved.createtrainsloth.station.StationLinkKeyUtil;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public class StationLinkBlockItem extends BlockItem {

    private static final String TAG_HUB_POS = "HubPos";
    private static final String TAG_HUB_DIMENSION = "HubDimension";

    public StationLinkBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public boolean isFoil(@NotNull ItemStack stack) {
        return hasHubBinding(stack);
    }

    @Override
    public @NotNull InteractionResult onItemUseFirst(@NotNull ItemStack stack, UseOnContext context) {
        InteractionResult result = useOn(context);
        return result == InteractionResult.PASS ? InteractionResult.PASS : result;
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        Block clickedBlock = level.getBlockState(clickedPos).getBlock();

        if (player == null) {
            return InteractionResult.FAIL;
        }

        if (clickedBlock instanceof StationHubBlock && player.isShiftKeyDown()) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            setHubBinding(stack, clickedPos, level.dimension().location());
            StationHubId hubId = StationHubLocator.idFor(level.dimension(), clickedPos);
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.hub_selected", hubId.value()),
                true
            );
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()
            && hasHubBinding(stack)
            && !(clickedBlock instanceof StationHubBlock)
            && !(clickedBlock instanceof StationBlock)) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            clearHubBinding(stack);
            player.displayClientMessage(Component.translatable("create_train_sloth.station_link.cleared"), true);
            return InteractionResult.SUCCESS;
        }

        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        BlockState intendedState = getBlock().getStateForPlacement(placeContext);
        if (!(intendedState != null && intendedState.getBlock() instanceof StationLinkBlock)) {
            return InteractionResult.PASS;
        }

        if (!hasHubBinding(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("create_train_sloth.station_link.no_hub").withStyle(ChatFormatting.RED),
                    true
                );
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos hubPos = readHubPos(stack);
        ResourceLocation hubDimension = readHubDimension(stack);
        if (hubPos == null || hubDimension == null) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("create_train_sloth.station_link.no_hub").withStyle(ChatFormatting.RED),
                    true
                );
            }
            return InteractionResult.SUCCESS;
        }

        ResourceLocation currentDimension = level.dimension().location();
        if (!currentDimension.equals(hubDimension)) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("create_train_sloth.station_link.dimension_mismatch", hubDimension, currentDimension)
                        .withStyle(ChatFormatting.RED),
                    true
                );
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos placedPos = placeContext.getClickedPos();
        if (!isStationPlacement(level, placedPos, intendedState)) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("create_train_sloth.station_link.station_invalid").withStyle(ChatFormatting.RED),
                    true
                );
            }
            return InteractionResult.SUCCESS;
        }

        InteractionResult placeResult = super.useOn(context);
        if (!placeResult.consumesAction()) {
            return placeResult;
        }
        if (level.isClientSide) {
            return placeResult;
        }

        BlockState placedState = level.getBlockState(placedPos);
        StationBlockEntity stationBlockEntity = resolveStation(level, placedPos, placedState);
        if (stationBlockEntity == null || stationBlockEntity.getStation() == null || stationBlockEntity.getStation().name == null) {
            level.destroyBlock(placedPos, true, player);
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.station_unresolved").withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.SUCCESS;
        }

        if (!(level.getBlockEntity(placedPos) instanceof StationLinkBlockEntity stationLinkBlockEntity)) {
            level.destroyBlock(placedPos, true, player);
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.station_invalid").withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.SUCCESS;
        }

        if (CreateTrainSlothMod.runtime().stationHubRegistry() == null) {
            level.destroyBlock(placedPos, true, player);
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.runtime_not_ready").withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.SUCCESS;
        }

        StationHubId hubId = StationHubLocator.idFor(hubDimension, hubPos);
        Optional<StationHub> existingHub = CreateTrainSlothMod.runtime().stationHubRegistry().findHub(hubId);
        if (existingHub.isEmpty()) {
            CreateTrainSlothMod.runtime().stationHubRegistry()
                .createHub(hubId, StationHubLocator.displayNameFor(hubPos));
        }

        String stationName = stationBlockEntity.getStation().name;
        boolean added = CreateTrainSlothMod.runtime().stationHubRegistry().addPlatform(hubId, stationName);
        String linkKey = StationLinkKeyUtil.encode(level.dimension().location(), placedPos);
        CreateTrainSlothMod.runtime().stationHubRegistry().registerStationLink(hubId, stationName, linkKey);
        stationLinkBlockEntity.bind(hubId.value(), stationName);

        if (level.getBlockEntity(hubPos) instanceof StationHubBlockEntity stationHubBlockEntity) {
            stationHubBlockEntity.refreshNow();
        }

        player.displayClientMessage(
            added
                ? Component.translatable("create_train_sloth.station_link.linked", stationName, hubId.value())
                : Component.translatable("create_train_sloth.station_link.already_linked", stationName, hubId.value()),
            true
        );
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(
        @NotNull ItemStack stack,
        @NotNull TooltipContext context,
        @NotNull List<Component> tooltipComponents,
        @NotNull TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        if (!hasHubBinding(stack)) {
            tooltipComponents.add(
                Component.translatable("create_train_sloth.station_link.tooltip.empty")
                    .withStyle(ChatFormatting.GRAY)
            );
            return;
        }

        BlockPos hubPos = readHubPos(stack);
        ResourceLocation hubDim = readHubDimension(stack);
        if (hubPos == null || hubDim == null) {
            return;
        }

        tooltipComponents.add(
            Component.translatable("create_train_sloth.station_link.tooltip.bound")
                .withStyle(ChatFormatting.GOLD)
        );
        tooltipComponents.add(
            Component.literal(hubDim + " @ " + hubPos.getX() + ", " + hubPos.getY() + ", " + hubPos.getZ())
                .withStyle(ChatFormatting.GRAY)
        );
        tooltipComponents.add(
            Component.translatable("create_train_sloth.station_link.tooltip.clear")
                .withStyle(ChatFormatting.DARK_GRAY)
        );
    }

    private static void setHubBinding(ItemStack stack, BlockPos pos, ResourceLocation dimension) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.put(TAG_HUB_POS, NbtUtils.writeBlockPos(pos));
        tag.putString(TAG_HUB_DIMENSION, dimension.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearHubBinding(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(TAG_HUB_POS);
        tag.remove(TAG_HUB_DIMENSION);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    private static boolean hasHubBinding(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_HUB_POS) && tag.contains(TAG_HUB_DIMENSION);
    }

    private static BlockPos readHubPos(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(TAG_HUB_POS)) {
            return null;
        }
        return NbtUtils.readBlockPos(tag, TAG_HUB_POS).orElse(null);
    }

    private static ResourceLocation readHubDimension(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(TAG_HUB_DIMENSION)) {
            return null;
        }
        return ResourceLocation.tryParse(tag.getString(TAG_HUB_DIMENSION));
    }

    private static boolean isStationPlacement(Level level, BlockPos placePos, BlockState placedState) {
        return resolveStation(level, placePos, placedState) != null;
    }

    private static StationBlockEntity resolveStation(Level level, BlockPos placePos, BlockState placedState) {
        if (!(placedState.getBlock() instanceof StationLinkBlock)) {
            return null;
        }

        BlockPos supportPos = placePos.relative(placedState.getValue(StationLinkBlock.FACING));
        if (!(level.getBlockState(supportPos).getBlock() instanceof StationBlock)) {
            return null;
        }
        if (!(level.getBlockEntity(supportPos) instanceof StationBlockEntity stationBlockEntity)) {
            return null;
        }
        return stationBlockEntity;
    }
}
