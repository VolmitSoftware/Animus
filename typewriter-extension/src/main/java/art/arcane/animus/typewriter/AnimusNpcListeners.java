package art.arcane.animus.typewriter;

import art.arcane.animus.bukkit.api.AnimusNpcs;
import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import com.typewritermc.core.entries.Query;
import com.typewritermc.core.entries.Ref;
import com.typewritermc.core.interaction.InteractionContextBuilder;
import com.typewritermc.core.interaction.InteractionKt;
import com.typewritermc.engine.paper.entry.TriggerEntryKt;
import java.util.List;
import java.util.Optional;
import kotlin.Unit;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;
import org.bukkit.Bukkit;

/** Entry listeners Typewriter calls; it finds them through the {@code entryListeners} list in extension.json. */
public final class AnimusNpcListeners {
    private AnimusNpcListeners() {}

    /** A click on an Animus NPC starts, or moves on, the dialogue of every event entry on that NPC. */
    public static void onAnimusNpcInteract(VirtualNpcInteractionEvent event, Query<AnimusNpcInteractEventEntry> query) {
        if (event.isPreview()) return;
        AnimusNpcs npcs = Bukkit.getServicesManager().load(AnimusNpcs.class);
        Optional<String> key = npcs == null ? Optional.empty() : npcs.byUuid(event.getNpcId()).map(NpcInfo::key);
        if (key.isEmpty()) return;
        Sequence<AnimusNpcEntry> references = Query.Companion.findWhere(JvmClassMappingKt.getKotlinClass(AnimusNpcEntry.class),
                (AnimusNpcEntry entry) -> key.get().equals(entry.getNpc()));
        List<String> ids = SequencesKt.toList(SequencesKt.map(references, AnimusNpcEntry::getId));
        if (ids.isEmpty()) return;
        Sequence<AnimusNpcInteractEventEntry> events = query.findWhere((AnimusNpcInteractEventEntry entry) -> {
            Ref<AnimusNpcEntry> identifier = entry.getIdentifier();
            return identifier != null && ids.contains(identifier.getId());
        });
        TriggerEntryKt.startDialogueWithOrNextDialogue(events, event.getViewer(), InteractionKt.context((InteractionContextBuilder builder) -> Unit.INSTANCE));
    }
}
