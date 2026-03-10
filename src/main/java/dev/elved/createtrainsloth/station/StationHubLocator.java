package dev.elved.createtrainsloth.station;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class StationHubLocator {

    private StationHubLocator() {
    }

    public static StationHubId idFor(ResourceKey<Level> dimension, BlockPos pos) {
        return idFor(dimension.location(), pos);
    }

    public static StationHubId idFor(ResourceLocation dimension, BlockPos pos) {
        String dim = dimension.toString().replace(':', '_').replace('/', '_');
        return new StationHubId("hub_" + dim + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
    }

    public static String displayNameFor(BlockPos pos) {
        return "Hub " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
