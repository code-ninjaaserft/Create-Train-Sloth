package dev.elved.createtrainsloth.interlocking.schematic;

import net.minecraft.nbt.CompoundTag;

public record StellwerkSectionView(
    int index,
    String id,
    int fromNodeIndex,
    int toNodeIndex,
    StellwerkSectionState state,
    boolean locked,
    String occupiedBy
) {
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Index", index);
        tag.putString("Id", id);
        tag.putInt("From", fromNodeIndex);
        tag.putInt("To", toNodeIndex);
        tag.putString("State", state.name());
        tag.putBoolean("Locked", locked);
        tag.putString("OccupiedBy", occupiedBy);
        return tag;
    }

    public static StellwerkSectionView fromTag(CompoundTag tag) {
        return new StellwerkSectionView(
            tag.getInt("Index"),
            tag.getString("Id"),
            tag.getInt("From"),
            tag.getInt("To"),
            StellwerkSectionState.byName(tag.getString("State")),
            tag.getBoolean("Locked"),
            tag.getString("OccupiedBy")
        );
    }
}
