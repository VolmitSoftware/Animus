package art.arcane.animus.core.runtime;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.navigation.WaypointSettings;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RouteDocumentsTest {
    @TempDir Path directory;
    private static final String ROUTE="""
            {"schemaVersion":1,"mode":"walk","speed":2,"loop":true,"waitTicks":10,
             "points":[{"x":1,"y":61,"z":3},{"x":3,"y":61,"z":3}]}
            """;
    @Test void sharedRouteIsLoadedAsBoundedImmutableTypedData() throws Exception {
        try(var store=new DocumentStore(directory)) {
            Path routes=store.resolve("behaviors/routes");Files.createDirectories(routes);Files.writeString(routes.resolve("patrol.json"),ROUTE);
            var data=RouteDocuments.read(store,"patrol");assertTrue(data.loop());assertEquals(2,data.points().size());assertEquals(10,data.waitTicks());
            assertThrows(UnsupportedOperationException.class,()->data.points().clear());
        }
    }
    @Test void missingOversizedAndMalformedUtf8ReferencesFailWithoutUncheckedReads() throws Exception {
        try(var store=new DocumentStore(directory)) {
            Path routes=store.resolve("behaviors/routes");Files.createDirectories(routes);
            assertThrows(IOException.class,()->RouteDocuments.read(store,"missing"));
            Files.writeString(routes.resolve("large.json")," ".repeat(64*1024+1));
            assertTrue(assertThrows(IOException.class,()->RouteDocuments.read(store,"large")).getMessage().contains("64 KiB"));
            Files.write(routes.resolve("bad.json"),new byte[]{(byte)0xc3,(byte)0x28});
            assertThrows(IOException.class,()->RouteDocuments.read(store,"bad"));
            assertThrows(IllegalArgumentException.class,()->RouteDocuments.read(store,"../outside"));
        }
    }
    @Test void schemaAndRecursiveReferencesAreRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.document(ROUTE.replace("\"schemaVersion\":1","\"schemaVersion\":2")));
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.document("{\"schemaVersion\":1,\"route\":\"self\"}"));
    }
}
