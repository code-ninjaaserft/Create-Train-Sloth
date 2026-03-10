package dev.elved.createtrainsloth.ponder.scenes;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.Direction;

public final class StellwerkScenes {

    private StellwerkScenes() {
    }

    public static void introduction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stellwerk_intro", "Placing and Powering the Stellwerk");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        Selection world = util.select().everywhere();
        scene.world().showSection(world, Direction.UP);
        scene.idle(15);
        scene.overlay().showText(80)
            .text("The Stellwerk is a central dispatcher for one rail network.");
        scene.idle(45);
        scene.overlay().showText(100)
            .text("Open it to view section states, locks, occupancy, and route reservations in one schematic.");
        scene.idle(90);
    }

    public static void schematicView(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stellwerk_schematic", "Reading the Schematic Network");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(100)
            .text("The center panel is a schematic map, not a world map.");
        scene.idle(60);
        scene.overlay().showText(90)
            .text("Green: free, Yellow: reserved, Red: occupied/blocked, Blue: selected.");
        scene.idle(50);
    }

    public static void manualLocking(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stellwerk_locking", "Locking and Unlocking Sections");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Select a section and lock it before maintenance work.");
        scene.idle(50);
        scene.overlay().showOutline(PonderPalette.RED, "locked_section", util.select().fromTo(1, 1, 2, 3, 1, 2), 50);
        scene.overlay().showControls(
            util.vector().topOf(util.grid().at(2, 1, 2)),
            Pointing.DOWN,
            40
        );
        scene.idle(60);
    }

    public static void automaticRouting(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stellwerk_auto_routing", "Automatic Route Planning");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(100)
            .text("Auto-routing computes routes that avoid blocked, locked, and occupied sections.");
        scene.idle(45);
        scene.overlay().showOutline(PonderPalette.BLUE, "autoroute", util.select().fromTo(2, 1, 1, 2, 1, 3), 60);
        scene.idle(65);
    }

    public static void alternativeRouting(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stellwerk_alternative_routes", "Alternative Path Selection");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(100)
            .text("If the preferred route is blocked, Stellwerk can switch to a safe fallback.");
        scene.idle(50);
        scene.overlay().showOutlineWithText(util.select().fromTo(1, 1, 1, 4, 1, 3), 70)
            .text("Fallback routes use cooldown/hysteresis to avoid constant route flipping.");
        scene.idle(65);
    }

    public static void multiTrainDispatch(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stellwerk_multi_train", "Multi-Train Dispatch Overview");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(10);
        scene.overlay().showText(120)
            .text("One Stellwerk can supervise multiple trains and keep dispatch spacing stable.");
        scene.idle(70);
        scene.overlay().showOutlineWithText(util.select().fromTo(1, 1, 1, 4, 1, 3), 70)
            .text("Use section locks and reservation view to quickly diagnose bottlenecks.");
        scene.idle(80);
    }

    public static void stationHubLinking(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("station_hub_linking", "Grouping Platforms with Station Hubs");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(15);

        scene.overlay().showText(95)
            .text("Place a Station Hub block for a rail system. It acts as one destination with multiple platforms.");
        scene.idle(70);

        scene.overlay().showControls(
            util.vector().topOf(util.grid().at(2, 1, 2)),
            Pointing.DOWN,
            45
        );
        scene.overlay().showText(95)
            .text("Use the Station Link item: click the Hub first, then click Track Stations to assign them.");
        scene.idle(90);

        scene.overlay().showText(100)
            .text("Trains can target the Hub in schedules, and Stellwerk can choose the best free platform.");
        scene.idle(80);
    }
}
