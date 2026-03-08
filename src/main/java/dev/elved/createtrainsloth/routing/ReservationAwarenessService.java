package dev.elved.createtrainsloth.routing;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import java.util.UUID;

public class ReservationAwarenessService {

    public boolean isPrimaryPathBlocked(Train train, DiscoveredPath path) {
        if (path == null) {
            return true;
        }
        return countOccupiedSignals(train, path) > 0;
    }

    public int countOccupiedSignals(Train train, DiscoveredPath path) {
        if (path == null || train.graph == null) {
            return 0;
        }

        int occupiedSignals = 0;
        if (train.navigation.waitingForSignal != null && train.navigation.waitingForSignal.getFirst() != null) {
            SignalBoundary signal = train.graph.getPoint(EdgePointType.SIGNAL, train.navigation.waitingForSignal.getFirst());
            if (signal != null && isSignalOccupied(signal)) {
                occupiedSignals++;
            }
        }

        if (!train.reservedSignalBlocks.isEmpty()) {
            occupiedSignals++;
        }

        return occupiedSignals;
    }

    public int estimateConflictComplexity(DiscoveredPath path) {
        if (path == null) {
            return Integer.MAX_VALUE / 8;
        }
        // With current public API we cannot reliably inspect each hidden branch edge.
        // We use path branch-count as a stable conflict proxy until Create adds richer hooks.
        return path.path.size();
    }

    private boolean isSignalOccupied(SignalBoundary signal) {
        UUID primary = signal.groups.getFirst();
        UUID secondary = signal.groups.getSecond();
        return isGroupOccupied(primary) || isGroupOccupied(secondary);
    }

    private boolean isGroupOccupied(UUID groupId) {
        if (groupId == null) {
            return false;
        }
        SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(groupId);
        return group != null && group.isOccupiedUnless((SignalBoundary) null);
    }
}
