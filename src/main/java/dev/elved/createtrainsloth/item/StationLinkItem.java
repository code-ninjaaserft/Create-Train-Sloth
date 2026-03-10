package dev.elved.createtrainsloth.item;

import com.simibubi.create.content.trains.station.StationBlock;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.StationHubBlock;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationHubLocator;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class StationLinkItem extends Item {

    private static final String TAG_HUB_POS = "HubPos";
    private static final String TAG_HUB_DIMENSION = "HubDimension";

    public StationLinkItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(@NotNull ItemStack stack) {
        return hasHubBinding(stack);
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (player == null) {
            return InteractionResult.FAIL;
        }

        if (player.isShiftKeyDown() && hasHubBinding(stack)) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            clearHubBinding(stack);
            player.displayClientMessage(Component.translatable("create_train_sloth.station_link.cleared"), true);
            return InteractionResult.SUCCESS;
        }

        if (level.getBlockState(clickedPos).getBlock() instanceof StationHubBlock) {
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

        if (!(level.getBlockState(clickedPos).getBlock() instanceof StationBlock)) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!hasHubBinding(stack)) {
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.no_hub")
                    .withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        if (!(level.getBlockEntity(clickedPos) instanceof StationBlockEntity stationBlockEntity)) {
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.station_invalid")
                    .withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        if (stationBlockEntity.getStation() == null || stationBlockEntity.getStation().name == null) {
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.station_unresolved")
                    .withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        BlockPos hubPos = readHubPos(stack);
        ResourceLocation hubDimension = readHubDimension(stack);
        if (hubPos == null || hubDimension == null) {
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.no_hub")
                    .withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        ResourceLocation currentDimension = level.dimension().location();
        if (!currentDimension.equals(hubDimension)) {
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.dimension_mismatch", hubDimension, currentDimension)
                    .withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        if (CreateTrainSlothMod.runtime().stationHubRegistry() == null) {
            player.displayClientMessage(
                Component.translatable("create_train_sloth.station_link.runtime_not_ready")
                    .withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        StationHubId hubId = StationHubLocator.idFor(hubDimension, hubPos);
        Optional<StationHub> existingHub = CreateTrainSlothMod.runtime().stationHubRegistry().findHub(hubId);
        if (existingHub.isEmpty()) {
            CreateTrainSlothMod.runtime().stationHubRegistry()
                .createHub(hubId, StationHubLocator.displayNameFor(hubPos));
        }

        String stationName = stationBlockEntity.getStation().name;
        boolean added = CreateTrainSlothMod.runtime().stationHubRegistry().addPlatform(hubId, stationName);
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
}
