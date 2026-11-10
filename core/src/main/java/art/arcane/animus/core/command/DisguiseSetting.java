package art.arcane.animus.core.command;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcDisguise;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.text.Palette;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code /animus npc disguise}: the NPC's own {@link NpcDisguise} text, and what its body shows of it. */
final class DisguiseSetting {
    static final String NOT_INSTALLED = "LibsDisguises is not installed, so the NPC keeps its own body until it is";

    private DisguiseSetting() {}

    /** The disguise text, the body it gives and what that body leaves out. */
    static List<String> show(NpcRepository repository, String id) throws IOException {
        NpcDefinition effective = repository.find(id).orElseThrow(() -> new IOException(CommandGroup.unknownNpc(id)));
        Optional<String> text = NpcDisguise.declared(effective.json());
        if (text.isEmpty()) return List.of(id + " has no disguise; /animus npc disguise " + id + " \"zombie setBaby true\" sets one");
        List<String> lines = new ArrayList<>();
        lines.add(id + " wears '" + text.get() + "' as " + effective.json().get("type").getAsString());
        if (!NpcDisguise.available()) lines.add(NOT_INSTALLED);
        lines.addAll(repository.disguiseNotes(id));
        return lines;
    }

    /**
     * Saves the disguise text, or removes it for {@code reset}. With LibsDisguises installed, text it cannot read is refused
     * before anything is saved.
     */
    static List<String> set(NpcRepository repository, String id, String text) throws IOException {
        boolean reset = text.equalsIgnoreCase(NpcCommands.RESET);
        if (!reset) NpcDisguise.resolve(text);
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        if (reset) document.remove(NpcDisguise.FIELD);
        else document.addProperty(NpcDisguise.FIELD, text);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        if (reset) return List.of(Palette.success(id + ": disguise removed; tx " + transaction));
        List<String> lines = new ArrayList<>();
        lines.add(Palette.success(id + ": disguised as " + repository.find(id).orElseThrow().json().get("type").getAsString() + "; tx " + transaction));
        if (!NpcDisguise.available()) lines.add(NOT_INSTALLED);
        lines.addAll(repository.disguiseNotes(id));
        return lines;
    }
}
