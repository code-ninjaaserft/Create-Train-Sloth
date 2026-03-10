package dev.elved.createtrainsloth.interlocking.schematic;

import net.minecraft.nbt.CompoundTag;

public record StellwerkNodeView(
    int index,
    String label,
    double x,
    double z,
    boolean station,
    boolean junction
) {
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Index", index);
        tag.putString("Label", label);
        tag.putDouble("X", x);
        tag.putDouble("Z", z);
        tag.putBoolean("Station", station);
        tag.putBoolean("Junction", junction);
        return tag;
    }

    public static StellwerkNodeView fromTag(CompoundTag tag) {
        return new StellwerkNodeView(
            tag.getInt("Index"),
            tag.getString("Label"),
            tag.getDouble("X"),
            tag.getDouble("Z"),
            tag.getBoolean("Station"),
            tag.getBoolean("Junction")
        );
    }
}
