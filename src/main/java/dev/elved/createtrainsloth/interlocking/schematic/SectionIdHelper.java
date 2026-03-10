package dev.elved.createtrainsloth.interlocking.schematic;

import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import java.util.UUID;
import net.createmod.catnip.data.Couple;

public final class SectionIdHelper {

    private SectionIdHelper() {
    }

    public static String sectionId(UUID graphId, TrackNode first, TrackNode second) {
        return sectionId(graphId, first.getLocation(), second.getLocation());
    }

    public static String sectionId(UUID graphId, Couple<TrackNode> nodes) {
        if (nodes == null || nodes.getFirst() == null || nodes.getSecond() == null) {
            return "";
        }
        return sectionId(graphId, nodes.getFirst().getLocation(), nodes.getSecond().getLocation());
    }

    public static String sectionId(UUID graphId, TrackNodeLocation first, TrackNodeLocation second) {
        String tokenA = nodeToken(first);
        String tokenB = nodeToken(second);
        if (tokenA.compareTo(tokenB) <= 0) {
            return graphId + "|" + tokenA + "|" + tokenB;
        }
        return graphId + "|" + tokenB + "|" + tokenA;
    }

    public static String nodeToken(TrackNodeLocation location) {
        return location.dimension.location() + ":"
            + location.getX() + ","
            + location.getY() + ","
            + location.getZ() + ","
            + location.yOffsetPixels;
    }
}
