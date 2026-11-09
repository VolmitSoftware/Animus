package art.arcane.animus.core.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandActorTest {
    @Test void aFullFileQueueNamesTheSettingAndTellsScriptsToSendAgain() {
        assertTrue(CommandActor.BUSY.contains("[files] maximumOperations"), CommandActor.BUSY);
        assertTrue(CommandActor.BUSY.endsWith("send it again"), CommandActor.BUSY);
    }
}
