package dev.elved.createtrainsloth.station;

import java.util.Locale;
import java.util.Objects;

public record StationHubId(String value) {

    public StationHubId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Station hub id cannot be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}

