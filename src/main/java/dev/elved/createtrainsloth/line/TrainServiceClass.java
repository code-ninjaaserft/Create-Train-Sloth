package dev.elved.createtrainsloth.line;

import java.util.Locale;

public enum TrainServiceClass {
    S("S", 20),
    IR("IR", 35),
    RE("RE", 45),
    IC("IC", 65),
    ICN("ICN", 75),
    ICE("ICE", 90);

    private final String prefix;
    private final int priorityWeight;

    TrainServiceClass(String prefix, int priorityWeight) {
        this.prefix = prefix;
        this.priorityWeight = priorityWeight;
    }

    public String prefix() {
        return prefix;
    }

    public int priorityWeight() {
        return priorityWeight;
    }

    public static TrainServiceClass fromStringOrDefault(String value, TrainServiceClass fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (TrainServiceClass serviceClass : values()) {
            if (serviceClass.name().equals(normalized) || serviceClass.prefix.equals(normalized)) {
                return serviceClass;
            }
        }

        return fallback;
    }
}
