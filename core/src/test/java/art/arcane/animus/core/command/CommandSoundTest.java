package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The sounds {@code /animus} plays: Iris's typing chime, success ding and failure tone, one outcome per command. */
class CommandSoundTest {
    /** Records the sounds a player would hear. */
    static class Listener extends AnimusDirectorTest.Actor {
        final List<CommandSound> heard = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        Listener(String... nodes) { super(true, nodes); }
        @Override public void play(CommandSound sound) { heard.add(sound); }
        @Override public boolean reply(Runnable action) { later.add(action); return true; }
    }

    @Director(name = "probe")
    static final class Probe {
        @Director void ok() { AnimusDirector.actor().tell(Palette.success("Saved")); }
        @Director void refuse() { AnimusDirector.actor().tell(Palette.error("Unknown NPC: ghost")); }
        @Director void status() { AnimusDirector.actor().tell("Behavior run: FAILED | npc=guard"); }
        @Director void twice() { CommandActor actor = AnimusDirector.actor(); actor.tell(Palette.success("one")); actor.tell(Palette.error("two")); }
        @Director void later() { CommandActor actor = AnimusDirector.actor(); actor.reply(() -> actor.tell(Palette.error("Disk full"))); }
        @Director void take(@Param(name = "npc") String npc) { AnimusDirector.actor().tell("Took " + npc); }
        @Director @AnimusPermission("probe.secret") void secret() { AnimusDirector.actor().tell("never"); }
    }

    private final AnimusDirector director = new AnimusDirector(new Probe(), (String parameter, CommandActor actor) -> List.of());

    private List<CommandSound> run(String... words) {
        Listener player = new Listener(AnimusDirector.ADMIN);
        director.execute(player, words);
        player.later.forEach(Runnable::run);
        return player.heard;
    }

    @Test void eachOutcomePlaysIrisSounds() {
        assertEquals(List.of(new CommandSound.Tone("minecraft:block.amethyst_cluster.break", 0.77f, 1.65f),
                new CommandSound.Tone("minecraft:block.respawn_anchor.charge", 0.125f, 2.99f)), CommandSound.SUCCESS.tones(new Random(1)));
        assertEquals(List.of(new CommandSound.Tone("minecraft:block.amethyst_cluster.break", 0.77f, 0.25f),
                new CommandSound.Tone("minecraft:block.beacon.deactivate", 0.2f, 0.45f)), CommandSound.FAILURE.tones(new Random(1)));
        Random random = new Random(7);
        for (int index = 0; index < 200; index++) {
            CommandSound.Tone chime = CommandSound.TYPING.tones(random).getFirst();
            assertEquals("minecraft:block.amethyst_block.chime", chime.sound());
            assertEquals(0.25f, chime.volume());
            assertTrue(chime.pitch() >= 0.125f && chime.pitch() < 1.95f, "pitch " + chime.pitch());
        }
    }

    @Test void theFirstAnswerDecidesTheOneSoundACommandPlays() {
        assertEquals(List.of(CommandSound.SUCCESS), run("ok"));
        assertEquals(List.of(CommandSound.FAILURE), run("refuse"));
        assertEquals(List.of(CommandSound.FAILURE), run("status"), "a rejected status is a failure");
        assertEquals(List.of(CommandSound.SUCCESS), run("twice"), "one sound per command");
        assertEquals(List.of(CommandSound.FAILURE), run("later"), "an answer from another thread still sounds");
        assertEquals(List.of(CommandSound.SUCCESS), run("take", "guard"));
    }

    @Test void helpPagesDingAndRefusalsSoundLow() {
        assertEquals(List.of(CommandSound.SUCCESS), run(), "the root help");
        assertEquals(List.of(CommandSound.SUCCESS), run("help=2"), "a page turn");
        assertEquals(List.of(CommandSound.SUCCESS), run("page=2"), "a page bar click");
        assertEquals(List.of(CommandSound.FAILURE), run("nothing"), "an unknown command");
        assertEquals(List.of(CommandSound.FAILURE), run("take"), "a missing value shows the usage");
        Listener visitor = new Listener();
        director.execute(visitor, new String[]{"secret"});
        assertEquals(List.of(CommandSound.FAILURE), visitor.heard, "no permission");
    }

    @Test void typingChimesOnEveryCompletionForPlayersOnly() {
        Listener player = new Listener(AnimusDirector.ADMIN);
        director.complete(player, new String[]{""});
        director.complete(player, new String[]{"ta"});
        assertEquals(List.of(CommandSound.TYPING, CommandSound.TYPING), player.heard);
        List<CommandSound> console = new ArrayList<>();
        director.complete(new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN) {
            @Override public void play(CommandSound sound) { console.add(sound); }
        }, new String[]{""});
        assertTrue(console.isEmpty(), "the console hears nothing");
    }

    @Test void answersAreSortedByTone() {
        assertEquals(CommandSound.FAILURE, CommandFeedback.outcome(Palette.error("No permission")));
        assertEquals(CommandSound.FAILURE, CommandFeedback.outcome("Behaviors validate: INVALID | 3 problems"));
        assertEquals(CommandSound.SUCCESS, CommandFeedback.outcome(Palette.success("Saved guard")));
        assertEquals(CommandSound.SUCCESS, CommandFeedback.outcome("Behavior run: OK | execution=1"));
        assertEquals(CommandSound.SUCCESS, CommandFeedback.outcome("guard heading to 1 2 3"));
    }
}
