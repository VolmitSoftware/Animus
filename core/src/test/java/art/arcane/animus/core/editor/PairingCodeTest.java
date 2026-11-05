package art.arcane.animus.core.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingCodeTest {
    @Test
    void decodesEveryValidFixtureAndEncodesItBackExactly() throws IOException {
        JsonObject fixtures = SharedFixtures.object(SharedFixtures.fixture("editor/pairing.json"));
        int decoded = 0;
        for (JsonElement element : fixtures.getAsJsonArray("valid")) {
            JsonObject entry = element.getAsJsonObject();
            String code = entry.get("code").getAsString();
            JsonObject payload = entry.getAsJsonObject("payload");
            PairingCode pairing = PairingCode.decode(code);
            assertEquals(payload.get("tokenId").getAsString(), pairing.tokenId());
            assertEquals(payload.get("secret").getAsString(), pairing.secret());
            assertEquals(payload.get("fingerprint").getAsString(), pairing.fingerprint());
            assertEquals(Instant.parse(payload.get("expiresAt").getAsString()), pairing.expiresAt());
            assertEquals(payload.has("directUrl"), pairing.directUrl().isPresent());
            assertEquals(payload.has("relayUrl"), pairing.relayUrl().isPresent());
            assertEquals(code, pairing.encode());
            decoded++;
        }
        assertEquals(3, decoded);
    }

    @Test
    void refusesEveryInvalidFixture() throws IOException {
        JsonObject fixtures = SharedFixtures.object(SharedFixtures.fixture("editor/pairing.json"));
        int refused = 0;
        for (JsonElement element : fixtures.getAsJsonArray("invalid")) {
            JsonObject entry = element.getAsJsonObject();
            assertThrows(IllegalArgumentException.class, () -> PairingCode.decode(entry.get("code").getAsString()), entry.get("reason").getAsString());
            refused++;
        }
        assertTrue(refused >= 8);
    }

    @Test
    void refusesToIssueAPlainHttpAddressOffLoopback() {
        PairingCode valid = PairingCode.decode(firstValidCode());
        assertThrows(IllegalArgumentException.class, () -> new PairingCode(java.util.Optional.of("http://10.0.0.5:9797"), valid.relayUrl(),
                valid.fingerprint(), valid.serverKey(), valid.tokenId(), valid.secret(), valid.expiresAt(), valid.label(), valid.serverName()));
        PairingCode loopback = new PairingCode(java.util.Optional.of("http://localhost:9797"), java.util.Optional.empty(),
                valid.fingerprint(), valid.serverKey(), valid.tokenId(), valid.secret(), valid.expiresAt(), valid.label(), valid.serverName());
        assertEquals(loopback, PairingCode.decode(loopback.encode()));
    }

    private static String firstValidCode() {
        try {
            return SharedFixtures.object(SharedFixtures.fixture("editor/pairing.json")).getAsJsonArray("valid").get(0).getAsJsonObject().get("code").getAsString();
        } catch (IOException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
