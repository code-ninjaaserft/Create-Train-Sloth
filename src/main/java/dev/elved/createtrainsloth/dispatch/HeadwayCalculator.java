package dev.elved.createtrainsloth.dispatch;

import com.simibubi.create.content.trains.entity.Train;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.line.LineRuntimeState;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.Collection;

public class HeadwayCalculator {

    public int calculateTargetHeadwayTicks(TrainLine line, LineRuntimeState state, int assignedTrainCount, Collection<Train> activeLineTrains) {
        int trainCount = Math.max(1, assignedTrainCount);
        int minimumInterval = line.settings().resolveMinimumIntervalTicks();
        int override = line.settings().resolveTargetIntervalOverrideTicks();
        if (override > 0) {
            return Math.max(minimumInterval, override);
        }

        double estimatedRoundTrip = estimateRoundTripTicks(line, state, activeLineTrains);
        int computed = (int) Math.round(estimatedRoundTrip / trainCount) + line.settings().resolveSafetyBufferTicks();
        return Math.max(minimumInterval, computed);
    }

    private double estimateRoundTripTicks(TrainLine line, LineRuntimeState state, Collection<Train> activeLineTrains) {
        double createPredictionEstimate = estimateFromCreateSchedulePredictions(line, activeLineTrains);
        if (createPredictionEstimate > 0D) {
            return createPredictionEstimate;
        }

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

    private double estimateFromCreateSchedulePredictions(TrainLine line, Collection<Train> activeLineTrains) {
        if (activeLineTrains == null || activeLineTrains.isEmpty()) {
            return -1D;
        }

        long totalTransit = 0L;
        int contributors = 0;
        for (Train train : activeLineTrains) {
            if (train.runtime == null || train.runtime.predictionTicks == null || train.runtime.predictionTicks.isEmpty()) {
                continue;
            }

            int predictedTransit = 0;
            for (int ticks : train.runtime.predictionTicks) {
                if (ticks > 0) {
                    predictedTransit += ticks;
                }
            }

            if (predictedTransit <= 0) {
                continue;
            }

            totalTransit += predictedTransit;
            contributors++;
        }

        if (contributors == 0) {
            return -1D;
        }

        int stationFactor = Math.max(1, line.stationCount());
        int dwellPerStation = line.settings().resolveMinimumDwellTicks();
        double meanTransit = (double) totalTransit / contributors;
        return meanTransit + (double) dwellPerStation * stationFactor;
    }
}
