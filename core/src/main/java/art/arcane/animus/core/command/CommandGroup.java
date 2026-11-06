package art.arcane.animus.core.command;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** What every {@code /animus} command group shares: the services, file-lane work, owner hops and paged listings. */
abstract class CommandGroup {
    /** Stands for the player's selected NPC wherever a command takes an NPC id. */
    static final String SELECTED = "@selected";
    static final int MAXIMUM_LINES = 30;
    static final int MAXIMUM_LINE = 2048;
    final CommandServices services;

    CommandGroup(CommandServices services) { this.services = services; }

    static CommandActor actor() { return AnimusDirector.actor(); }

    AnimusRuntime runtime() { return services.runtime(); }

    NpcRuntime<?, ?> living() { return services.living(); }

    boolean stopping() { return services.stopping().getAsBoolean(); }

    /**
     * The NPC id a command names. It may be the bare id, the id with its folders ({@code world/town/smith}) or the file
     * path ({@code npcs/world/town/smith.json}); {@link #SELECTED} is the player's selection. Empty after telling the
     * actor why.
     */
    Optional<String> npc(CommandActor actor, String id) {
        if (id.indexOf('/') >= 0) return qualified(actor, id);
        if (!id.equalsIgnoreCase(SELECTED)) return Optional.of(id);
        if (actor.player().isEmpty()) { actor.tell(Palette.error("The console has no selection; name the NPC id.")); return Optional.empty(); }
        Optional<String> selected = living().selection(actor.player().get());
        if (selected.isEmpty()) actor.tell(Palette.error("No NPC selected; use /animus select <npc> or interact with a visible NPC."));
        return selected;
    }

    private Optional<String> qualified(CommandActor actor, String reference) {
        Optional<String> id = located(runtime().npcs()::find, reference);
        if (id.isEmpty()) actor.tell(Palette.error("No NPC at " + reference));
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

    /** Runs file work off the owner, then answers its lines on the actor's owner, capped like every Animus report. */
    void files(CommandActor actor, Work work, Runnable after) {
        try {
            services.files().execute(() -> {
                List<String> lines;
                try { lines = work.run(); }
                catch (IOException | RuntimeException failure) { lines = List.of(Palette.error(String.valueOf(failure.getMessage()))); }
                List<String> result = lines;
                if (!stopping()) actor.reply(() -> {
                    if (stopping()) return;
                    result.stream().limit(MAXIMUM_LINES).map(CommandGroup::clip).forEach(actor::tell);
                    if (result.size() > MAXIMUM_LINES) actor.tell("Showing " + MAXIMUM_LINES + " of " + result.size() + " results; inspect the files for the complete report.");
                    after.run();
                });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error("The Animus file queue is full; retry shortly.")); }
    }

    void files(CommandActor actor, Work work) { files(actor, work, () -> {}); }

    /** One line answered later on the actor's owner. */
    void reply(CommandActor actor, String message) {
        if (!stopping()) actor.reply(() -> { if (!stopping()) actor.tell(clip(message)); });
    }

    static String clip(String message) {
        return message.length() > MAXIMUM_LINE ? message.substring(0, MAXIMUM_LINE) + " … (truncated; inspect the JSON file for full details)" : message;
    }

    /** Runs NPC work on the NPC's owner, which on a regionized server is its region, and replies on the actor's own owner. */
    void onNpcOwner(CommandActor actor, String id, String label, Supplier<String> work) {
        NpcRuntime<?, ?> living = living();
        boolean accepted = living.onOwner(id, () -> {
            String message;
            try { message = work.get(); }
            catch (RuntimeException e) { message = label + ": " + e.getMessage(); }
            for (String line : message.split("\n")) reply(actor, line);
        }, (String reason) -> reply(actor, label + ": " + reason));
        if (!accepted) actor.tell(label + ": " + (living.available() ? "NPC " + id + " is not active; it starts when its chunk loads or a player is near" : living.status()));
    }

    /** A paged list in the help look. {@code command} is the command that shows it, without {@code page=}. */
    static void listing(CommandActor actor, String title, String command, List<String> rows, int page, String empty) {
        List<String> entries = rows.stream().map(CommandGroup::entry).toList();
        actor.menu(new DirectorMiniMenu.ContentMenu(title, command, entries, "<#AAAAAA>" + DirectorMiniMenu.escapeText(empty) + "</#AAAAAA>", page, DirectorMiniMenu.MAX_ENTRIES_PER_PAGE));
    }

    /** {@code id | rest}: the id in pink, the rest in grey. */
    private static String entry(String row) {
        int split = row.indexOf(" | ");
        String head = split < 0 ? row : row.substring(0, split), rest = split < 0 ? "" : row.substring(split + 3);
        return "<#777777>⇀</#777777> <#FF5FA8>" + DirectorMiniMenu.escapeText(clip(head)) + "</#FF5FA8>"
                + (rest.isEmpty() ? "" : " <#555555>|</#555555> <#AAAAAA>" + DirectorMiniMenu.escapeText(clip(rest)) + "</#AAAAAA>");
    }
}
