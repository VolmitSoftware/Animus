package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

/** Option text stays literal: it is placed in a component, never parsed for formatting codes. */
final class BukkitChoiceMessages {
    private BukkitChoiceMessages() {}

    static BaseComponent[] options(String commandLabel, String token, List<ChoiceOption> options) {
        List<BaseComponent> components = new ArrayList<>(options.size() * 2);
        for (ChoiceOption option : options) {
            if (!components.isEmpty()) components.add(new TextComponent("  "));
            TextComponent button = new TextComponent("[" + option.text() + "]");
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + commandLabel + " choice " + token + " " + option.id()));
            components.add(button);
        }
        return components.toArray(new BaseComponent[0]);
    }
}
