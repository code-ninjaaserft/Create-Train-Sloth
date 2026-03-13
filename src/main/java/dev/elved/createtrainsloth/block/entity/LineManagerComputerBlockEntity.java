package dev.elved.createtrainsloth.block.entity;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.line.InterlockingPlanningService;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LinePlanningService;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.menu.LineManagerComputerMenu;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

public class LineManagerComputerBlockEntity extends BlockEntity implements MenuProvider {

    private static final long SYNC_INTERVAL_TICKS = 20L;
    private static final String TAG_LINE_IDS = "LineIds";
    private static final String TAG_ASSIGNMENTS = "LineAssignments";
    private static final String TAG_SELECTED_LINE = "SelectedLine";
    private static final String TAG_SELECTED_LINE_STATIONS = "SelectedLineStations";
    private static final String TAG_SELECTED_LINE_NAME = "SelectedLineName";
    private static final String TAG_SELECTED_SERVICE_CLASS = "SelectedServiceClass";
    private static final String TAG_SELECTED_ALLOWED_DEPOT_HUBS = "SelectedAllowedDepotHubs";
    private static final String TAG_ROUTE_SERVICE_CLASSES = "RouteServiceClasses";
    private static final String TAG_ROUTE_STATIONS = "RouteStations";

    private final List<String> syncedLineIds = new ArrayList<>();
    private final Map<UUID, String> syncedAssignments = new LinkedHashMap<>();
    private final List<String> syncedSelectedLineStations = new ArrayList<>();
    private final List<String> syncedSelectedAllowedDepotHubs = new ArrayList<>();
    private final Map<String, TrainServiceClass> routeServiceByLine = new LinkedHashMap<>();
    private final Map<String, List<String>> routeStationsByLine = new LinkedHashMap<>();
    private final InterlockingPlanningService interlockingPlanningService = new InterlockingPlanningService();

    private int selectedLineIndex = 0;
    private String syncedSelectedLineName = "-";
    private TrainServiceClass syncedSelectedServiceClass = TrainServiceClass.RE;
    private long lastSyncTick = Long.MIN_VALUE;

