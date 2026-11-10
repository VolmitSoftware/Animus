package art.arcane.animus.core.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line edits to the NPC references Quests and BeautyQuests save. Only the lines that name a Citizens NPC change; every
 * other byte of the file, comments and item data included, stays as it was. A reference whose NPC was not imported is
 * reported and left alone, and so is every other value of the same key, because each key holds one kind of id.
 */
final class QuestYaml {
    private QuestYaml() {}

    /**
     * The edited text; {@code references} it pointed at Animus NPCs, {@code linked} ones that already did, a line per
     * changed key and a line per reference no Animus NPC answers.
     */
    record Rewrite(String text, int references, int linked, List<String> changes, List<String> problems) {
        boolean changed() { return !changes.isEmpty(); }
    }

    /** Quests' legacy Citizens id keys and the UUID keys Quests reads in their place. */
    private static final Map<String, String> QUESTS_LEGACY = Map.of(
            "npc-giver-id", "npc-giver-uuid",
            "npc-ids-to-talk-to", "npc-uuids-to-talk-to",
            "npc-delivery-ids", "npc-delivery-uuids",
            "npc-ids-to-kill", "npc-uuids-to-kill",
            "ride-npc", "ride-npc-uuid");
    private static final Set<String> QUESTS_UUIDS = Set.copyOf(QUESTS_LEGACY.values());
    /** BeautyQuests keys that hold one {@code factory#id} NPC reference: stage NPCs, pool NPCs and quest starters. */
    private static final Set<String> BEAUTY_NPC_KEYS = Set.of("npcID", "starterNPC", "starterID");
    static final String BEAUTY_FACTORY = "animus", BEAUTY_MOBS = "animusNPC";
    private static final String CITIZENS_MOBS = "citizensNPC";

    private static final Pattern ENTRY = Pattern.compile("^( *)(- +)?([^\\s:#'\"\\[{-][^:#]*?|'[^']*'|\"[^\"]*\")\\s*:(?:\\s+(.*?))?\\s*$");
    private static final Pattern ITEM = Pattern.compile("^( *)- +(.*?)\\s*$");
    private static final Pattern CITIZENS_REFERENCE = Pattern.compile("(?:citizens#)?(0|[1-9][0-9]{0,9})");

    /** One parsed {@code key: value} line; {@code column} is where the key starts, past any list dash. */
    private record Entry(int line, String indent, String dash, String key, String raw) {
        int column() { return indent.length() + dash.length(); }
        String prefix() { return indent + dash; }
    }
    private record Edit(int start, int end, List<String> lines) {}
    /** A key's values and the lines they span: one scalar, a flow list or a block list. */
    private record Values(List<String> values, Shape shape, int end, List<String> itemIndents) {}
    private enum Shape { SCALAR, FLOW, BLOCK }

    static Rewrite quests(String text, NpcLinks links) {
        Document document = new Document(text);
        for (int index = 0; index < document.lines.size(); index++) {
            Optional<Entry> entry = document.entry(index);
            if (entry.isEmpty()) continue;
            Entry found = entry.get();
            if (QUESTS_LEGACY.containsKey(found.key())) questsLegacy(document, found, links);
            else if (QUESTS_UUIDS.contains(found.key())) questsUuids(document, found, links);
        }
        return document.finish();
    }

    private static void questsLegacy(Document document, Entry entry, NpcLinks links) {
        Values values = document.values(entry);
        List<NpcLinks.Link> targets = new ArrayList<>();
        for (String value : values.values()) {
            Optional<Integer> id = citizensId(value, false);
            Optional<NpcLinks.Link> target = id.flatMap(links::imported);
            if (target.isEmpty()) {
                document.problem(entry, entry.key() + (id.isPresent() ? " names Citizens NPC " + id.get() + ", which no imported NPC has" : " has " + value + ", not a Citizens NPC id"));
                continue;
            }
            targets.add(target.get());
        }
        if (targets.size() != values.values().size() || targets.isEmpty()) return;
        String key = QUESTS_LEGACY.get(entry.key());
        List<String> quoted = targets.stream().map((NpcLinks.Link target) -> "'" + target.uuid() + "'").toList();
        List<String> replacement = new ArrayList<>();
        switch (values.shape()) {
            case SCALAR -> replacement.add(entry.prefix() + key + ": " + quoted.getFirst());
            case FLOW -> replacement.add(entry.prefix() + key + ": [" + String.join(", ", quoted) + "]");
            case BLOCK -> {
                replacement.add(entry.prefix() + key + ":");
                for (int item = 0; item < quoted.size(); item++) replacement.add(values.itemIndents().get(item) + "- " + quoted.get(item));
            }
        }
        document.references += targets.size();
        document.edit(new Edit(entry.line(), values.end(), replacement),
                entry, entry.key() + " " + String.join(", ", values.values()) + " -> " + String.join(", ", targets.stream().map(NpcLinks.Link::key).toList()) + " (" + key + ")");
    }

