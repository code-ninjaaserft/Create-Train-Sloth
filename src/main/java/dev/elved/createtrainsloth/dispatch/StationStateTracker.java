package dev.elved.createtrainsloth.dispatch;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class StationStateTracker {

    private final Map<UUID, TrainStationSnapshot> snapshots = new HashMap<>();

    public StationTransition observe(Train train, long gameTick) {
        TrainStationSnapshot snapshot = snapshots.computeIfAbsent(train.id, ignored -> new TrainStationSnapshot());

        GlobalStation currentStation = train.getCurrentStation();
        UUID currentStationId = currentStation != null ? currentStation.id : null;
        String currentStationName = currentStation != null ? currentStation.name : null;

        UUID departedStationId = null;
        String departedStationName = null;
        UUID arrivedStationId = null;
        String arrivedStationName = null;

        if (snapshot.stationId == null && currentStationId != null) {
            snapshot.stationId = currentStationId;
            snapshot.stationName = currentStationName;
            snapshot.arrivalTick = gameTick;
            arrivedStationId = currentStationId;
            arrivedStationName = currentStationName;
        } else if (snapshot.stationId != null && currentStationId == null) {
            departedStationId = snapshot.stationId;
            departedStationName = snapshot.stationName;
            snapshot.stationId = null;
            snapshot.stationName = null;
            snapshot.lastDepartureTick = gameTick;
        } else if (snapshot.stationId != null && currentStationId != null && !snapshot.stationId.equals(currentStationId)) {
            departedStationId = snapshot.stationId;
            departedStationName = snapshot.stationName;
            arrivedStationId = currentStationId;
            arrivedStationName = currentStationName;
            snapshot.stationId = currentStationId;
            snapshot.stationName = currentStationName;
            snapshot.arrivalTick = gameTick;
        }

        return new StationTransition(departedStationId, departedStationName, arrivedStationId, arrivedStationName, gameTick);
    }

    public long arrivalTick(UUID trainId) {
        TrainStationSnapshot snapshot = snapshots.get(trainId);
        if (snapshot == null) {
            return Long.MIN_VALUE;
        }
        return snapshot.arrivalTick;
    }

    public record StationTransition(
        @Nullable UUID departedStationId,
        @Nullable String departedStationName,
        @Nullable UUID arrivedStationId,
        @Nullable String arrivedStationName,
        long tick
    ) {
    }

    private static class TrainStationSnapshot {
        @Nullable UUID stationId;
        @Nullable String stationName;
        long arrivalTick = Long.MIN_VALUE;
        long lastDepartureTick = Long.MIN_VALUE;
    }
}
