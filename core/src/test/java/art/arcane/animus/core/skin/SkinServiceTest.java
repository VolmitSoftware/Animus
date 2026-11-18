package art.arcane.animus.core.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.SkinSettings;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.SkinDocuments;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.toolkit.SkinLookup;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server's own skin work: signing unsigned skins on first use, skins from the NPC's name, and following a player. */
class SkinServiceTest {
    static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[]{9, 9, 9});
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final List<String> log = new ArrayList<>(), looked = new ArrayList<>();
    final List<SkinSigner.Request> signed = new ArrayList<>();
    final Map<String, SkinLookup.Found> accounts = new HashMap<>();
    int refreshes;
    long now = 1_000_000;
    SkinSigner signer = new SkinSigner() {
        @Override public Optional<String> unavailable() { return Optional.empty(); }
        @Override public CompletableFuture<Signed> sign(Request request) {
            signed.add(request);
            return CompletableFuture.completedFuture(new Signed(new BodySkin(texture("https://textures.minecraft.net/texture/signed", request.slim()), SIGNATURE),
                    "https://textures.minecraft.net/texture/signed", request.slim()));
        }
    };

    static String texture(String url, boolean slim) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"" + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String npc(String id, String name, String extra) {
        return "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + java.util.UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)) + "\",\"id\":\"" + id
                + "\",\"group\":\"town\",\"name\":\"" + name + "\",\"type\":\"minecraft:player\",\"spawned\":true,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}";
    }

    static String skinFile(JsonObject document) { return JsonDocuments.write(document); }

    @BeforeEach void open() throws IOException { store = new DocumentStore(root); }

    SkinService service() throws IOException {
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty(), npcs.diagnostics().toString());
        return new SkinService(new SkinService.Parts(() -> npcs, () -> store, Runnable::run, () -> refreshes++, () -> SkinSettings.DEFAULT,
                (String name) -> { looked.add(name); return CompletableFuture.completedFuture(accounts.getOrDefault(name.toLowerCase(), SkinLookup.Found.missing("No Minecraft account is named " + name))); },
                signer, (String text) -> CompletableFuture.completedFuture(Optional.of("Notch")), log::add, () -> now, Duration.ZERO));
    }

    String write(String path, String text) throws IOException {
        store.commit(List.of(new DocumentStore.Change(path, "missing", text)));
        return JsonDocuments.hash(text);
    }

    @Test void scan_unsignedSkinWithKeptPng_isSignedAndItsWearersMove() throws IOException {
        JsonObject unsigned = new JsonObject();
        unsigned.addProperty("textureRaw", texture("https://example.invalid/none", true));
        unsigned.addProperty("label", "Guard");
        unsigned.addProperty("image", Base64.getEncoder().encodeToString(MineSkinTest.png(64, 64)));
        String text = skinFile(unsigned), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("npcs/town/guard.json", npc("guard", "Guard", ",\"skin\":\"" + hash + "\""));
        service().scan().join();
        assertEquals(1, signed.size());
        assertTrue(signed.getFirst().source() instanceof SkinSigner.Image, "the kept PNG is uploaded");
        assertTrue(signed.getFirst().slim(), "the arm model comes from the texture");
        assertEquals("Guard", signed.getFirst().name());
        String moved = npcs.find("guard").orElseThrow().json().get("skin").getAsString();
        assertFalse(moved.equals(hash), "the NPC wears the signed file");
        assertNull(store.read("skins/" + hash + ".json"), "the unsigned file goes");
        assertEquals(SIGNATURE, SkinDocuments.read(store, moved).signature());
        JsonObject now = JsonDocuments.object(store.read("skins/" + moved + ".json"));
        assertEquals("Guard", now.get("label").getAsString());
        assertFalse(now.has("image"), "the PNG is dropped once signed");
        assertEquals(1, refreshes);
        assertTrue(log.stream().anyMatch((String line) -> line.startsWith("Signed skin Guard through MineSkin, so players see it; now " + moved.substring(0, 12) + ", moved")), log.toString());
        service().scan().join();
        assertEquals(1, signed.size(), "a signed skin is not signed again");
    }

    @Test void scan_unsignedSkinWithAddressOnly_signsTheAddress() throws IOException {
        JsonObject unsigned = new JsonObject();
        unsigned.addProperty("textureRaw", texture("https://textures.minecraft.net/texture/abc", false));
        String text = skinFile(unsigned), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("groups/looks.json", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"looks\",\"values\":{\"skins\":[\"" + hash + "\"]}}");
        service().scan().join();
        assertEquals(1, signed.size(), "a skin a trait group pools is signed too");
        assertEquals(new SkinSigner.Address("https://textures.minecraft.net/texture/abc"), signed.getFirst().source());
        assertFalse(store.read("groups/looks.json").contains(hash), "the group moves to the signed file");
    }

    @Test void scan_unsignedSkinWithNothingToSign_saysSoOnce() throws IOException {
        JsonObject unsigned = new JsonObject();
        unsigned.addProperty("textureRaw", Base64.getEncoder().encodeToString("{\"profileName\":\"animus-editor\",\"textures\":{\"SKIN\":{}}}".getBytes(StandardCharsets.UTF_8)));
        String text = skinFile(unsigned), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("npcs/town/guard.json", npc("guard", "Guard", ",\"skin\":\"" + hash + "\""));
        SkinService service = service();
        service.scan().join();
        service.scan().join();
        assertTrue(signed.isEmpty());
        assertEquals(1, log.stream().filter((String line) -> line.contains("keeps no PNG to sign from")).count(), log.toString());
    }

    @Test void scan_signingRefused_logsWhyAndWaitsBeforeTryingAgain() throws IOException {
        signer = SkinSigner.off("MineSkin is rate limiting this server; try again in a minute");
        JsonObject unsigned = new JsonObject();
        unsigned.addProperty("textureRaw", texture("https://textures.minecraft.net/texture/abc", false));
        String text = skinFile(unsigned), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("npcs/town/guard.json", npc("guard", "Guard", ",\"skin\":\"" + hash + "\""));
        SkinService service = service();
        service.scan().join();
        service.scan().join();
        assertEquals(1, log.stream().filter((String line) -> line.contains("MineSkin is rate limiting this server")).count(), "retried only after a wait: " + log);
        assertTrue(log.getLast().contains("trying again in 30 minutes"), log.toString());
        now += Duration.ofMinutes(31).toMillis();
        service.scan().join();
        assertEquals(2, log.stream().filter((String line) -> line.contains("MineSkin is rate limiting this server")).count());
    }

    @Test void scan_fromNameWithoutSkin_wearsTheAccountNamedLikeTheNpc() throws IOException {
        accounts.put("notch", SkinLookup.Found.skin("Notch", new BodySkin(texture("https://textures.minecraft.net/texture/notch", false), SIGNATURE)));
        write("npcs/town/notch.json", npc("notch", "&6Notch", ",\"modules\":{\"animus:skin\":{\"fromName\":true}}"));
        write("npcs/town/bench.json", npc("bench", "Bench Sitter", ",\"modules\":{\"animus:skin\":{\"fromName\":true}}"));
        SkinService service = service();
        service.scan().join();
        assertEquals(List.of("Notch"), looked, "colour codes are stripped; a name with a space is not looked up");
        String hash = npcs.find("notch").orElseThrow().json().get("skin").getAsString();
        JsonObject file = JsonDocuments.object(store.read("skins/" + hash + ".json"));
        assertEquals("Notch", file.get("player").getAsString(), "the skin remembers the account it came from");
        assertTrue(log.stream().anyMatch((String line) -> line.contains("'Bench Sitter' is not a Minecraft name")), log.toString());
        service.scan().join();
        assertEquals(1, looked.size(), "an NPC that has its skin is not looked up again");
    }

    /** The skin the server takes from an imported NPC's name belongs to that import: rollback right after apply undoes both. */
    @Test void scan_fromNameOnAnImportedNpc_rollsBackWithTheImport() throws IOException {
        java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(root.resolve("imports")).resolve("saves.yml"), """
                npc:
                  '7':
                    uuid: 00000000-0000-0000-0000-000000000007
                    name: Notch
                    traits:
                      type: PLAYER
                      spawned: true
                      location: {world: world, x: 1, y: 64, z: 1}
                """);
        accounts.put("notch", SkinLookup.Found.skin("Notch", new BodySkin(texture("https://textures.minecraft.net/texture/notch", false), SIGNATURE)));
        SkinService service = service();
        art.arcane.animus.core.migration.CitizensDropIn flow = new art.arcane.animus.core.migration.CitizensDropIn(store, npcs, art.arcane.animus.core.migration.CitizensDropIn.Host.NONE);
        flow.preview("reject", Optional.empty(), Map.of(), false);
        flow.applyStaged();
        service.scan().join();
        assertTrue(npcs.find("citizens-7").orElseThrow().json().has("skin"), "the imported NPC took the skin of the account named like it");
        String rolledBack = String.join("\n", flow.rollbackApplied());
        assertTrue(rolledBack.contains("Citizens import undone"), rolledBack);
        assertTrue(npcs.all().isEmpty(), rolledBack);
    }

    @Test void scan_fromNameWithNoSuchAccount_isAskedAgainOnlyHoursLater() throws IOException {
        write("npcs/town/ghost.json", npc("ghost", "Ghosty", ",\"modules\":{\"animus:skin\":{\"fromName\":true}}"));
        SkinService service = service();
        service.scan().join();
        service.scan().join();
        assertEquals(1, looked.size());
        now += Duration.ofHours(7).toMillis();
        service.scan().join();
        assertEquals(2, looked.size());
    }

    @Test void follow_playerChangedTheirSkin_followersWearTheNewOneOthersKeepTheOld() throws IOException {
        JsonObject old = new JsonObject();
        old.addProperty("textureRaw", texture("https://textures.minecraft.net/texture/old", false));
        old.addProperty("signature", SIGNATURE);
        old.addProperty("label", "Notch");
        old.addProperty("player", "Notch");
        String text = skinFile(old), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("npcs/town/guard.json", npc("guard", "Guard", ",\"skin\":\"" + hash + "\",\"modules\":{\"animus:skin\":{\"refresh\":true}}"));
        write("npcs/town/statue.json", npc("statue", "Statue", ",\"skin\":\"" + hash + "\""));
        accounts.put("notch", SkinLookup.Found.skin("Notch", new BodySkin(texture("https://textures.minecraft.net/texture/old", false), SIGNATURE)));
        SkinService service = service();
        service.scan().join();
        assertEquals(List.of("Notch"), looked, "a new follower is checked at the next scan");
        assertEquals(hash, npcs.find("guard").orElseThrow().json().get("skin").getAsString(), "same texture, nothing to change");
        accounts.put("notch", SkinLookup.Found.skin("Notch", new BodySkin(texture("https://textures.minecraft.net/texture/new", false), SIGNATURE)));
        service.scan().join();
        assertEquals(1, looked.size(), "a known follower waits for the refresh");
        now += Duration.ofMinutes(6).toMillis();
        service.refresh().join();
        String moved = npcs.find("guard").orElseThrow().json().get("skin").getAsString();
        assertFalse(moved.equals(hash), "the follower wears the new skin");
        assertEquals(hash, npcs.find("statue").orElseThrow().json().get("skin").getAsString(), "an NPC that does not follow keeps it");
        assertTrue(SkinFile.parse(store.read("skins/" + moved + ".json")).url().orElseThrow().endsWith("/new"));
        assertTrue(log.getLast().startsWith("Notch changed their skin: Skin Notch on "), log.toString());
    }

    @Test void follow_lookupsAreKeptForFiveMinutes() throws IOException {
        JsonObject old = new JsonObject();
        old.addProperty("textureRaw", texture("https://textures.minecraft.net/texture/old", false));
        old.addProperty("signature", SIGNATURE);
        old.addProperty("player", "Notch");
        String text = skinFile(old), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("npcs/town/guard.json", npc("guard", "Guard", ",\"skin\":\"" + hash + "\",\"modules\":{\"animus:skin\":{\"refresh\":true}}"));
        accounts.put("notch", SkinLookup.Found.skin("Notch", new BodySkin(texture("https://textures.minecraft.net/texture/old", false), SIGNATURE)));
        SkinService service = service();
        service.refresh().join();
        service.refresh().join();
        assertEquals(1, looked.size(), "the second pass reuses the answer");
    }

    @Test void follow_placeholderSource_isFilledThenLookedUp() throws IOException {
        JsonObject old = new JsonObject();
        old.addProperty("textureRaw", texture("https://textures.minecraft.net/texture/old", false));
        old.addProperty("signature", SIGNATURE);
        old.addProperty("player", "%top_player%");
        String text = skinFile(old), hash = write("skins/" + JsonDocuments.hash(text) + ".json", text);
        write("npcs/town/champion.json", npc("champion", "Champion", ",\"skin\":\"" + hash + "\",\"modules\":{\"animus:skin\":{\"refresh\":true}}"));
        accounts.put("notch", SkinLookup.Found.skin("Notch", new BodySkin(texture("https://textures.minecraft.net/texture/notch", false), SIGNATURE)));
        service().refresh().join();
        assertEquals(List.of("Notch"), looked);
        String moved = npcs.find("champion").orElseThrow().json().get("skin").getAsString();
        assertEquals("%top_player%", JsonDocuments.object(store.read("skins/" + moved + ".json")).get("player").getAsString(), "the placeholder stays the source");
    }
}
