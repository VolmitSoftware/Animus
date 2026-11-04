package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerSink;
import java.util.Objects;
import java.util.function.Supplier;

/** Delivers validated clicks to {@code dialogue_choice} triggers. The supplier returns null where triggers are not running. */
public final class DialogueTriggers implements DialogueChoiceSink {
    private final Supplier<TriggerSink> triggers;

    public DialogueTriggers(Supplier<TriggerSink> triggers) { this.triggers = Objects.requireNonNull(triggers); }

    @Override public void publish(DialogueChoice choice) {
        TriggerSink sink = triggers.get();
        if (sink != null) sink.publish(new TriggerEvent.DialogueChoice(choice.npc().uuid(), choice.player(), choice.prompt(), choice.option()));
    }
}
