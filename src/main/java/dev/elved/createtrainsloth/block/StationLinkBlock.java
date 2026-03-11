package dev.elved.createtrainsloth.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.trains.station.StationBlock;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.entity.StationLinkBlockEntity;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationLinkKeyUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class StationLinkBlock extends BaseEntityBlock implements IWrenchable {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final MapCodec<StationLinkBlock> CODEC = simpleCodec(StationLinkBlock::new);

    private static final VoxelShape FLOOR_SHAPE = box(2, 0, 2, 14, 4, 14);
    private static final VoxelShape CEILING_SHAPE = box(2, 12, 2, 14, 16, 14);
    private static final VoxelShape NORTH_SHAPE = box(2, 2, 0, 14, 14, 4);
    private static final VoxelShape SOUTH_SHAPE = box(2, 2, 12, 14, 14, 16);
    private static final VoxelShape WEST_SHAPE = box(0, 2, 2, 4, 14, 14);
    private static final VoxelShape EAST_SHAPE = box(12, 2, 2, 16, 14, 14);

    public StationLinkBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StationLinkBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction supportDirection = context.getClickedFace().getOpposite();
        BlockState candidate = defaultBlockState().setValue(FACING, supportDirection);
        return candidate.canSurvive(context.getLevel(), context.getClickedPos()) ? candidate : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction supportDirection = state.getValue(FACING);
        BlockPos supportPos = pos.relative(supportDirection);
        return level.getBlockState(supportPos).getBlock() instanceof StationBlock;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected void onRemove(
        BlockState state,
        Level level,
        BlockPos pos,
        BlockState newState,
        boolean movedByPiston
    ) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof StationLinkBlockEntity stationLinkBlockEntity
                && stationLinkBlockEntity.hasBinding()
                && CreateTrainSlothMod.runtime().stationHubRegistry() != null) {
                StationHubId hubId = new StationHubId(stationLinkBlockEntity.hubId());
                String stationName = stationLinkBlockEntity.stationName();
                String linkKey = StationLinkKeyUtil.encode(level.dimension().location(), pos);
                CreateTrainSlothMod.runtime().stationHubRegistry().unregisterStationLink(hubId, stationName, linkKey);
                if (!CreateTrainSlothMod.runtime().stationHubRegistry().hasStationLinks(hubId, stationName)) {
                    CreateTrainSlothMod.runtime().stationHubRegistry().removePlatform(hubId, stationName);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state,
        BlockGetter level,
        BlockPos pos,
        CollisionContext context
    ) {
        return switch (state.getValue(FACING)) {
            case DOWN -> FLOOR_SHAPE;
            case UP -> CEILING_SHAPE;
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
        };
    }
}
