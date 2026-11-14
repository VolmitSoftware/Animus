package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The actor of one {@code /animus} command as its handler sees it, or of one Animus menu reply. A reply that reports a
 * change is remembered for {@code /animus undo}, and a player in Simple mode sees its {@code [Undo]} without the
 * transaction id. The outcome sound plays once: when the handler is done if it answered by then, a refusal if any answer
 * was red, otherwise with its first later answer, so a refusal from the file lane plays the failure tone too. A menu
 * reply plays only the failure tone, since the click that caused it already ticked.
 */
public final class ReplyActor implements CommandActor {
    private final CommandActor actor;
    private final boolean simple;
    private final RecentChanges changes;
    /** A menu reply: success stays silent. */
    private final boolean quiet;
    /** The node the running command needed, kept with each change it reports; empty for the menu. */
    private String node = "";
    private boolean answered, refused, waiting, sounded;

    ReplyActor(CommandActor actor, boolean simple, RecentChanges changes) { this(actor, simple, changes, false); }

    private ReplyActor(CommandActor actor, boolean simple, RecentChanges changes, boolean quiet) {
        this.actor = Objects.requireNonNull(actor);
        this.simple = simple;
        this.changes = Objects.requireNonNull(changes);
        this.quiet = quiet;
    }

    /**
     * {@code actor} answering as a command does, for a reply that no command runs: the Animus menu's, and the commands it
     * runs for a click. A refusal as its first answer, now or from the file lane, plays the failure tone; success is silent.
     */
    public static CommandActor menu(CommandActor actor, boolean simple, RecentChanges changes) {
        ReplyActor replies = new ReplyActor(actor, simple, changes, true);
        replies.finish(true);
        return replies;
    }

    @Override public void tell(String message) {
        heard(message != null && message.startsWith(Palette.error("")));
        if (actor.player().isEmpty()) { actor.tell(message); return; }
        ChatLine line = actor.line(message);
        if (line.transactions().isEmpty()) { actor.tell(message); return; }
        remember(line);
        if (simple) actor.rich(line.withoutTransactionIds());
        else actor.tell(message);
    }

    @Override public void rich(ChatLine line) {
        heard(line.refusal());
        if (actor.player().isPresent()) remember(line);
        actor.rich(simple ? line.withoutTransactionIds() : line);
    }

    @Override public void plain(String line) {
        heard(line != null && line.startsWith(Palette.error("")));
        actor.plain(line);
    }

    @Override public void help(AnimusHelp.Page page) { heard(false); actor.help(page); }
    @Override public void menu(DirectorMiniMenu.ContentMenu menu) { heard(false); actor.menu(menu); }
    @Override public void page(List<String> lines) { heard(false); actor.page(lines); }
    @Override public ChatLine line(String message) { return actor.line(message); }
    @Override public boolean permitted(String node) { return actor.permitted(node); }
    @Override public Optional<UUID> player() { return actor.player(); }
    @Override public boolean reply(Runnable action) { return actor.reply(action); }
    @Override public Optional<WorldPosition> position() { return actor.position(); }
    @Override public Optional<Coordinates.Facing> facing() { return actor.facing(); }
    @Override public Optional<Vec3> target() { return actor.target(); }
    @Override public void play(CommandSound sound) { actor.play(sound); }
    @Override public List<UUID> nearby() { return actor.nearby(); }

    /**
     * The command about to run needs {@code permission}; each change it reports keeps that node for {@code /animus undo}.
     * {@code animus.tools} names no content, as the menu's own commands write routes and the like, so their changes are
     * kept like the menu's: undo then needs the node for what the change wrote.
     */
    void needs(String permission) { node = permission.equals(PermissionNodes.TOOLS) ? "" : permission; }

    /** The handler is done: {@code ran} false means it never ran or threw, which sounds low at once. */
    synchronized void finish(boolean ran) {
        if (!ran) sound(true);
        else if (answered) sound(refused);
        else waiting = true;
    }

    private synchronized void heard(boolean refusal) {
        if (sounded) return;
        if (waiting) { sound(refusal); return; }
        answered = true;
        refused |= refusal;
    }

    private void sound(boolean refusal) {
        sounded = true;
        if (refusal) actor.play(CommandSound.FAILURE);
        else if (!quiet) actor.play(CommandSound.SUCCESS);
    }

    private void remember(ChatLine line) {
        if (line.refusal()) return;
        UUID player = actor.player().orElseThrow();
        for (String transaction : line.transactions()) changes.record(player, transaction, line.lead(), node);
    }
}
