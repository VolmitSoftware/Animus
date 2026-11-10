package art.arcane.animus.typewriter;

import com.typewritermc.core.entries.Ref;
import com.typewritermc.engine.paper.entry.TriggerableEntry;
import com.typewritermc.engine.paper.entry.entries.EventEntry;
import java.util.List;

/** The {@code on_animus_npc_interact} event: a player clicks the Animus NPC its {@code identifier} entry names. */
public final class AnimusNpcInteractEventEntry implements EventEntry {
    private String id = "";
    private String name = "";
    private List<Ref<TriggerableEntry>> triggers = List.of();
    private Ref<AnimusNpcEntry> identifier;

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public List<Ref<TriggerableEntry>> getTriggers() { return triggers; }
    public Ref<AnimusNpcEntry> getIdentifier() { return identifier; }
}
