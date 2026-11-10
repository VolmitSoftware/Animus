package art.arcane.animus.core.command;

import art.arcane.animus.core.text.DocsLink;
import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /animus} help in the menu every Volmit plugin shares, VolmLib's {@link DirectorMiniMenu}: the strikethrough
 * banner, one {@code ⇀ name [required] ⊰optional⊱} line per entry whose hover holds the description, usage and an
 * example, a click that fills in a command with parameters, runs one without and opens a group, and the page bar with
 * {@code 〈 Page N} and {@code Page N ❭}. The root page groups its entries under topic headings that link the wiki; a
 * topic cut by a page break repeats its heading. The console gets every entry as plain text.
 */
public final class AnimusHelp {
    /** A heading of the root page, the docs section it links and the root entries under it, in order. */
    record Topic(String title, DocsLink docs, List<String> names) {}

    static final List<Topic> TOPICS = List.of(
            new Topic("Tools", DocsLink.TOOLS, List.of("setup", "tools")),
            new Topic("NPCs", DocsLink.COMMANDS, List.of("status", "list", "create", "mythic", "inspect", "edit", "clone", "delete", "spawn", "despawn",
                    "select", "selection", "validate", "reload", "rollback", "npc")),
            new Topic("Movement", DocsLink.MOVEMENT, List.of("navigation", "schedule", "ride")),
            new Topic("Combat", DocsLink.COMBAT, List.of("combat")),
            new Topic("Content", DocsLink.CONTENT, List.of("modules", "rigs", "pack", "import", "record")),
            new Topic("Services", DocsLink.SERVICES, List.of("shop", "gui", "hologram", "build", "editor")),
            new Topic("Quests", DocsLink.QUESTS, List.of("quest")),
            new Topic("Behaviors", DocsLink.BEHAVIORS, List.of("behaviors")),
            new Topic("Groups", DocsLink.GROUPS, List.of("groups")),
            new Topic("Diagnostics", DocsLink.DIAGNOSTICS, List.of("debug", "proof")));

    /** Verbs in the order a group lists them: reading first, then doing, then undoing; unlisted verbs follow A-Z. */
    private static final List<String> VERBS = List.of("status", "list", "inspect", "members", "summary", "capabilities", "problems", "packs", "triggers",
            "trace", "schematics", "tokens", "performance", "network", "npc", "watch", "mode", "go", "look", "create", "start", "run", "play", "open", "menu",
            "preview", "stage", "apply", "plan", "review", "engage", "disengage", "target", "guard", "attack", "mob", "release", "assign", "unassign",
            "action", "condition", "spawn", "move", "path", "mount", "control", "folia", "pause", "resume", "survey", "donate", "team", "ghost", "pair",
            "revoke", "validate", "schema", "reload", "build", "citizens", "shops", "gloss", "live", "file", "import", "export", "close", "stop", "cancel", "clear", "reset",
            "rollback", "delete", "state", "behavior", "player", "relationship", "all", "confirm");

