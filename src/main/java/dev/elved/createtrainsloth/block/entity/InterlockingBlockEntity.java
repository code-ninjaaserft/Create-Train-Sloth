package dev.elved.createtrainsloth.block.entity;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class InterlockingBlockEntity extends BlockEntity {

    public InterlockingBlockEntity(BlockPos pos, BlockState blockState) {
        super(TrainSlothRegistries.INTERLOCKING_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, InterlockingBlockEntity blockEntity) {
        if (level == null || level.isClientSide()) {
            return;
        }

        if (CreateTrainSlothMod.runtime().interlockingControlService() != null) {
            CreateTrainSlothMod.runtime().interlockingControlService().heartbeat(level, pos);
        }
    }
}
