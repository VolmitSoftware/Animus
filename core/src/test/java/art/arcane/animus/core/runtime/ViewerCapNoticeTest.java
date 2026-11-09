package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewerCapNoticeTest {
    @Test void checksEveryThirtySecondsAndWarnsAtMostEveryTenMinutes() {
        ViewerCapNotice notice = new ViewerCapNotice();
        assertTrue(notice.due(100));
        assertFalse(notice.due(101), "a check waits 30 seconds after the last one");
        assertTrue(notice.due(100 + ViewerCapNotice.CHECK_TICKS));
        String warning = notice.warn(700, "Alex", 64);
        assertTrue(warning.startsWith("Alex has 64 NPCs in view, the [npcs] maximumVisible cap in config.toml"), warning);
        assertFalse(notice.due(700 + ViewerCapNotice.CHECK_TICKS), "no check while the last warning is recent");
        assertTrue(notice.due(700 + ViewerCapNotice.WARN_TICKS));
    }
}
