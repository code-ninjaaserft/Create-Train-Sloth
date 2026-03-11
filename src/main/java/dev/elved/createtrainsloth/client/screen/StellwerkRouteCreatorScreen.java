package dev.elved.createtrainsloth.client.screen;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.menu.StellwerkMenu;
import dev.elved.createtrainsloth.network.EditStellwerkRoutePayload;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class StellwerkRouteCreatorScreen extends AbstractContainerScreen<StellwerkMenu> {

    private static final ResourceLocation SCREEN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/station_hub_screen.png"
    );
    private static final ResourceLocation BUTTONS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/stellwerk_buttons.png"
    );
    private static final int TEXTURE_WIDTH = 128;
    private static final int TEXTURE_HEIGHT = 48;
    private static final int GUI_WIDTH = 236;
    private static final int GUI_HEIGHT = 186;
    private static final int LIST_X = 12;
    private static final int LIST_Y = 94;
    private static final int LIST_W = 212;
    private static final int LIST_H = 72;
    private static final int ROW_H = 12;
    private static final int VISIBLE_ROWS = LIST_H / ROW_H;
    private static final String[] SERVICE_CLASSES = { "S", "IR", "RE", "IC", "ICN", "ICE" };

    private Button backButton;
    private Button linePrevButton;
    private Button lineNextButton;
    private Button servicePrevButton;
    private Button serviceNextButton;
    private Button createRouteButton;
    private Button applyMetaButton;
    private Button addStationButton;
    private Button removeStationButton;
    private EditBox createRouteNameInput;
    private EditBox routeNameInput;
    private EditBox stationInput;
    private int serviceClassIndex = 2;
    private int selectedStationIndex = -1;
    private int dragSourceIndex = -1;
    private int dragTargetIndex = -1;
    private int scrollOffset = 0;
    private String lastSelectedLine = "-";

    public StellwerkRouteCreatorScreen(StellwerkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();

        int contentLeft = leftPos + 12;
        int right = contentLeft + 212;

        backButton = addRenderableWidget(new RouteStyledButton(
            right - 44,
            topPos + 8,
            44,
            14,
            Component.translatable("create_train_sloth.stellwerk.route_creator.button.back"),
            button -> openMainScreen()
        ));

        linePrevButton = addRenderableWidget(new RouteStyledButton(
            contentLeft,
            topPos + 24,
            18,
            14,
            Component.literal("<"),
            button -> sendMenuButton(StellwerkMenu.BUTTON_LINE_PREV)
        ));
        lineNextButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 194,
            topPos + 24,
            18,
            14,
            Component.literal(">"),
            button -> sendMenuButton(StellwerkMenu.BUTTON_LINE_NEXT)
        ));

        servicePrevButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 140,
            topPos + 42,
            16,
            14,
            Component.literal("<"),
            button -> cycleServiceClass(-1)
        ));
        serviceNextButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 196,
            topPos + 42,
            16,
            14,
            Component.literal(">"),
            button -> cycleServiceClass(1)
        ));

        createRouteNameInput = new EditBox(font, contentLeft, topPos + 58, 128, 14, Component.empty());
        createRouteNameInput.setMaxLength(60);
        addRenderableWidget(createRouteNameInput);
        createRouteButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 130,
            topPos + 58,
            82,
            14,
            Component.translatable("create_train_sloth.stellwerk.route_creator.button.create_route"),
            button -> createRoute()
        ));

        routeNameInput = new EditBox(font, contentLeft, topPos + 74, 128, 14, Component.empty());
        routeNameInput.setMaxLength(60);
        addRenderableWidget(routeNameInput);
        applyMetaButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 130,
            topPos + 74,
            82,
            14,
            Component.translatable("create_train_sloth.stellwerk.route_creator.button.apply"),
            button -> applyRouteMeta()
        ));

        stationInput = new EditBox(font, contentLeft, topPos + 168, 128, 14, Component.empty());
        stationInput.setMaxLength(120);
        addRenderableWidget(stationInput);
        addStationButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 130,
            topPos + 168,
            40,
            14,
            Component.translatable("create_train_sloth.stellwerk.route_creator.button.add"),
            button -> addStation()
        ));
        removeStationButton = addRenderableWidget(new RouteStyledButton(
            contentLeft + 172,
            topPos + 168,
            40,
            14,
            Component.translatable("create_train_sloth.stellwerk.route_creator.button.remove"),
            button -> removeSelectedStation()
        ));

        syncFromMenuSelection();
        updateButtonState();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!menu.selectedLineLabel().equals(lastSelectedLine)) {
            syncFromMenuSelection();
        }
        clampSelectionAndScroll();
        updateButtonState();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        graphics.blit(SCREEN_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);

        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;
        graphics.fill(x, y, x + LIST_W, y + LIST_H, 0xCC353535);
        graphics.fill(x, y, x + LIST_W, y + 1, 0xFF999999);
        graphics.fill(x, y + LIST_H - 1, x + LIST_W, y + LIST_H, 0xFF222222);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.route_creator.title"),
            12,
            10,
            0x5C2B1E,
            false
        );
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.route_creator.line"),
            12,
            26,
            0xE7DEC9,
            false
        );
        graphics.drawString(font, trimToWidth(menu.selectedLineLabel(), 160), 34, 27, 0xF6ECD4, false);

        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.route_creator.service_class"),
            12,
            44,
            0xE7DEC9,
            false
        );
        graphics.drawCenteredString(font, currentServiceClass(), 184, 45, 0xF6ECD4);

        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.route_creator.new_route"),
            12,
            60,
            0xD7CEB8,
            false
        );
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.route_creator.route_name"),
            12,
            76,
            0xD7CEB8,
            false
        );
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.stellwerk.route_creator.route_station"),
            12,
            86,
            0xD7CEB8,
            false
        );

        List<String> stations = routeStations();
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int stationIndex = scrollOffset + row;
            if (stationIndex >= stations.size()) {
                break;
            }

            int rowY = LIST_Y + row * ROW_H + 2;
            int textColor = 0xEFE2C8;
            if (stationIndex == selectedStationIndex) {
                graphics.fill(LIST_X + 1, LIST_Y + row * ROW_H + 1, LIST_X + LIST_W - 1, LIST_Y + row * ROW_H + ROW_H, 0xAA4B93DF);
                textColor = 0xFFFFFF;
            } else if (dragSourceIndex >= 0 && stationIndex == dragTargetIndex) {
                graphics.fill(LIST_X + 1, LIST_Y + row * ROW_H + 1, LIST_X + LIST_W - 1, LIST_Y + row * ROW_H + ROW_H, 0x886F6F6F);
            }

            String label = (stationIndex + 1) + ". " + trimToWidth(stations.get(stationIndex), LIST_W - 12);
            graphics.drawString(font, label, LIST_X + 4, rowY, textColor, false);
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
        if (button == 0) {
            int stationIndex = stationIndexAt(mouseX, mouseY);
            if (stationIndex >= 0) {
                selectedStationIndex = stationIndex;
                dragSourceIndex = stationIndex;
                dragTargetIndex = stationIndex;
                updateButtonState();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragSourceIndex >= 0) {
            int stationIndex = stationIndexAt(mouseX, mouseY);
            if (stationIndex >= 0) {
                dragTargetIndex = stationIndex;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragSourceIndex >= 0) {
            if (dragTargetIndex >= 0 && dragTargetIndex != dragSourceIndex) {
                moveStation(dragSourceIndex, dragTargetIndex);
            }
            dragSourceIndex = -1;
            dragTargetIndex = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (insideStationList(mouseX, mouseY)) {
            int maxOffset = Math.max(0, routeStations().size() - VISIBLE_ROWS);
            scrollOffset = Mth.clamp(scrollOffset + (deltaY > 0 ? -1 : 1), 0, maxOffset);
            clampSelectionAndScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == 256) {
            openMainScreen();
            return true;
        }
        if ((keyCode == 257 || keyCode == 335) && createRouteNameInput.isFocused()) {
            createRoute();
            return true;
        }
        if ((keyCode == 257 || keyCode == 335) && stationInput.isFocused()) {
            addStation();
            return true;
        }
        if (createRouteNameInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (routeNameInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (stationInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (createRouteNameInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (routeNameInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (stationInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void sendMenuButton(int id) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void createRoute() {
        String routeName = createRouteNameInput.getValue().trim();
        if (routeName.isBlank()) {
            return;
        }
        PacketDistributor.sendToServer(EditStellwerkRoutePayload.createRoute(menu.blockPos(), routeName, currentServiceClass()));
        createRouteNameInput.setValue("");
    }

    private void applyRouteMeta() {
        String lineId = menu.selectedLineLabel();
        if ("-".equals(lineId)) {
            return;
        }
        PacketDistributor.sendToServer(EditStellwerkRoutePayload.updateMeta(
            menu.blockPos(),
            lineId,
            routeNameInput.getValue().trim(),
            currentServiceClass()
        ));
    }

    private void addStation() {
        String lineId = menu.selectedLineLabel();
        String stationName = stationInput.getValue().trim();
        if ("-".equals(lineId) || stationName.isBlank()) {
            return;
        }
        PacketDistributor.sendToServer(EditStellwerkRoutePayload.addStation(menu.blockPos(), lineId, stationName));
        stationInput.setValue("");
    }

    private void removeSelectedStation() {
        List<String> stations = routeStations();
        String lineId = menu.selectedLineLabel();
        if ("-".equals(lineId) || selectedStationIndex < 0 || selectedStationIndex >= stations.size()) {
            return;
        }
        PacketDistributor.sendToServer(EditStellwerkRoutePayload.removeStation(
            menu.blockPos(),
            lineId,
            stations.get(selectedStationIndex)
        ));
    }

    private void moveStation(int fromIndex, int toIndex) {
        String lineId = menu.selectedLineLabel();
        if ("-".equals(lineId)) {
            return;
        }
        PacketDistributor.sendToServer(EditStellwerkRoutePayload.moveStation(menu.blockPos(), lineId, fromIndex, toIndex));
    }

    private void openMainScreen() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new StellwerkScreen(menu, minecraft.player.getInventory(), title));
    }

    private void cycleServiceClass(int delta) {
        serviceClassIndex = Math.floorMod(serviceClassIndex + delta, SERVICE_CLASSES.length);
        updateButtonState();
    }

    private String currentServiceClass() {
        return SERVICE_CLASSES[Math.max(0, Math.min(serviceClassIndex, SERVICE_CLASSES.length - 1))];
    }

    private void syncFromMenuSelection() {
        lastSelectedLine = menu.selectedLineLabel();
        routeNameInput.setValue(menu.selectedLineName().equals("-") ? "" : menu.selectedLineName());
        String selectedClass = menu.selectedServiceClass();
        for (int i = 0; i < SERVICE_CLASSES.length; i++) {
            if (SERVICE_CLASSES[i].equalsIgnoreCase(selectedClass)) {
                serviceClassIndex = i;
                break;
            }
        }
        selectedStationIndex = -1;
        scrollOffset = 0;
        clampSelectionAndScroll();
    }

    private List<String> routeStations() {
        return menu.selectedLineStations();
    }

    private void clampSelectionAndScroll() {
        List<String> stations = routeStations();
        if (stations.isEmpty()) {
            selectedStationIndex = -1;
            scrollOffset = 0;
            return;
        }

        selectedStationIndex = Mth.clamp(selectedStationIndex, 0, stations.size() - 1);
        int maxOffset = Math.max(0, stations.size() - VISIBLE_ROWS);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxOffset);
        if (selectedStationIndex >= 0) {
            if (selectedStationIndex < scrollOffset) {
                scrollOffset = selectedStationIndex;
            } else if (selectedStationIndex >= scrollOffset + VISIBLE_ROWS) {
                scrollOffset = selectedStationIndex - VISIBLE_ROWS + 1;
            }
        }
    }

    private int stationIndexAt(double mouseX, double mouseY) {
        if (!insideStationList(mouseX, mouseY)) {
            return -1;
        }

        int row = (int) ((mouseY - (topPos + LIST_Y)) / ROW_H);
        int index = scrollOffset + row;
        return index >= 0 && index < routeStations().size() ? index : -1;
    }

    private boolean insideStationList(double mouseX, double mouseY) {
        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;
        return mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + LIST_H;
    }

    private void updateButtonState() {
        boolean hasLine = !"-".equals(menu.selectedLineLabel());
        boolean hasSelection = selectedStationIndex >= 0 && selectedStationIndex < routeStations().size();

        linePrevButton.active = menu.lineCount() > 0;
        lineNextButton.active = menu.lineCount() > 0;
        servicePrevButton.active = true;
        serviceNextButton.active = true;
        createRouteButton.active = !createRouteNameInput.getValue().trim().isBlank();
        applyMetaButton.active = hasLine && !routeNameInput.getValue().trim().isBlank();
        addStationButton.active = hasLine && !stationInput.getValue().trim().isBlank();
        removeStationButton.active = hasLine && hasSelection;
        backButton.active = true;
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

    private static class RouteStyledButton extends Button {

        protected RouteStyledButton(int x, int y, int width, int height, Component message, OnPress onPress) {
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
}
