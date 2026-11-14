package art.arcane.animus.bukkit.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.gui.GuiModel;
import java.util.Map;
import java.util.Optional;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

/** The clicks a Bukkit window hands its screen; the mod host maps the same four (AnimusMenuTest). */
class BukkitScreensTest {
    @Test void onlyLeftRightAndTheirShiftClicksReachAScreen() {
        Map<ClickType, GuiModel.Click> bound = Map.of(ClickType.LEFT, GuiModel.Click.LEFT, ClickType.RIGHT, GuiModel.Click.RIGHT,
                ClickType.SHIFT_LEFT, GuiModel.Click.SHIFT_LEFT, ClickType.SHIFT_RIGHT, GuiModel.Click.SHIFT_RIGHT);
        for (ClickType type : ClickType.values()) assertEquals(Optional.ofNullable(bound.get(type)), BukkitScreens.click(type), type.name());
    }
}
