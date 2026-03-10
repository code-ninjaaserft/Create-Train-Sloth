package dev.elved.createtrainsloth.schedule;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.TextScheduleInstruction;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public class StellwerkControlInstruction extends TextScheduleInstruction {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "stellwerk_control"
    );

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(icon(), Component.literal(resolveModeName(getLabelText())));
    }

    @Override
    public List<Component> getTitleAs(String type) {
        return ImmutableList.of(
            Component.translatable("create_train_sloth.schedule." + type + ".stellwerk_control")
                .withStyle(ChatFormatting.GOLD),
            Component.translatable("generic.in_quotes", Component.literal(resolveModeName(getLabelText())))
        );
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public ItemStack getSecondLineIcon() {
        return icon();
    }

    @Override
    public boolean supportsConditions() {
        return false;
    }

    @Override
    public List<Component> getSecondLineTooltip(int slot) {
        return ImmutableList.of(
            Component.translatable("create_train_sloth.schedule.tooltip.stellwerk_control"),
            Component.translatable("create_train_sloth.schedule.tooltip.stellwerk_control_hint")
                .withStyle(ChatFormatting.GRAY)
        );
    }

    @Nullable
    @Override
    public DiscoveredPath start(ScheduleRuntime runtime, Level level) {
        boolean enabled = parseEnabled(getLabelText());
        if (CreateTrainSlothMod.runtime().stellwerkControlModeService() != null && runtime.train != null) {
            CreateTrainSlothMod.runtime().stellwerkControlModeService().setStellwerkEnabled(runtime.train.id, enabled);
        }

        runtime.state = ScheduleRuntime.State.PRE_TRANSIT;
        runtime.currentEntry++;
        return null;
    }

    private ItemStack icon() {
        return new ItemStack(Items.COMPARATOR);
    }

    private static boolean parseEnabled(String input) {
        String value = input == null ? "" : input.trim().toLowerCase();
        if (value.isEmpty()) {
            return true;
        }
        return !(value.equals("legacy")
            || value.equals("off")
            || value.equals("false")
            || value.equals("0")
            || value.equals("create"));
    }

    private static String resolveModeName(String input) {
        return parseEnabled(input) ? "Stellwerk" : "Legacy";
    }
}
