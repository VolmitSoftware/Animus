package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code GET /v1/terrain/surface} and {@code /v1/terrain/blocks} over a generated world beside the fixture NPCs at x -7.5 and 16.5, z 0.5. */
class EditorTerrainTest {
    private static final int CAP = 512 * 1024;
    @TempDir Path directory;
    private EditorHarness harness;
    private SchemaValidator schema;
    private FakeTerrain terrain;

    /** Chunks within 8 of the origin are loaded except (2, 2). Reads complete on their own "owner" thread. */
    static final class FakeTerrain implements EditorTerrain {
        final ExecutorService owner = Executors.newSingleThreadExecutor();
        final List<String> reads = new CopyOnWriteArrayList<>();
        volatile IntFunction<String> extraState = null;
        volatile String top = "minecraft:grass_block";

        boolean loaded(int chunkX, int chunkZ) { return Math.abs(chunkX) <= 8 && Math.abs(chunkZ) <= 8 && !(chunkX == 2 && chunkZ == 2); }

        @Override public Optional<World> world(String reference) {
            return reference.equals("world") || reference.equals("minecraft:overworld") ? Optional.of(new World("w-1", -64, 320)) : Optional.empty();
        }

        @Override public CompletionStage<Optional<Surface>> surface(String world, int chunkX, int chunkZ) {
            reads.add("surface " + chunkX + " " + chunkZ);
            return CompletableFuture.supplyAsync(() -> {
                if (!loaded(chunkX, chunkZ)) return Optional.<Surface>empty();
                int[] heights = new int[256], water = new int[256];
                String[] blocks = new String[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int index = z * 16 + x, worldX = chunkX * 16 + x;
                    heights[index] = 63 + Math.floorMod(worldX, 3);
                    blocks[index] = worldX == 3 ? "minecraft:water" : top;
                    water[index] = worldX == 3 ? 2 : 0;
                }
                return Optional.of(new Surface(heights, blocks, water));
            }, owner);
        }

        @Override public CompletionStage<Optional<Blocks>> blocks(String world, int chunkX, int chunkZ, int minY, int height) {
            reads.add("blocks " + chunkX + " " + chunkZ + " " + minY + " " + height);
            return CompletableFuture.supplyAsync(() -> {
                if (!loaded(chunkX, chunkZ)) return Optional.<Blocks>empty();
                String[] states = new String[16 * 16 * height];
                for (int i = 0; i < states.length; i++) {
                    int y = minY + i / 256;
                    IntFunction<String> extra = extraState;
                    states[i] = extra != null ? extra.apply(i) : y < 64 ? "minecraft:stone" : y == 64 && i % 16 == 0
                            ? "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]" : "minecraft:air";
                }
                return Optional.of(new Blocks(states));
            }, owner);
        }
    }

    @BeforeEach void open() throws Exception { open(EditorSettings.DEFAULT); }

    private void open(EditorSettings settings) throws Exception {
        if (harness != null) close();
        harness = new EditorHarness(directory.resolve("h" + System.nanoTime()), settings);
        terrain = new FakeTerrain();
        harness.host.terrain = terrain;
        schema = EditorMessageContractTest.validator();
    }

    @AfterEach void close() throws Exception {
        terrain.owner.shutdownNow();
        harness.close();
        harness = null;
    }

    private static EditorSettings radius(int blocks) {
        JsonObject table = EditorSettings.DEFAULT.toJson();
        table.addProperty("terrainRadius", blocks);
        return EditorSettings.parse(table);
    }

    private JsonObject valid(String definition, EditorResponse response) {
        assertEquals(200, response.status(), () -> response.body().toString());
        JsonObject body = EditorHarness.json(response);
        List<String> problems = schema.check(definition, body);
        assertTrue(problems.isEmpty(), () -> definition + " " + problems);
        assertTrue(body.toString().getBytes(StandardCharsets.UTF_8).length <= CAP);
        return body;
    }

    private static void refused(String code, int status, EditorResponse response) {
        assertEquals(status, response.status(), () -> response.body().toString());
        assertEquals(code, EditorHarness.json(response).getAsJsonObject("error").get("code").getAsString());
    }

    private EditorResponse get(String target, String token) throws Exception { return harness.send("GET", target, token, null); }

