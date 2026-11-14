package art.arcane.animus.core.editor;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The name a paired editor shows for a server when [editor] serverName is empty. */
class EditorServerNameTest {
    @Test
    void fallback_isTheMotdFirstLineWithoutCodes() {
        assertEquals("My Town", EditorServerName.fallback("§aMy §lTown\nsecond line", Path.of("/srv/towny")));
        assertEquals("Harbor", EditorServerName.fallback("&6Harbor", Path.of("/srv/towny")));
    }

    @Test
    void fallback_isTheFolderWhenTheMotdIsEmptyOrVanilla() {
        assertEquals("towny", EditorServerName.fallback("A Minecraft Server", Path.of("/srv/towny")));
        assertEquals("towny", EditorServerName.fallback("  ", Path.of("/srv/towny/.")));
        assertEquals("towny", EditorServerName.fallback(null, Path.of("/srv/towny")));
    }

    @Test
    void fallback_isTheWorkingFolderForAModServersEmptyDirectory() {
        String working = Path.of("").toAbsolutePath().getFileName().toString();
        assertEquals(working, EditorServerName.fallback("A Minecraft Server", Path.of("")));
    }

    @Test
    void bounded_isPlainAndAtMost64Characters() {
        assertEquals(64, EditorServerName.bounded("x".repeat(200)).length());
        assertEquals("Town", EditorServerName.bounded("&fTown"));
        assertEquals("Minecraft server", EditorServerName.bounded("&f"));
    }
}
