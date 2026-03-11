package dev.elved.createtrainsloth.client.screen;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.menu.StationHubMenu;
import dev.elved.createtrainsloth.network.RenameHubPlatformPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class StationHubScreen extends AbstractContainerScreen<StationHubMenu> {

    private static final ResourceLocation SCREEN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/station_hub_screen.png"
    );
    private static final ResourceLocation BUTTONS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "textures/gui/stellwerk_buttons.png"
    );

    private static final int GUI_WIDTH = 236;
    private static final int GUI_HEIGHT = 186;

    private EditBox renameBox;
    private Button previousButton;
    private Button nextButton;
    private Button removeButton;
    private Button renameButton;
    private String lastSelectedPlatform = "-";

    public StationHubScreen(StationHubMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();

        int contentLeft = leftPos + 12;
        int buttonRowY = topPos + 136;

        previousButton = addRenderableWidget(new HubStyledButton(
            contentLeft,
            buttonRowY,
            20,
            16,
            Component.literal("<"),
            button -> sendMenuButton(StationHubMenu.BUTTON_PREVIOUS_STATION)
        ));
        nextButton = addRenderableWidget(new HubStyledButton(
            contentLeft + 22,
            buttonRowY,
            20,
            16,
            Component.literal(">"),
            button -> sendMenuButton(StationHubMenu.BUTTON_NEXT_STATION)
        ));
        removeButton = addRenderableWidget(new HubStyledButton(
            contentLeft + 44,
            buttonRowY,
            168,
            16,
            Component.translatable("create_train_sloth.hub.button.remove"),
            button -> sendMenuButton(StationHubMenu.BUTTON_REMOVE_STATION)
        ));

        renameButton = addRenderableWidget(new HubStyledButton(
            contentLeft,
            topPos + 156,
            212,
            16,
            Component.translatable("create_train_sloth.hub.button.rename"),
            button -> renameSelectedPlatform()
        ));

        renameBox = new EditBox(font, contentLeft, topPos + 116, 212, 16, Component.empty());
        renameBox.setValue(menu.selectedPlatformName().equals("-") ? "" : menu.selectedPlatformName());
        renameBox.setMaxLength(100);
        addRenderableWidget(renameBox);
        setInitialFocus(renameBox);

        updateButtonState();
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        String selected = menu.selectedPlatformName();
        if (!selected.equals(lastSelectedPlatform) && !renameBox.isFocused()) {
            renameBox.setValue(selected.equals("-") ? "" : selected);
            lastSelectedPlatform = selected;
        }

        updateButtonState();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        graphics.blit(SCREEN_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.hub.title"),
            12,
            8,
            0x5C2B1E,
            false
        );
        graphics.drawString(font, trimToWidth(menu.hubName(), 208), 12, 20, 0x7A5A3E, false);
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.hub.platform_count", menu.platformCount()),
            12,
            34,
            0xE7DEC9,
            false
        );
        graphics.drawString(
            font,
            Component.translatable(
                "create_train_sloth.hub.availability",
                menu.freePlatforms(),
                menu.soonFreePlatforms(),
                menu.blockedPlatforms()
            ),
            12,
            46,
            0xD7CEB8,
            false
        );
        graphics.drawString(
            font,
            Component.translatable("create_train_sloth.hub.selected_station"),
            12,
            60,
            0xE7DEC9,
            false
        );
        graphics.drawString(font, trimToWidth(menu.selectedPlatformName(), 208), 12, 72, 0xF6ECD4, false);
        graphics.drawString(font, Component.translatable("create_train_sloth.hub.rename_hint"), 12, 104, 0xD7CEB8, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && (keyCode == 256 || minecraft.options.keyInventory.matches(keyCode, scanCode))) {
            onClose();
            return true;
        }

        if ((keyCode == 257 || keyCode == 335) && renameBox.isFocused()) {
            renameSelectedPlatform();
            return true;
        }

        if (renameBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renameBox.charTyped(codePoint, modifiers)) {
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

    private void renameSelectedPlatform() {
        String selected = menu.selectedPlatformName();
        String renamed = renameBox.getValue().trim();
        if (selected.equals("-") || renamed.isBlank() || renamed.equals(selected)) {
            return;
        }

        PacketDistributor.sendToServer(new RenameHubPlatformPayload(menu.blockPos(), selected, renamed));
    }

    private void updateButtonState() {
        boolean hasSelection = menu.platformCount() > 0 && !menu.selectedPlatformName().equals("-");
        previousButton.active = hasSelection;
        nextButton.active = hasSelection;
        removeButton.active = hasSelection;
        renameButton.active = hasSelection && !renameBox.getValue().trim().isBlank();
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

    private static class HubStyledButton extends Button {

        private static final int TEXTURE_WIDTH = 128;
        private static final int TEXTURE_HEIGHT = 48;

        protected HubStyledButton(int x, int y, int width, int height, Component message, OnPress onPress) {
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
