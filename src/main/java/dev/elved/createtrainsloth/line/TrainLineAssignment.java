package dev.elved.createtrainsloth.line;

import java.util.UUID;

public record TrainLineAssignment(UUID trainId, LineId lineId, TrainServiceClass serviceClass) {
}
