package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * One command's actor, which plays the outcome sound once: the first answer decides it, a refusal in red or a
 * {@code FAILED}-style status plays {@link CommandSound#FAILURE}, anything else {@link CommandSound#SUCCESS}. A command that
 * answers later, from a file or region thread, still sounds when its answer arrives.
 */
final class CommandFeedback implements CommandActor {
    private static final String REFUSAL = RichText.colorize(Palette.PREFIX + "&c");
    private static final Pattern REJECTED = Pattern.compile("^[A-Z][^:|]{0,40}: (INVALID|NOT_FOUND|CONFLICT|UNAVAILABLE|BUSY|CANCELLED|FAILED|TIMED_OUT)\\b.*", Pattern.DOTALL);
    private final CommandActor actor;
    private final AtomicBoolean sounded = new AtomicBoolean();

    CommandFeedback(CommandActor actor) { this.actor = Objects.requireNonNull(actor); }

    /** The sound an answer plays: failure for a refusal or a rejected status, success otherwise. */
    static CommandSound outcome(String answer) {
        if (answer == null) return CommandSound.SUCCESS;
        if (answer.startsWith(REFUSAL)) return CommandSound.FAILURE;
        String plain = RichText.plain(answer);
        if (plain.startsWith("[Animus]: ")) plain = plain.substring("[Animus]: ".length());
        return REJECTED.matcher(plain).matches() ? CommandSound.FAILURE : CommandSound.SUCCESS;
    }

    /** Plays {@code sound} unless this command already sounded. */
    void settle(CommandSound sound) { if (sounded.compareAndSet(false, true)) actor.play(sound); }

    @Override public boolean permitted(String node) { return actor.permitted(node); }
    @Override public void tell(String message) { settle(outcome(message)); actor.tell(message); }
    @Override public void plain(String line) { settle(outcome(line)); actor.plain(line); }
    @Override public void rich(ChatLine line) { settle(outcome(line.console())); actor.rich(line); }
    @Override public void help(AnimusHelp.Page page) { settle(CommandSound.SUCCESS); actor.help(page); }
    @Override public void menu(DirectorMiniMenu.ContentMenu menu) { settle(CommandSound.SUCCESS); actor.menu(menu); }
    @Override public void page(List<String> lines) { settle(CommandSound.SUCCESS); actor.page(lines); }
    @Override public Optional<UUID> player() { return actor.player(); }
    @Override public boolean reply(Runnable action) { return actor.reply(action); }
    @Override public Optional<WorldPosition> position() { return actor.position(); }
    @Override public Optional<Coordinates.Facing> facing() { return actor.facing(); }
    @Override public Optional<Vec3> target() { return actor.target(); }
    @Override public List<UUID> nearby() { return actor.nearby(); }
    @Override public void play(CommandSound sound) { actor.play(sound); }
}
