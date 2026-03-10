package dev.elved.createtrainsloth.interlocking.schematic;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public record StellwerkSchematicSnapshot(
    long generatedAtTick,
    String networkLabel,
    List<StellwerkNodeView> nodes,
    List<StellwerkSectionView> sections,
    List<StellwerkTrainView> trains
) {
    public static StellwerkSchematicSnapshot empty() {
        return new StellwerkSchematicSnapshot(0L, "<none>", List.of(), List.of(), List.of());
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("GeneratedAtTick", generatedAtTick);
        tag.putString("NetworkLabel", networkLabel);

        ListTag nodesTag = new ListTag();
        for (StellwerkNodeView node : nodes) {
            nodesTag.add(node.toTag());
        }
        tag.put("Nodes", nodesTag);

        ListTag sectionsTag = new ListTag();
        for (StellwerkSectionView section : sections) {
            sectionsTag.add(section.toTag());
        }
        tag.put("Sections", sectionsTag);

        ListTag trainsTag = new ListTag();
        for (StellwerkTrainView train : trains) {
            trainsTag.add(train.toTag());
        }
        tag.put("Trains", trainsTag);

        return tag;
    }

    public static StellwerkSchematicSnapshot fromTag(CompoundTag tag) {
        List<StellwerkNodeView> nodes = new ArrayList<>();
        for (Tag nodeTag : tag.getList("Nodes", Tag.TAG_COMPOUND)) {
            nodes.add(StellwerkNodeView.fromTag((CompoundTag) nodeTag));
        }

        List<StellwerkSectionView> sections = new ArrayList<>();
        for (Tag sectionTag : tag.getList("Sections", Tag.TAG_COMPOUND)) {
            sections.add(StellwerkSectionView.fromTag((CompoundTag) sectionTag));
        }

        List<StellwerkTrainView> trains = new ArrayList<>();
        for (Tag trainTag : tag.getList("Trains", Tag.TAG_COMPOUND)) {
            trains.add(StellwerkTrainView.fromTag((CompoundTag) trainTag));
        }

        return new StellwerkSchematicSnapshot(
            tag.getLong("GeneratedAtTick"),
            tag.getString("NetworkLabel"),
            List.copyOf(nodes),
            List.copyOf(sections),
            List.copyOf(trains)
        );
    }
}
