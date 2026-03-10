package dev.elved.createtrainsloth.block;

import com.mojang.serialization.MapCodec;
import dev.elved.createtrainsloth.block.entity.InterlockingBlockEntity;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class InterlockingBlock extends BaseEntityBlock {

    public static final MapCodec<InterlockingBlock> CODEC = simpleCodec(InterlockingBlock::new);

    public InterlockingBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InterlockingBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, TrainSlothRegistries.INTERLOCKING_BLOCK_ENTITY.get(), InterlockingBlockEntity::serverTick);
    }
}
