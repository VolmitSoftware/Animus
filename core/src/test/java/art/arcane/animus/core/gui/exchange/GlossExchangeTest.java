package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.gui.GuiValidator;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GlossExchangeTest {
    static final String HOLOGRAM = "{\"schemaVersion\":3,\"revision\":1,\"anchor\":{\"world\":\"world\",\"position\":[0,64,0]},\"lines\":[\"&dHello\"]}";
    static final String INVENTORY = "{\"schemaVersion\":1,\"revision\":1,\"title\":\"&8Shop\",\"resolution\":\"9x3\"}";
    static final String ANIMUS_ONLY = "{\"schemaVersion\":1,\"revision\":1,\"keys\":{\"B\":{\"type\":\"button\",\"actions\":[{\"type\":\"animus:open-shop\",\"shop\":\"shop:bakery\"}]}}}";
    static final GuiValidator ACCEPT = (GuiKind kind, String id, String json) -> List.of();

    @TempDir Path temporary;

    private static void write(Path file, String text) throws IOException { Files.createDirectories(file.getParent()); Files.writeString(file, text); }

    @Test void idsMapBothWays() {
        assertEquals("gloss:lobby", GlossIds.toAnimus("lobby", "gloss").orElseThrow());
        assertEquals("gloss:my-shop.v2".replace('.', '-'), GlossIds.toAnimus("My-Shop.v2", "gloss").orElseThrow());
        assertEquals("gloss:town/main", GlossIds.toAnimus("town/main", "gloss").orElseThrow());
        assertTrue(GlossIds.toAnimus("...", "gloss").isPresent());
        assertTrue(GlossIds.toAnimus("", "gloss").isEmpty());
        assertEquals("lobby", GlossIds.toGloss(GuiKind.HOLOGRAM, "gloss:lobby").orElseThrow());
        assertEquals("town.signs.a", GlossIds.toGloss(GuiKind.HOLOGRAM, "town:signs/a").orElseThrow());
        assertEquals("town.signs/a", GlossIds.toGloss(GuiKind.MENU, "town:signs/a").orElseThrow());
        assertTrue(GlossIds.toGloss(GuiKind.HOLOGRAM, "gloss:_hidden").isEmpty());
    }

    @Test void importsTheExportLayoutAndValidatesFirst(@TempDir Path data) throws IOException {
        Path source = temporary.resolve("export");
        write(source.resolve("holograms/Lobby.json"), HOLOGRAM);
        write(source.resolve("inventories/shop.json"), INVENTORY);
        write(source.resolve("boards/side.json"), "{}");
        JsonObject workspace = new JsonObject();
        workspace.addProperty("format", "gloss-workspace-export"); workspace.addProperty("version", 1);
        JsonArray documents = new JsonArray();
        for (String[] entry : new String[][]{{"holograms", "Lobby"}, {"inventories", "shop"}, {"boards", "side"}}) {
            JsonObject document = new JsonObject();
            document.addProperty("kind", entry[0]); document.addProperty("id", entry[1]); document.addProperty("path", entry[0] + "/" + entry[1] + ".json");
            documents.add(document);
        }
        workspace.add("documents", documents);
        write(source.resolve("workspace.json"), JsonDocuments.write(workspace));
        GuiValidator refuseInventories = (GuiKind kind, String id, String json) -> kind == GuiKind.INVENTORY ? List.of("$.title: no") : List.of();
        GlossExchange exchange = new GlossExchange(data, refuseInventories);
        GlossExchange.Report report = exchange.importDocuments(GlossSources.read(source), "gloss", false);
        assertEquals(List.of("hologram gloss:lobby"), report.written());
        assertTrue(report.refused().stream().anyMatch((String line) -> line.contains("gloss:shop") && line.contains("$.title: no")));
        assertTrue(report.skipped().stream().anyMatch((String line) -> line.contains("boards")));
        assertTrue(report.notes().stream().anyMatch((String line) -> line.contains("Lobby")));
        assertTrue(Files.exists(data.resolve("gui/holograms/gloss/lobby.json")));
        assertFalse(Files.exists(data.resolve("gui/inventories/gloss/shop.json")));
        GlossExchange.Report again = exchange.importDocuments(GlossSources.read(source), "gloss", false);
        assertEquals(List.of("hologram gloss:lobby"), again.unchanged());
    }

    @Test void differentContentIsKeptUnlessReplaced(@TempDir Path data) throws IOException {
        write(data.resolve("gui/holograms/gloss/lobby.json"), HOLOGRAM.replace("Hello", "Old"));
        GlossExchange exchange = new GlossExchange(data, ACCEPT);
        List<GlossDocument> documents = List.of(new GlossDocument(GuiKind.HOLOGRAM, "lobby", HOLOGRAM));
        assertTrue(exchange.importDocuments(documents, "gloss", false).refused().get(0).contains("replace=true"));
        assertEquals(List.of("hologram gloss:lobby"), exchange.importDocuments(documents, "gloss", true).written());
        assertTrue(Files.readString(data.resolve("gui/holograms/gloss/lobby.json")).contains("Hello"));
    }

    @Test void collidingIdsRefuseTheLater(@TempDir Path data) throws IOException {
        GlossExchange exchange = new GlossExchange(data, ACCEPT);
        GlossExchange.Report report = exchange.importDocuments(List.of(new GlossDocument(GuiKind.HOLOGRAM, "Lobby", HOLOGRAM),
                new GlossDocument(GuiKind.HOLOGRAM, "lobby", HOLOGRAM)), "gloss", false);
        assertEquals(1, report.written().size());
        assertTrue(report.refused().get(0).contains("lobby"));
    }

    @Test void bundlesRoundTrip(@TempDir Path data) throws IOException {
        write(data.resolve("gui/holograms/gloss/lobby.json"), HOLOGRAM);
        write(data.resolve("gui/inventories/town/shop.json"), INVENTORY);
        write(data.resolve("gui/inventories/town/counter.json"), ANIMUS_ONLY);
        GlossExchange exchange = new GlossExchange(data, ACCEPT);
        List<GlossExchange.Exported> exported = new ArrayList<>();
        GlossExchange.Report report = exchange.collect(Optional.empty(), Optional.empty(), exported);
        assertTrue(report.skipped().stream().anyMatch((String line) -> line.contains("town:counter") && line.contains("animus:")));
        Path bundle = temporary.resolve("out.json");
        GlossBundle.write(bundle, exported);
        JsonObject root = JsonDocuments.object(Files.readString(bundle));
        assertEquals("gloss-editor-workspace", root.get("format").getAsString());
        JsonObject workspace = root.getAsJsonObject("workspace");
        assertEquals(2, workspace.get("schemaVersion").getAsInt());
        assertEquals(2, workspace.getAsJsonArray("documents").size());
        JsonObject first = workspace.getAsJsonArray("documents").get(0).getAsJsonObject();
        UUID.fromString(first.get("id").getAsString());
        assertTrue(first.get("json").isJsonPrimitive());
        List<GlossDocument> back = GlossSources.read(bundle).documents();
        assertEquals(2, back.size());
        assertTrue(back.stream().anyMatch((GlossDocument document) -> document.kind() == GuiKind.INVENTORY && document.id().equals("town.shop")));
        assertEquals(JsonDocuments.write(JsonDocuments.object(HOLOGRAM)), JsonDocuments.write(JsonDocuments.object(back.stream()
                .filter((GlossDocument document) -> document.kind() == GuiKind.HOLOGRAM).findFirst().orElseThrow().json())));
        GlossBundle.write(temporary.resolve("again.json"), exported);
        assertEquals(Files.readString(bundle), Files.readString(temporary.resolve("again.json")));
    }

    @Test void bundleFormatIsCheckedByName() throws IOException {
        Path bundle = temporary.resolve("bad.json");
        write(bundle, "{\"format\":\"gloss-editor-workspace\",\"version\":2,\"workspace\":{},\"images\":[]}");
        IOException failure = assertThrows(IOException.class, () -> GlossSources.read(bundle));
        assertTrue(failure.getMessage().contains("version 2"));
        write(bundle, "{\"format\":\"gloss-editor-workspace\",\"version\":1,\"workspace\":{\"schemaVersion\":3,\"documents\":[]},\"images\":[]}");
        assertTrue(assertThrows(IOException.class, () -> GlossSources.read(bundle)).getMessage().contains("schemaVersion 3"));
    }

    @Test void exportsTheFolderLayoutAndReportsNamespacedReferences(@TempDir Path data) throws IOException {
        write(data.resolve("gui/inventories/town/menu.json"), "{\"schemaVersion\":1,\"revision\":1,\"keys\":{\"X\":{\"type\":\"button\",\"actions\":[{\"type\":\"inventory\",\"id\":\"town:other\"}]}}}");
        GlossExchange exchange = new GlossExchange(data, ACCEPT);
        List<GlossExchange.Exported> exported = new ArrayList<>();
        GlossExchange.Report report = exchange.collect(Optional.of(GuiKind.INVENTORY), Optional.empty(), exported);
        assertTrue(report.notes().stream().anyMatch((String line) -> line.contains("town:other")));
        Path folder = temporary.resolve("folder");
        GlossExchange.Report written = GlossFolders.writeExport(folder, exported);
        assertEquals(1, written.written().size());
        assertTrue(Files.exists(folder.resolve("inventories/town.menu.json")));
        JsonObject workspace = JsonDocuments.object(Files.readString(folder.resolve("workspace.json")));
        assertEquals("gloss-workspace-export", workspace.get("format").getAsString());
        assertEquals("inventories/town.menu.json", workspace.getAsJsonArray("documents").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(1, GlossSources.read(folder).documents().size());
    }

    @Test void glossFoldersAreNeverOverwrittenWithoutReplace(@TempDir Path gloss) throws IOException {
        write(gloss.resolve("holograms/lobby.json"), HOLOGRAM.replace("Hello", "Theirs"));
        List<GlossExchange.Exported> exported = List.of(new GlossExchange.Exported(GuiKind.HOLOGRAM, "gloss:lobby", "lobby", HOLOGRAM, 0L));
        assertEquals(1, GlossFolders.writeLive(gloss, exported, false).refused().size());
        assertEquals(1, GlossFolders.writeLive(gloss, exported, true).written().size());
        List<GlossDocument> live = GlossFolders.readLive(gloss, Optional.of(GuiKind.HOLOGRAM), Optional.empty());
        assertEquals(1, live.size());
        assertEquals("lobby", live.get(0).id());
    }

    @Test void commandsImportFilesAndRefuseGlossWithoutIt(@TempDir Path data) throws IOException {
        write(data.resolve("imports/inbox/pack/holograms/lobby.json"), HOLOGRAM);
        List<String> told = new ArrayList<>();
        art.arcane.animus.core.command.CommandActor actor = new art.arcane.animus.core.command.CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { told.add(message); }
            @Override public Optional<UUID> player() { return Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        int[] reloads = {0};
        GlossExchange exchange = new GlossExchange(data, ACCEPT, () -> art.arcane.animus.core.gui.GlossBridge.absent("Gloss is not installed"), Runnable::run, told::add);
        exchange.importFile(actor, "pack", "town", false, () -> reloads[0]++);
        assertTrue(Files.exists(data.resolve("gui/holograms/town/lobby.json")));
        assertEquals(1, reloads[0]);
        assertTrue(told.get(0).startsWith("GUI import: 1 written"));
        exchange.exportGloss(actor, GlossExchange.kind("all"), Optional.empty(), false);
        assertEquals("Gloss is not installed", told.get(told.size() - 1));
        exchange.exportFile(actor, "out", Optional.of("bundle"));
        assertTrue(Files.exists(data.resolve("exports/gui/out.json")));
        exchange.importFile(actor, "../escape", "gloss", false, () -> {});
        assertTrue(told.get(told.size() - 1).startsWith("GUI import: name a folder"));
    }
}
