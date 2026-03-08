package dev.elved.createtrainsloth.line;

import dev.elved.createtrainsloth.config.TrainSlothConfig;
import net.minecraft.nbt.CompoundTag;

public class LineSettings {

    private int minimumIntervalTicks = -1;
    private int targetIntervalOverrideTicks = -1;
    private int minimumDwellTicks = -1;
    private int dwellExtensionTicks = -1;
    private int safetyBufferTicks = -1;
    private double resynchronizationAggressiveness = -1D;
    private int routeSwitchCooldownTicks = -1;
    private int routeReplanWaitTicks = -1;

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
        return settings;
    }
}
