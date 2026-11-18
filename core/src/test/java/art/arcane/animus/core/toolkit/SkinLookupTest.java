package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class SkinLookupTest {
    @Test void aFailedLookupSaysWhyInPlainWords() {
        SkinLookup.Found offline = SkinLookup.unreachable("Notch", new CompletionException(new ConnectException("refused")));
        assertEquals("Could not reach Mojang to look up Notch; the server needs internet access", offline.problem());
        assertTrue(offline.unreachable());
        SkinLookup.Found limited = SkinLookup.unreachable("Notch", new IOException("HTTP 429"));
        assertEquals("Mojang is rate limiting this server's skin lookups; try Notch again in a minute", limited.problem());
    }
}
