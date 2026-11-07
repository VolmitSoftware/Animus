package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /animus} help grouped by topic. Each entry shows its name, its parameter chips and its description on one line;
 * hovering shows the usage and an example; a click puts a command that takes parameters in the chat box and runs one
 * that does not, or opens a group. A player gets pages that fit the chat window with clickable arrows; the console gets
 * every entry as plain text.
 */
public final class AnimusHelp {
    /** Chat rows a page body may fill: the chat window less the banner and the page bar. */
    static final int ROWS = 17;
    /** Characters of default-font text that fit one chat row, a little under the real width so estimates never overflow. */
    static final int ROW_CHARACTERS = 52;
    /** Optional parameters shown as chips before the rest fold into one {@code [+N]} chip. */
    static final int OPTIONAL_CHIPS = 3;
    private static final String ARROW = "⇀ ";

    /** A heading of the root page, the docs section it links and the root entries under it, in order. */
    record Topic(String title, DocsLink docs, List<String> names) {}

    static final List<Topic> TOPICS = List.of(
            new Topic("NPCs", DocsLink.COMMANDS, List.of("status", "list", "create", "inspect", "edit", "clone", "delete", "spawn", "despawn",
                    "select", "selection", "validate", "reload", "rollback", "npc")),
            new Topic("Movement", DocsLink.MOVEMENT, List.of("navigation", "ride")),
            new Topic("Combat", DocsLink.COMBAT, List.of("combat")),
            new Topic("Content", DocsLink.CONTENT, List.of("modules", "rigs", "pack", "import", "record")),
            new Topic("Services", DocsLink.SERVICES, List.of("shop", "gui", "build", "editor")),
            new Topic("Behaviors", DocsLink.BEHAVIORS, List.of("behaviors")),
            new Topic("Groups", DocsLink.GROUPS, List.of("groups")),
            new Topic("Diagnostics", DocsLink.DIAGNOSTICS, List.of("debug", "proof")),
            new Topic("Tools", DocsLink.TOOLS, List.of("tools", "setup")));

    /** Verbs in the order a group lists them: reading first, then doing, then undoing; unlisted verbs follow A-Z. */
    private static final List<String> VERBS = List.of("status", "list", "inspect", "members", "summary", "capabilities", "problems", "packs", "triggers",
            "trace", "schematics", "tokens", "performance", "network", "npc", "watch", "mode", "go", "look", "create", "start", "run", "play", "open", "menu",
            "preview", "stage", "apply", "plan", "review", "accept", "engage", "disengage", "target", "guard", "attack", "mob", "release", "assign", "unassign",
            "action", "condition", "spawn", "move", "path", "mount", "control", "folia", "pause", "resume", "survey", "donate", "team", "ghost", "pair",
            "revoke", "validate", "schema", "reload", "build", "gloss", "file", "import", "export", "citizens", "close", "stop", "cancel", "clear", "reset",
            "rollback", "delete", "state", "behavior", "player", "relationship", "all", "confirm");

    /** A topic heading with the entries under it. */
    public record Section(String title, DocsLink docs, List<DirectorRuntimeNode> entries) {
        public Section { entries = List.copyOf(entries); }
    }

    /** One help request: every section of the group, and the page a player asked for, counted from 0. */
    public record Page(DirectorRuntimeNode group, List<Section> sections, int index) {
        public Page {
            Objects.requireNonNull(group);
            sections = List.copyOf(sections);
        }

        /** Every entry, in the order shown. */
        public List<DirectorRuntimeNode> entries() { return sections.stream().flatMap((Section section) -> section.entries().stream()).toList(); }
    }

    private AnimusHelp() {}

    /** The help of {@code group} limited to {@code visible}: by topic at the root, as one section inside a group. */
    public static Page page(DirectorRuntimeNode group, List<DirectorRuntimeNode> visible, int index) {
        List<Section> sections = new ArrayList<>();
        if (group.getParent() == null) {
            Map<String, DirectorRuntimeNode> byName = new LinkedHashMap<>();
            for (DirectorRuntimeNode node : visible) byName.put(node.getDescriptor().getName(), node);
            for (Topic topic : TOPICS) {
                List<DirectorRuntimeNode> entries = new ArrayList<>();
                for (String name : topic.names()) { DirectorRuntimeNode node = byName.remove(name); if (node != null) entries.add(node); }
                if (!entries.isEmpty()) sections.add(new Section(topic.title(), topic.docs(), entries));
            }
            if (!byName.isEmpty()) sections.add(new Section("More", DocsLink.COMMANDS, sorted(List.copyOf(byName.values()))));
        } else if (!visible.isEmpty()) {
            sections.add(new Section(title(group), topic(group).docs(), sorted(visible)));
        }
        return new Page(group, sections, Math.max(0, index));
    }