    public LineManagerComputerBlockEntity(BlockPos pos, BlockState blockState) {
        super(TrainSlothRegistries.LINE_MANAGER_COMPUTER_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LineManagerComputerBlockEntity blockEntity) {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (blockEntity.refreshControlData(level, false)) {
            blockEntity.setChangedAndSync();
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.create_train_sloth.line_manager_computer");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        if (level != null && !level.isClientSide()) {
            if (refreshControlData(level, true)) {
                setChangedAndSync();
            }
        }
        return new LineManagerComputerMenu(containerId, playerInventory, this);
    }

    public List<String> syncedLineIds() {
        return List.copyOf(syncedLineIds);
    }

    public int selectedLineIndex() {
        return selectedLineIndex;
    }

    public String selectedLineLabel() {
        if (syncedLineIds.isEmpty() || selectedLineIndex < 0 || selectedLineIndex >= syncedLineIds.size()) {
            return "-";
        }
        return syncedLineIds.get(selectedLineIndex);
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

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        TrainLine line = lineRegistry == null ? null : lineRegistry.findLine(new LineId(lineId)).orElse(null);
        return recommendedTrainCount(line);
    }

    public int selectedLineTargetTrainCount() {
        String lineId = selectedLineLabel();
        if ("-".equals(lineId)) {
            return 0;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        TrainLine line = lineRegistry == null ? null : lineRegistry.findLine(new LineId(lineId)).orElse(null);
        if (line == null) {
            return selectedLineRecommendedTrainCount();
        }
        return line.settings().resolveTargetTrainCount(recommendedTrainCount(line));
    }

    public boolean selectedLineUsesManualTrainCount() {
        String lineId = selectedLineLabel();
        if ("-".equals(lineId)) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }
        TrainLine line = lineRegistry.findLine(new LineId(lineId)).orElse(null);
        return line != null && line.settings().hasManualTrainCount();
    }

    public List<String> selectedAllowedDepotHubs() {
        return List.copyOf(syncedSelectedAllowedDepotHubs);
    }

    private int recommendedTrainCount(@Nullable TrainLine line) {
        LinePlanningService planningService = CreateTrainSlothMod.runtime().linePlanningService();
        if (planningService == null) {
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

        return planningService.recommendedTrainCount(line, syncedSelectedLineStations.size(), syncedSelectedServiceClass);
    }

    public boolean cycleLineSelection(int delta) {
        if (syncedLineIds.isEmpty()) {
            return false;
        }
        selectedLineIndex = Math.floorMod(selectedLineIndex + delta, syncedLineIds.size());
        if (level != null && !level.isClientSide()) {
            refreshControlData(level, true);
        }
        setChangedAndSync();
        return true;
    }

    public boolean toggleSelectedLineManualTrainCount() {
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

        if (line.settings().hasManualTrainCount()) {
            line.settings().clearManualTrainCount();
        } else {
            int recommended = recommendedTrainCount(line);
            line.settings().setManualTrainCount(Math.max(1, recommended));
        }

        lineRegistry.markDirty();
        refreshControlData(level, true);
        setChangedAndSync();
        return true;
    }

    public boolean adjustSelectedLineManualTrainCount(int delta) {
        if (level == null || level.isClientSide()) {
            return false;
        }
        if (delta == 0) {
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

        int currentTarget = line.settings().hasManualTrainCount()
            ? line.settings().getManualTrainCountRaw()
            : Math.max(1, recommendedTrainCount(line));
        line.settings().setManualTrainCount(currentTarget + delta);

        lineRegistry.markDirty();
        refreshControlData(level, true);
        setChangedAndSync();
        return true;
    }

    public boolean toggleLineDepotHub(String lineIdRaw, String hubIdRaw) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String lineId = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineId.isBlank() || "-".equals(lineId)) {
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

        String normalizedHubId = normalizeDepotHubId(hubIdRaw);
        if (normalizedHubId.isBlank()) {
            return false;
        }

        StationHubRegistry stationHubRegistry = CreateTrainSlothMod.runtime().stationHubRegistry();
        String resolvedHubId = normalizedHubId;
        if (stationHubRegistry != null) {
            Optional<StationHub> resolvedHub = stationHubRegistry.findHubForScheduleFilter(normalizedHubId);
            if (resolvedHub.isPresent()) {
                resolvedHubId = resolvedHub.get().id().value();
                if (!resolvedHub.get().isDepotHub() && !line.settings().allowedDepotHubIds().contains(resolvedHubId)) {
                    return false;
                }
            } else if (!line.settings().allowedDepotHubIds().contains(normalizedHubId)) {
                // Unknown ids may still be removed if they already exist, but should not be newly added.
                return false;
            }
        }
        if (!line.settings().toggleAllowedDepotHubId(resolvedHubId)) {
            return false;
        }

        lineRegistry.markDirty();
        refreshControlData(level, true);
        setChangedAndSync();
        return true;
    }

    private String normalizeDepotHubId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("hubid:")) {
            return value.substring("hubid:".length()).trim();
        }
        if (value.startsWith("hub:")) {
            return value.substring("hub:".length()).trim();
        }
        if (value.startsWith("station:")) {
            return value.substring("station:".length()).trim();
        }
        return value;
    }

    public boolean generateLinesFromHubs() {
        if (level == null || level.isClientSide()) {
            return false;
        }

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        boolean generatedAny = interlockingPlanningService.generateLinesFromHubs(
            lineRegistry,
            CreateTrainSlothMod.runtime().stationHubRegistry() == null
                ? List.of()
                : CreateTrainSlothMod.runtime().stationHubRegistry().allHubs(),
            List.of()
        );
        if (!generatedAny) {
            return false;
        }

        refreshControlData(level, true);
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

        Optional<String> createdLine = interlockingPlanningService.createRoute(
            lineRegistry,
            routeStationsByLine,
            routeServiceByLine,
            routeName,
            serviceClassRaw
        );
        if (createdLine.isEmpty()) {
            return false;
        }

        refreshControlData(level, true);
        selectedLineIndex = Math.max(0, syncedLineIds.indexOf(createdLine.get()));
        refreshControlData(level, true);
        setChangedAndSync();
        return true;
    }

    public boolean updateSelectedRouteMeta(String routeNameRaw, String serviceClassRaw) {
        if (level == null || level.isClientSide()) {
            return false;
        }

        String lineId = selectedLineLabel();
        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            return false;
        }

        boolean changed = interlockingPlanningService.updateRouteMeta(
            lineRegistry,
            routeServiceByLine,
            lineId,
            routeNameRaw,
            serviceClassRaw
        );
        if (!changed) {
            return false;
        }

        refreshControlData(level, true);
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

        boolean removed = interlockingPlanningService.deleteRoute(
            lineRegistry,
            routeServiceByLine,
            routeStationsByLine,
            lineIdValue
        );
        if (!removed) {
            return false;
        }

        refreshControlData(level, true);
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

