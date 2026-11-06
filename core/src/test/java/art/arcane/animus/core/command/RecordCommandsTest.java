package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.volmlib.util.director.annotations.Director;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordCommandsTest {
    /** Records each call the record group makes. */
    static final class Control implements RecordingControl {
        final List<String> calls = new ArrayList<>();
        @Override public void start(CommandActor actor, String id) { calls.add("start " + id); }
        @Override public void stop(CommandActor actor) { calls.add("stop"); }
        @Override public void cancel(CommandActor actor) { calls.add("cancel"); }
        @Override public List<String> status(CommandActor actor) { calls.add("status"); return List.of("Recordings enabled"); }
        @Override public void ghost(CommandActor actor, Optional<Boolean> on) { calls.add("ghost " + on.map(String::valueOf).orElse("toggle")); }
        @Override public void preview(CommandActor actor, String id) { calls.add("preview " + id); }
        @Override public void stopPreview(CommandActor actor) { calls.add("unpreview"); }
        @Override public List<String> ids() { return List.of("demo", "market"); }
    }

    @Director(name = "animus")
    static final class Root {
        RecordCommands record;
        Root(RecordingControl control) { record = new RecordCommands(() -> control); }
    }

    private final Control control = new Control();
    private final AnimusDirector director = new AnimusDirector(new Root(control),
            (String parameter, CommandActor actor) -> parameter.equals("recording") ? control.ids() : parameter.equals("action") ? List.of("play", "stop") : List.of());

    private AnimusDirectorTest.Actor run(boolean player, String... words) {
        AnimusDirectorTest.Actor actor = new AnimusDirectorTest.Actor(player, AnimusDirector.ADMIN);
        director.execute(actor, words);
        return actor;
    }

    @Test void everyRecordCommandReachesTheSessions() {
        run(true, "record", "start", "demo");
        run(true, "record", "stop");
        run(true, "record", "cancel");
        run(true, "record", "ghost", "off");
        run(true, "record", "ghost", "toggle");
        run(true, "record", "preview", "demo");
        run(true, "record", "preview", "demo", "action=stop");
        AnimusDirectorTest.Actor console = run(false, "record", "status");
        assertEquals(List.of("start demo", "stop", "cancel", "ghost false", "ghost toggle", "preview demo", "unpreview", "status"), control.calls);
        assertTrue(console.text().contains("Recordings enabled"), console.text());
    }

    @Test void badWordsAreRefusedWithTheUsageLine() {
        AnimusDirectorTest.Actor stray = run(true, "record", "preview", "demo", "stop");
        assertTrue(stray.text().contains("Usage: /animus record preview"), stray.text());
        AnimusDirectorTest.Actor state = run(true, "record", "ghost", "maybe");
        assertTrue(state.text().contains("Unknown state maybe"), state.text());
        AnimusDirectorTest.Actor action = run(true, "record", "preview", "demo", "action=rewind");
        assertTrue(action.text().contains("Unknown action rewind"), action.text());
        assertEquals(List.of(), control.calls);
    }

    @Test void theConsoleCannotRecordAndVisitorsCannotSeeTheGroup() {
        AnimusDirectorTest.Actor console = run(false, "record", "start", "demo");
        assertEquals(List.of(), control.calls, console.text());
        AnimusDirectorTest.Actor visitor = new AnimusDirectorTest.Actor(true, "animus.status");
        director.execute(visitor, new String[]{"record", "start", "demo"});
        assertTrue(visitor.text().contains("No permission: animus."), visitor.text());
        assertEquals(List.of(), control.calls);
    }

    @Test void recordingIdsComplete() {
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN);
        assertEquals(List.of("demo"), director.complete(admin, new String[]{"record", "preview", "d"}));
        assertTrue(director.complete(admin, new String[]{"record", ""}).containsAll(List.of("start", "stop", "cancel", "status", "ghost", "preview")));
    }
}
