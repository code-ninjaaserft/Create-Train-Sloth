package dev.elved.createtrainsloth.block.entity;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSchematicBuilder;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkNodeView;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSchematicSnapshot;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSectionState;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSectionView;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkTrainView;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.menu.StellwerkMenu;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import dev.elved.createtrainsloth.station.StationHub;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InterlockingBlockEntity extends BlockEntity implements MenuProvider {

    private static final long SNAPSHOT_INTERVAL_TICKS = 20L;
    private static final String TAG_AUTO_ROUTING = "AutoRoutingEnabled";
    private static final String TAG_LOCKED_SECTIONS = "LockedSections";
    private static final String TAG_SNAPSHOT = "SchematicSnapshot";
    private static final String TAG_LINE_IDS = "LineIds";
    private static final String TAG_TRAIN_IDS = "TrainIds";
    private static final String TAG_ASSIGNMENTS = "LineAssignments";
    private static final String TAG_SELECTED_TRAIN = "SelectedTrain";
    private static final String TAG_SELECTED_LINE = "SelectedLine";
    private static final String TAG_SELECTED_LINE_STATIONS = "SelectedLineStations";
    private static final String TAG_SELECTED_LINE_NAME = "SelectedLineName";
    private static final String TAG_SELECTED_SERVICE_CLASS = "SelectedServiceClass";
    private static final String TAG_ROUTE_SERVICE_CLASSES = "RouteServiceClasses";
    private static final String TAG_ROUTE_STATIONS = "RouteStations";
    private static final long STATION_STATE_TTL_TICKS = 120L;
    private static final Map<String, Map<String, PublishedStationState>> PUBLISHED_STATION_STATES = new ConcurrentHashMap<>();

    private final Set<String> lockedSectionIds = new LinkedHashSet<>();
    private final StellwerkSchematicBuilder schematicBuilder = new StellwerkSchematicBuilder();
    private final List<String> syncedLineIds = new ArrayList<>();
    private final List<UUID> syncedTrainIds = new ArrayList<>();
    private final Map<UUID, String> syncedAssignments = new LinkedHashMap<>();
    private final List<String> syncedSelectedLineStations = new ArrayList<>();
    private final Map<String, TrainServiceClass> routeServiceByLine = new LinkedHashMap<>();
    private final Map<String, List<String>> routeStationsByLine = new LinkedHashMap<>();
    private StellwerkSchematicSnapshot schematicSnapshot = StellwerkSchematicSnapshot.empty();
    private boolean autoRoutingEnabled = true;
    private long lastSnapshotTick = Long.MIN_VALUE;
    private int selectedTrainIndex = 0;
    private int selectedLineIndex = 0;
    private String syncedSelectedLineName = "-";
    private TrainServiceClass syncedSelectedServiceClass = TrainServiceClass.RE;

    public InterlockingBlockEntity(BlockPos pos, BlockState blockState) {
        super(TrainSlothRegistries.INTERLOCKING_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, InterlockingBlockEntity blockEntity) {
        if (level == null || level.isClientSide()) {
            return;
        }

        if (blockEntity.refreshSchematicIfDue(level, false)) {
            blockEntity.setChangedAndSync();
        }
        if (CreateTrainSlothMod.runtime().interlockingControlService() != null) {
            CreateTrainSlothMod.runtime().interlockingControlService()
                .heartbeat(level, pos, blockEntity.autoRoutingEnabled, Set.copyOf(blockEntity.lockedSectionIds));
        }
        blockEntity.publishStationStates(level);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.create_train_sloth.interlocking_block");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new StellwerkMenu(containerId, playerInventory, this);
    }

    public StellwerkSchematicSnapshot snapshot() {
        return schematicSnapshot;
    }

    public boolean autoRoutingEnabled() {
        return autoRoutingEnabled;
    }

    public void toggleAutoRouting() {
        autoRoutingEnabled = !autoRoutingEnabled;
        markUpdated();
    }

    public boolean toggleLockForSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= schematicSnapshot.sections().size()) {
            return false;
        }

        String sectionId = schematicSnapshot.sections().get(sectionIndex).id();
        if (sectionId == null || sectionId.isBlank()) {
            return false;
        }

        if (lockedSectionIds.contains(sectionId)) {
            lockedSectionIds.remove(sectionId);
        } else {
            lockedSectionIds.add(sectionId);
        }
        markUpdated();
        return true;
    }

    public boolean unlockSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= schematicSnapshot.sections().size()) {
            return false;
        }

        String sectionId = schematicSnapshot.sections().get(sectionIndex).id();
        if (!lockedSectionIds.remove(sectionId)) {
            return false;
        }

        markUpdated();
        return true;
    }

    public Set<String> lockedSectionIds() {
        return Set.copyOf(lockedSectionIds);
    }

    public List<String> syncedLineIds() {
        return List.copyOf(syncedLineIds);
    }

    public List<UUID> syncedTrainIds() {
        return List.copyOf(syncedTrainIds);
    }

    public int selectedTrainIndex() {
        return selectedTrainIndex;
    }

    public int selectedLineIndex() {
        return selectedLineIndex;
    }

    public String selectedTrainLabel() {
        UUID selectedTrain = selectedTrainId();
        if (selectedTrain == null) {
            return "-";
        }

        for (StellwerkTrainView train : schematicSnapshot.trains()) {
            if (train.trainId().equals(selectedTrain)) {
                return train.trainName() == null || train.trainName().isBlank() ? shortTrainToken(selectedTrain) : train.trainName();
            }
        }

        return shortTrainToken(selectedTrain);
    }

    public String selectedLineLabel() {
        if (syncedLineIds.isEmpty() || selectedLineIndex < 0 || selectedLineIndex >= syncedLineIds.size()) {
            return "-";
        }
        return syncedLineIds.get(selectedLineIndex);
    }

    public String selectedTrainAssignmentLabel() {
        UUID selectedTrain = selectedTrainId();
        if (selectedTrain == null) {
            return "-";
        }
        return syncedAssignments.getOrDefault(selectedTrain, "-");
    }

    public List<String> selectedLineStations() {
        return List.copyOf(syncedSelectedLineStations);
    }

    public String selectedLineName() {
        return syncedSelectedLineName == null || syncedSelectedLineName.isBlank() ? "-" : syncedSelectedLineName;
    }

    public String selectedServiceClass() {
        return syncedSelectedServiceClass.prefix();
    }

    public int selectedLineAssignedTrainCount() {
        String lineId = selectedLineLabel();
        if ("-".equals(lineId)) {
            return 0;
        }

        int count = 0;
        for (String assignedLine : syncedAssignments.values()) {
            if (lineId.equals(assignedLine)) {
                count++;
            }
        }
        return count;
    }

    public int selectedLineRecommendedTrainCount() {
        String lineId = selectedLineLabel();
        if ("-".equals(lineId)) {
            return 0;
        }

        int stationCount = Math.max(1, syncedSelectedLineStations.size());
        int base = Math.max(1, (int) Math.ceil(stationCount / 2.5D));
        double classFactor = switch (syncedSelectedServiceClass) {
            case S -> 1.35D;
            case IR -> 1.15D;
            case RE -> 1.0D;
            case IC -> 0.85D;
            case ICN -> 0.8D;
            case ICE -> 0.7D;
        };

        int recommended = (int) Math.round(base * classFactor);
        return Math.max(1, Math.min(12, recommended));
    }

    public boolean cycleTrainSelection(int delta) {
        if (syncedTrainIds.isEmpty()) {
            return false;
        }
        selectedTrainIndex = Math.floorMod(selectedTrainIndex + delta, syncedTrainIds.size());
        setChangedAndSync();
        return true;
    }

    public boolean cycleLineSelection(int delta) {
        if (syncedLineIds.isEmpty()) {
            return false;
        }
        selectedLineIndex = Math.floorMod(selectedLineIndex + delta, syncedLineIds.size());
        if (level != null && !level.isClientSide()) {
            refreshControlData(level);
        }
        setChangedAndSync();
        return true;
    }

    public boolean assignSelectedTrainToSelectedLine() {
        if (level == null || level.isClientSide()) {
            return false;
        }

        UUID trainId = selectedTrainId();
        String lineId = selectedLineLabel();
        if (trainId == null || "-".equals(lineId)) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        TrainServiceClass serviceClass = routeServiceByLine.getOrDefault(lineId, TrainServiceClass.RE);
        lineRegistry.assignTrain(trainId, new LineId(lineId), serviceClass);
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean unassignSelectedTrain() {
        if (level == null || level.isClientSide()) {
            return false;
        }

        UUID trainId = selectedTrainId();
        if (trainId == null) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        lineRegistry.unassignTrain(trainId);
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean generateLinesFromHubs() {
        if (level == null || level.isClientSide()) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        boolean generatedAny = false;
        List<StationHub> hubs = new ArrayList<>(CreateTrainSlothMod.runtime().stationHubRegistry() == null
            ? List.of()
            : CreateTrainSlothMod.runtime().stationHubRegistry().allHubs());
        hubs.sort(Comparator.comparing(hub -> hub.id().value()));

        for (StationHub hub : hubs) {
            String lineIdRaw = sanitizeLineId("hub_" + hub.id().value());
            LineId lineId = new LineId(lineIdRaw);
            TrainLine line = lineRegistry.findLine(lineId).orElse(null);
            if (line == null) {
                line = lineRegistry.createLine(lineId, hub.displayName());
            }
            line.setDisplayName(hub.displayName());
            for (String platform : hub.platformStationNames()) {
                line.addStationName(platform);
            }
            generatedAny = true;
        }

        if (!generatedAny) {
            for (StellwerkTrainView train : schematicSnapshot.trains()) {
                String lineIdRaw = sanitizeLineId("line_" + shortTrainToken(train.trainId()));
                LineId lineId = new LineId(lineIdRaw);
                TrainLine line = lineRegistry.findLine(lineId).orElse(null);
                if (line == null) {
                    line = lineRegistry.createLine(lineId, "Line " + train.trainName());
                }
                line.setDisplayName(train.trainName());
                generatedAny = true;
            }
        }

        if (!generatedAny) {
            return false;
        }

        lineRegistry.markDirty();
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean createRoute(String routeNameRaw, String serviceClassRaw) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String routeName = routeNameRaw == null ? "" : routeNameRaw.trim();
        if (routeName.isBlank()) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        String baseId = sanitizeLineId("route_" + routeName);
        String finalId = baseId;
        int suffix = 2;
        while (lineRegistry.findLine(new LineId(finalId)).isPresent()) {
            finalId = baseId + "_" + suffix++;
        }

        TrainLine line = lineRegistry.createLine(new LineId(finalId), routeName);
        line.setDisplayName(routeName);
        routeStationsByLine.put(finalId, new ArrayList<>());
        routeServiceByLine.put(finalId, parseServiceClass(serviceClassRaw));
        lineRegistry.markDirty();
        refreshControlData(level);
        selectedLineIndex = Math.max(0, syncedLineIds.indexOf(finalId));
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean updateSelectedRouteMeta(String routeNameRaw, String serviceClassRaw) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String lineId = selectedLineLabel();
        if ("-".equals(lineId)) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineId)).orElse(null);
        if (line == null) {
            return false;
        }

        String routeName = routeNameRaw == null ? "" : routeNameRaw.trim();
        boolean changed = false;
        if (!routeName.isBlank() && !routeName.equals(line.displayName())) {
            line.setDisplayName(routeName);
            changed = true;
        }

        TrainServiceClass parsedService = parseServiceClass(serviceClassRaw);
        if (routeServiceByLine.getOrDefault(lineId, TrainServiceClass.RE) != parsedService) {
            routeServiceByLine.put(lineId, parsedService);
            changed = true;
        }

        if (!changed) {
            return false;
        }

        lineRegistry.markDirty();
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean deleteRoute(String lineIdRaw) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String lineIdValue = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineIdValue.isBlank()) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        boolean removed = lineRegistry.removeLine(new LineId(lineIdValue));
        if (!removed) {
            return false;
        }

        routeServiceByLine.remove(lineIdValue);
        routeStationsByLine.remove(lineIdValue);
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean editRouteStation(String lineIdRaw, String stationNameRaw, boolean add) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String lineIdValue = lineIdRaw == null ? "" : lineIdRaw.trim();
        String stationName = stationNameRaw == null ? "" : stationNameRaw.trim();
        if (lineIdValue.isBlank() || stationName.isBlank()) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineIdValue)).orElse(null);
        if (line == null) {
            return false;
        }

        List<String> orderedStations = routeStationsByLine.computeIfAbsent(lineIdValue, ignored -> new ArrayList<>());
        String normalizedStation = normalizeStationName(stationName);
        boolean changed;
        if (add) {
            changed = !orderedStations.contains(normalizedStation) && orderedStations.add(normalizedStation);
        } else {
            changed = orderedStations.remove(normalizedStation);
        }
        if (!changed) {
            return false;
        }

        applyRouteStationsToLine(line, orderedStations);
        routeServiceByLine.putIfAbsent(lineIdValue, TrainServiceClass.RE);
        lineRegistry.markDirty();
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    public boolean moveRouteStation(String lineIdRaw, int fromIndex, int toIndex) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String lineIdValue = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineIdValue.isBlank()) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineIdValue)).orElse(null);
        List<String> orderedStations = routeStationsByLine.get(lineIdValue);
        if (line == null || orderedStations == null || orderedStations.isEmpty()) {
            return false;
        }

        if (fromIndex < 0 || fromIndex >= orderedStations.size() || toIndex < 0 || toIndex >= orderedStations.size()) {
            return false;
        }
        if (fromIndex == toIndex) {
            return false;
        }

        String station = orderedStations.remove(fromIndex);
        orderedStations.add(toIndex, station);
        applyRouteStationsToLine(line, orderedStations);
        lineRegistry.markDirty();
        refreshControlData(level);
        setChangedAndSync();
        return true;
    }

    @Nullable
    private UUID selectedTrainId() {
        if (syncedTrainIds.isEmpty() || selectedTrainIndex < 0 || selectedTrainIndex >= syncedTrainIds.size()) {
            return null;
        }
        return syncedTrainIds.get(selectedTrainIndex);
    }

    private boolean refreshSchematicIfDue(Level level, boolean force) {
        boolean changed = false;

        if (force || level.getGameTime() - lastSnapshotTick >= SNAPSHOT_INTERVAL_TICKS) {
            schematicSnapshot = schematicBuilder.build(level, lockedSectionIds());
            lastSnapshotTick = level.getGameTime();
            changed = true;
        }

        if (refreshControlData(level)) {
            changed = true;
        }

        return changed;
    }

    private void markUpdated() {
        if (level != null && !level.isClientSide()) {
            refreshSchematicIfDue(level, true);
        }

        setChangedAndSync();
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeSyncData(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readSyncData(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        writeSyncData(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        readSyncData(tag);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void writeSyncData(CompoundTag tag) {
        tag.putBoolean(TAG_AUTO_ROUTING, autoRoutingEnabled);
        ListTag locked = new ListTag();
        for (String section : lockedSectionIds) {
            locked.add(StringTag.valueOf(section));
        }
        tag.put(TAG_LOCKED_SECTIONS, locked);
        tag.put(TAG_SNAPSHOT, schematicSnapshot.toTag());

        ListTag linesTag = new ListTag();
        for (String lineId : syncedLineIds) {
            linesTag.add(StringTag.valueOf(lineId));
        }
        tag.put(TAG_LINE_IDS, linesTag);

        ListTag trainsTag = new ListTag();
        for (UUID trainId : syncedTrainIds) {
            trainsTag.add(StringTag.valueOf(trainId.toString()));
        }
        tag.put(TAG_TRAIN_IDS, trainsTag);

        ListTag assignmentsTag = new ListTag();
        for (Map.Entry<UUID, String> entry : syncedAssignments.entrySet()) {
            CompoundTag assignment = new CompoundTag();
            assignment.putUUID("TrainId", entry.getKey());
            assignment.putString("LineId", entry.getValue());
            assignmentsTag.add(assignment);
        }
        tag.put(TAG_ASSIGNMENTS, assignmentsTag);
        tag.putInt(TAG_SELECTED_TRAIN, selectedTrainIndex);
        tag.putInt(TAG_SELECTED_LINE, selectedLineIndex);
        tag.putString(TAG_SELECTED_LINE_NAME, selectedLineName());
        tag.putString(TAG_SELECTED_SERVICE_CLASS, selectedServiceClass());

        ListTag lineStationsTag = new ListTag();
        for (String stationName : syncedSelectedLineStations) {
            lineStationsTag.add(StringTag.valueOf(stationName));
        }
        tag.put(TAG_SELECTED_LINE_STATIONS, lineStationsTag);

        ListTag routeServiceTag = new ListTag();
        for (Map.Entry<String, TrainServiceClass> entry : routeServiceByLine.entrySet()) {
            CompoundTag routeService = new CompoundTag();
            routeService.putString("LineId", entry.getKey());
            routeService.putString("ServiceClass", entry.getValue().name());
            routeServiceTag.add(routeService);
        }
        tag.put(TAG_ROUTE_SERVICE_CLASSES, routeServiceTag);

        ListTag routeStationsTag = new ListTag();
        for (Map.Entry<String, List<String>> entry : routeStationsByLine.entrySet()) {
            CompoundTag route = new CompoundTag();
            route.putString("LineId", entry.getKey());
            ListTag stations = new ListTag();
            for (String station : entry.getValue()) {
                stations.add(StringTag.valueOf(station));
            }
            route.put("Stations", stations);
            routeStationsTag.add(route);
        }
        tag.put(TAG_ROUTE_STATIONS, routeStationsTag);
    }

    private void readSyncData(CompoundTag tag) {
        autoRoutingEnabled = !tag.contains(TAG_AUTO_ROUTING) || tag.getBoolean(TAG_AUTO_ROUTING);
        lockedSectionIds.clear();
        for (Tag element : tag.getList(TAG_LOCKED_SECTIONS, Tag.TAG_STRING)) {
            lockedSectionIds.add(element.getAsString());
        }

        if (tag.contains(TAG_SNAPSHOT, Tag.TAG_COMPOUND)) {
            schematicSnapshot = StellwerkSchematicSnapshot.fromTag(tag.getCompound(TAG_SNAPSHOT));
        } else {
            schematicSnapshot = StellwerkSchematicSnapshot.empty();
        }

        syncedLineIds.clear();
        for (Tag element : tag.getList(TAG_LINE_IDS, Tag.TAG_STRING)) {
            syncedLineIds.add(element.getAsString());
        }

        syncedTrainIds.clear();
        for (Tag element : tag.getList(TAG_TRAIN_IDS, Tag.TAG_STRING)) {
            try {
                syncedTrainIds.add(UUID.fromString(element.getAsString()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        syncedAssignments.clear();
        for (Tag element : tag.getList(TAG_ASSIGNMENTS, Tag.TAG_COMPOUND)) {
            CompoundTag assignment = (CompoundTag) element;
            if (!assignment.hasUUID("TrainId") || !assignment.contains("LineId", Tag.TAG_STRING)) {
                continue;
            }
            syncedAssignments.put(assignment.getUUID("TrainId"), assignment.getString("LineId"));
        }

        selectedTrainIndex = tag.getInt(TAG_SELECTED_TRAIN);
        selectedLineIndex = tag.getInt(TAG_SELECTED_LINE);

        syncedSelectedLineStations.clear();
        for (Tag element : tag.getList(TAG_SELECTED_LINE_STATIONS, Tag.TAG_STRING)) {
            syncedSelectedLineStations.add(element.getAsString());
        }

        syncedSelectedLineName = tag.contains(TAG_SELECTED_LINE_NAME, Tag.TAG_STRING)
            ? tag.getString(TAG_SELECTED_LINE_NAME)
            : "-";
        syncedSelectedServiceClass = parseServiceClass(tag.getString(TAG_SELECTED_SERVICE_CLASS));

        routeServiceByLine.clear();
        for (Tag element : tag.getList(TAG_ROUTE_SERVICE_CLASSES, Tag.TAG_COMPOUND)) {
            CompoundTag routeService = (CompoundTag) element;
            if (!routeService.contains("LineId", Tag.TAG_STRING)) {
                continue;
            }
            routeServiceByLine.put(
                routeService.getString("LineId"),
                parseServiceClass(routeService.getString("ServiceClass"))
            );
        }

        routeStationsByLine.clear();
        for (Tag element : tag.getList(TAG_ROUTE_STATIONS, Tag.TAG_COMPOUND)) {
            CompoundTag route = (CompoundTag) element;
            if (!route.contains("LineId", Tag.TAG_STRING)) {
                continue;
            }
            List<String> stations = new ArrayList<>();
            for (Tag stationTag : route.getList("Stations", Tag.TAG_STRING)) {
                stations.add(stationTag.getAsString());
            }
            routeStationsByLine.put(route.getString("LineId"), stations);
        }
        clampSelections();
    }

    private boolean refreshControlData(Level level) {
        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            boolean changed = !syncedLineIds.isEmpty()
                || !syncedTrainIds.isEmpty()
                || !syncedAssignments.isEmpty()
                || !syncedSelectedLineStations.isEmpty()
                || !routeServiceByLine.isEmpty()
                || !routeStationsByLine.isEmpty()
                || !"-".equals(syncedSelectedLineName)
                || syncedSelectedServiceClass != TrainServiceClass.RE;
            syncedLineIds.clear();
            syncedTrainIds.clear();
            syncedAssignments.clear();
            syncedSelectedLineStations.clear();
            routeServiceByLine.clear();
            routeStationsByLine.clear();
            syncedSelectedLineName = "-";
            syncedSelectedServiceClass = TrainServiceClass.RE;
            if (clampSelections()) {
                changed = true;
            }
            return changed;
        }

        List<String> newLineIds = lineRegistry.allLines()
            .stream()
            .map(line -> line.id().value())
            .sorted()
            .toList();
        boolean changed = ensureRouteMapsSyncedWithLines(lineRegistry, newLineIds);

        List<UUID> newTrainIds = schematicSnapshot.trains()
            .stream()
            .map(StellwerkTrainView::trainId)
            .sorted(Comparator.comparing(UUID::toString))
            .toList();

        Map<UUID, String> newAssignments = new LinkedHashMap<>();
        for (UUID trainId : newTrainIds) {
            lineRegistry.assignmentOf(trainId).ifPresent(assignment -> newAssignments.put(trainId, assignment.lineId().value()));
        }

        if (!syncedLineIds.equals(newLineIds)
            || !syncedTrainIds.equals(newTrainIds)
            || !syncedAssignments.equals(newAssignments)) {
            syncedLineIds.clear();
            syncedLineIds.addAll(newLineIds);
            syncedTrainIds.clear();
            syncedTrainIds.addAll(newTrainIds);
            syncedAssignments.clear();
            syncedAssignments.putAll(newAssignments);
            changed = true;
        }

        if (clampSelections()) {
            changed = true;
        }

        List<String> newSelectedLineStations = resolveSelectedLineStations(lineRegistry);
        String lineId = selectedLineLabel();
        String newSelectedLineName = "-";
        TrainServiceClass newSelectedService = TrainServiceClass.RE;
        if (!"-".equals(lineId)) {
            newSelectedLineName = lineRegistry.findLine(new LineId(lineId))
                .map(TrainLine::displayName)
                .orElse(lineId);
            newSelectedService = routeServiceByLine.getOrDefault(lineId, TrainServiceClass.RE);
        }

        if (!syncedSelectedLineStations.equals(newSelectedLineStations)) {
            syncedSelectedLineStations.clear();
            syncedSelectedLineStations.addAll(newSelectedLineStations);
            changed = true;
        }
        if (!syncedSelectedLineName.equals(newSelectedLineName)) {
            syncedSelectedLineName = newSelectedLineName;
            changed = true;
        }
        if (syncedSelectedServiceClass != newSelectedService) {
            syncedSelectedServiceClass = newSelectedService;
            changed = true;
        }

        return changed;
    }

    private boolean clampSelections() {
        int previousTrainIndex = selectedTrainIndex;
        int previousLineIndex = selectedLineIndex;

        if (syncedTrainIds.isEmpty()) {
            selectedTrainIndex = 0;
        } else {
            selectedTrainIndex = Math.max(0, Math.min(selectedTrainIndex, syncedTrainIds.size() - 1));
        }

        if (syncedLineIds.isEmpty()) {
            selectedLineIndex = 0;
        } else {
            selectedLineIndex = Math.max(0, Math.min(selectedLineIndex, syncedLineIds.size() - 1));
        }

        return previousTrainIndex != selectedTrainIndex || previousLineIndex != selectedLineIndex;
    }

    private static String shortTrainToken(UUID trainId) {
        String raw = trainId.toString().replace("-", "");
        return raw.length() <= 6 ? raw : raw.substring(0, 6);
    }

    private static String sanitizeLineId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "line";
        }
        String cleaned = raw.toLowerCase()
            .replaceAll("[^a-z0-9_\\-]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        return cleaned.isBlank() ? "line" : cleaned;
    }

    private List<String> resolveSelectedLineStations(LineRegistry lineRegistry) {
        if (syncedLineIds.isEmpty() || selectedLineIndex < 0 || selectedLineIndex >= syncedLineIds.size()) {
            return List.of();
        }

        String lineId = syncedLineIds.get(selectedLineIndex);
        if (routeStationsByLine.containsKey(lineId)) {
            return List.copyOf(routeStationsByLine.get(lineId));
        }
        return lineRegistry.findLine(new LineId(lineId))
            .map(line -> line.stationNames()
                .stream()
                .sorted()
                .toList())
            .orElseGet(List::of);
    }

    private boolean ensureRouteMapsSyncedWithLines(LineRegistry lineRegistry, List<String> lineIds) {
        boolean changed = false;
        Set<String> existingLines = Set.copyOf(lineIds);

        if (routeServiceByLine.keySet().removeIf(lineId -> !existingLines.contains(lineId))) {
            changed = true;
        }
        if (routeStationsByLine.keySet().removeIf(lineId -> !existingLines.contains(lineId))) {
            changed = true;
        }

        for (String lineId : lineIds) {
            if (!routeServiceByLine.containsKey(lineId)) {
                routeServiceByLine.put(lineId, TrainServiceClass.RE);
                changed = true;
            }

            if (!routeStationsByLine.containsKey(lineId)) {
                List<String> stations = lineRegistry.findLine(new LineId(lineId))
                    .map(line -> line.stationNames()
                        .stream()
                        .sorted()
                        .toList())
                    .orElseGet(List::of);
                routeStationsByLine.put(lineId, new ArrayList<>(stations));
                changed = true;
            }
        }

        return changed;
    }

    private void applyRouteStationsToLine(TrainLine line, List<String> orderedStations) {
        List<String> current = new ArrayList<>(line.stationNames());
        for (String station : current) {
            line.removeStationName(station);
        }
        for (String station : orderedStations) {
            line.addStationName(station);
        }
    }

    private TrainServiceClass parseServiceClass(String raw) {
        return TrainServiceClass.fromStringOrDefault(raw, TrainServiceClass.RE);
    }

    private String normalizeStationName(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static StellwerkSectionState lookupPublishedStationState(Level level, String stationNameRaw) {
        if (level == null || stationNameRaw == null || stationNameRaw.isBlank()) {
            return null;
        }

        String dimensionKey = level.dimension().location().toString();
        Map<String, PublishedStationState> byStation = PUBLISHED_STATION_STATES.get(dimensionKey);
        if (byStation == null) {
            return null;
        }

        String stationName = stationNameRaw.trim().toLowerCase(Locale.ROOT);
        PublishedStationState published = byStation.get(stationName);
        if (published == null) {
            return null;
        }

        if (level.getGameTime() - published.updatedAtTick() > STATION_STATE_TTL_TICKS) {
            byStation.remove(stationName);
            return null;
        }

        return published.state();
    }

    private void publishStationStates(Level level) {
        Map<String, StellwerkSectionState> stationStates = computeStationStates();
        String dimensionKey = level.dimension().location().toString();
        Map<String, PublishedStationState> byStation = PUBLISHED_STATION_STATES.computeIfAbsent(
            dimensionKey,
            ignored -> new ConcurrentHashMap<>()
        );

        long now = level.getGameTime();
        for (Map.Entry<String, StellwerkSectionState> entry : stationStates.entrySet()) {
            byStation.put(entry.getKey(), new PublishedStationState(entry.getValue(), now));
        }
        byStation.entrySet().removeIf(entry -> now - entry.getValue().updatedAtTick() > STATION_STATE_TTL_TICKS);
    }

    private Map<String, StellwerkSectionState> computeStationStates() {
        Map<String, StellwerkSectionState> stationStates = new LinkedHashMap<>();
        if (schematicSnapshot == null || schematicSnapshot.nodes().isEmpty()) {
            return stationStates;
        }

        Map<Integer, String> stationNamesByNode = new LinkedHashMap<>();
        for (StellwerkNodeView node : schematicSnapshot.nodes()) {
            if (!node.station() || node.label() == null || node.label().isBlank()) {
                continue;
            }
            stationNamesByNode.put(node.index(), node.label().trim().toLowerCase(Locale.ROOT));
        }

        for (Map.Entry<Integer, String> entry : stationNamesByNode.entrySet()) {
            int nodeIndex = entry.getKey();
            StellwerkSectionState nodeState = StellwerkSectionState.FREE;
            for (StellwerkSectionView section : schematicSnapshot.sections()) {
                if (section.fromNodeIndex() != nodeIndex && section.toNodeIndex() != nodeIndex) {
                    continue;
                }
                nodeState = combineStationState(nodeState, section.state());
            }

            String stationName = entry.getValue();
            stationStates.merge(stationName, nodeState, InterlockingBlockEntity::combineStationState);
        }

        return stationStates;
    }

    private static StellwerkSectionState combineStationState(StellwerkSectionState a, StellwerkSectionState b) {
        return severityOf(b) > severityOf(a) ? b : a;
    }

    private static int severityOf(StellwerkSectionState state) {
        return switch (state) {
            case FREE -> 0;
            case RESERVED -> 1;
            case OCCUPIED -> 2;
            case BLOCKED -> 3;
        };
    }

    private record PublishedStationState(StellwerkSectionState state, long updatedAtTick) {
    }
}
