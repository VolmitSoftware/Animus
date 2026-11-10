package art.arcane.animus.core.migration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relinks one BetonQuest 3 package file from Citizens NPCs to the Animus NPCs they were imported as. Only single-line
 * {@code key: value} entries change; comments, layout and every other line keep their bytes. In {@code npcs},
 * {@code citizens <id>} becomes {@code animus <animus id>} and {@code citizens <name> byName} becomes
 * {@code animus <name> byName}. An {@code npckill} objective on a relinked NPC becomes {@code animusnpckill}, which Animus
 * registers. Anything else that needs Citizens is reported and left alone.
 */
public final class BetonQuestLinks {
    public record Change(String path, int line, String before, String after) {}
    /** The new text (the same instance when nothing changed), the changes, the relinked {@code npcs} keys and review notes. */
    public record Result(String text, List<Change> changes, Set<String> linkedNpcs, List<String> notes) {
        public Result { changes = List.copyOf(changes); linkedNpcs = Set.copyOf(linkedNpcs); notes = List.copyOf(notes); }
    }

    private static final Pattern SECTION = Pattern.compile("^([A-Za-z0-9_-]+):(.*)$");
    private static final Pattern ENTRY = Pattern.compile("^(\\s+)([^\\s#][^:]*?):(\\s*)(['\"]?)(.*?)\\4(\\s*(?:#.*)?)$");
    private static final Pattern NUMBER = Pattern.compile("0|[1-9][0-9]{0,9}");
    private static final Set<String> CITIZENS_ACTIONS = Set.of("npcmove", "npcstop");

    private BetonQuestLinks() {}

    /**
     * {@code linkedNpcs} are the {@code npcs} keys relinked anywhere in the plugin, found by a first pass with an empty set;
     * an {@code npckill} names one of them by its last {@code >} part.
     */
    public static Result rewrite(String path, String text, NpcLinks links, Set<String> linkedNpcs) {
        List<Change> changes = new ArrayList<>();
        Set<String> linked = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        StringBuilder out = new StringBuilder(text.length() + 64);
        String section = "";
        int number = 0;
        for (String raw : text.split("(?<=\n)", -1)) {
            if (raw.isEmpty()) continue;
            number++;
            String ending = raw.endsWith("\r\n") ? "\r\n" : raw.endsWith("\n") ? "\n" : "";
            String line = raw.substring(0, raw.length() - ending.length());
            Matcher top = SECTION.matcher(line);
            if (top.matches()) {
                section = top.group(1);
                String rest = top.group(2).strip();
                if (section.equals("npcs") && (rest.startsWith("{") || rest.startsWith("[")))
                    notes.add(path + ":" + number + ": npcs is written on one line; relink its Citizens NPCs by hand");
                out.append(raw);
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            Optional<String> value = entry.matches() ? relinked(path + ":" + number + " " + section + "." + entry.group(2).strip(), section, entry.group(5), links, linkedNpcs, notes) : Optional.empty();
            if (value.isEmpty()) { out.append(raw); continue; }
            String after = entry.group(1) + entry.group(2) + ":" + entry.group(3) + entry.group(4) + value.get() + entry.group(4) + entry.group(6);
            if (section.equals("npcs")) linked.add(entry.group(2).strip());
            changes.add(new Change(path, number, line, after));
            out.append(after).append(ending);
        }
        return new Result(changes.isEmpty() ? text : out.toString(), changes, linked, notes);
    }

    private static Optional<String> relinked(String where, String section, String value, NpcLinks links, Set<String> linkedNpcs, List<String> notes) {
        String[] words = value.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return Optional.empty();
        String first = words[0].toLowerCase(Locale.ROOT);
        if (section.equals("npcs")) {
            if (!first.equals("citizens")) return Optional.empty();
            if (words.length < 2) { notes.add(where + ": names no Citizens NPC"); return Optional.empty(); }
            String rest = value.strip().substring(words[0].length()).strip();
            String argument = words[1];
            boolean byName = List.of(words).subList(2, words.length).stream().anyMatch("byName"::equalsIgnoreCase);
            if (byName) {
                if (links.named(argument)) return Optional.of("animus " + rest);
                notes.add(where + ": no imported NPC is named " + argument + "; left as is");
                return Optional.empty();
            }
            if (!NUMBER.matcher(argument).matches()) { notes.add(where + ": " + argument + " is not a Citizens id; relink it by hand"); return Optional.empty(); }
            Optional<String> key = links.citizens(Integer.parseInt(argument));
            if (key.isEmpty()) { notes.add(where + ": Citizens NPC " + argument + " was not imported; left as is"); return Optional.empty(); }
            return Optional.of("animus " + key.get() + rest.substring(argument.length()));
        }
        if (CITIZENS_ACTIONS.contains(first)) { notes.add(where + ": " + first + " needs Citizens and is not converted"); return Optional.empty(); }
        if (!first.equals("npckill")) return Optional.empty();
        String reference = words.length > 1 ? words[1] : "";
        if (!linkedNpcs.contains(reference.substring(reference.lastIndexOf('>') + 1))) {
            notes.add(where + ": npckill " + reference + " names no relinked NPC; left as is");
            return Optional.empty();
        }
        String stripped = value.strip();
        return Optional.of("animusnpckill" + stripped.substring(words[0].length()));
    }
}