    private static void questsUuids(Document document, Entry entry, NpcLinks links) {
        for (String value : document.values(entry).values()) {
            UUID uuid;
            try { uuid = UUID.fromString(value); }
            catch (IllegalArgumentException invalid) { document.problem(entry, entry.key() + " " + value + " is not a UUID"); continue; }
            if (links.has(uuid)) document.linked++;
            else document.problem(entry, entry.key() + " " + uuid + " is not an Animus NPC");
        }
    }

    static Rewrite beautyQuests(String text, NpcLinks links) {
        Document document = new Document(text);
        for (int index = 0; index < document.lines.size(); index++) {
            Optional<Entry> entry = document.entry(index);
            if (entry.isEmpty()) continue;
            Entry found = entry.get();
            if (BEAUTY_NPC_KEYS.contains(found.key()) && !found.raw().isEmpty()) beautyNpc(document, found, links);
            else if (found.key().equals("factoryName") && scalar(found.raw()).equals(CITIZENS_MOBS)) beautyMob(document, found, links);
        }
        return document.finish();
    }

    private static void beautyNpc(Document document, Entry entry, NpcLinks links) {
        String value = scalar(entry.raw());
        if (value.startsWith(BEAUTY_FACTORY + "#")) {
            if (links.key(value.substring(BEAUTY_FACTORY.length() + 1)).isPresent()) document.linked++;
            else document.problem(entry, entry.key() + " " + value + " is not an Animus NPC");
            return;
        }
        Optional<Integer> id = citizensId(value, true);
        if (id.isEmpty()) return;
        NpcLinks.Link target = links.imported(id.get()).orElse(null);
        if (target == null) { document.problem(entry, entry.key() + " names Citizens NPC " + id.get() + ", which no imported NPC has"); return; }
        document.references++;
        document.edit(new Edit(entry.line(), entry.line() + 1, List.of(entry.prefix() + entry.key() + ": " + BEAUTY_FACTORY + "#" + target.key())),
                entry, entry.key() + " " + value + " -> " + BEAUTY_FACTORY + "#" + target.key());
    }

    private static void beautyMob(Document document, Entry entry, NpcLinks links) {
        Optional<Entry> sibling = document.sibling(entry, "value");
        Optional<Integer> id = sibling.flatMap((Entry value) -> citizensId(scalar(value.raw()), false));
        if (id.isEmpty()) { document.problem(entry, "kill target has no Citizens NPC id"); return; }
        NpcLinks.Link target = links.imported(id.get()).orElse(null);
        if (target == null) { document.problem(entry, "kill target names Citizens NPC " + id.get() + ", which no imported NPC has"); return; }
        Entry value = sibling.get();
        document.references++;
        document.edit(new Edit(entry.line(), entry.line() + 1, List.of(entry.prefix() + "factoryName: " + BEAUTY_MOBS)), entry,
                "kill target Citizens NPC " + id.get() + " -> " + BEAUTY_MOBS + " " + target.key());
        document.edit(new Edit(value.line(), value.line() + 1, List.of(value.prefix() + "value: " + target.key())), null, null);
    }

    /** A Citizens id: a bare number, and with {@code factory} also BeautyQuests' {@code citizens#12}. */
    private static Optional<Integer> citizensId(String value, boolean factory) {
        Matcher matcher = CITIZENS_REFERENCE.matcher(value);
        if (!matcher.matches() || (!factory && value.startsWith("citizens#"))) return Optional.empty();
        try { return Optional.of(Integer.parseInt(matcher.group(1))); }
        catch (NumberFormatException tooLarge) { return Optional.empty(); }
    }

