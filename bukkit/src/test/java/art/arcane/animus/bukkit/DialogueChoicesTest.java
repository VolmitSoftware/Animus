package art.arcane.animus.bukkit;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.bukkit.event.VirtualNpcDialogueChoiceEvent;
import art.arcane.animus.core.behavior.builtin.DialogueChoice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Plugins see a dialogue answer before the behavior does, and a cancelled answer never reaches it. */
class DialogueChoicesTest {
    private static Player player(UUID id) {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DialogueChoice choice(UUID player) {
        return new DialogueChoice(new NpcHandle(UUID.randomUUID()), player, Optional.of("town:greet"), "intro", "yes");
    }

    @Test void theEventComesFirstAndACancelStopsTheBehavior() {
        UUID id = UUID.randomUUID();
        Player online = player(id);
        List<String> order = new ArrayList<>();
        DialogueChoices choices = new DialogueChoices((UUID player) -> Optional.of(online), (Event event) -> order.add("event " + ((VirtualNpcDialogueChoiceEvent) event).getOption()),
                (DialogueChoice heard) -> order.add("behavior " + heard.option()));
        choices.publish(choice(id));
        assertEquals(List.of("event yes", "behavior yes"), order);

        order.clear();
        DialogueChoices vetoed = new DialogueChoices((UUID player) -> Optional.of(online), (Event event) -> ((VirtualNpcDialogueChoiceEvent) event).setCancelled(true),
                (DialogueChoice heard) -> order.add("behavior " + heard.option()));
        vetoed.publish(choice(id));
        assertEquals(List.of(), order, "a cancelled answer never reaches the behavior");
    }

    @Test void anAnswerFromAPlayerWhoLeftStillReachesTheBehavior() {
        List<String> order = new ArrayList<>();
        new DialogueChoices((UUID player) -> Optional.empty(), (Event event) -> order.add("event"), (DialogueChoice heard) -> order.add("behavior")).publish(choice(UUID.randomUUID()));
        assertEquals(List.of("behavior"), order);
    }
}
