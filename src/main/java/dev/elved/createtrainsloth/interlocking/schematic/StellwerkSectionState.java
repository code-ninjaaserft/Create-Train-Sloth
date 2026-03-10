package dev.elved.createtrainsloth.interlocking.schematic;

public enum StellwerkSectionState {
    FREE(0xFF3FA35B),
    RESERVED(0xFFD1B041),
    OCCUPIED(0xFFC64B4B),
    BLOCKED(0xFF5D5D5D);

    private final int color;

    StellwerkSectionState(int color) {
        this.color = color;
    }

    public int color() {
        return color;
    }

    public static StellwerkSectionState byName(String name) {
        for (StellwerkSectionState state : values()) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return FREE;
    }
}