    private EditorResponse get(String target, String token, String ifNoneMatch) throws Exception {
        EditorRequest plain = harness.request("GET", target, token, null);
        Map<String, String> headers = new LinkedHashMap<>(plain.headers());
        headers.put("If-None-Match", ifNoneMatch);
        return harness.request(new EditorRequest(plain.transport(), plain.method(), plain.path(), headers, plain.body()));
    }

    private static short[] shorts(JsonObject body, String field) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(body.get(field).getAsString())).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[buffer.remaining() / 2];
        buffer.asShortBuffer().get(out);
        return out;
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : array) out.add(element.getAsString());
        return out;
    }

    /** Reads an LSB-first bitstream back to indices. */
    static int[] unpack(byte[] data, int bits, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int value = 0;
            for (int b = 0; b < bits; b++) {
                long bit = (long) i * bits + b;
                if ((data[(int) (bit >>> 3)] >>> (bit & 7) & 1) != 0) value |= 1 << b;
            }
            out[i] = value;
        }
        return out;
    }

    @Test
    void surface_nearAnNpc_answersTheTileWithItsHash() throws Exception {
        String token = harness.token();
        EditorResponse response = get("/v1/terrain/surface?world=world&tx=0&tz=0", token);
        JsonObject body = valid("terrain-surface-response", response);
        assertEquals(64, body.get("size").getAsInt());
        assertEquals(-64, body.get("minY").getAsInt());
        assertEquals(320, body.get("maxY").getAsInt());
        List<String> chunks = strings(body.getAsJsonArray("chunks"));
        assertEquals(16, chunks.size());
        assertEquals("unloaded", chunks.get(2 * 4 + 2));
        assertEquals("ok", chunks.get(0));
        assertEquals("\"" + body.get("hash").getAsString() + "\"", response.headers().get("ETag"));
        List<String> palette = strings(body.getAsJsonArray("palette"));
        assertEquals("minecraft:air", palette.getFirst());
        short[] heights = shorts(body, "heights"), blocks = shorts(body, "blocks");
        byte[] water = Base64.getDecoder().decode(body.get("water").getAsString());
        assertEquals(4096, heights.length);
        assertEquals(4096, water.length);
        assertEquals(63 + 5 % 3, heights[5 * 64 + 5]);
        assertEquals("minecraft:grass_block", palette.get(blocks[5 * 64 + 5]));
        assertEquals("minecraft:water", palette.get(blocks[7 * 64 + 3]));
        assertEquals(2, water[7 * 64 + 3]);
        assertEquals(-32768, heights[(2 * 16 + 1) * 64 + 2 * 16 + 1], "a cell of the unloaded chunk");
        assertEquals(0, blocks[(2 * 16 + 1) * 64 + 2 * 16 + 1]);
    }

    @Test
    void surface_unchangedHash_answersNotModified_andAChangeMovesTheHash() throws Exception {
        String token = harness.token();
        JsonObject first = valid("terrain-surface-response", get("/v1/terrain/surface?world=world&tx=0&tz=0", token));
        String hash = first.get("hash").getAsString();
        assertEquals(hash, valid("terrain-surface-response", get("/v1/terrain/surface?world=world&tx=0&tz=0", token)).get("hash").getAsString());
        EditorResponse same = get("/v1/terrain/surface?world=world&tx=0&tz=0", token, "\"" + hash + "\"");
        assertEquals(304, same.status());
        assertTrue(same.body().isEmpty());
        assertEquals("\"" + hash + "\"", same.headers().get("ETag"));
        terrain.top = "minecraft:sand";
        EditorResponse changed = get("/v1/terrain/surface?world=world&tx=0&tz=0", token, "\"" + hash + "\"");
        assertNotEquals(hash, valid("terrain-surface-response", changed).get("hash").getAsString());
    }

    @Test
    void surface_farFromEveryNpc_isForbiddenAndNeverRead() throws Exception {
        String token = harness.token();
        JsonObject body = valid("terrain-surface-response", get("/v1/terrain/surface?world=world&tx=40&tz=40", token));
        assertTrue(strings(body.getAsJsonArray("chunks")).stream().allMatch("forbidden"::equals));
        assertTrue(terrain.reads.isEmpty(), terrain.reads::toString);
        assertTrue(Set.of(-32768).containsAll(java.util.Arrays.stream(toInts(shorts(body, "heights"))).boxed().toList()));
    }

    @Test
    void surface_partlyInsideTheRadius_readsOnlyTheNearChunks() throws Exception {
        open(radius(16));
        String token = harness.token();
        JsonObject body = valid("terrain-surface-response", get("/v1/terrain/surface?world=world&tx=0&tz=0", token));
        List<String> chunks = strings(body.getAsJsonArray("chunks"));
        assertEquals("ok", chunks.get(0));
        assertEquals("ok", chunks.get(1));
        assertEquals("forbidden", chunks.get(3), "x 48..64 is more than 16 blocks from x 16.5");
        assertEquals("forbidden", chunks.get(3 * 4));
        assertFalse(terrain.reads.contains("surface 3 0"));
    }

    @Test
    void surface_tokenLimitedToAnotherFolder_seesNothing() throws Exception {
        String token = harness.token(new EditorTokens.Grant("", Optional.of(EditorHarness.ADMIN), EditorScope.DEFAULT_PAIRING, List.of("town"), Duration.ofDays(1)));
        JsonObject body = valid("terrain-surface-response", get("/v1/terrain/surface?world=world&tx=0&tz=0", token));
        assertTrue(strings(body.getAsJsonArray("chunks")).stream().allMatch("forbidden"::equals));
        assertTrue(terrain.reads.isEmpty());
    }

    @Test
    void radiusZero_forbidsEveryChunk_andTheFeatureSaysWhy() throws Exception {
        open(radius(0));
        String token = harness.token();
        JsonObject body = valid("terrain-surface-response", get("/v1/terrain/surface?world=world&tx=0&tz=0", token));
        assertTrue(strings(body.getAsJsonArray("chunks")).stream().allMatch("forbidden"::equals));
        JsonObject feature = EditorHarness.json(get("/v1/capabilities", token)).getAsJsonObject("features").getAsJsonObject("terrain");
        assertFalse(feature.get("available").getAsBoolean());
        assertTrue(feature.get("reason").getAsString().contains("terrainRadius"));
    }

    @Test
    void capabilities_listTerrain_andAHostWithoutTerrainAnswersNotFound() throws Exception {
        String token = harness.token();
        assertTrue(EditorHarness.json(get("/v1/capabilities", token)).getAsJsonObject("features").getAsJsonObject("terrain").get("available").getAsBoolean());
        harness.host.terrain = null;
        assertFalse(EditorHarness.json(get("/v1/capabilities", token)).getAsJsonObject("features").getAsJsonObject("terrain").get("available").getAsBoolean());
        refused("not-found", 404, get("/v1/terrain/surface?world=world&tx=0&tz=0", token));
    }

    @Test
    void tokenWithoutTerrainScope_isRefused() throws Exception {
        String token = harness.token(EditorHarness.grant(List.of(EditorScope.READ)));
        refused("scope-missing", 403, get("/v1/terrain/surface?world=world&tx=0&tz=0", token));
        refused("scope-missing", 403, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=60&height=8", token));
        assertTrue(terrain.reads.isEmpty());
    }

    @Test
    void bounds_areChecked() throws Exception {
        String token = harness.token();
        refused("invalid-request", 400, get("/v1/terrain/surface?world=world&tx=468751&tz=0", token));
        refused("invalid-request", 400, get("/v1/terrain/surface?world=world&tz=0", token));
        refused("invalid-request", 400, get("/v1/terrain/surface?world=world&tx=a&tz=0", token));
        refused("invalid-request", 400, get("/v1/terrain/surface?world=world&tx=0&tz=0&extra=1", token));
        refused("not-found", 404, get("/v1/terrain/surface?world=nether&tx=0&tz=0", token));
        refused("invalid-request", 400, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=60&height=0", token));
        refused("invalid-request", 400, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=60&height=65", token));
        refused("invalid-request", 400, get("/v1/terrain/blocks?world=world&cx=1875001&cz=0&y=60&height=8", token));
        refused("invalid-request", 400, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=320&height=8", token));
        refused("invalid-request", 400, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=-80&height=16", token));
        JsonObject clipped = valid("terrain-blocks-response", get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=300&height=64", token));
        assertEquals(300, clipped.get("y").getAsInt());
        assertEquals(20, clipped.get("height").getAsInt());
        JsonObject low = valid("terrain-blocks-response", get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=-70&height=10", token));
        assertEquals(-64, low.get("y").getAsInt());
        assertEquals(4, low.get("height").getAsInt());
        refused("not-found", 404, get("/v1/terrain/elsewhere?world=world", token));
    }

    @Test
    void blocks_packTheSliceAsAPaletteAndABitstream() throws Exception {
        String token = harness.token();
        EditorResponse response = get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=62&height=4", token);
        JsonObject body = valid("terrain-blocks-response", response);
        assertEquals("ok", body.get("status").getAsString());
        assertEquals("\"" + body.get("hash").getAsString() + "\"", response.headers().get("ETag"));
        List<String> palette = strings(body.getAsJsonArray("palette"));
        assertEquals(Set.of("minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"), Set.copyOf(palette));
        int bits = body.get("bits").getAsInt();
        assertEquals(2, bits);
        byte[] data = Base64.getDecoder().decode(body.get("data").getAsString());
        assertEquals((16 * 16 * 4 * bits + 7) / 8, data.length);
        int[] indices = unpack(data, bits, 16 * 16 * 4);
        assertEquals("minecraft:stone", palette.get(indices[0]));
        assertEquals("minecraft:stone", palette.get(indices[256 + 17]));
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", palette.get(indices[2 * 256]));
        assertEquals("minecraft:air", palette.get(indices[2 * 256 + 1]));
        assertEquals("minecraft:air", palette.get(indices[3 * 256 + 255]));
        assertEquals(304, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=62&height=4", token, body.get("hash").getAsString()).status());
    }

    @Test
    void blocks_unloadedOrForbidden_carryNoBlocks() throws Exception {
        String token = harness.token();
        JsonObject unloaded = valid("terrain-blocks-response", get("/v1/terrain/blocks?world=world&cx=2&cz=2&y=60&height=8", token));
        assertEquals("unloaded", unloaded.get("status").getAsString());
        assertEquals(List.of("minecraft:air"), strings(unloaded.getAsJsonArray("palette")));
        assertEquals(0, unloaded.get("bits").getAsInt());
        assertEquals("", unloaded.get("data").getAsString());
        JsonObject forbidden = valid("terrain-blocks-response", get("/v1/terrain/blocks?world=world&cx=500&cz=0&y=60&height=8", token));
        assertEquals("forbidden", forbidden.get("status").getAsString());
        assertFalse(terrain.reads.stream().anyMatch((String read) -> read.startsWith("blocks 500")));
    }

    @Test
    void blocks_worstCasePaletteFitsTheCap_andOneMoreStateIsTooLarge() throws Exception {
        String token = harness.token();
        String padding = "_".repeat(40);
        terrain.extraState = (int index) -> "minecraft:state_" + padding + "[n=" + Math.min(index, 4095) + "]";
        JsonObject full = valid("terrain-blocks-response", get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=60&height=64", token));
        assertEquals(4096, full.getAsJsonArray("palette").size());
        assertEquals(12, full.get("bits").getAsInt());
        terrain.extraState = (int index) -> "minecraft:state_" + padding + "[n=" + Math.min(index, 4096) + "]";
        refused("too-large", 413, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=60&height=64", token));
        String long_ = "x".repeat(200);
        terrain.extraState = (int index) -> "minecraft:" + long_ + "[n=" + Math.min(index, 4000) + "]";
        refused("too-large", 413, get("/v1/terrain/blocks?world=world&cx=0&cz=0&y=60&height=64", token));
    }

    @Test
    void bitstream_roundTripsEveryWidth() {
        Random random = new Random(7);
        for (int bits = 1; bits <= 12; bits++) {
            int[] values = new int[1000 + bits];
            for (int i = 0; i < values.length; i++) values[i] = random.nextInt(1 << bits);
            byte[] packed = EditorTerrainReads.pack(values, bits);
            assertEquals((values.length * bits + 7) / 8, packed.length);
            assertArrayEquals(values, unpack(packed, bits, values.length), "bits " + bits);
        }
        assertThrows(IllegalArgumentException.class, () -> EditorTerrainReads.pack(new int[]{4}, 2));
    }

    private static int[] toInts(short[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }
}
