package art.arcane.animus.core.command;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** What every {@code /animus} command group shares: the services, file-lane work, owner hops and paged listings. */
abstract class CommandGroup {
    /** Stands for the player's selected NPC wherever a command takes an NPC id. */
    static final String SELECTED = "@selected";
    /** Lines a reply may fill on a player's screen before the rest goes behind [Details]. */
    static final int PLAYER_LINES = 6;
    static final int MAXIMUM_LINE = 2048;
    final CommandServices services;

    CommandGroup(CommandServices services) { this.services = services; }

    static CommandActor actor() { return AnimusDirector.actor(); }

    AnimusRuntime runtime() { return services.runtime(); }

    NpcRuntime<?, ?> living() { return services.living(); }

    boolean stopping() { return services.stopping().getAsBoolean(); }

    /**
     * The NPC id a command names. It may be the bare id, the id with its folders ({@code world/town/smith}) or the file
     * path ({@code npcs/world/town/smith.json}); {@link #SELECTED} is the player's selection. An id no NPC has is refused
     * here, the same way for every command. Empty after telling the actor why.
     */
    Optional<String> npc(CommandActor actor, String id) {
        if (id.indexOf('/') >= 0) return qualified(actor, id);
        if (!id.equalsIgnoreCase(SELECTED)) {
            if (runtime().npcs().find(id).isPresent()) return Optional.of(id);
            actor.tell(Palette.error(unknownNpc(id)));
            return Optional.empty();
        }
        if (actor.player().isEmpty()) { actor.tell(Palette.error("The console has no selection; give the NPC id")); return Optional.empty(); }
        return selected(actor, selection(actor.player().get()));
    }

    /**
     * The player's selected NPCs, in the order picked. The toolkit's selection, which the wand, its screens and
     * {@code /animus select} all change, is the only one; a platform without the toolkit has the runtime's single pick.
     */
    List<String> selection(UUID player) {
        Optional<Toolkit<?, ?>> toolkit = services == null ? Optional.empty() : Toolkit.of(services.shops().screens());
        return toolkit.map((Toolkit<?, ?> found) -> found.selected(player)).orElseGet(() -> living().selection(player).map(List::of).orElse(List.of()));
    }

    /** What {@link #SELECTED} names: the one selected NPC. None, or several, is a refusal that says what to do. */
    static Optional<String> selected(CommandActor actor, List<String> selection) {
        if (selection.size() == 1) return Optional.of(selection.getFirst());
        actor.tell(Palette.error(selection.isEmpty() ? "No NPC selected; /animus select <npc>, or click one with the wand (/animus tools wand)"
                : selection.size() + " NPCs are selected; name one, or /animus select <npc> to keep only it"));
        return Optional.empty();
    }

    private Optional<String> qualified(CommandActor actor, String reference) {
        Optional<String> id = located(runtime().npcs()::find, reference);
        if (id.isEmpty()) actor.tell(Palette.error(unknownNpc(reference) + ", or give the bare id"));
        return id;
    }

    /**
     * The id of the NPC a folder-qualified id or file path names, only when that NPC lives exactly there; a moved or
     * mistyped folder finds nothing.
     */
    static Optional<String> located(Function<String, Optional<NpcDefinition>> find, String reference) {
        int slash = reference.lastIndexOf('/');
        String path = reference.startsWith("npcs/") && reference.endsWith(".json") ? reference
                : slash < 0 ? NpcPaths.path("", reference) : NpcPaths.path(reference.substring(0, slash), reference.substring(slash + 1));
        if (!NpcPaths.file(path)) return Optional.empty();
        String id = NpcPaths.idOf(path);
        return find.apply(id).filter((NpcDefinition definition) -> definition.path().equals(path)).map(NpcDefinition::id);
    }

    @FunctionalInterface interface Work { List<String> run() throws IOException; }

