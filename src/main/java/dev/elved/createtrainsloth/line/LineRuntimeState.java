package dev.elved.createtrainsloth.line;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LineRuntimeState {

    private static final double EMA_ALPHA = 0.2D;

    private long lastDepartureTick = Long.MIN_VALUE;
    private long pendingDispatchExpiryTick = Long.MIN_VALUE;
    private UUID pendingDispatchTrain;

    private double averageTravelTicks;
    private double averageDwellTicks;
    private double averageRoundTripTicks;

    private final Map<UUID, Long> lastArrivalByTrain = new HashMap<>();
    private final Map<UUID, Long> lastDepartureByTrain = new HashMap<>();
    private final Map<UUID, UUID> lastDepartureStationByTrain = new HashMap<>();

    public long lastDepartureTick() {
        return lastDepartureTick;
    }

    public boolean hasPendingDispatch(long gameTick) {
        return pendingDispatchTrain != null && gameTick <= pendingDispatchExpiryTick;
    }

    public UUID pendingDispatchTrain() {
        return pendingDispatchTrain;
    }

    public void setPendingDispatch(UUID trainId, long expiryTick) {
        pendingDispatchTrain = trainId;
        pendingDispatchExpiryTick = expiryTick;
    }

    public void clearPendingDispatchIf(UUID trainId) {
        if (pendingDispatchTrain != null && pendingDispatchTrain.equals(trainId)) {
            pendingDispatchTrain = null;
            pendingDispatchExpiryTick = Long.MIN_VALUE;
        }
    }

    public double averageTravelTicks() {
        return averageTravelTicks;
    }

    public double averageDwellTicks() {
        return averageDwellTicks;
    }

    public double averageRoundTripTicks() {
        return averageRoundTripTicks;
    }

    public void recordArrival(UUID trainId, UUID stationId, long gameTick) {
        Long lastDeparture = lastDepartureByTrain.get(trainId);
        if (lastDeparture != null && gameTick > lastDeparture) {
            averageTravelTicks = ema(averageTravelTicks, gameTick - lastDeparture);
        }
        lastArrivalByTrain.put(trainId, gameTick);
    }

    public void recordDeparture(UUID trainId, UUID stationId, long gameTick) {
        Long lastArrival = lastArrivalByTrain.get(trainId);
        if (lastArrival != null && gameTick >= lastArrival) {
            averageDwellTicks = ema(averageDwellTicks, gameTick - lastArrival);
        }

        UUID previousStation = lastDepartureStationByTrain.get(trainId);
        Long previousDeparture = lastDepartureByTrain.get(trainId);
        if (previousStation != null && previousDeparture != null && previousStation.equals(stationId) && gameTick > previousDeparture) {
            averageRoundTripTicks = ema(averageRoundTripTicks, gameTick - previousDeparture);
        }

        lastDepartureByTrain.put(trainId, gameTick);
        lastDepartureStationByTrain.put(trainId, stationId);
        lastDepartureTick = gameTick;
    }

    private double ema(double current, double sample) {
        if (current <= 0D) {
            return sample;
        }
        return current + EMA_ALPHA * (sample - current);
    }
}
