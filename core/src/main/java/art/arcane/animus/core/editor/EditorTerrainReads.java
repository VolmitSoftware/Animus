package art.arcane.animus.core.editor;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@code GET /v1/terrain/surface} and {@code GET /v1/terrain/blocks}: loaded terrain near the NPCs a token may see. A chunk
 * farther than {@code [editor] terrainRadius} from every such NPC in its world is {@code forbidden} and never read; a chunk
 * the server does not hold is {@code unloaded}. Reads run on the owning thread through {@link EditorTerrain}; encoding and
 * hashing run after, off that thread. Every answer carries its content hash as an ETag and fits the relay frame.
 */
final class EditorTerrainReads {
    /** The most bytes one terrain body may serialize to: a 1 MiB relay frame less base64 and frame overhead. */
    static final int BODY_CAP = 512 * 1024;
    static final int TILE = 64, MAX_HEIGHT = 64, MAX_PALETTE = 4096;
    static final int MAX_TILE = 30_000_000 / TILE, MAX_CHUNK = 30_000_000 / 16;
    static final String AIR = "minecraft:air";
    private static final short UNKNOWN = Short.MIN_VALUE;
    private static final long READ_SECONDS = 10;

    private record Anchor(String group, String world, double x, double z) {}

    private final Supplier<EditorApi.Parts> parts;
    private volatile Map<String, NpcDefinition> anchoredFrom;
    private volatile List<Anchor> anchors = List.of();

    EditorTerrainReads(Supplier<EditorApi.Parts> parts) { this.parts = parts; }

    /** The {@code terrain} feature of {@code GET /v1/capabilities}. */
    EditorHost.Feature feature() {
        if (parts.get().host().terrain().isEmpty()) return EditorHost.Feature.no("this platform has no terrain reads");
        if (parts.get().settings().terrainRadius() == 0) return EditorHost.Feature.no("[editor] terrainRadius is 0");
        return EditorHost.Feature.yes();
    }

    CompletionStage<EditorResponse> surface(EditorRequest request, EditorTokens.Token token) throws EditorError {
        Map<String, String> query = query(request, Set.of("world", "tx", "tz"));
        int tx = integer(query, "tx", -MAX_TILE, MAX_TILE), tz = integer(query, "tz", -MAX_TILE, MAX_TILE);
        EditorTerrain source = source();
        String reference = query.get("world");
        EditorTerrain.World world = world(source, reference);
        int per = TILE / 16;
        String[] status = new String[per * per];
        List<CompletableFuture<Optional<EditorTerrain.Surface>>> reads = new ArrayList<>();
        Permission permission = permission(source, world, token);
        for (int i = 0; i < status.length; i++) {
            int chunkX = tx * per + i % per, chunkZ = tz * per + i / per;
            if (!permission.allows(chunkX, chunkZ)) { status[i] = "forbidden"; reads.add(CompletableFuture.completedFuture(Optional.empty())); continue; }
            reads.add(source.surface(world.key(), chunkX, chunkZ).toCompletableFuture());
        }
        return gather(reads).thenApplyAsync((List<Optional<EditorTerrain.Surface>> chunks) -> {
            for (int i = 0; i < status.length; i++) if (status[i] == null) status[i] = chunks.get(i).isPresent() ? "ok" : "unloaded";
            return answer(request, surfaceBody(reference, tx, tz, world, status, chunks));
        });
    }

    CompletionStage<EditorResponse> blocks(EditorRequest request, EditorTokens.Token token) throws EditorError {
        Map<String, String> query = query(request, Set.of("world", "cx", "cz", "y", "height"));
        int cx = integer(query, "cx", -MAX_CHUNK, MAX_CHUNK), cz = integer(query, "cz", -MAX_CHUNK, MAX_CHUNK);
        int y = integer(query, "y", -30_000_000, 30_000_000), height = integer(query, "height", 1, MAX_HEIGHT);
        EditorTerrain source = source();
        String reference = query.get("world");
        EditorTerrain.World world = world(source, reference);
        int from = Math.max(y, world.minY()), to = Math.min(y + height, world.maxY());
        if (from >= to) throw EditorMessages.invalid("y " + y + " to " + (y + height) + " is outside the world's build range " + world.minY() + " to " + world.maxY() + ".");
        CompletableFuture<Optional<EditorTerrain.Blocks>> read = permission(source, world, token).allows(cx, cz)
                ? source.blocks(world.key(), cx, cz, from, to - from).toCompletableFuture() : null;
        if (read == null) return CompletableFuture.completedFuture(answer(request, blocksBody(reference, cx, cz, from, to - from, "forbidden", null)));
        return gather(List.of(read)).thenApplyAsync((List<Optional<EditorTerrain.Blocks>> slice) -> answer(request,
                blocksBody(reference, cx, cz, from, to - from, slice.getFirst().isPresent() ? "ok" : "unloaded", slice.getFirst().map(EditorTerrain.Blocks::states).orElse(null))));
    }

