package art.arcane.animus.core.command;

import art.arcane.animus.core.npc.GroupMerge;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.npc.VanillaAttributes;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/** {@code /animus npc}: focused edits of one NPC's definition file. */
@Director(name = "npc", description = "Change one setting of an NPC's definition")
final class NpcCommands extends CommandGroup {
    static final String RESET = "reset";

    NpcCommands(CommandServices services) { super(services); }

    @Director(description = "Show, set or reset one vanilla attribute of an NPC, such as scale or movement_speed")
    void attribute(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                   @Param(name = "attribute", description = "A vanilla attribute id such as scale or minecraft:gravity") String attribute,
                   @Param(name = "value", defaultValue = "", description = "A number in the attribute's range, reset, or nothing to show it") String value) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            if (value.isEmpty()) return show(repository, id, attribute);
            String line = attribute(repository, id, attribute, value);
            living().refresh();
            return List.of(Palette.success(line));
        }));
    }

    /** The NPC's effective value of an attribute, where it comes from, and its vanilla default and range. */
    static List<String> show(NpcRepository repository, String id, String attribute) throws IOException {
        VanillaAttributes.Attribute known = known(attribute);
        NpcDefinition effective = repository.find(id).orElseThrow(() -> new IOException("Unknown NPC: " + id));
        OptionalDouble current = NpcAttributes.of(effective.json()).get(known.id());
        String range = "vanilla default " + NpcAttributes.format(known.defaultValue()) + ", range " + NpcAttributes.format(known.minimum()) + ".." + NpcAttributes.format(known.maximum());
        List<String> lines = new ArrayList<>();
        if (current.isEmpty()) lines.add(id + ": " + known.id() + " is not set; " + range);
        else {
            String source = repository.provenance(id).getOrDefault(NpcAttributes.FIELD + "." + known.id(), GroupMerge.NPC);
            lines.add(id + ": " + known.id() + " = " + NpcAttributes.format(current.getAsDouble()) + " (" + source + "); " + range);
        }
        lines.add(NpcAttributes.note(known.id()));
        return lines;
    }

    /** Sets or resets one attribute in the NPC's own file as a new revision; returns the line to answer. */
    static String attribute(NpcRepository repository, String id, String attribute, String value) throws IOException {
        VanillaAttributes.Attribute known = known(attribute);
        boolean reset = value.equalsIgnoreCase(RESET);
        double number = 0;
        if (!reset) {
            try { number = Double.parseDouble(value); }
            catch (NumberFormatException notNumber) { throw new IllegalArgumentException("value must be a number or reset"); }
            String problem = NpcAttributes.problem(known.id(), number);
            if (problem != null) throw new IllegalArgumentException(problem);
        }
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonElement existing = document.get(NpcAttributes.FIELD);
        JsonObject attributes = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
        for (String key : List.copyOf(attributes.keySet())) if (NpcAttributes.id(key).filter(known.id()::equals).isPresent()) attributes.remove(key);
        if (!reset) attributes.addProperty(known.id(), number);
        if (attributes.isEmpty()) document.remove(NpcAttributes.FIELD);
        else document.add(NpcAttributes.FIELD, attributes);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + ": " + known.id() + (reset ? " reset" : " = " + NpcAttributes.format(number)) + "; transaction " + transaction;
    }

    /** Attribute ids for completion, bare. */
    static List<String> ids() { return NpcAttributes.table().stream().map(VanillaAttributes.Attribute::id).toList(); }

    private static VanillaAttributes.Attribute known(String attribute) {
        return NpcAttributes.attribute(attribute).orElseThrow(() -> new IllegalArgumentException(NpcAttributes.problem(attribute, 0)));
    }
}
