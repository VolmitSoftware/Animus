package art.arcane.animus.core.command;

import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code /animus tools}: the chest-GUI tool palette, the selection wand and the manage screen. {@code /animus tools}
 * alone opens the palette. The same screens open on every platform.
 */
@Director(name = ToolkitCommands.GROUP, aliases = "tool", description = "Click-to-manage NPC tools: palette, wand and manage screen")
@AnimusPermission(PermissionNodes.TOOLS)
final class ToolkitCommands extends CommandGroup {
    static final String GROUP = "tools", OPEN = "open";

    ToolkitCommands(CommandServices services) { super(services); }

    /** {@code /animus tools} alone runs {@code /animus tools open}. */
    static List<String> expand(List<String> tokens) {
        return tokens.size() == 1 && tokens.getFirst().equalsIgnoreCase(GROUP) ? List.of(GROUP, OPEN) : tokens;
    }

    @Director(description = "Open the tool palette", origin = DirectorOrigin.PLAYER)
    void open() { run(Toolkit.Tool.PALETTE); }

    @Director(description = "Take the selection wand: click an NPC to select it, sneak-click to add or remove", origin = DirectorOrigin.PLAYER)
    void wand() {
        CommandActor actor = actor();
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.wand(actor.player().orElseThrow()));
    }

    @Director(description = "Manage the selected NPCs", origin = DirectorOrigin.PLAYER)
    void manage() { run(Toolkit.Tool.MANAGE); }

    private void run(Toolkit.Tool tool) {
        CommandActor actor = actor();
        UUID player = actor.player().orElseThrow();
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.open(player, tool, new Toolkit.Commands(this::inspect,
                (CommandActor viewer) -> services.editor().pair(viewer, "", "", "", ""), (CommandActor viewer, String id) -> NpcCommands.revive(this, viewer, id))));
    }

    private Optional<Toolkit<?, ?>> toolkit(CommandActor actor) {
        Optional<Toolkit<?, ?>> found = Toolkit.of(services.shops().screens());
        if (found.isEmpty()) actor.tell(Palette.error("The toolkit is not running on this server"));
        return found;
    }

    /** The same two lines {@code /animus inspect} answers. */
    private void inspect(CommandActor actor, String id) {
        Administration administration = new Administration(runtime());
        try {
            services.files().execute(() -> {
                Administration.Inspection inspection;
                try { inspection = administration.inspect(id); }
                catch (IOException | RuntimeException failure) { reply(actor, Palette.error(String.valueOf(failure.getMessage()))); return; }
                if (!stopping()) actor.reply(() -> { if (!stopping()) AnimusCommandTree.inspect(actor, inspection, living().inspect(id)); });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }
}
