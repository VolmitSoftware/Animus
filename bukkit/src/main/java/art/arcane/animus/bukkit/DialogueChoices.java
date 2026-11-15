package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.event.VirtualNpcDialogueChoiceEvent;
import art.arcane.animus.core.behavior.builtin.DialogueChoice;
import art.arcane.animus.core.behavior.builtin.DialogueChoiceSink;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * A validated dialogue answer, on the thread that owns the player: plugins see it first as a cancellable
 * {@link VirtualNpcDialogueChoiceEvent}, and the behavior hears it only when none cancelled it.
 */
final class DialogueChoices implements DialogueChoiceSink {
    private final Function<UUID, Optional<Player>> players;
    private final Consumer<Event> events;
    private final DialogueChoiceSink behaviors;

    DialogueChoices(Function<UUID, Optional<Player>> players, Consumer<Event> events, DialogueChoiceSink behaviors) {
        this.players = Objects.requireNonNull(players);
        this.events = Objects.requireNonNull(events);
        this.behaviors = Objects.requireNonNull(behaviors);
    }

    @Override public void publish(DialogueChoice choice) {
        Optional<Player> player = players.apply(choice.player());
        if (player.isPresent()) {
            VirtualNpcDialogueChoiceEvent event = new VirtualNpcDialogueChoiceEvent(player.get(), choice.npc().uuid(), choice.behavior(), choice.prompt(), choice.option());
            events.accept(event);
            if (event.isCancelled()) return;
        }
        behaviors.publish(choice);
    }
}
