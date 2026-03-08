package dev.elved.createtrainsloth.line;

import java.util.Locale;
import java.util.Objects;

public record LineId(String value) {

    public LineId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Line id cannot be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