    /** {@code /animus behaviors state} reads as {@code Behaviors > state}: the root entry's topic, then the inner names. */
    private static String title(DirectorRuntimeNode group) {
        List<String> names = new ArrayList<>();
        for (DirectorRuntimeNode cursor = group; cursor.getParent() != null; cursor = cursor.getParent()) names.addFirst(cursor.getDescriptor().getName());
        String head = Character.toUpperCase(names.getFirst().charAt(0)) + names.getFirst().substring(1);
        return names.size() == 1 ? head : head + " " + String.join(" ", names.subList(1, names.size()));
    }

    /** The root topic a node sits under. */
    static Topic topic(DirectorRuntimeNode node) {
        DirectorRuntimeNode top = node;
        while (top.getParent() != null && top.getParent().getParent() != null) top = top.getParent();
        String name = top.getDescriptor().getName();
        for (Topic topic : TOPICS) if (topic.names().contains(name)) return topic;
        return TOPICS.getFirst();
    }

    private static List<DirectorRuntimeNode> sorted(List<DirectorRuntimeNode> nodes) {
        Comparator<DirectorRuntimeNode> verb = Comparator.comparingInt((DirectorRuntimeNode node) -> {
            int rank = VERBS.indexOf(node.getDescriptor().getName());
            return rank < 0 ? VERBS.size() : rank;
        });
        return nodes.stream().sorted(verb.thenComparing((DirectorRuntimeNode node) -> node.getDescriptor().getName(), String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** A heading or an entry, with the chat rows it fills. */
    private record Row(ChatLine line, int rows) {}

    /** The rows of every page; a section cut by a page break repeats its heading on the next page. */
    static List<List<Row>> pages(Page page) {
        List<List<Row>> pages = new ArrayList<>();
        List<Row> current = new ArrayList<>();
        int used = 0;
        for (Section section : page.sections()) {
            Row heading = heading(section);
            boolean headed = false;
            for (DirectorRuntimeNode node : section.entries()) {
                ChatLine line = entry(node);
                Row row = new Row(line, rows(line));
                int needed = row.rows() + (headed ? 0 : heading.rows());
                if (!current.isEmpty() && used + needed > ROWS) {
                    pages.add(current);
                    current = new ArrayList<>();
                    used = 0;
                    headed = false;
                    needed = row.rows() + heading.rows();
                }
                if (!headed) { current.add(heading); headed = true; }
                current.add(row);
                used += needed;
            }
        }
        if (!current.isEmpty() || pages.isEmpty()) pages.add(current);
        return pages;
    }

    /** How many pages a player sees. */
    public static int pageCount(Page page) { return pages(page).size(); }

    /** The player's page: the banner, the rows that fit, and the page arrows when there is more than one page. */
    public static List<ChatLine> lines(Page page) {
        List<List<Row>> pages = pages(page);
        int index = Math.min(page.index(), pages.size() - 1), count = pages.size();
        List<ChatLine> lines = new ArrayList<>();
        lines.add(banner(page.group().path() + (count > 1 ? " (" + (index + 1) + "/" + count + ")" : "")));
        if (page.sections().isEmpty()) lines.add(ChatLine.of(Palette.GREY + "Nothing here you may run"));
        for (Row row : pages.get(index)) lines.add(row.line());
        if (count > 1) lines.add(bar(page.group(), index, count));
        return lines;
    }

    /** Every entry as plain text, one per line, under its topic heading. */
    public static List<String> console(Page page) {
        List<String> lines = new ArrayList<>();
        lines.add("--- " + page.group().path() + " ---");
        if (page.sections().isEmpty()) lines.add("Nothing here you may run");
        for (Section section : page.sections()) {
            lines.add(section.title() + ": " + section.docs().url());
            for (DirectorRuntimeNode node : section.entries())
                lines.add("  " + node.getDescriptor().getName() + parameters(node) + " - " + description(node));
        }
        return lines;
    }

    private static Row heading(Section section) {
        ChatLine line = ChatLine.of(Palette.PINK_DEEP + "&l" + section.title()).docs(section.docs());
        return new Row(line, 1);
    }

    /** One entry: name and chips that click like the entry, then the description in grey. */
    static ChatLine entry(DirectorRuntimeNode node) {
        boolean group = !node.isInvocable();
        List<DirectorParameterDescriptor> shown = shown(node);
        boolean run = group || shown.isEmpty();
        String target = group ? node.path() + " help" : node.path() + (run ? "" : " ");
        String hover = group ? description(node) + "\nClick to list its commands"
                : AnimusDirector.usage(node) + "\n" + description(node) + "\nExample: " + example(node) + "\n" + (run ? "Click to run" : "Click to fill in the command");
        ChatLine line = ChatLine.of(Palette.DARK_GREY + ARROW).command(node.getDescriptor().getName(), Palette.PINK, target, run, hover);
        if (group) return line.text(Palette.GREY + " " + description(node) + Palette.DARK_GREY + " ›");
        int optional = 0;
        List<DirectorParameterDescriptor> folded = new ArrayList<>();
        for (DirectorParameterDescriptor parameter : shown) {
            if (!parameter.isRequired() && optional++ >= OPTIONAL_CHIPS) { folded.add(parameter); continue; }
            line.command(chip(parameter), parameter.isRequired() ? Palette.PINK_LIGHT : Palette.GREY, target, false, parameterHover(parameter));
        }
        if (!folded.isEmpty()) {
            List<String> rest = folded.stream().map((DirectorParameterDescriptor parameter) -> chip(parameter) + " " + parameter.getDescription()).toList();
            line.command("[+" + folded.size() + "]", Palette.GREY, target, false, String.join("\n", rest));
        }
        return line.text(Palette.GREY + " " + description(node));
    }

    private static List<DirectorParameterDescriptor> shown(DirectorRuntimeNode node) {
        if (!node.isInvocable()) return List.of();
        return node.getDescriptor().getParameters().stream().filter((DirectorParameterDescriptor parameter) -> !parameter.isContextual()).toList();
    }

    /** {@code <npc>} for a required parameter, {@code [page=1]} for an optional one; an empty default reads {@code [world=]}. */
    static String chip(DirectorParameterDescriptor parameter) {
        return parameter.isRequired() ? "<" + parameter.getName() + ">" : "[" + parameter.getName() + "=" + parameter.getDefaultValue() + "]";
    }

    private static String parameterHover(DirectorParameterDescriptor parameter) {
        String text = parameter.getName() + ": " + parameter.getDescription();
        if (parameter.isRequired()) return text + "\nRequired";
        return text + "\nOptional, write " + parameter.getName() + "=value" + (parameter.getDefaultValue().isEmpty() ? "" : "; default " + parameter.getDefaultValue());
    }

    private static String parameters(DirectorRuntimeNode node) {
        StringBuilder out = new StringBuilder();
        for (DirectorParameterDescriptor parameter : shown(node)) out.append(' ').append(chip(parameter));
        return out.toString();
    }

    private static String description(DirectorRuntimeNode node) {
        String text = node.getDescriptor().getDescription();
        return text == null || text.isBlank() ? node.getDescriptor().getName() : text;
    }

    /** A filled-in command: required values from {@link #EXAMPLES}, optional ones left out. */
    static String example(DirectorRuntimeNode node) {
        StringBuilder out = new StringBuilder(node.path());
        for (DirectorParameterDescriptor parameter : shown(node))
            if (parameter.isRequired()) out.append(' ').append(EXAMPLES.getOrDefault(parameter.getName(), "<" + parameter.getName() + ">"));
        return out.toString();
    }

    private static final Map<String, String> EXAMPLES = Map.ofEntries(
            Map.entry("npc", "guard"), Map.entry("id", "gate-guard"), Map.entry("x", "12"), Map.entry("y", "64"), Map.entry("z", "-30"),
            Map.entry("patch", "{\"name\":\"Gate Guard\"}"), Map.entry("transaction", "<transaction id>"), Map.entry("group", "townsfolk"),
            Map.entry("attribute", "scale"), Map.entry("player", "Steve"), Map.entry("uuid", "<player UUID>"), Map.entry("target", "<entity UUID>"),
            Map.entry("mob", "<mob UUID>"), Map.entry("behavior", "town:greet"), Map.entry("execution", "<execution UUID>"), Map.entry("state", "town:greet"),
            Map.entry("animation", "wave"), Map.entry("capability", "example:wave"), Map.entry("input", "{}"), Map.entry("file", "saves.yml"),
            Map.entry("source", "Shopkeepers"), Map.entry("preview", "<preview id>"), Map.entry("application", "<application id>"),
            Map.entry("categories", "all"), Map.entry("shop", "smith"), Map.entry("gui", "main"), Map.entry("menu", "main"), Map.entry("kind", "all"),
            Map.entry("name", "town"), Map.entry("build", "guard"), Map.entry("schematic", "house.schem"), Map.entry("token", "<token id>"),
            Map.entry("recording", "wave"), Map.entry("body", "minecraft:player"), Map.entry("entity", "12"), Map.entry("dx", "2"), Map.entry("dy", "0"),
            Map.entry("dz", "0"), Map.entry("vehicle", "12"));

    private static ChatLine banner(String title) {
        String bar = Palette.DARK_GREY + "&m          &r";
        return ChatLine.of(bar + " " + Palette.PINK + title + " " + bar);
    }

    private static ChatLine bar(DirectorRuntimeNode group, int index, int count) {
        ChatLine line = ChatLine.of("");
        if (index > 0) line.run("<", group.path() + " help=" + index, "Page " + index);
        line.text(Palette.GREY + " Page " + (index + 1) + " of " + count + " ");
        if (index + 1 < count) line.run(">", group.path() + " help=" + (index + 2), "Page " + (index + 2));
        return line;
    }

    /** Chat rows a line fills, from the text a player reads; long entries wrap. */
    static int rows(ChatLine line) {
        int length = line.visible().length();
        return Math.max(1, (length + ROW_CHARACTERS - 1) / ROW_CHARACTERS);
    }
}
