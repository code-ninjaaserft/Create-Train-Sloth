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
import net.minecraft.client.Minecraft;
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
    private static final ResourceLocation BUTTONS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/stellwerk_buttons.png"
    );

    private static final int GUI_WIDTH = 236;
    private static final int GUI_HEIGHT = 186;
    private static final int MAP_X = 10;
    private static final int MAP_Y = 30;
    private static final int MAP_W = 150;
    private static final int MAP_H = 96;
    private static final int CONTROL_X = 164;
    private static final int CONTROL_Y = 30;

    private final Map<Integer, ProjectedNode> projectedNodes = new HashMap<>();
    private int selectedSection = -1;
    private double zoom = 1.0D;
    private double panX;
    private double panY;
    private Button lockButton;
    private Button unlockButton;
    private Button autoRoutingButton;
    private Button missionPingButton;

    public StellwerkScreen(StellwerkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();

        int controlsX = leftPos + CONTROL_X;
        int controlsY = topPos + CONTROL_Y;

        lockButton = addRenderableWidget(new StellwerkStyledButton(
            controlsX,
            controlsY,
            58,
            14,
            Component.translatable("create_train_sloth.stellwerk.button.lock"),
            button -> onLockPressed()
        ));

        unlockButton = addRenderableWidget(new StellwerkStyledButton(
            controlsX,
            controlsY + 16,
            58,
            14,
            Component.translatable("create_train_sloth.stellwerk.button.unlock"),
            button -> onUnlockPressed()
        ));

        autoRoutingButton = addRenderableWidget(new StellwerkStyledButton(
            controlsX,
            controlsY + 32,
            58,
            14,
            autoRoutingText(),
            button -> sendMenuButton(StellwerkMenu.BUTTON_TOGGLE_AUTOROUTING)
        ));

        missionPingButton = addRenderableWidget(new StellwerkStyledButton(
            controlsX,
            controlsY + 48,
            58,
            14,
            Component.translatable("create_train_sloth.stellwerk.button.mission_ping"),
            button -> sendMenuButton(StellwerkMenu.BUTTON_TRIGGER_MISSION_PING)
        ));

        addRenderableWidget(new StellwerkStyledButton(
            controlsX,
            controlsY + 64,
            18,
            14,
            Component.literal("+"),
            button -> zoom = Mth.clamp(zoom + 0.15D, 0.6D, 2.25D)
        ));
        addRenderableWidget(new StellwerkStyledButton(
            controlsX + 20,
            controlsY + 64,
            18,
            14,
            Component.literal("-"),
            button -> zoom = Mth.clamp(zoom - 0.15D, 0.6D, 2.25D)
        ));
        addRenderableWidget(new StellwerkStyledButton(
            controlsX + 40,
            controlsY + 64,
            18,
            14,
            Component.translatable("create_train_sloth.stellwerk.button.center"),
            button -> {
                zoom = 1.0D;
                panX = 0D;
                panY = 0D;
            }
        ));
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
        graphics.drawString(font, Component.translatable("create_train_sloth.stellwerk.title"), 10, 8, 0x5C2B1E, false);

        String summary = "N:" + menu.nodeCount() + " S:" + menu.sectionCount() + " T:" + menu.trainCount();
        graphics.drawString(font, summary, 10, 19, 0x7A5A3E, false);
        graphics.drawString(font, "F " + menu.freeCount(), 94, 19, StellwerkSectionState.FREE.color(), false);
        graphics.drawString(font, "R " + menu.reservedCount(), 118, 19, StellwerkSectionState.RESERVED.color(), false);
        graphics.drawString(font, "O " + menu.occupiedCount(), 145, 19, StellwerkSectionState.OCCUPIED.color(), false);
        graphics.drawString(font, "B " + menu.blockedCount(), 171, 19, StellwerkSectionState.BLOCKED.color(), false);

        StellwerkSectionView section = selectedSectionView();
        if (section == null) {
            graphics.drawString(font, Component.translatable("create_train_sloth.stellwerk.no_selection"), 10, 129, 0x6F6C65, false);
        } else {
            graphics.drawString(font, Component.translatable("create_train_sloth.stellwerk.selected"), 10, 129, 0x5D5A52, false);
            graphics.drawString(
                font,
                Component.translatable("create_train_sloth.stellwerk.section_state", section.state().name()),
                10,
                140,
                section.state().color(),
                false
            );
            String occupiedBy = section.occupiedBy() == null || section.occupiedBy().isBlank() ? "-" : section.occupiedBy();
            graphics.drawString(
                font,
                Component.translatable("create_train_sloth.stellwerk.occupied_by", trimToWidth(occupiedBy, 52)),
                10,
                151,
                0x6D6960,
                false
            );
        }

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

        missionPingButton.active = menu.trackedTrainCount() > 0;
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

    private String trimToWidth(String value, int maxWidth) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private static class StellwerkStyledButton extends Button {

        private static final int TEXTURE_WIDTH = 128;
        private static final int TEXTURE_HEIGHT = 48;

        protected StellwerkStyledButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            OnPress onPress
        ) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int v = !active ? 32 : isHoveredOrFocused() ? 16 : 0;
            blitButtonTiled(graphics, getX(), getY(), width, height, v);

            int color = !active ? 0x9A8E80 : isHoveredOrFocused() ? 0xFFF7E8 : 0xFFEADBC2;
            graphics.drawCenteredString(
                Minecraft.getInstance().font,
                getMessage(),
                getX() + width / 2,
                getY() + (height - 8) / 2,
                color
            );
        }

        private void blitButtonTiled(GuiGraphics graphics, int x, int y, int width, int height, int v) {
            if (width <= 8) {
                graphics.blit(BUTTONS_TEXTURE, x, y, 0, v, width, height, TEXTURE_WIDTH, TEXTURE_HEIGHT);
                return;
            }

            int left = 4;
            int right = 4;
            int middleWidth = width - left - right;

            graphics.blit(BUTTONS_TEXTURE, x, y, 0, v, left, height, TEXTURE_WIDTH, TEXTURE_HEIGHT);

            int drawX = x + left;
            while (middleWidth > 0) {
                int slice = Math.min(120, middleWidth);
                graphics.blit(BUTTONS_TEXTURE, drawX, y, 4, v, slice, height, TEXTURE_WIDTH, TEXTURE_HEIGHT);
                drawX += slice;
                middleWidth -= slice;
            }

            graphics.blit(BUTTONS_TEXTURE, x + width - right, y, 124, v, right, height, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }
    }

    private record ProjectedNode(int x, int y) {
    }
}