    /** Runs file work off the owner, then answers its lines on the actor's owner; a long reply goes behind [Details] for a player. */
    void files(CommandActor actor, Work work, Runnable after) {
        try {
            services.files().execute(() -> {
                List<String> lines;
                try { lines = work.run(); }
                catch (IOException | RuntimeException failure) { lines = List.of(Palette.error(reason(failure))); }
                List<String> result = lines;
                if (!stopping()) actor.reply(() -> {
                    if (stopping()) return;
                    lines(actor, result.stream().map(CommandGroup::clip).toList());
                    after.run();
                });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    void files(CommandActor actor, Work work) { files(actor, work, () -> {}); }

    /**
     * A reply of several lines. The console gets every line. A player gets up to {@link #PLAYER_LINES}; a longer reply
     * shows the first lines, then how many of how many with every line behind [Details].
     */
    static void lines(CommandActor actor, List<String> lines) {
        if (actor.player().isEmpty() || lines.size() <= PLAYER_LINES) { lines.forEach(actor::tell); return; }
        lines.subList(0, PLAYER_LINES - 1).forEach(actor::tell);
        actor.rich(ChatLine.reply("Showing " + (PLAYER_LINES - 1) + " of " + lines.size() + " lines").details("Details", lines));
    }

    /** One line answered later on the actor's owner. */
    void reply(CommandActor actor, String message) {
        if (!stopping()) actor.reply(() -> { if (!stopping()) actor.tell(clip(message)); });
    }

    static String clip(String message) {
        return message.length() > MAXIMUM_LINE ? message.substring(0, MAXIMUM_LINE) + " (cut; the full text is in the JSON file)" : message;
    }

    /** Runs NPC work on the NPC's owner, which on a regionized server is its region, and replies on the actor's own owner. */
    void onNpcOwner(CommandActor actor, String id, String label, Supplier<String> work) {
        NpcRuntime<?, ?> living = living();
        boolean accepted = living.onOwner(id, () -> {
            String message;
            try { message = work.get(); }
            catch (RuntimeException e) { message = failed(label, reason(e)); }
            for (String line : message.split("\n")) reply(actor, line);
        }, (String reason) -> reply(actor, failed(label, reason)));
        if (!accepted) actor.tell(failed(label, living.available() ? inactive(id, runtime().npcs().find(id)) : living.status()));
    }

    /** A refusal from NPC work, in red. */
    static String failed(String label, String reason) { return Palette.error(label + ": " + reason); }

    /**
     * Why an NPC cannot take live work: it does not exist, it is despawned, or it is spawned but not active because nobody
     * is near its chunk.
     */
    static String inactive(String id, Optional<NpcDefinition> definition) {
        if (definition.isEmpty()) return unknownNpc(id);
        if (!definition.get().spawned()) return id + " is despawned; /animus spawn " + id + " first";
        return id + " is spawned but not active; it becomes active when its chunk loads or a player comes near";
    }

    /** The refusal for an NPC id nothing matches. */
    static String unknownNpc(String id) { return "No NPC named " + id + "; /animus list shows ids"; }

    /** A failure's message, or its kind when it has none, so a reply never reads "null". */
    static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** The command a clicked NPC id suggests. */
    static String inspectCommand(String id) { return "/" + art.arcane.animus.core.BuildIdentity.ID + " inspect " + id; }

    /** A paged list in the help look. {@code command} is the command that shows it, without {@code page=}. The console gets every row. */
    static void listing(CommandActor actor, String title, String command, List<String> rows, int page, String empty) {
        listing(actor, title, command, rows, page, empty, null);
    }

    /**
     * A paged list whose rows click: {@code click} turns a row's head, such as an NPC id, into the command a click puts in
     * the chat box. A player gets VolmLib's shared menu page with its page bar; a page past the end shows the last one.
     * The console gets the title and every whole row. A row may carry detail lines after {@code \n}: the hover shows
     * them, and the console prints them after the row.
     */
    static void listing(CommandActor actor, String title, String command, List<String> rows, int page, String empty, Function<String, String> click) {
        if (actor.player().isEmpty()) {
            actor.plain("--- " + title + " ---");
            if (rows.isEmpty()) actor.plain(empty);
            for (String row : rows) {
                int detail = row.indexOf('\n');
                actor.plain("⇀ " + (detail < 0 ? row : row.substring(0, detail) + " (" + row.substring(detail + 1).replace("\n", "; ") + ")"));
            }
            return;
        }
        List<String> entries = rows.stream().map((String row) -> entry(row, click)).toList();
        actor.menu(new DirectorMiniMenu.ContentMenu(title, command, entries, "<#AAAAAA>" + DirectorMiniMenu.escapeText(empty) + "</#AAAAAA>", page, DirectorMiniMenu.MAX_ENTRIES_PER_PAGE));
    }

    /**
     * {@code id | rest}: the id in pink, the rest in grey, on one chat row. A longer rest ends in an ellipsis and the hover
     * shows all of it, then any detail lines; with {@code click}, the row suggests a command and its hover says which.
     */
    static String entry(String text, Function<String, String> click) {
        int lines = text.indexOf('\n');
        String row = lines < 0 ? text : text.substring(0, lines), detail = lines < 0 ? "" : text.substring(lines + 1);
        int split = row.indexOf(" | ");
        String head = clip(split < 0 ? row : row.substring(0, split)), rest = clip(split < 0 ? "" : row.substring(split + 3));
        int room = Math.max(8, ROW - head.length() - 5);
        boolean cut = rest.length() > room;
        String shown = cut ? rest.substring(0, room - 1) + "…" : rest;
        String line = "<#777777>⇀</#777777> <#FF5FA8>" + DirectorMiniMenu.escapeText(head) + "</#FF5FA8>"
                + (shown.isEmpty() ? "" : " <#555555>|</#555555> <#AAAAAA>" + DirectorMiniMenu.escapeText(shown) + "</#AAAAAA>");
        String target = click == null ? null : click.apply(head);
        if (target == null && !cut && detail.isEmpty()) return line;
        List<String> hovered = new ArrayList<>();
        if (cut) hovered.add(rest.replace(" | ", "\n"));
        if (!detail.isEmpty()) hovered.add(detail);
        if (target != null) hovered.add("Click to fill in " + target);
        String hover = String.join("\n", hovered);
        String open = "<hover:show_text:'" + attribute(DirectorMiniMenu.escapeText(hover)) + "'>";
        return target == null ? open + line + "</hover>" : open + "<click:suggest_command:'" + attribute(target) + "'>" + line + "</click></hover>";
    }

    /** Characters of a listing row that fit one chat row. */
    static final int ROW = 52;

    /** A value inside a single-quoted MiniMessage argument. */
    private static String attribute(String value) { return value.replace("\\", "\\\\").replace("'", "\\'"); }
}
