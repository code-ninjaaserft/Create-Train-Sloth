package dev.elved.createtrainsloth.client.screen;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkNodeView;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSchematicSnapshot;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSectionState;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkSectionView;
import dev.elved.createtrainsloth.interlocking.schematic.StellwerkTrainView;
import dev.elved.createtrainsloth.menu.StellwerkMenu;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

public class StellwerkScreen extends AbstractContainerScreen<StellwerkMenu> {

    private static final ResourceLocation SCREEN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/stellwerk_screen.png"
    );
    private static final ResourceLocation ICONS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/stellwerk_icons.png"
    );

    private static final int GUI_WIDTH = 248;
    private static final int GUI_HEIGHT = 222;
    private static final int MAP_X = 70;
    private static final int MAP_Y = 22;
    private static final int MAP_W = 167;
    private static final int MAP_H = 137;

    private final Map<Integer, ProjectedNode> projectedNodes = new HashMap<>();
    private int selectedSection = -1;
    private double zoom = 1.0D;
    private double panX;
    private double panY;
    private Button lockButton;
    private Button unlockButton;
    private Button autoRoutingButton;

    public StellwerkScreen(StellwerkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();

        int controlsX = leftPos + 163;
        int controlsY = topPos + 169;

        lockButton = Button.builder(
                Component.translatable("create_train_sloth.stellwerk.button.lock"),
                button -> onLockPressed()
            )
            .bounds(controlsX, controlsY, 34, 16)
            .build();
        addRenderableWidget(lockButton);

        unlockButton = Button.builder(
                Component.translatable("create_train_sloth.stellwerk.button.unlock"),
                button -> onUnlockPressed()
            )
            .bounds(controlsX + 38, controlsY, 34, 16)
            .build();
        addRenderableWidget(unlockButton);

        autoRoutingButton = Button.builder(
                autoRoutingText(),
                button -> sendMenuButton(StellwerkMenu.BUTTON_TOGGLE_AUTOROUTING)
            )
            .bounds(controlsX, controlsY + 19, 72, 16)
            .build();
        addRenderableWidget(autoRoutingButton);

        addRenderableWidget(Button.builder(Component.literal("+"), button -> zoom = Mth.clamp(zoom + 0.15D, 0.6D, 2.25D))
            .bounds(controlsX, controlsY + 38, 22, 16)
            .build());
        addRenderableWidget(Button.builder(Component.literal("-"), button -> zoom = Mth.clamp(zoom - 0.15D, 0.6D, 2.25D))
            .bounds(controlsX + 25, controlsY + 38, 22, 16)
            .build());
        addRenderableWidget(Button.builder(
                Component.translatable("create_train_sloth.stellwerk.button.center"),
                button -> {
                    zoom = 1.0D;
                    panX = 0D;
                    panY = 0D;
                })
            .bounds(controlsX + 50, controlsY + 38, 22, 16)
            .build());

        updateButtonState();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        autoRoutingButton.setMessage(autoRoutingText());
        updateButtonState();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        graphics.blit(SCREEN_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
        drawSchematic(graphics);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("create_train_sloth.stellwerk.title"), 10, 7, 0xF0E6C8, false);

        String summary = "N:" + menu.nodeCount() + " S:" + menu.sectionCount() + " T:" + menu.trainCount();
        graphics.drawString(font, summary, 10, 18, 0xB0A98A, false);
        graphics.drawString(font, "F " + menu.freeCount(), 92, 18, StellwerkSectionState.FREE.color(), false);
        graphics.drawString(font, "R " + menu.reservedCount(), 118, 18, StellwerkSectionState.RESERVED.color(), false);
        graphics.drawString(font, "O " + menu.occupiedCount(), 145, 18, StellwerkSectionState.OCCUPIED.color(), false);
        graphics.drawString(font, "B " + menu.blockedCount(), 173, 18, StellwerkSectionState.BLOCKED.color(), false);

        StellwerkSectionView section = selectedSectionView();
        if (section == null) {
            graphics.drawString(font, Component.translatable("create_train_sloth.stellwerk.no_selection"), 10, 170, 0x9A9272, false);
            return;
        }

        graphics.drawString(font, Component.translatable("create_train_sloth.stellwerk.selected"), 10, 167, 0xD3C8A3, false);
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.section_state", section.state().name()),
            10,
            179,
            section.state().color(),
            false
        );
        String occupiedBy = section.occupiedBy() == null || section.occupiedBy().isBlank() ? "-" : section.occupiedBy();
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.occupied_by", occupiedBy),
            10,
            191,
            0xB7B099,
            false
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics, mouseX, mouseY, partialTicks);
        super.render(graphics, mouseX, mouseY, partialTicks);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideMap(mouseX, mouseY)) {
            int nearest = nearestSectionIndex(mouseX, mouseY);
            if (nearest >= 0) {
                selectedSection = nearest;
                updateButtonState();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && insideMap(mouseX, mouseY)) {
            panX = Mth.clamp(panX + dragX, -MAP_W / 2D, MAP_W / 2D);
            panY = Mth.clamp(panY + dragY, -MAP_H / 2D, MAP_H / 2D);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void drawSchematic(GuiGraphics graphics) {
        StellwerkSchematicSnapshot snapshot = snapshot();
        projectedNodes.clear();
        if (snapshot.nodes().isEmpty()) {
            return;
        }

        int clipMinX = leftPos + MAP_X;
        int clipMinY = topPos + MAP_Y;
        int clipMaxX = clipMinX + MAP_W;
        int clipMaxY = clipMinY + MAP_H;
        graphics.enableScissor(clipMinX, clipMinY, clipMaxX, clipMaxY);

        double minX = snapshot.nodes().stream().mapToDouble(StellwerkNodeView::x).min().orElse(0D);
        double maxX = snapshot.nodes().stream().mapToDouble(StellwerkNodeView::x).max().orElse(1D);
        double minZ = snapshot.nodes().stream().mapToDouble(StellwerkNodeView::z).min().orElse(0D);
        double maxZ = snapshot.nodes().stream().mapToDouble(StellwerkNodeView::z).max().orElse(1D);

        double spanX = Math.max(1D, maxX - minX);
        double spanZ = Math.max(1D, maxZ - minZ);
        double centerX = leftPos + MAP_X + MAP_W / 2D;
        double centerY = topPos + MAP_Y + MAP_H / 2D;

        for (StellwerkNodeView node : snapshot.nodes()) {
            double normalizedX = (node.x() - minX) / spanX;
            double normalizedZ = (node.z() - minZ) / spanZ;
            double rawX = leftPos + MAP_X + 6 + normalizedX * (MAP_W - 12);
            double rawY = topPos + MAP_Y + MAP_H - 6 - normalizedZ * (MAP_H - 12);

            int projectedX = Mth.floor(centerX + (rawX - centerX) * zoom + panX);
            int projectedY = Mth.floor(centerY + (rawY - centerY) * zoom + panY);
            projectedNodes.put(node.index(), new ProjectedNode(projectedX, projectedY));
        }

        for (StellwerkSectionView section : snapshot.sections()) {
            ProjectedNode from = projectedNodes.get(section.fromNodeIndex());
            ProjectedNode to = projectedNodes.get(section.toNodeIndex());
            if (from == null || to == null) {
                continue;
            }

            int color = section.index() == selectedSection ? 0xFF4B93DF : section.state().color();
            drawLine(graphics, from.x, from.y, to.x, to.y, color);
        }

        for (StellwerkNodeView node : snapshot.nodes()) {
            ProjectedNode projected = projectedNodes.get(node.index());
            if (projected == null) {
                continue;
            }

            int color = node.station() ? 0xFFDFC68F : node.junction() ? 0xFFD6D6D6 : 0xFF8E8E8E;
            graphics.fill(projected.x - 1, projected.y - 1, projected.x + 2, projected.y + 2, color);
        }

        for (StellwerkTrainView train : snapshot.trains()) {
            if (train.sectionIndex() < 0 || train.sectionIndex() >= snapshot.sections().size()) {
                continue;
            }

            StellwerkSectionView section = snapshot.sections().get(train.sectionIndex());
            ProjectedNode from = projectedNodes.get(section.fromNodeIndex());
            ProjectedNode to = projectedNodes.get(section.toNodeIndex());
            if (from == null || to == null) {
                continue;
            }

            int centerTrainX = (from.x + to.x) / 2;
            int centerTrainY = (from.y + to.y) / 2;
            graphics.blit(ICONS_TEXTURE, centerTrainX - 4, centerTrainY - 4, 0, 0, 8, 8, 64, 64);
        }

        graphics.disableScissor();
    }

    private void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            graphics.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }

        double xStep = dx / (double) steps;
        double yStep = dy / (double) steps;
        double x = x1;
        double y = y1;
        for (int i = 0; i <= steps; i++) {
            int px = Mth.floor(x);
            int py = Mth.floor(y);
            graphics.fill(px, py, px + 1, py + 1, color);
            x += xStep;
            y += yStep;
        }
    }

    private void onLockPressed() {
        if (selectedSection < 0) {
            return;
        }
        sendMenuButton(StellwerkMenu.BUTTON_LOCK_SECTION_BASE + selectedSection);
    }

    private void onUnlockPressed() {
        if (selectedSection < 0) {
            return;
        }
        sendMenuButton(StellwerkMenu.BUTTON_UNLOCK_SECTION_BASE + selectedSection);
    }

    private void sendMenuButton(int id) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void updateButtonState() {
        StellwerkSectionView selected = selectedSectionView();
        boolean hasSelection = selected != null;
        lockButton.active = hasSelection;
        unlockButton.active = hasSelection && selected.locked();
    }

    private Component autoRoutingText() {
        return Component.translatable(
            menu.autoRoutingEnabled()
                ? "create_train_sloth.stellwerk.button.auto_on"
                : "create_train_sloth.stellwerk.button.auto_off"
        );
    }

    private StellwerkSchematicSnapshot snapshot() {
        return menu.blockEntity() == null ? StellwerkSchematicSnapshot.empty() : menu.blockEntity().snapshot();
    }

    private StellwerkSectionView selectedSectionView() {
        List<StellwerkSectionView> sections = snapshot().sections();
        if (selectedSection < 0 || selectedSection >= sections.size()) {
            return null;
        }
        return sections.get(selectedSection);
    }

    private boolean insideMap(double mouseX, double mouseY) {
        int x1 = leftPos + MAP_X;
        int y1 = topPos + MAP_Y;
        return mouseX >= x1 && mouseX < x1 + MAP_W && mouseY >= y1 && mouseY < y1 + MAP_H;
    }

    private int nearestSectionIndex(double mouseX, double mouseY) {
        StellwerkSchematicSnapshot snapshot = snapshot();
        double bestDistanceSq = Double.MAX_VALUE;
        int bestIndex = -1;
        for (StellwerkSectionView section : snapshot.sections()) {
            ProjectedNode from = projectedNodes.get(section.fromNodeIndex());
            ProjectedNode to = projectedNodes.get(section.toNodeIndex());
            if (from == null || to == null) {
                continue;
            }

            double distanceSq = pointToSegmentDistanceSq(mouseX, mouseY, from.x, from.y, to.x, to.y);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestIndex = section.index();
            }
        }
        return bestDistanceSq <= 49D ? bestIndex : -1;
    }

    private static double pointToSegmentDistanceSq(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0D && dy == 0D) {
            return sq(px - x1) + sq(py - y1);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Mth.clamp(t, 0D, 1D);
        double cx = x1 + t * dx;
        double cy = y1 + t * dy;
        return sq(px - cx) + sq(py - cy);
    }

    private static double sq(double value) {
        return value * value;
    }

    private record ProjectedNode(int x, int y) {
    }
}
