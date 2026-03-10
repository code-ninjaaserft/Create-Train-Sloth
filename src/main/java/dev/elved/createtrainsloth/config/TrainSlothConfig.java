package dev.elved.createtrainsloth.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class TrainSlothConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final Dispatch DISPATCH = new Dispatch(BUILDER);
    public static final Routing ROUTING = new Routing(BUILDER);
    public static final ScheduleIntegration SCHEDULE = new ScheduleIntegration(BUILDER);
    public static final Debug DEBUG = new Debug(BUILDER);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TrainSlothConfig() {
    }

    public static final class Dispatch {
        public final ModConfigSpec.BooleanValue enableAutomaticDispatch;
        public final ModConfigSpec.IntValue minimumIntervalTicks;
        public final ModConfigSpec.IntValue targetIntervalOverrideTicks;
        public final ModConfigSpec.IntValue minimumDwellTicks;
        public final ModConfigSpec.IntValue dwellExtensionTicks;
        public final ModConfigSpec.IntValue safetyBufferTicks;
        public final ModConfigSpec.IntValue fallbackRoundTripTicks;
        public final ModConfigSpec.DoubleValue resynchronizationAggressiveness;

        private Dispatch(ModConfigSpec.Builder builder) {
            builder.push("dispatch");
            enableAutomaticDispatch = builder.comment("Enable automatic line headway dispatching.")
                .define("enableAutomaticDispatch", true);
            minimumIntervalTicks = builder.comment("Global minimum departure spacing for trains in the same line.")
                .defineInRange("minimumIntervalTicks", 200, 20, 20 * 60 * 30);
            targetIntervalOverrideTicks = builder.comment("Global fixed interval override. <= 0 means dynamic from round-trip estimate.")
                .defineInRange("targetIntervalOverrideTicks", -1, -1, 20 * 60 * 30);
            minimumDwellTicks = builder.comment("Minimum station dwell before a dispatched departure is allowed.")
                .defineInRange("minimumDwellTicks", 100, 0, 20 * 60 * 10);
            dwellExtensionTicks = builder.comment("Extra dwell added by dispatch control for stabilization.")
                .defineInRange("dwellExtensionTicks", 20, 0, 20 * 60 * 10);
            safetyBufferTicks = builder.comment("Extra buffer applied to computed headway.")
                .defineInRange("safetyBufferTicks", 40, 0, 20 * 60 * 10);
            fallbackRoundTripTicks = builder.comment("Fallback round-trip estimate when no telemetry exists.")
                .defineInRange("fallbackRoundTripTicks", 20 * 60 * 2, 20, 20 * 60 * 60);
            resynchronizationAggressiveness = builder.comment("0..1 - how aggressively late lines are compressed back toward target headway.")
                .defineInRange("resynchronizationAggressiveness", 0.25D, 0D, 1D);
            builder.pop();
        }
    }

    public static final class Routing {
        public final ModConfigSpec.BooleanValue enableAlternativeRouting;
        public final ModConfigSpec.BooleanValue enableScheduleAlternativeInstruction;
        public final ModConfigSpec.BooleanValue enableProactivePlatformPlanning;
        public final ModConfigSpec.BooleanValue enablePreDepartureAlternativeSelection;
        public final ModConfigSpec.BooleanValue enableInterlockingOverride;
        public final ModConfigSpec.BooleanValue requireInterlockingBlockForOverride;
        public final ModConfigSpec.BooleanValue useScheduleDestinationAlternatives;
        public final ModConfigSpec.BooleanValue enableNumericStationFamilyFallback;
        public final ModConfigSpec.IntValue replanWaitTicks;
        public final ModConfigSpec.IntValue switchCooldownTicks;
        public final ModConfigSpec.IntValue maxCandidatePaths;
        public final ModConfigSpec.IntValue maxSearchCost;
        public final ModConfigSpec.IntValue scoreImprovementThreshold;

        private Routing(ModConfigSpec.Builder builder) {
            builder.push("routing");
            enableAlternativeRouting = builder.comment("Enable route fallback when preferred track is blocked.")
                .define("enableAlternativeRouting", true);
            enableScheduleAlternativeInstruction = builder.comment("Enable the 'Alternative Destination' Create schedule instruction handling.")
                .define("enableScheduleAlternativeInstruction", true);
            enableProactivePlatformPlanning = builder.comment("Plan and reserve preferred platform alternatives ahead of signals.")
                .define("enableProactivePlatformPlanning", true);
            enablePreDepartureAlternativeSelection = builder.comment("Select alternative destination entries before departure when the primary station/path is blocked.")
                .define("enablePreDepartureAlternativeSelection", true);
            enableInterlockingOverride = builder.comment("Enable central interlocking override logic for routing decisions.")
                .define("enableInterlockingOverride", true);
            requireInterlockingBlockForOverride = builder.comment("Require at least one placed interlocking block to activate routing override.")
                .define("requireInterlockingBlockForOverride", true);
            useScheduleDestinationAlternatives = builder.comment("Use current Create schedule destination filters to discover fallback stations.")
                .define("useScheduleDestinationAlternatives", true);
            enableNumericStationFamilyFallback = builder.comment("Treat stations like 'Name', 'Name 2', 'Name 3' as alternatives of one station family.")
                .define("enableNumericStationFamilyFallback", true);
            replanWaitTicks = builder.comment("Ticks waiting for signal before alternative route selection starts.")
                .defineInRange("replanWaitTicks", 60, 0, 20 * 60 * 5);
            switchCooldownTicks = builder.comment("Minimum ticks between route switches per train.")
                .defineInRange("switchCooldownTicks", 120, 0, 20 * 60 * 5);
            maxCandidatePaths = builder.comment("Upper bound of route candidates considered per replan.")
                .defineInRange("maxCandidatePaths", 8, 1, 64);
            maxSearchCost = builder.comment("Max navigation cost passed to Create path search.")
                .defineInRange("maxSearchCost", 20_000, 100, 1_000_000);
            scoreImprovementThreshold = builder.comment("Required score improvement before switching away from current route.")
                .defineInRange("scoreImprovementThreshold", 50, 0, 100_000);
            builder.pop();
        }
    }

    public static final class ScheduleIntegration {
        public final ModConfigSpec.BooleanValue enableScheduleLineAutoSetup;
        public final ModConfigSpec.BooleanValue forceScheduleLineAssignment;
        public final ModConfigSpec.BooleanValue deriveDwellFromTimedConditions;

        private ScheduleIntegration(ModConfigSpec.Builder builder) {
            builder.push("scheduleIntegration");
            enableScheduleLineAutoSetup = builder.comment("Derive line definitions and station filters from Create schedule entries.")
                .define("enableScheduleLineAutoSetup", true);
            forceScheduleLineAssignment = builder.comment("If true, schedule-derived line assignment overwrites command assignment for that train.")
                .define("forceScheduleLineAssignment", true);
            deriveDwellFromTimedConditions = builder.comment("Use Create timed wait conditions as minimum dwell baseline for derived lines.")
                .define("deriveDwellFromTimedConditions", true);
            builder.pop();
        }
    }

    public static final class Debug {
        public final ModConfigSpec.BooleanValue verboseLogs;

        private Debug(ModConfigSpec.Builder builder) {
            builder.push("debug");
            verboseLogs = builder.comment("Enable verbose server logs for dispatch and routing decisions.")
                .define("verboseLogs", true);
            builder.pop();
        }
    }
}
