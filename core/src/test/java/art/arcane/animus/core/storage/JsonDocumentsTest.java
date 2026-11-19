package art.arcane.animus.core.storage;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Documents read and write alike on the Gson the mod jars bundle and on the newer Gson Spigot and Paper ship, which
 * added a nesting limit, number size limits and a duplicate-key check after null that Animus must not depend on.
 */
class JsonDocumentsTest {
    @Test void aDuplicateKeyIsRefusedEvenAfterNull() {
        IOException afterNull = assertThrows(IOException.class, () -> JsonDocuments.object("{\"name\":null,\"name\":\"Bob\"}"));
        assertEquals("$.name: duplicate key", afterNull.getMessage());
        assertThrows(IOException.class, () -> JsonDocuments.object("{\"name\":\"Al\",\"name\":\"Bob\"}"));
    }

    @Test void nestingStopsAtAnimusOwnLimitBeforeGsonsLimit() throws IOException {
        assertTrue(JsonDocuments.object("{\"a\":" + arrays(64) + "}").has("a"));
        IOException deeper = assertThrows(IOException.class, () -> JsonDocuments.object("{\"a\":" + arrays(65) + "}"));
        assertTrue(deeper.getMessage().endsWith(": nesting exceeds 64"), deeper.getMessage());
        IOException beyondGson = assertThrows(IOException.class, () -> JsonDocuments.object("{\"a\":" + arrays(300) + "}"));
        assertTrue(beyondGson.getMessage().endsWith(": nesting exceeds 64"), beyondGson.getMessage());
    }

    @Test void nonAsciiDigitsAreTextWhenQuotedAndMalformedOtherwise() throws IOException {
        JsonObject quoted = JsonDocuments.object("{\"count\":\"\u0661\u0662\"}");
        assertTrue(quoted.getAsJsonPrimitive("count").isString());
        assertEquals("\u0661\u0662", quoted.get("count").getAsString());
        assertThrows(IOException.class, () -> JsonDocuments.object("{\"count\":\u0661\u0662}"));
    }

    @Test void anExponentBeyondGsonsNumberLimitKeepsItsExactValue() throws IOException {
        JsonObject document = JsonDocuments.object("{\"exponent\":1e100000}");
        assertEquals(new BigDecimal("1e100000"), document.get("exponent").getAsBigDecimal());
        assertEquals(document, JsonDocuments.object(JsonDocuments.write(document)));
    }

    @Test void writtenTextIsByteForByteStable() throws IOException {
        String read = "{\"name\":\"<b>Bob & 'Al'</b>\",\"line\":\"a\\u2028b\",\"emoji\":\"\uD83D\uDE00\",\"whole\":12,\"scale\":1.50,"
                + "\"exponent\":1e3,\"none\":null,\"empty\":[],\"nested\":{\"list\":[1,\"two\",null,{}]}}";
        String expected = """
                {
                  "name": "<b>Bob & 'Al'</b>",
                  "line": "a\\u2028b",
                  "emoji": "\uD83D\uDE00",
                  "whole": 12,
                  "scale": 1.50,
                  "exponent": 1E+3,
                  "none": null,
                  "empty": [],
                  "nested": {
                    "list": [
                      1,
                      "two",
                      null,
                      {}
                    ]
                  }
                }
                """;
        assertEquals(expected, JsonDocuments.write(JsonDocuments.object(read)));
    }

    private static String arrays(int depth) { return "[".repeat(depth) + "]".repeat(depth); }
}
