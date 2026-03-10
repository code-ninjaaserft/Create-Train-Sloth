package dev.elved.createtrainsloth.interlocking.schematic;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.createmod.catnip.data.Couple;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class StellwerkSchematicBuilder {

    public StellwerkSchematicSnapshot build(Level level, Set<String> lockedSectionIds) {
        if (level == null) {
            return StellwerkSchematicSnapshot.empty();
        }

        ResourceKey<Level> dimension = level.dimension();
        Map<String, NodeDraft> nodesByToken = new LinkedHashMap<>();
        Map<String, SectionDraft> sectionsById = new LinkedHashMap<>();
        Set<String> occupiedSections = new HashSet<>();
        Set<String> reservedSections = new HashSet<>();
        Map<String, Set<String>> occupiedBySection = new HashMap<>();
        List<TrainDraft> trainDrafts = new ArrayList<>();

        int graphCount = 0;
        int stationCount = 0;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            if (!containsDimension(graph, dimension)) {
                continue;
            }

            graphCount++;
            Map<String, String> stationByNode = collectStationLabels(graph, dimension);
            stationCount += stationByNode.size();

            for (TrackNodeLocation location : graph.getNodes()) {
                if (!dimension.equals(location.getDimension())) {
                    continue;
                }

                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    continue;
                }

                String token = SectionIdHelper.nodeToken(location);
                NodeDraft draft = nodesByToken.computeIfAbsent(
                    token,
                    ignored -> new NodeDraft(token, location.getLocation().x, location.getLocation().z)
                );
                draft.junction = countRelevantConnections(graph, node, dimension) > 2;

                String stationName = stationByNode.get(token);
                if (stationName != null && !stationName.isBlank()) {
                    draft.station = true;
                    draft.label = stationName;
                }
            }

            for (TrackNodeLocation location : graph.getNodes()) {
                if (!dimension.equals(location.getDimension())) {
                    continue;
                }

                TrackNode from = graph.locateNode(location);
                if (from == null) {
                    continue;
                }

                String fromToken = SectionIdHelper.nodeToken(from.getLocation());
                for (TrackNode to : graph.getConnectionsFrom(from).keySet()) {
                    if (to == null || !dimension.equals(to.getLocation().getDimension())) {
                        continue;
                    }

                    String toToken = SectionIdHelper.nodeToken(to.getLocation());
                    String sectionId = SectionIdHelper.sectionId(graph.id, from, to);
                    sectionsById.putIfAbsent(sectionId, new SectionDraft(sectionId, fromToken, toToken));
                }
            }

            for (Train train : Create.RAILWAYS.trains.values()) {
                if (train.graph != graph) {
                    continue;
                }

                LinkedHashSet<String> trainSections = new LinkedHashSet<>();
                markTrainOccupiedSections(train, graph.id, occupiedSections, occupiedBySection, trainSections);
                markTrainReservedSection(train, graph.id, reservedSections, trainSections, dimension);
                trainDrafts.add(TrainDraft.fromTrain(train, trainSections));
            }
        }

        List<Map.Entry<String, NodeDraft>> sortedNodes = new ArrayList<>(nodesByToken.entrySet());
        sortedNodes.sort(
            Comparator.comparingDouble((Map.Entry<String, NodeDraft> entry) -> entry.getValue().x)
                .thenComparingDouble(entry -> entry.getValue().z)
                .thenComparing(Map.Entry::getKey)
        );

        Map<String, Integer> nodeIndexByToken = new HashMap<>();
        List<StellwerkNodeView> nodes = new ArrayList<>();
        for (int index = 0; index < sortedNodes.size(); index++) {
            NodeDraft draft = sortedNodes.get(index).getValue();
            nodeIndexByToken.put(draft.token, index);
            String label = draft.label != null && !draft.label.isBlank() ? draft.label : "N" + index;
            nodes.add(new StellwerkNodeView(index, label, draft.x, draft.z, draft.station, draft.junction));
        }

        List<String> sortedSectionIds = new ArrayList<>(sectionsById.keySet());
        sortedSectionIds.sort(String::compareTo);
        Map<String, Integer> sectionIndexById = new HashMap<>();
        List<StellwerkSectionView> sections = new ArrayList<>();
        for (String sectionId : sortedSectionIds) {
            SectionDraft sectionDraft = sectionsById.get(sectionId);
            Integer fromNode = nodeIndexByToken.get(sectionDraft.fromToken);
            Integer toNode = nodeIndexByToken.get(sectionDraft.toToken);
            if (fromNode == null || toNode == null) {
                continue;
            }

            boolean locked = lockedSectionIds.contains(sectionId);
            StellwerkSectionState state = resolveState(sectionId, locked, occupiedSections, reservedSections);
            String occupiedBy = String.join(", ", occupiedBySection.getOrDefault(sectionId, Set.of()));
            int sectionIndex = sections.size();
            sectionIndexById.put(sectionId, sectionIndex);
            sections.add(new StellwerkSectionView(sectionIndex, sectionId, fromNode, toNode, state, locked, occupiedBy));
        }

        List<StellwerkTrainView> trains = new ArrayList<>();
        for (TrainDraft draft : trainDrafts) {
            int sectionIndex = resolveTrainSectionIndex(draft.sectionIds, sectionIndexById);
            trains.add(new StellwerkTrainView(
                draft.trainId,
                draft.trainName,
                sectionIndex,
                draft.target,
                draft.waitingForSignal
            ));
        }

        String networkLabel = "graphs=" + graphCount
            + " nodes=" + nodes.size()
            + " sections=" + sections.size()
            + " stations=" + stationCount
            + " trains=" + trains.size();

        return new StellwerkSchematicSnapshot(
            level.getGameTime(),
            networkLabel,
            List.copyOf(nodes),
            List.copyOf(sections),
            List.copyOf(trains)
        );
    }

    private static boolean containsDimension(TrackGraph graph, ResourceKey<Level> dimension) {
        for (TrackNodeLocation location : graph.getNodes()) {
            if (dimension.equals(location.getDimension())) {
                return true;
            }
        }
        return false;
    }

    private static int countRelevantConnections(TrackGraph graph, TrackNode node, ResourceKey<Level> dimension) {
        int count = 0;
        for (TrackNode candidate : graph.getConnectionsFrom(node).keySet()) {
            if (candidate != null && dimension.equals(candidate.getLocation().getDimension())) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, String> collectStationLabels(TrackGraph graph, ResourceKey<Level> dimension) {
        Map<String, String> labels = new HashMap<>();
        for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
            if (station == null || station.edgeLocation == null || station.name == null || station.name.isBlank()) {
                continue;
            }

            TrackNodeLocation first = station.edgeLocation.getFirst();
            TrackNodeLocation second = station.edgeLocation.getSecond();
            if (first != null && dimension.equals(first.getDimension())) {
                labels.putIfAbsent(SectionIdHelper.nodeToken(first), station.name);
            }
            if (second != null && dimension.equals(second.getDimension())) {
                labels.putIfAbsent(SectionIdHelper.nodeToken(second), station.name);
            }
        }
        return labels;
    }

    private static void markTrainOccupiedSections(
        Train train,
        UUID graphId,
        Set<String> occupiedSections,
        Map<String, Set<String>> occupiedBySection,
        Set<String> trainSections
    ) {
        Couple<Couple<TrackNode>> endpointEdges = train.getEndpointEdges();
        addEdgeOccupancy(train, graphId, endpointEdges.getFirst(), occupiedSections, occupiedBySection, trainSections);
        addEdgeOccupancy(train, graphId, endpointEdges.getSecond(), occupiedSections, occupiedBySection, trainSections);
    }

    private static void addEdgeOccupancy(
        Train train,
        UUID graphId,
        Couple<TrackNode> edge,
        Set<String> occupiedSections,
        Map<String, Set<String>> occupiedBySection,
        Set<String> trainSections
    ) {
        if (edge == null || edge.getFirst() == null || edge.getSecond() == null) {
            return;
        }

        String sectionId = SectionIdHelper.sectionId(graphId, edge);
        if (sectionId.isBlank()) {
            return;
        }

        occupiedSections.add(sectionId);
        trainSections.add(sectionId);
        occupiedBySection.computeIfAbsent(sectionId, ignored -> new LinkedHashSet<>())
            .add(train.name == null ? train.id.toString() : train.name.getString());
    }

    private static void markTrainReservedSection(
        Train train,
        UUID graphId,
        Set<String> reservedSections,
        Set<String> trainSections,
        ResourceKey<Level> dimension
    ) {
        GlobalStation destination = train.navigation.destination;
        if (destination == null || destination.edgeLocation == null) {
            return;
        }

        TrackNodeLocation first = destination.edgeLocation.getFirst();
        TrackNodeLocation second = destination.edgeLocation.getSecond();
        if (first == null || second == null) {
            return;
        }
        if (!dimension.equals(first.getDimension()) || !dimension.equals(second.getDimension())) {
            return;
        }

        String sectionId = SectionIdHelper.sectionId(graphId, first, second);
        reservedSections.add(sectionId);
        trainSections.add(sectionId);
    }

    private static int resolveTrainSectionIndex(Set<String> trainSections, Map<String, Integer> sectionIndexById) {
        for (String sectionId : trainSections) {
            Integer idx = sectionIndexById.get(sectionId);
            if (idx != null) {
                return idx;
            }
        }
        return -1;
    }

    private static StellwerkSectionState resolveState(
        String sectionId,
        boolean locked,
        Set<String> occupiedSections,
        Set<String> reservedSections
    ) {
        if (locked) {
            return StellwerkSectionState.BLOCKED;
        }
        if (occupiedSections.contains(sectionId)) {
            return StellwerkSectionState.OCCUPIED;
        }
        if (reservedSections.contains(sectionId)) {
            return StellwerkSectionState.RESERVED;
        }
        return StellwerkSectionState.FREE;
    }

    private static final class NodeDraft {
        private final String token;
        private final double x;
        private final double z;
        private String label;
        private boolean station;
        private boolean junction;

        private NodeDraft(String token, double x, double z) {
            this.token = token;
            this.x = x;
            this.z = z;
        }
    }

    private record SectionDraft(String id, String fromToken, String toToken) {
    }

    private record TrainDraft(
        UUID trainId,
        String trainName,
        Set<String> sectionIds,
        String target,
        boolean waitingForSignal
    ) {
        private static TrainDraft fromTrain(Train train, Set<String> sections) {
            String trainName = train.name == null ? train.id.toString() : train.name.getString();
            String target = train.navigation.destination == null ? "<none>" : train.navigation.destination.name;
            return new TrainDraft(
                train.id,
                trainName,
                Set.copyOf(sections),
                target == null || target.isBlank() ? "<none>" : target,
                train.navigation.waitingForSignal != null
            );
        }
    }
}