        boolean changed = interlockingPlanningService.editRouteStation(
            lineRegistry,
            routeStationsByLine,
            routeServiceByLine,
            lineIdValue,
            stationName,
            add
        );
        if (!changed) {
            return false;
        }

        refreshControlData(level, true);
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

        boolean moved = interlockingPlanningService.moveRouteStation(
            lineRegistry,
            routeStationsByLine,
            lineIdValue,
            fromIndex,
            toIndex
        );
        if (!moved) {
            return false;
        }

        refreshControlData(level, true);
        setChangedAndSync();
        return true;
    }

    private boolean refreshControlData(Level level, boolean force) {
        if (!force && level.getGameTime() - lastSyncTick < SYNC_INTERVAL_TICKS) {
            return false;
        }
        lastSyncTick = level.getGameTime();

        LineRegistry lineRegistry = CreateTrainSlothMod.runtime().lineRegistry();
        if (lineRegistry == null) {
            boolean changed = !syncedLineIds.isEmpty()
                || !syncedAssignments.isEmpty()
                || !syncedSelectedLineStations.isEmpty()
                || !syncedSelectedAllowedDepotHubs.isEmpty()
                || !routeServiceByLine.isEmpty()
                || !routeStationsByLine.isEmpty()
                || !"-".equals(syncedSelectedLineName)
                || syncedSelectedServiceClass != TrainServiceClass.RE;
            syncedLineIds.clear();
            syncedAssignments.clear();
            syncedSelectedLineStations.clear();
            syncedSelectedAllowedDepotHubs.clear();
            routeServiceByLine.clear();
            routeStationsByLine.clear();
            syncedSelectedLineName = "-";
            syncedSelectedServiceClass = TrainServiceClass.RE;
            if (clampSelections()) {
                changed = true;
            }
            return changed;
        }

        List<String> newLineIds = lineRegistry.allLines().stream()
            .map(line -> line.id().value())
            .sorted(Comparator.naturalOrder())
            .toList();

        Map<UUID, String> newAssignments = new LinkedHashMap<>();
        lineRegistry.assignments().forEach((trainId, assignment) -> newAssignments.put(trainId, assignment.lineId().value()));

        Map<String, TrainServiceClass> updatedRouteServices = new LinkedHashMap<>();
        Map<String, List<String>> updatedRouteStations = new LinkedHashMap<>();
        for (String lineId : newLineIds) {
            TrainLine line = lineRegistry.findLine(new LineId(lineId)).orElse(null);
            if (line == null) {
                continue;
            }

            TrainServiceClass serviceClass = line.settings().resolveServiceClass();
            TrainServiceClass cachedServiceClass = routeServiceByLine.get(lineId);
            if (cachedServiceClass != null && serviceClass == TrainServiceClass.RE && cachedServiceClass != TrainServiceClass.RE) {
                serviceClass = cachedServiceClass;
            }
            if (line.settings().resolveServiceClass() != serviceClass) {
                line.settings().setServiceClass(serviceClass);
                lineRegistry.markDirty();
            }
            updatedRouteServices.put(lineId, serviceClass);

            List<String> orderedStations = new ArrayList<>();
            List<String> previousStations = routeStationsByLine.get(lineId);
            if (previousStations != null) {
                for (String station : previousStations) {
                    if (line.matchesStationName(station) || line.stationNames().contains(station)) {
                        orderedStations.add(station);
                    }
                }
            }
            for (String station : line.stationNames()) {
                if (!orderedStations.contains(station)) {
                    orderedStations.add(station);
                }
            }
            updatedRouteStations.put(lineId, orderedStations);
        }

        boolean changed = !syncedLineIds.equals(newLineIds)
            || !syncedAssignments.equals(newAssignments)
            || !routeServiceByLine.equals(updatedRouteServices)
            || !routeStationsByLine.equals(updatedRouteStations);

        if (changed) {
            syncedLineIds.clear();
            syncedLineIds.addAll(newLineIds);
            syncedAssignments.clear();
            syncedAssignments.putAll(newAssignments);
            routeServiceByLine.clear();
            routeServiceByLine.putAll(updatedRouteServices);
            routeStationsByLine.clear();
            routeStationsByLine.putAll(updatedRouteStations);
        }

        if (clampSelections()) {
            changed = true;
        }

        String selectedLineId = selectedLineLabel();
        List<String> lineStations = "-".equals(selectedLineId)
            ? List.of()
            : new ArrayList<>(routeStationsByLine.getOrDefault(selectedLineId, List.of()));

        String lineName = "-";
        TrainServiceClass selectedClass = TrainServiceClass.RE;
        List<String> selectedAllowedDepotHubs = List.of();
        if (!"-".equals(selectedLineId)) {
            TrainLine selectedLine = lineRegistry.findLine(new LineId(selectedLineId)).orElse(null);
            if (selectedLine != null) {
                lineName = selectedLine.displayName();
                selectedAllowedDepotHubs = new ArrayList<>(selectedLine.settings().allowedDepotHubIds());
            }
            selectedClass = routeServiceByLine.getOrDefault(selectedLineId, TrainServiceClass.RE);
        }

        if (!syncedSelectedLineStations.equals(lineStations)) {
            syncedSelectedLineStations.clear();
            syncedSelectedLineStations.addAll(lineStations);
            changed = true;
        }

        if (!syncedSelectedLineName.equals(lineName)) {
            syncedSelectedLineName = lineName;
            changed = true;
        }

        if (syncedSelectedServiceClass != selectedClass) {
            syncedSelectedServiceClass = selectedClass;
            changed = true;
        }

        if (!syncedSelectedAllowedDepotHubs.equals(selectedAllowedDepotHubs)) {
            syncedSelectedAllowedDepotHubs.clear();
            syncedSelectedAllowedDepotHubs.addAll(selectedAllowedDepotHubs);
            changed = true;
        }

        return changed;
    }

    private boolean clampSelections() {
        int previousLine = selectedLineIndex;
        if (syncedLineIds.isEmpty()) {
            selectedLineIndex = 0;
        } else {
            selectedLineIndex = Math.max(0, Math.min(selectedLineIndex, syncedLineIds.size() - 1));
        }
        return previousLine != selectedLineIndex;
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
        ListTag linesTag = new ListTag();
        for (String lineId : syncedLineIds) {
            linesTag.add(StringTag.valueOf(lineId));
        }
        tag.put(TAG_LINE_IDS, linesTag);

        ListTag assignmentsTag = new ListTag();
        for (Map.Entry<UUID, String> entry : syncedAssignments.entrySet()) {
            CompoundTag assignment = new CompoundTag();
            assignment.putUUID("TrainId", entry.getKey());
            assignment.putString("LineId", entry.getValue());
            assignmentsTag.add(assignment);
        }
        tag.put(TAG_ASSIGNMENTS, assignmentsTag);
        tag.putInt(TAG_SELECTED_LINE, selectedLineIndex);
        tag.putString(TAG_SELECTED_LINE_NAME, selectedLineName());
        tag.putString(TAG_SELECTED_SERVICE_CLASS, selectedServiceClass());

        ListTag lineStationsTag = new ListTag();
        for (String stationName : syncedSelectedLineStations) {
            lineStationsTag.add(StringTag.valueOf(stationName));
        }
        tag.put(TAG_SELECTED_LINE_STATIONS, lineStationsTag);

        ListTag allowedDepotHubsTag = new ListTag();
        for (String hubId : syncedSelectedAllowedDepotHubs) {
            allowedDepotHubsTag.add(StringTag.valueOf(hubId));
        }
        tag.put(TAG_SELECTED_ALLOWED_DEPOT_HUBS, allowedDepotHubsTag);

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
        syncedLineIds.clear();
        for (Tag element : tag.getList(TAG_LINE_IDS, Tag.TAG_STRING)) {
            syncedLineIds.add(element.getAsString());
        }

        syncedAssignments.clear();
        for (Tag element : tag.getList(TAG_ASSIGNMENTS, Tag.TAG_COMPOUND)) {
            CompoundTag assignment = (CompoundTag) element;
            if (!assignment.hasUUID("TrainId") || !assignment.contains("LineId", Tag.TAG_STRING)) {
                continue;
            }
            syncedAssignments.put(assignment.getUUID("TrainId"), assignment.getString("LineId"));
        }

        selectedLineIndex = tag.getInt(TAG_SELECTED_LINE);

        syncedSelectedLineStations.clear();
        for (Tag element : tag.getList(TAG_SELECTED_LINE_STATIONS, Tag.TAG_STRING)) {
            syncedSelectedLineStations.add(element.getAsString());
        }

        syncedSelectedAllowedDepotHubs.clear();
        for (Tag element : tag.getList(TAG_SELECTED_ALLOWED_DEPOT_HUBS, Tag.TAG_STRING)) {
            syncedSelectedAllowedDepotHubs.add(element.getAsString());
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

    private TrainServiceClass parseServiceClass(String raw) {
        return TrainServiceClass.fromStringOrDefault(raw, TrainServiceClass.RE);
    }
}
