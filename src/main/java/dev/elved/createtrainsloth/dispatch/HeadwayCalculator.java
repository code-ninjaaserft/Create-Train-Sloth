package dev.elved.createtrainsloth.dispatch;

import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.line.LineRuntimeState;
import dev.elved.createtrainsloth.line.TrainLine;

public class HeadwayCalculator {

    public int calculateTargetHeadwayTicks(TrainLine line, LineRuntimeState state, int assignedTrainCount) {
        int trainCount = Math.max(1, assignedTrainCount);
        int minimumInterval = line.settings().resolveMinimumIntervalTicks();
        int override = line.settings().resolveTargetIntervalOverrideTicks();
        if (override > 0) {
            return Math.max(minimumInterval, override);
        }

        double estimatedRoundTrip = estimateRoundTripTicks(line, state);
        int computed = (int) Math.round(estimatedRoundTrip / trainCount) + line.settings().resolveSafetyBufferTicks();
        return Math.max(minimumInterval, computed);
    }

    private double estimateRoundTripTicks(TrainLine line, LineRuntimeState state) {
        if (state.averageRoundTripTicks() > 0D) {
            return state.averageRoundTripTicks();
        }

        if (state.averageTravelTicks() > 0D) {
            int stationFactor = Math.max(2, line.stationCount() <= 0 ? 2 : line.stationCount());
            double dwellEstimate = state.averageDwellTicks() > 0D ? state.averageDwellTicks() : line.settings().resolveMinimumDwellTicks();
            return state.averageTravelTicks() * stationFactor + dwellEstimate * stationFactor;
        }

        return TrainSlothConfig.DISPATCH.fallbackRoundTripTicks.get();
    }
}
