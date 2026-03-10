package dev.elved.createtrainsloth.interlocking;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.interlocking.schematic.SectionIdHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class InterlockingControlService {

    private static final long HEARTBEAT_TTL_TICKS = 80L;

    private final Map<ResourceKey<Level>, Map<BlockPos, InterlockingNodeState>> statesByDimension = new HashMap<>();
    private final Map<ResourceKey<Level>, InterlockingSnapshot> latestSnapshotByDimension = new HashMap<>();

    public void heartbeat(Level level, BlockPos blockPos, boolean autoRoutingEnabled, Set<String> lockedSections) {
        if (level == null || level.isClientSide() || blockPos == null) {
            return;
        }

        statesByDimension.computeIfAbsent(level.dimension(), ignored -> new HashMap<>())
            .put(
                blockPos.immutable(),
                new InterlockingNodeState(
                    level.getGameTime(),
                    autoRoutingEnabled,
                    Set.copyOf(lockedSections)
                )
            );
    }

    public void captureTick(Level level, List<Train> trains) {
        if (level == null || level.isClientSide()) {
            return;
        }

        long gameTick = level.getGameTime();
        pruneStaleNodes(level.dimension(), gameTick);

        List<TrainState> trainStates = new ArrayList<>();
        Map<String, Integer> occupiedStations = new LinkedHashMap<>();
        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            String destination = train.navigation.destination == null ? "<none>" : train.navigation.destination.name;
            String currentStation = train.getCurrentStation() == null ? "<none>" : train.getCurrentStation().name;
            String waitingSignal = train.navigation.waitingForSignal == null
                ? "<none>"
                : train.navigation.waitingForSignal.getFirst().toString();

            trainStates.add(
                new TrainState(
                    train.id,
                    train.name == null ? "<unnamed>" : train.name.getString(),
                    destination,
                    currentStation,
                    train.speed,
                    train.navigation.distanceToDestination,
                    waitingSignal
                )
            );

            if (train.getCurrentStation() != null && train.getCurrentStation().name != null) {
                occupiedStations.merge(train.getCurrentStation().name, 1, Integer::sum);
            }
        }

        latestSnapshotByDimension.put(
            level.dimension(),
            new InterlockingSnapshot(
                gameTick,
                activeInterlockingCount(level),
                List.copyOf(trainStates),
                Map.copyOf(occupiedStations)
            )
        );
    }

    public boolean isOverrideActive(Level level) {
        if (level == null || level.isClientSide()) {
            return false;
        }
        if (!TrainSlothConfig.ROUTING.enableInterlockingOverride.get()) {
            return false;
        }
        if (!TrainSlothConfig.ROUTING.requireInterlockingBlockForOverride.get()) {
            return true;
        }

        pruneStaleNodes(level.dimension(), level.getGameTime());
        return activeInterlockingCount(level) > 0 && hasAnyAutoRoutingEnabled(level);
    }

    public int activeInterlockingCount(Level level) {
        if (level == null) {
            return 0;
        }
        Map<BlockPos, InterlockingNodeState> activeNodes = statesByDimension.get(level.dimension());
        return activeNodes == null ? 0 : activeNodes.size();
    }

    public boolean hasAnyAutoRoutingEnabled(Level level) {
        if (level == null) {
            return false;
        }

        Map<BlockPos, InterlockingNodeState> states = statesByDimension.get(level.dimension());
        if (states == null || states.isEmpty()) {
            return false;
        }
        for (InterlockingNodeState state : states.values()) {
            if (state.autoRoutingEnabled()) {
                return true;
            }
        }
        return false;
    }

    public Set<String> lockedSections(Level level) {
        if (level == null) {
            return Set.of();
        }

        Set<String> locked = new HashSet<>();
        Map<BlockPos, InterlockingNodeState> states = statesByDimension.get(level.dimension());
        if (states == null) {
            return Set.of();
        }

        for (InterlockingNodeState state : states.values()) {
            locked.addAll(state.lockedSections());
        }
        return Set.copyOf(locked);
    }

    public boolean isSectionLocked(Level level, String sectionId) {
        if (sectionId == null || sectionId.isBlank()) {
            return false;
        }
        return lockedSections(level).contains(sectionId);
    }

    public boolean isStationLocked(Level level, TrackGraph graph, GlobalStation station) {
        if (level == null || graph == null || station == null || station.edgeLocation == null) {
            return false;
        }

        String sectionId = SectionIdHelper.sectionId(
            graph.id,
            station.edgeLocation.getFirst(),
            station.edgeLocation.getSecond()
        );
        return isSectionLocked(level, sectionId);
    }

    public Optional<InterlockingSnapshot> latestSnapshot(Level level) {
        if (level == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestSnapshotByDimension.get(level.dimension()));
    }

    private void pruneStaleNodes(ResourceKey<Level> dimension, long now) {
        Map<BlockPos, InterlockingNodeState> nodes = statesByDimension.get(dimension);
        if (nodes == null) {
            return;
        }

        nodes.entrySet().removeIf(entry -> now - entry.getValue().lastHeartbeatTick() > HEARTBEAT_TTL_TICKS);
        if (nodes.isEmpty()) {
            statesByDimension.remove(dimension);
        }
    }

    public record InterlockingSnapshot(
        long tick,
        int activeInterlockingBlocks,
        List<TrainState> trains,
        Map<String, Integer> occupiedStations
    ) {
    }

    public record TrainState(
        UUID trainId,
        String trainName,
        String destinationStation,
        String currentStation,
        double speed,
        double distanceToDestination,
        String waitingSignal
    ) {
    }

    private record InterlockingNodeState(long lastHeartbeatTick, boolean autoRoutingEnabled, Set<String> lockedSections) {
    }
}