    private EditorTerrain source() throws EditorError {
        return parts.get().host().terrain().orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "This server has no terrain reads."));
    }

    private static EditorTerrain.World world(EditorTerrain source, String reference) throws EditorError {
        if (reference == null || reference.isBlank() || reference.length() > 256) throw EditorMessages.invalid("world: a world name is required.");
        return source.world(reference).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "No loaded world " + reference + "."));
    }

    private static Map<String, String> query(EditorRequest request, Set<String> fields) throws EditorError {
        Map<String, String> query = EditorReplies.query(request);
        JsonObject object = new JsonObject();
        query.forEach(object::addProperty);
        EditorMessages.fields(object, "$query", fields);
        return query;
    }

    private static int integer(Map<String, String> query, String name, int min, int max) throws EditorError {
        String raw = query.get(name);
        if (raw == null) throw EditorMessages.invalid(name + ": required.");
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException notNumber) { /* Refused below. */ }
        throw EditorMessages.invalid(name + ": expected an integer from " + min + " to " + max + ".");
    }

    private static <T> CompletableFuture<List<T>> gather(List<CompletableFuture<T>> reads) {
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).orTimeout(READ_SECONDS, TimeUnit.SECONDS)
                .handle((Void done, Throwable failure) -> {
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                        if (cause instanceof TimeoutException) throw new CompletionException(new EditorError(EditorError.Code.TIMEOUT, "The terrain read did not finish in " + READ_SECONDS + " s."));
                        throw new CompletionException(cause);
                    }
                    return reads.stream().map(CompletableFuture::join).toList();
                });
    }

    /** Which chunks the token may read in one world. */
    private record Permission(double radius, List<Anchor> anchors) {
        boolean allows(int chunkX, int chunkZ) {
            double minX = chunkX * 16.0, minZ = chunkZ * 16.0, maxX = minX + 16, maxZ = minZ + 16;
            for (Anchor anchor : anchors) {
                double dx = Math.max(Math.max(minX - anchor.x(), 0), anchor.x() - maxX), dz = Math.max(Math.max(minZ - anchor.z(), 0), anchor.z() - maxZ);
                if (dx * dx + dz * dz <= radius * radius) return true;
            }
            return false;
        }
    }

    private Permission permission(EditorTerrain source, EditorTerrain.World world, EditorTokens.Token token) {
        int radius = parts.get().settings().terrainRadius();
        if (radius == 0) return new Permission(0, List.of());
        Map<String, Boolean> sameWorld = new HashMap<>();
        List<Anchor> near = new ArrayList<>();
        for (Anchor anchor : anchors()) {
            if (!token.group(anchor.group())) continue;
            if (sameWorld.computeIfAbsent(anchor.world(), (String reference) -> source.world(reference).map((EditorTerrain.World found) -> found.key().equals(world.key())).orElse(false)))
                near.add(anchor);
        }
        return new Permission(radius, near);
    }

    /** The NPC positions, rebuilt only when the repository swapped in new definitions. */
    private List<Anchor> anchors() {
        NpcRepository repository = parts.get().npcs().get();
        if (repository == null) return List.of();
        Map<String, NpcDefinition> current = repository.snapshot();
        if (current == anchoredFrom) return anchors;
        List<Anchor> next = new ArrayList<>(current.size());
        for (NpcDefinition definition : current.values()) {
            JsonObject position = definition.json().getAsJsonObject("position");
            next.add(new Anchor(definition.group(), position.get("world").getAsString(), position.get("x").getAsDouble(), position.get("z").getAsDouble()));
        }
        anchors = List.copyOf(next);
        anchoredFrom = current;
        return anchors;
    }

    private static JsonObject surfaceBody(String reference, int tx, int tz, EditorTerrain.World world, String[] status, List<Optional<EditorTerrain.Surface>> chunks) {
        int per = TILE / 16, cells = TILE * TILE;
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put(AIR, 0);
        ByteBuffer heights = ByteBuffer.allocate(cells * 2).order(ByteOrder.LITTLE_ENDIAN), blocks = ByteBuffer.allocate(cells * 2).order(ByteOrder.LITTLE_ENDIAN);
        byte[] water = new byte[cells];
        for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) {
            int cell = z * TILE + x;
            Optional<EditorTerrain.Surface> chunk = chunks.get((z / 16) * per + x / 16);
            int column = (z % 16) * 16 + x % 16;
            short height = UNKNOWN;
            int block = 0, depth = 0;
            if (chunk.isPresent() && chunk.get().heights()[column] != Integer.MIN_VALUE) {
                height = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, chunk.get().heights()[column]));
                block = palette.computeIfAbsent(chunk.get().blocks()[column], (String id) -> palette.size());
                depth = Math.max(0, Math.min(255, chunk.get().water()[column]));
            }
            heights.putShort(cell * 2, height);
            blocks.putShort(cell * 2, (short) block);
            water[cell] = (byte) depth;
        }
        Hash hash = new Hash().text("surface").text(world.key()).text(tx + "," + tz);
        for (String id : palette.keySet()) hash.text(id);
        for (String chunk : status) hash.text(chunk);
        hash.bytes(heights.array()).bytes(blocks.array()).bytes(water);
        JsonObject body = new JsonObject();
        body.addProperty("world", reference);
        body.addProperty("tx", tx);
        body.addProperty("tz", tz);
        body.addProperty("size", TILE);
        body.addProperty("minY", world.minY());
        body.addProperty("maxY", world.maxY());
        body.addProperty("hash", hash.done());
        body.add("chunks", array(List.of(status)));
        body.add("palette", array(palette.keySet()));
        Base64.Encoder base64 = Base64.getEncoder();
        body.addProperty("heights", base64.encodeToString(heights.array()));
        body.addProperty("blocks", base64.encodeToString(blocks.array()));
        body.addProperty("water", base64.encodeToString(water));
        return body;
    }

    private static JsonObject blocksBody(String reference, int cx, int cz, int y, int height, String status, String[] states) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        int[] indices = new int[states == null ? 0 : states.length];
        if (states == null) palette.put(AIR, 0);
        for (int i = 0; i < indices.length; i++) {
            indices[i] = palette.computeIfAbsent(states[i], (String state) -> palette.size());
            if (palette.size() > MAX_PALETTE) throw new CompletionException(new EditorError(EditorError.Code.TOO_LARGE, "The slice holds more than " + MAX_PALETTE + " block states; ask for fewer layers."));
        }
        int bits = palette.size() <= 1 ? 0 : Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        byte[] data = bits == 0 ? new byte[0] : pack(indices, bits);
        Hash hash = new Hash().text("blocks").text(reference).text(cx + "," + cz + "," + y + "," + height).text(status);
        for (String state : palette.keySet()) hash.text(state);
        hash.bytes(data);
        JsonObject body = new JsonObject();
        body.addProperty("world", reference);
        body.addProperty("cx", cx);
        body.addProperty("cz", cz);
        body.addProperty("y", y);
        body.addProperty("height", height);
        body.addProperty("status", status);
        body.addProperty("hash", hash.done());
        body.add("palette", array(palette.keySet()));
        body.addProperty("bits", bits);
        body.addProperty("data", Base64.getEncoder().encodeToString(data));
        return body;
    }

    /** The body, or 304 when the browser already holds this hash, with the hash as ETag either way. Over the cap is too-large. */
    private static EditorResponse answer(EditorRequest request, JsonObject body) {
        String hash = body.get("hash").getAsString();
        Map<String, String> etag = Map.of("ETag", "\"" + hash + "\"");
        if (holds(request.header("if-none-match"), hash)) return EditorReplies.withHeaders(EditorReplies.empty(304), etag);
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > BODY_CAP)
            throw new CompletionException(new EditorError(EditorError.Code.TOO_LARGE, "The terrain answer is over " + BODY_CAP / 1024 + " KiB; ask for fewer layers."));
        return EditorReplies.withHeaders(EditorResponse.json(200, body), etag);
    }

    private static boolean holds(Optional<String> header, String hash) {
        if (header.isEmpty()) return false;
        for (String tag : header.get().split(",")) {
            String value = tag.strip();
            if (value.startsWith("W/")) value = value.substring(2);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            if (value.equals(hash)) return true;
        }
        return false;
    }

    /** Indices as an LSB-first bitstream, {@code bits} per index, entries running across byte boundaries. */
    static byte[] pack(int[] indices, int bits) {
        byte[] out = new byte[(int) (((long) indices.length * bits + 7) / 8)];
        long bit = 0;
        for (int value : indices) {
            if (value < 0 || value >= 1 << bits) throw new IllegalArgumentException("index " + value + " does not fit " + bits + " bits");
            for (int b = 0; b < bits; b++, bit++) if ((value >>> b & 1) != 0) out[(int) (bit >>> 3)] |= (byte) (1 << (bit & 7));
        }
        return out;
    }

    private static JsonArray array(Iterable<String> values) {
        JsonArray out = new JsonArray();
        for (String value : values) out.add(value);
        return out;
    }

    /** SHA-256 over length-prefixed parts, written {@code sha256:<hex>}. */
    private static final class Hash {
        private final MessageDigest digest;

        Hash() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
        }

        Hash text(String value) { return bytes(value.getBytes(StandardCharsets.UTF_8)); }

        Hash bytes(byte[] value) {
            digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
            digest.update(value);
            return this;
        }

        String done() { return "sha256:" + HexFormat.of().formatHex(digest.digest()); }
    }
}