    /** A topic heading with the entries under it; a group's own page has one section without a heading. */
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
            sections.add(new Section("", topic(group).docs(), sorted(visible)));
        }
        return new Page(group, sections, Math.max(0, index));
    }

    /** The root topic a node sits under; NPCs for one no topic lists. */
    static Topic topic(DirectorRuntimeNode node) {
        DirectorRuntimeNode top = node;
        while (top.getParent() != null && top.getParent().getParent() != null) top = top.getParent();
        String name = top.getDescriptor().getName();
        for (Topic topic : TOPICS) if (topic.names().contains(name)) return topic;
        return TOPICS.get(1);
    }

    private static List<DirectorRuntimeNode> sorted(List<DirectorRuntimeNode> nodes) {
        Comparator<DirectorRuntimeNode> verb = Comparator.comparingInt((DirectorRuntimeNode node) -> {
            int rank = VERBS.indexOf(node.getDescriptor().getName());
            return rank < 0 ? VERBS.size() : rank;
        });
        return nodes.stream().sorted(verb.thenComparing((DirectorRuntimeNode node) -> node.getDescriptor().getName(), String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** A heading or an entry as a MiniMessage line, with the chat rows it fills. */
    private record Row(String line, int rows) {}

    /**
     * The rows of every page. A page holds what VolmLib's menu holds: up to {@link DirectorMiniMenu#MAX_ENTRIES_PER_PAGE}
     * lines in the chat window less the banner, the page bar and a group's back link. A cut topic repeats its heading.
     */
    static List<List<Row>> pages(Page page) {
        int budget = DirectorMiniMenu.CHAT_LINE_COUNT - (page.group().getParent() == null ? 2 : 3);
        List<List<Row>> pages = new ArrayList<>();
        List<Row> current = new ArrayList<>();
        int used = 0;
        for (Section section : page.sections()) {
            Row heading = section.title().isEmpty() ? null : row(heading(section));
            boolean headed = heading == null;
            for (DirectorRuntimeNode node : section.entries()) {
                Row row = row(entry(node));
                int needed = row.rows() + (headed ? 0 : heading.rows());
                if (!current.isEmpty() && (used + needed > budget || current.size() + (headed ? 1 : 2) > DirectorMiniMenu.MAX_ENTRIES_PER_PAGE)) {
                    pages.add(current);
                    current = new ArrayList<>();
                    used = 0;
                    headed = heading == null;
                    needed = row.rows() + (headed ? 0 : heading.rows());
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

    /** The player's page: the banner, a group's back link, the rows that fit, and the page bar, all as MiniMessage. */
    public static List<String> lines(Page page) {
        List<List<Row>> pages = pages(page);
        int index = Math.min(page.index(), pages.size() - 1);
        DirectorRuntimeNode group = page.group();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(group.path(), AnimusDirector.THEME));
        if (group.getParent() != null) lines.add(DirectorMiniMenu.backLink(group.getParent().path() + " help=1", AnimusDirector.THEME, AnimusDirector.TEXT));
        if (page.sections().isEmpty())
            lines.add("<" + AnimusDirector.THEME.muted() + ">Nothing here you may run</" + AnimusDirector.THEME.muted() + ">");
        for (Row row : pages.get(index)) lines.add(row.line());
        int total = page.entries().size();
        DirectorMiniMenu.ContentPage shown = new DirectorMiniMenu.ContentPage(index + 1, pages.size(), 0, 0, total);
        lines.add(DirectorMiniMenu.paginationBar(shown, group.path(), AnimusDirector.THEME, AnimusDirector.TEXT));
        return lines.stream().map(AnimusHelp::clicks).toList();
    }

    /** Every entry as plain text, one per line, under its topic heading. */
    public static List<String> console(Page page) {
        List<String> lines = new ArrayList<>();
        lines.add("--- " + page.group().path() + " ---");
        if (page.sections().isEmpty()) lines.add("Nothing here you may run");
        for (Section section : page.sections()) {
            if (!section.title().isEmpty()) lines.add(section.title() + ": " + section.docs().url());
            for (DirectorRuntimeNode node : section.entries())
                lines.add("  " + node.getDescriptor().getName() + parameters(node) + " - " + description(node));
        }
        return lines;
    }

    private static final java.util.regex.Pattern PAGE_CLICK = java.util.regex.Pattern.compile("(<click:run_command:[^>]*?) (?:help|page)=(\\d+)");

    /** A link to a help page runs {@code help N}, which a client's command tree parses, rather than {@code help=N}. */
    static String clicks(String line) { return PAGE_CLICK.matcher(line).replaceAll("$1 help $2"); }

    private static Row row(String line) { return new Row(line, Math.max(1, DirectorMiniMenu.chatRows(line))); }

    /** A topic heading in bold deep pink, then {@code [Docs]} opening its wiki section. */
    static String heading(Section section) {
        String deep = AnimusDirector.THEME.primaryRight(), chip = AnimusDirector.THEME.optional();
        return "<" + deep + "><bold>" + DirectorMiniMenu.escapeText(section.title()) + "</bold></" + deep + "> "
                + "<hover:show_text:'<" + AnimusDirector.THEME.description() + ">Open the Animus wiki'><click:open_url:'" + section.docs().url() + "'>"
                + "<" + chip + ">[Docs]</" + chip + "></click></hover>";
    }

    /**
     * One entry exactly as VolmLib's menu draws it, chips, hover and click included: the entry line of a one-entry page of
     * its group, which sits just above the page bar.
     */
    static String entry(DirectorRuntimeNode node) {
        DirectorRuntimeNode group = node.getParent() == null ? node : node.getParent();
        List<String> rendered = DirectorMiniMenu.render(new DirectorMiniMenu.DirectorHelpPage(group, List.of(node), 0, 1), AnimusDirector.THEME, AnimusDirector.TEXT);
        String line = rendered.get(rendered.size() - 2);
        if (node.isInvocable()) return line.replace(hoverText(volmLibExample(node)), hoverText(AnimusDirector.usage(node)));
        String category = DirectorMiniMenu.escapeText(AnimusDirector.TEXT.resolve(DirectorHelpMessages.CATEGORY, MessageArgs.empty()));
        return line.replace(category, DirectorMiniMenu.escapeText(description(node)));
    }

    /** The example VolmLib's hover prints, {@code create id=<String> x=<String>}, which Animus swaps for its usage line. */
    private static String volmLibExample(DirectorRuntimeNode node) {
        StringBuilder example = new StringBuilder(node.path());
        for (DirectorParameterDescriptor parameter : node.getDescriptor().getParameters()) {
            if (parameter.isContextual() && !parameter.isContextualOverride()) continue;
            String fallback = parameter.getDefaultValue();
            example.append(' ').append(parameter.getName()).append('=').append(fallback != null && !fallback.isBlank() ? fallback : "<" + parameter.getType().getSimpleName() + ">");
        }
        return example.toString();
    }

    /** Text as it sits inside a rendered hover: MiniMessage-escaped, then quoted for the attribute. */
    private static String hoverText(String text) { return DirectorMiniMenu.escapeText(text).replace("\\", "\\\\").replace("'", "\\'"); }

    private static List<DirectorParameterDescriptor> shown(DirectorRuntimeNode node) {
        if (!node.isInvocable()) return List.of();
        return node.getDescriptor().getParameters().stream().filter((DirectorParameterDescriptor parameter) -> !parameter.isContextual()).toList();
    }

    /** {@code <npc>} for a required parameter, {@code [page=1]} for an optional one; an empty default reads {@code [world=]}. */
    static String chip(DirectorParameterDescriptor parameter) {
        return parameter.isRequired() ? "<" + parameter.getName() + ">" : "[" + parameter.getName() + "=" + parameter.getDefaultValue() + "]";
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
}
