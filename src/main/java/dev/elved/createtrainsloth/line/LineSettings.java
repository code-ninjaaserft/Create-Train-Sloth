package dev.elved.createtrainsloth.line;

import dev.elved.createtrainsloth.config.TrainSlothConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public class LineSettings {

    private int minimumIntervalTicks = -1;
    private int targetIntervalOverrideTicks = -1;
    private int minimumDwellTicks = -1;
    private int dwellExtensionTicks = -1;
    private int safetyBufferTicks = -1;
    private double resynchronizationAggressiveness = -1D;
    private int routeSwitchCooldownTicks = -1;
    private int routeReplanWaitTicks = -1;
    private TrainServiceClass serviceClass = TrainServiceClass.RE;
    private int manualTrainCount = -1;
    private final List<String> allowedDepotHubIds = new ArrayList<>();

    public int resolveMinimumIntervalTicks() {
        return minimumIntervalTicks > 0 ? minimumIntervalTicks : TrainSlothConfig.DISPATCH.minimumIntervalTicks.get();
    }

    public int resolveTargetIntervalOverrideTicks() {
        return targetIntervalOverrideTicks > 0 ? targetIntervalOverrideTicks : TrainSlothConfig.DISPATCH.targetIntervalOverrideTicks.get();
    }

    public int resolveMinimumDwellTicks() {
        return minimumDwellTicks >= 0 ? minimumDwellTicks : TrainSlothConfig.DISPATCH.minimumDwellTicks.get();
    }

    public int resolveDwellExtensionTicks() {
        return dwellExtensionTicks >= 0 ? dwellExtensionTicks : TrainSlothConfig.DISPATCH.dwellExtensionTicks.get();
    }

    public int resolveSafetyBufferTicks() {
        return safetyBufferTicks >= 0 ? safetyBufferTicks : TrainSlothConfig.DISPATCH.safetyBufferTicks.get();
    }

    public double resolveResynchronizationAggressiveness() {
        return resynchronizationAggressiveness >= 0D ? resynchronizationAggressiveness : TrainSlothConfig.DISPATCH.resynchronizationAggressiveness.get();
    }

    public int resolveRouteSwitchCooldownTicks() {
        return routeSwitchCooldownTicks >= 0 ? routeSwitchCooldownTicks : TrainSlothConfig.ROUTING.switchCooldownTicks.get();
    }

    public int resolveRouteReplanWaitTicks() {
        return routeReplanWaitTicks >= 0 ? routeReplanWaitTicks : TrainSlothConfig.ROUTING.replanWaitTicks.get();
    }

    public TrainServiceClass resolveServiceClass() {
        return serviceClass == null ? TrainServiceClass.RE : serviceClass;
    }

    public boolean hasManualTrainCount() {
        return manualTrainCount > 0;
    }

    public int resolveTargetTrainCount(int recommendedTrainCount) {
        int recommended = Math.max(1, recommendedTrainCount);
        return hasManualTrainCount() ? Math.max(1, manualTrainCount) : recommended;
    }

    public List<String> allowedDepotHubIds() {
        return List.copyOf(allowedDepotHubIds);
    }

    public boolean hasDepotHubRestrictions() {
        return !allowedDepotHubIds.isEmpty();
    }

    public boolean allowsDepotHubId(String hubIdRaw) {
        if (!hasDepotHubRestrictions()) {
            return true;
        }
        String hubId = normalizeDepotHubId(hubIdRaw);
        return !hubId.isBlank() && allowedDepotHubIds.contains(hubId);
    }

    public int getMinimumIntervalTicksRaw() {
        return minimumIntervalTicks;
    }

    public void setMinimumIntervalTicks(int value) {
        this.minimumIntervalTicks = value;
    }

    public int getTargetIntervalOverrideTicksRaw() {
        return targetIntervalOverrideTicks;
    }

    public void setTargetIntervalOverrideTicks(int value) {
        this.targetIntervalOverrideTicks = value;
    }

    public int getMinimumDwellTicksRaw() {
        return minimumDwellTicks;
    }

    public void setMinimumDwellTicks(int value) {
        this.minimumDwellTicks = value;
    }

    public int getDwellExtensionTicksRaw() {
        return dwellExtensionTicks;
    }

    public void setDwellExtensionTicks(int value) {
        this.dwellExtensionTicks = value;
    }

    public int getSafetyBufferTicksRaw() {
        return safetyBufferTicks;
    }

    public void setSafetyBufferTicks(int value) {
        this.safetyBufferTicks = value;
    }

    public double getResynchronizationAggressivenessRaw() {
        return resynchronizationAggressiveness;
    }

    public void setResynchronizationAggressiveness(double value) {
        this.resynchronizationAggressiveness = value;
    }

    public int getRouteSwitchCooldownTicksRaw() {
        return routeSwitchCooldownTicks;
    }

    public void setRouteSwitchCooldownTicks(int value) {
        this.routeSwitchCooldownTicks = value;
    }

    public int getRouteReplanWaitTicksRaw() {
        return routeReplanWaitTicks;
    }

    public void setRouteReplanWaitTicks(int value) {
        this.routeReplanWaitTicks = value;
    }

    public TrainServiceClass getServiceClassRaw() {
        return serviceClass;
    }

    public void setServiceClass(TrainServiceClass value) {
        this.serviceClass = value == null ? TrainServiceClass.RE : value;
    }

    public int getManualTrainCountRaw() {
        return manualTrainCount;
    }

    public void setManualTrainCount(int value) {
        this.manualTrainCount = Math.max(1, Math.min(64, value));
    }

    public void clearManualTrainCount() {
        this.manualTrainCount = -1;
    }

    public boolean toggleAllowedDepotHubId(String hubIdRaw) {
        String hubId = normalizeDepotHubId(hubIdRaw);
        if (hubId.isBlank()) {
            return false;
        }
        if (allowedDepotHubIds.contains(hubId)) {
            allowedDepotHubIds.remove(hubId);
            return true;
        }
        allowedDepotHubIds.add(hubId);
        return true;
    }

    public void setAllowedDepotHubIds(List<String> hubIds) {
        allowedDepotHubIds.clear();
        if (hubIds == null) {
            return;
        }
        for (String hubId : hubIds) {
            String normalized = normalizeDepotHubId(hubId);
            if (!normalized.isBlank() && !allowedDepotHubIds.contains(normalized)) {
                allowedDepotHubIds.add(normalized);
            }
        }
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("MinimumIntervalTicks", minimumIntervalTicks);
        tag.putInt("TargetIntervalOverrideTicks", targetIntervalOverrideTicks);
        tag.putInt("MinimumDwellTicks", minimumDwellTicks);
        tag.putInt("DwellExtensionTicks", dwellExtensionTicks);
        tag.putInt("SafetyBufferTicks", safetyBufferTicks);
        tag.putDouble("ResyncAggressiveness", resynchronizationAggressiveness);
        tag.putInt("RouteSwitchCooldownTicks", routeSwitchCooldownTicks);
        tag.putInt("RouteReplanWaitTicks", routeReplanWaitTicks);
        tag.putString("ServiceClass", resolveServiceClass().name());
        tag.putInt("ManualTrainCount", manualTrainCount);
        ListTag depotHubsTag = new ListTag();
        for (String hubId : allowedDepotHubIds) {
            depotHubsTag.add(StringTag.valueOf(hubId));
        }
        tag.put("AllowedDepotHubs", depotHubsTag);
        return tag;
    }

    public static LineSettings read(CompoundTag tag) {
        LineSettings settings = new LineSettings();
        settings.minimumIntervalTicks = tag.getInt("MinimumIntervalTicks");
        settings.targetIntervalOverrideTicks = tag.getInt("TargetIntervalOverrideTicks");
        settings.minimumDwellTicks = tag.getInt("MinimumDwellTicks");
        settings.dwellExtensionTicks = tag.getInt("DwellExtensionTicks");
        settings.safetyBufferTicks = tag.getInt("SafetyBufferTicks");
        settings.resynchronizationAggressiveness = tag.getDouble("ResyncAggressiveness");
        settings.routeSwitchCooldownTicks = tag.getInt("RouteSwitchCooldownTicks");
        settings.routeReplanWaitTicks = tag.getInt("RouteReplanWaitTicks");
        settings.serviceClass = TrainServiceClass.fromStringOrDefault(tag.getString("ServiceClass"), TrainServiceClass.RE);
        settings.manualTrainCount = tag.getInt("ManualTrainCount");
        List<String> allowedHubs = new ArrayList<>();
        for (Tag hubTag : tag.getList("AllowedDepotHubs", Tag.TAG_STRING)) {
            allowedHubs.add(hubTag.getAsString());
        }
        settings.setAllowedDepotHubIds(allowedHubs);
        return settings;
    }

    private String normalizeDepotHubId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("hubid:")) {
            value = value.substring("hubid:".length()).trim();
        } else if (value.startsWith("hub:")) {
            value = value.substring("hub:".length()).trim();
        }
        return value;
    }
}
