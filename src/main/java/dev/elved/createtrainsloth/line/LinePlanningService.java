package dev.elved.createtrainsloth.line;

public class LinePlanningService {

    public int recommendedTrainCount(TrainLine line, int orderedStopCount, TrainServiceClass serviceClass) {
        int stopCount = orderedStopCount > 0
            ? orderedStopCount
            : line == null
                ? 1
                : Math.max(1, line.stationCount());

        int base = Math.max(1, (int) Math.ceil(stopCount / 2.5D));
        double classFactor = switch (serviceClass == null ? TrainServiceClass.RE : serviceClass) {
            case S -> 1.35D;
            case IR -> 1.15D;
            case RE -> 1.0D;
            case IC -> 0.85D;
            case ICN -> 0.8D;
            case ICE -> 0.7D;
        };

        int recommended = (int) Math.round(base * classFactor);
        return Math.max(1, Math.min(12, recommended));
    }
}
