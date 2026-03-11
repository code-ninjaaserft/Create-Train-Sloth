package dev.elved.createtrainsloth.station;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class StationLinkKeyUtil {

    private StationLinkKeyUtil() {
    }

    public static String encode(ResourceLocation dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return "";
        }
        return dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Nullable
    public static LinkLocation decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2) {
            return null;
        }

        ResourceLocation dimension = ResourceLocation.tryParse(parts[0]);
        if (dimension == null) {
            return null;
        }

        String[] coords = parts[1].split(",", 3);
        if (coords.length != 3) {
            return null;
        }

        try {
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int z = Integer.parseInt(coords[2]);
            return new LinkLocation(dimension, new BlockPos(x, y, z));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record LinkLocation(ResourceLocation dimension, BlockPos pos) {
    }
}