    /** A plain, single-quoted or double-quoted YAML scalar without its quotes or trailing comment. */
    static String scalar(String raw) {
        String value = raw.strip();
        if (value.startsWith("'")) {
            int close = value.indexOf('\'', 1);
            while (close >= 0 && close + 1 < value.length() && value.charAt(close + 1) == '\'') close = value.indexOf('\'', close + 2);
            return value.substring(1, close < 0 ? value.length() : close).replace("''", "'");
        }
        if (value.startsWith("\"")) {
            int close = value.indexOf('"', 1);
            return value.substring(1, close < 0 ? value.length() : close);
        }
        return value.replaceFirst("\\s+#.*$", "").strip();
    }

    /** The file as lines, the edits planned on them and the report so far. */
    private static final class Document {
        final String eol;
        final List<String> lines;
        final List<Edit> edits = new ArrayList<>();
        final List<String> changes = new ArrayList<>(), problems = new ArrayList<>();
        int references, linked;

        Document(String text) {
            eol = text.contains("\r\n") ? "\r\n" : "\n";
            lines = new ArrayList<>(List.of(text.split(Pattern.quote(eol), -1)));
        }

        Optional<Entry> entry(int index) {
            Matcher matcher = ENTRY.matcher(lines.get(index));
            if (!matcher.matches()) return Optional.empty();
            String key = matcher.group(3);
            if (key.length() >= 2 && (key.startsWith("'") || key.startsWith("\""))) key = scalar(key);
            return Optional.of(new Entry(index, matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2), key, matcher.group(4) == null ? "" : matcher.group(4)));
        }

        Values values(Entry entry) {
            String raw = entry.raw();
            if (raw.startsWith("[")) {
                int close = raw.indexOf(']');
                String inside = raw.substring(1, close < 0 ? raw.length() : close);
                List<String> values = inside.isBlank() ? List.of() : java.util.Arrays.stream(inside.split(",")).map(QuestYaml::scalar).toList();
                return new Values(values, Shape.FLOW, entry.line() + 1, List.of());
            }
            if (!scalar(raw).isEmpty()) return new Values(List.of(scalar(raw)), Shape.SCALAR, entry.line() + 1, List.of());
            List<String> values = new ArrayList<>(), indents = new ArrayList<>();
            int next = entry.line() + 1;
            while (next < lines.size()) {
                Matcher item = ITEM.matcher(lines.get(next));
                if (!item.matches() || item.group(1).length() < entry.indent().length() || ENTRY.matcher(lines.get(next)).matches()) break;
                values.add(scalar(item.group(2)));
                indents.add(item.group(1));
                next++;
            }
            return new Values(values, Shape.BLOCK, next, indents);
        }

        /** The entry with {@code key} in the same YAML mapping as {@code entry}, before or after it. */
        Optional<Entry> sibling(Entry entry, String key) {
            int column = entry.column();
            for (int index = entry.line() + 1; index < lines.size(); index++) {
                int lead = lead(lines.get(index));
                if (lead < 0) continue;
                if (lead < column) break;
                Optional<Entry> other = lead == column ? entry(index) : Optional.empty();
                if (other.isPresent() && other.get().dash().isEmpty() && other.get().key().equals(key)) return other;
            }
            if (!entry.dash().isEmpty()) return Optional.empty();
            for (int index = entry.line() - 1; index >= 0; index--) {
                int lead = lead(lines.get(index));
                if (lead < 0 || lead > column) continue;
                Optional<Entry> other = entry(index);
                if (other.isEmpty() || other.get().column() != column) return Optional.empty();
                if (other.get().key().equals(key)) return other;
                if (!other.get().dash().isEmpty()) return Optional.empty();
            }
            return Optional.empty();
        }

        /** Leading spaces, or -1 for a blank or comment line. */
        private static int lead(String line) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) return -1;
            return line.length() - line.stripLeading().length();
        }

        void edit(Edit edit, Entry entry, String change) {
            edits.add(edit);
            if (change != null) changes.add("line " + (entry.line() + 1) + ": " + change);
        }

        void problem(Entry entry, String problem) { problems.add("line " + (entry.line() + 1) + ": " + problem); }

        Rewrite finish() {
            List<String> result = new ArrayList<>(lines);
            edits.stream().sorted(Comparator.comparingInt(Edit::start).reversed()).forEach((Edit edit) -> {
                for (int index = edit.end() - 1; index >= edit.start(); index--) result.remove(index);
                result.addAll(edit.start(), edit.lines());
            });
            return new Rewrite(String.join(eol, result), references, linked, List.copyOf(changes), List.copyOf(problems));
        }
    }
}
