package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.config.EditorNotifySettings.Kind;
import art.arcane.animus.core.text.RichText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EditorNoticesTest {
    static final UUID BRIAN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final String TX = "1a2b3c4d-0000-4000-8000-000000000001";

    /** The platform side: names it knows and every notice it was asked to deliver. */
    static final class Channel implements EditorNoticeChannel {
        final List<EditorNotice> delivered = new ArrayList<>();
        final Map<UUID, String> names = Map.of(BRIAN, "Brian");

        @Override public Optional<String> name(UUID player) { return Optional.ofNullable(names.get(player)); }

        @Override public synchronized void deliver(EditorNotice notice) { delivered.add(notice); }
    }

    private final Channel channel = new Channel();
    private final List<String> log = new ArrayList<>();
    private final AtomicReference<EditorNotifySettings> settings = new AtomicReference<>(EditorNotifySettings.DEFAULT);
    private long nanos;
    private boolean debug;
    private final EditorNotices notices = new EditorNotices(settings::get, channel, log::add, () -> nanos, () -> debug);

    private static EditorNotices.Who brian() { return new EditorNotices.Who("tok_abcdefgh", "laptop", Optional.of(BRIAN)); }

    private static EditorNotices.Event apply(List<String> documents, String transaction) {
        return new EditorNotices.Event(Kind.APPLY, brian(), "applied", "", documents, Optional.of(transaction), List.of());
    }

    private void advance(Duration duration) { nanos += duration.toNanos(); }

    private static String plain(String coloured) {
        StringBuilder out = new StringBuilder();
        for (RichText.Run run : RichText.sectionRuns(coloured)) out.append(run.text());
        return out.toString();
    }

    /** The whole chat line as the player reads it: the text, then the copy piece. */
    private static String chat(EditorNotice notice) {
        EditorNotice.Line line = notice.chat().orElseThrow();
        return plain(line.text()) + line.copy().map((EditorNotice.Copy copy) -> plain(copy.text())).orElse("");
    }

    private static String hover(EditorNotice notice) { return plain(notice.chat().orElseThrow().hover().orElseThrow()); }

    private static Optional<String> suggest(EditorNotice notice) { return notice.chat().orElseThrow().suggest(); }

    @Test
    void apply_afterTheQuietWindow_dingsOnceWithWhoWhatAndTransaction() {
        notices.record(apply(List.of("npc:town/guard", "npc:town/baker", "npc:town/smith", "behavior:greet"), TX));
        notices.tick();
        assertTrue(channel.delivered.isEmpty(), "nothing is sent while more events may follow");
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals(1, channel.delivered.size());
        EditorNotice notice = channel.delivered.getFirst();
        assertEquals(Optional.of(BRIAN), notice.owner(), "the token's owner hears it without the notify permission");
        assertEquals(EditorNotifySettings.DEFAULT.sound(Kind.APPLY), notice.sound().orElseThrow());
        assertEquals("[Animus]: Brian's editor applied 3 NPCs, 1 behavior (tx 1a2b3c4d)", chat(notice));
        assertFalse(notice.chat().orElseThrow().text().contains("\n"), "one line");
        assertEquals("Click to suggest /animus rollback " + TX, hover(notice));
        assertEquals(Optional.of("/animus rollback " + TX), suggest(notice));
        EditorNotice.Copy copy = notice.chat().orElseThrow().copy().orElseThrow();
        assertEquals(TX, copy.value(), "the transaction id is behind a click-to-copy");
        assertEquals(" (tx 1a2b3c4d)", plain(copy.text()));
        assertTrue(notice.actionBar().isEmpty(), "the action bar is off by default");
        assertEquals(List.of("Editor: Brian's editor applied 3 NPCs, 1 behavior (tx 1a2b3c4d)"), log);
    }

    @Test
    void burst_ofManyApplies_coalescesIntoOneNotice() {
        for (int index = 0; index < 300; index++) {
            notices.record(apply(List.of("npc:town/n" + index), "00000000-0000-4000-8000-" + String.format("%012d", index)));
            advance(Duration.ofMillis(5));
            notices.tick();
        }
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals(1, channel.delivered.size(), "a large import is one ding, not hundreds");
        String chat = chat(channel.delivered.getFirst());
        assertEquals("[Animus]: Brian's editor applied 300 NPCs (300 changes)", chat);
        assertEquals(Optional.of("/animus rollback 00000000-0000-4000-8000-000000000299"), suggest(channel.delivered.getFirst()), "the click offers the newest change");
    }

    @Test
    void steadyStream_isCutAtTheLongestWindow() {
        for (int index = 0; index < 100; index++) {
            notices.record(apply(List.of("npc:town/n" + index), TX));
            advance(Duration.ofMillis(100));
            notices.tick();
        }
        advance(EditorNotices.QUIET);
        notices.tick();
        long expected = (100 * 100L) / EditorNotices.LONGEST.toMillis();
        assertTrue(channel.delivered.size() >= expected && channel.delivered.size() <= expected + 2, "notices: " + channel.delivered.size());
    }

    @Test
    void differentKindsAndTokens_areSeparateNotices() {
        notices.record(apply(List.of("npc:a"), TX));
        notices.record(new EditorNotices.Event(Kind.SPAWN, brian(), "spawned", "", List.of("npc:town/guard"), Optional.empty(), List.of()));
        notices.record(new EditorNotices.Event(Kind.APPLY, new EditorNotices.Who("tok_other000", "", Optional.empty()), "applied", "", List.of("shop:bakery"), Optional.of(TX), List.of()));
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals(List.of("[Animus]: Brian's editor applied 1 NPC (tx 1a2b3c4d)", "[Animus]: Brian's editor spawned town/guard",
                "[Animus]: Editor tok_other000 applied 1 shop (tx 1a2b3c4d)"), channel.delivered.stream().map(EditorNoticesTest::chat).toList());
        assertTrue(suggest(channel.delivered.get(1)).isEmpty(), "a spawn has no transaction to roll back");
        assertTrue(channel.delivered.get(1).chat().orElseThrow().copy().isEmpty());
        assertTrue(channel.delivered.get(2).owner().isEmpty());
    }

    @Test
    void label_namesATokenWhoseOwnerIsUnknown() {
        notices.record(new EditorNotices.Event(Kind.PAIR, new EditorNotices.Who("tok_abcdefgh", "laptop", Optional.empty()), "paired", "", List.of(), Optional.empty(), List.of()));
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals("[Animus]: Editor \"laptop\" paired", chat(channel.delivered.getFirst()));
    }

    @Test
    void refusal_usesTheFailureSoundAndNamesTheReason() {
        notices.record(new EditorNotices.Event(Kind.REFUSED, brian(), "apply refused:", "conflict", List.of(), Optional.empty(), List.of("conflict: npc:town/guard changed on the server")));
        advance(EditorNotices.QUIET);
        notices.tick();
        EditorNotice notice = channel.delivered.getFirst();
        assertEquals(EditorNotifySettings.DEFAULT.sound(Kind.REFUSED), notice.sound().orElseThrow());
        assertEquals("[Animus]: Brian's editor apply refused: conflict", chat(notice));
        assertTrue(hover(notice).contains("npc:town/guard changed on the server"));
    }

    @Test
    void applyWithReloadProblems_soundsLikeAFailureAndSaysSo() {
        notices.record(new EditorNotices.Event(Kind.APPLY, brian(), "applied", "", List.of("npc:a"), Optional.of(TX), List.of("npcs/a.json: unknown world")));
        advance(EditorNotices.QUIET);
        notices.tick();
        EditorNotice notice = channel.delivered.getFirst();
        assertEquals(EditorNotifySettings.DEFAULT.sound(Kind.REFUSED), notice.sound().orElseThrow());
        assertEquals("[Animus]: Brian's editor applied 1 NPC; 1 problem (tx 1a2b3c4d)", chat(notice));
        assertTrue(hover(notice).contains("npcs/a.json: unknown world"));
    }

    @Test
    void valuesAreShownAsWritten_neverAsColourCodes() {
        notices.record(new EditorNotices.Event(Kind.PAIR, new EditorNotices.Who("tok_abcdefgh", "&4red", Optional.empty()), "paired", "", List.of(), Optional.empty(), List.of()));
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals("[Animus]: Editor \"&4red\" paired", chat(channel.delivered.getFirst()));
    }

    @Test
    void settings_switchChatActionBarSoundAndConsole() {
        settings.set(new EditorNotifySettings(true, false, true, false, EditorNotifySettings.DEFAULT.sounds()));
        notices.record(apply(List.of("npc:a"), TX));
        advance(EditorNotices.QUIET);
        notices.tick();
        EditorNotice notice = channel.delivered.getFirst();
        assertTrue(notice.chat().isEmpty());
        assertEquals("Brian's editor applied 1 NPC", plain(notice.actionBar().orElseThrow()));
        assertTrue(log.isEmpty(), "console = false keeps the log quiet");

        Map<Kind, EditorNotifySettings.Sound> silent = new java.util.EnumMap<>(EditorNotifySettings.DEFAULT.sounds());
        silent.put(Kind.APPLY, new EditorNotifySettings.Sound("", 0.8, 1.6));
        settings.set(new EditorNotifySettings(true, true, false, true, silent));
        notices.record(apply(List.of("npc:a"), TX));
        advance(EditorNotices.QUIET);
        notices.tick();
        assertTrue(channel.delivered.get(1).sound().isEmpty(), "an empty sound id is silent");

        settings.set(new EditorNotifySettings(false, true, true, true, EditorNotifySettings.DEFAULT.sounds()));
        notices.record(apply(List.of("npc:a"), TX));
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals(2, channel.delivered.size(), "enabled = false sends nothing");
    }

    @Test
    void debugMode_logsEachEventAsItHappens() {
        debug = true;
        notices.record(apply(List.of("npc:a"), TX));
        notices.record(apply(List.of("npc:b"), TX));
        assertEquals(List.of("Editor apply tok_abcdefgh npc:a tx " + TX, "Editor apply tok_abcdefgh npc:b tx " + TX), log);
        advance(EditorNotices.QUIET);
        notices.tick();
        assertEquals("Editor: Brian's editor applied 2 NPCs (2 changes)", log.getLast());
        assertFalse(channel.delivered.isEmpty());
    }
}
