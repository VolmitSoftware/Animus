package art.arcane.animus.core.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** MineSkin's v2 queue as the plugin speaks it, against canned answers: no network. */
class MineSkinTest {
    static final String VALUE = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\"}}}".getBytes(StandardCharsets.UTF_8));
    static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

    final List<HttpRequest> sent = new ArrayList<>();
    final Deque<MineSkin.Reply> replies = new ArrayDeque<>();
    final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    String key = "";

    @AfterEach void stop() { timers.shutdownNow(); }

    MineSkin client() {
        return new MineSkin((HttpRequest request) -> {
            sent.add(request);
            MineSkin.Reply reply = replies.poll();
            return reply == null ? CompletableFuture.failedFuture(new java.io.IOException("no answer")) : CompletableFuture.completedFuture(reply);
        }, () -> key, "Animus/test", timers, System::currentTimeMillis);
    }

    /** A 64x64 PNG header: enough for the size check, which reads only the IHDR. */
    static byte[] png(int width, int height) {
        ByteBuffer bytes = ByteBuffer.allocate(64);
        bytes.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'}).putInt(width).putInt(height);
        return bytes.array();
    }

    static String completed(String variant) {
        return "{\"success\":true,\"job\":{\"id\":\"6740f0\",\"status\":\"completed\",\"result\":\"u1\"},\"skin\":{\"uuid\":\"u1\",\"variant\":\"" + variant + "\","
                + "\"texture\":{\"data\":{\"value\":\"" + VALUE + "\",\"signature\":\"" + SIGNATURE + "\"},\"url\":{\"skin\":\"https://textures.minecraft.net/texture/abc\"}}},"
                + "\"rateLimit\":{\"next\":{\"relative\":0}}}";
    }

    static String body(HttpRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { byte[] chunk = new byte[item.remaining()]; item.get(chunk); out.writeBytes(chunk); }
            @Override public void onError(Throwable throwable) { done.completeExceptionally(throwable); }
            @Override public void onComplete() { done.complete(null); }
        });
        done.join();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test void sign_uploadQueuedThenPolled_returnsTheSignedTexture() throws Exception {
        replies.add(new MineSkin.Reply(202, "{\"success\":true,\"job\":{\"id\":\"6740f0aa\",\"status\":\"waiting\"},\"rateLimit\":{\"next\":{\"relative\":0}}}", -1));
        replies.add(new MineSkin.Reply(200, "{\"success\":true,\"job\":{\"id\":\"6740f0aa\",\"status\":\"active\"}}", -1));
        replies.add(new MineSkin.Reply(200, completed("slim"), -1));
        SkinSigner.Signed signed = client().sign(new SkinSigner.Request(new SkinSigner.Image(png(64, 64)), true, "Town guard!")).get(20, TimeUnit.SECONDS);
        assertEquals(VALUE, signed.skin().textureValue());
        assertEquals(SIGNATURE, signed.skin().signature());
        assertEquals("https://textures.minecraft.net/texture/abc", signed.url());
        assertTrue(signed.slim());
        assertEquals(List.of("POST /v2/queue", "GET /v2/queue/6740f0aa", "GET /v2/queue/6740f0aa"), sent.stream().map((HttpRequest request) -> request.method() + " " + request.uri().getPath()).toList());
        HttpRequest upload = sent.getFirst();
        assertTrue(upload.headers().firstValue("Content-Type").orElseThrow().startsWith("multipart/form-data; boundary="));
        assertEquals("Animus/test", upload.headers().firstValue("User-Agent").orElseThrow());
        assertFalse(upload.headers().firstValue("Authorization").isPresent(), "no key, no Authorization header");
        String form = body(upload);
        assertTrue(form.contains("name=\"variant\"\r\n\r\nslim\r\n"), form);
        assertTrue(form.contains("name=\"visibility\"\r\n\r\nunlisted\r\n"), form);
        assertTrue(form.contains("name=\"name\"\r\n\r\nTown guard\r\n"), "a name MineSkin takes: " + form);
        assertTrue(form.contains("name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png"), form);
    }

    @Test void sign_addressWithKey_sendsJsonAndTheBearerKey() throws Exception {
        key = "msk_test_secret";
        replies.add(new MineSkin.Reply(200, completed("classic"), -1));
        SkinSigner.Signed signed = client().sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/guard.png"), false, "")).get(5, TimeUnit.SECONDS);
        assertFalse(signed.slim());
        HttpRequest request = sent.getFirst();
        assertEquals("Bearer msk_test_secret", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("{\"url\":\"https://example.com/guard.png\",\"variant\":\"classic\",\"visibility\":\"unlisted\"}", body(request).replaceAll("\\s", ""), "an empty name is left out");
    }

    @Test void sign_rateLimited_waitsAndTriesAgain() throws Exception {
        replies.add(new MineSkin.Reply(429, "{\"success\":false,\"errors\":[{\"code\":\"rate_limit\",\"message\":\"slow down\"}]}", 0));
        replies.add(new MineSkin.Reply(200, completed("classic"), -1));
        client().sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/a.png"), false, "")).get(5, TimeUnit.SECONDS);
        assertEquals(2, sent.size());
    }

    @Test void sign_failedJob_isRefusedWithMineSkinsReason() {
        replies.add(new MineSkin.Reply(202, "{\"success\":true,\"job\":{\"id\":\"j1\",\"status\":\"waiting\"}}", -1));
        replies.add(new MineSkin.Reply(200, "{\"success\":true,\"job\":{\"id\":\"j1\",\"status\":\"failed\"},\"errors\":[{\"code\":\"invalid_image\",\"message\":\"Invalid image dimensions\"}]}", -1));
        String message = refusal(client().sign(new SkinSigner.Request(new SkinSigner.Image(png(64, 64)), false, "")));
        assertEquals("MineSkin could not read the skin image: Invalid image dimensions", message);
    }

    @Test void sign_refusedWithoutKey_namesTheConfigKey() {
        replies.add(new MineSkin.Reply(401, "{\"success\":false,\"errors\":[{\"code\":\"unauthorized\",\"message\":\"no\"}]}", -1));
        String message = refusal(client().sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/a.png"), false, "")));
        assertTrue(message.contains("[skins] mineskinKey in config.toml"), message);
    }

    @Test void sign_badKey_saysSo() {
        key = "msk_wrong";
        replies.add(new MineSkin.Reply(403, "{\"success\":false,\"errors\":[{\"code\":\"invalid_api_key\",\"message\":\"Invalid API key\"}]}", -1));
        String message = refusal(client().sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/a.png"), false, "")));
        assertEquals("MineSkin refused the API key in [skins] mineskinKey; check it in config.toml", message);
    }

    @Test void sign_notAPngOrWrongSize_isRefusedBeforeAnyRequest() {
        assertEquals("Not a PNG file", refusal(client().sign(new SkinSigner.Request(new SkinSigner.Image(new byte[40]), false, ""))));
        assertEquals("The PNG is 128x128; a skin is 64x64 (or the old 64x32)", refusal(client().sign(new SkinSigner.Request(new SkinSigner.Image(png(128, 128)), false, ""))));
        assertTrue(refusal(client().sign(new SkinSigner.Request(new SkinSigner.Address("ftp://example.com/a.png"), false, ""))).contains("http or https"));
        assertTrue(sent.isEmpty());
    }

    @Test void sign_unreachable_saysTheServerNeedsInternet() {
        String message = refusal(client().sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/a.png"), false, "")));
        assertEquals("Could not reach MineSkin; the server needs internet access", message);
    }

    @Test void sign_oneAtATime_secondWaitsForTheFirst() throws Exception {
        CompletableFuture<MineSkin.Reply> first = new CompletableFuture<>();
        List<HttpRequest> order = new ArrayList<>();
        MineSkin client = new MineSkin((HttpRequest request) -> {
            order.add(request);
            return order.size() == 1 ? first : CompletableFuture.completedFuture(new MineSkin.Reply(200, completed("classic"), -1));
        }, () -> "", "Animus/test", timers, System::currentTimeMillis);
        CompletableFuture<SkinSigner.Signed> a = client.sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/a.png"), false, ""));
        CompletableFuture<SkinSigner.Signed> b = client.sign(new SkinSigner.Request(new SkinSigner.Address("https://example.com/b.png"), false, ""));
        assertEquals(1, order.size(), "the second request waits");
        first.complete(new MineSkin.Reply(200, completed("classic"), -1));
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertEquals(2, order.size());
    }

    static String refusal(CompletableFuture<SkinSigner.Signed> signing) {
        try { signing.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failed) {
            assertInstanceOf(SkinSigner.Refused.class, failed.getCause());
            return failed.getCause().getMessage();
        } catch (Exception other) { throw new AssertionError(other); }
        throw new AssertionError("signed");
    }

    @Test void message_ofAWrappedRefusal_isItsOwnLine() {
        assertEquals("nope", SkinSigner.message(new CompletionException(new SkinSigner.Refused("nope"))));
        assertEquals("MineSkin could not sign the skin; try again later", SkinSigner.message(new IllegalStateException("x")));
    }

    @Test void skinId_readsMineSkinLinksAndIds() {
        assertEquals(java.util.Optional.of("6ca0b9ed49174a3398469ebbbbf7c6b6"), MineSkin.skinId("https://mineskin.org/skins/6ca0b9ed49174a3398469ebbbbf7c6b6"));
        assertEquals(java.util.Optional.of("6ca0b9ed49174a3398469ebbbbf7c6b6"), MineSkin.skinId(" https://www.mineskin.org/skins/6ca0b9ed-4917-4a33-9846-9ebbbbf7c6b6/?tab=1 "));
        assertEquals(java.util.Optional.of("e51d0e34"), MineSkin.skinId("https://minesk.in/e51d0e34"));
        assertEquals(java.util.Optional.of("e51d0e34"), MineSkin.skinId("mineskin.org/e51d0e34"));
        assertEquals(java.util.Optional.of("e51d0e34"), MineSkin.skinId("e51d0e34"));
        assertEquals(java.util.Optional.of("6ca0b9ed49174a3398469ebbbbf7c6b6"), MineSkin.skinId("6CA0B9ED-4917-4A33-9846-9EBBBBF7C6B6"));
        assertTrue(MineSkin.skinId("https://example.com/skins/e51d0e34").isEmpty(), "another site");
        assertTrue(MineSkin.skinId("https://mineskin.org/").isEmpty());
        assertTrue(MineSkin.skinId("Notch!").isEmpty());
        assertTrue(MineSkin.skinId("").isEmpty());
        assertTrue(MineSkin.isLink("https://minesk.in/e51d0e34"));
        assertFalse(MineSkin.isLink("https://example.com/guard.png"));
    }

    @Test void find_readsAnExistingSkinByItsId() throws Exception {
        replies.add(new MineSkin.Reply(200, "{\"success\":true,\"skin\":{\"uuid\":\"6ca0b9ed49174a3398469ebbbbf7c6b6\",\"shortId\":\"e51d0e34\",\"variant\":\"slim\","
                + "\"texture\":{\"data\":{\"value\":\"" + VALUE + "\",\"signature\":\"" + SIGNATURE + "\"},\"url\":{\"skin\":\"https://textures.minecraft.net/texture/abc\"}}}}", -1));
        SkinSigner.Signed found = client().find("e51d0e34").get(5, TimeUnit.SECONDS);
        assertEquals(VALUE, found.skin().textureValue());
        assertEquals(SIGNATURE, found.skin().signature());
        assertTrue(found.slim());
        assertEquals("https://api.mineskin.org/v2/skins/e51d0e34", sent.getFirst().uri().toString());
        assertEquals("GET", sent.getFirst().method());
    }

    @Test void sign_aMineSkinLink_readsThatSkinInsteadOfSigningAPage() throws Exception {
        replies.add(new MineSkin.Reply(200, "{\"success\":true,\"skin\":{\"uuid\":\"6ca0b9ed49174a3398469ebbbbf7c6b6\",\"variant\":\"classic\","
                + "\"texture\":{\"data\":{\"value\":\"" + VALUE + "\",\"signature\":\"" + SIGNATURE + "\"},\"url\":{\"skin\":\"https://textures.minecraft.net/texture/abc\"}}}}", -1));
        SkinSigner.Signed found = client().sign(new SkinSigner.Request(new SkinSigner.Address("https://mineskin.org/skins/6ca0b9ed49174a3398469ebbbbf7c6b6"), true, "")).get(5, TimeUnit.SECONDS);
        assertEquals(VALUE, found.skin().textureValue());
        assertFalse(found.slim(), "the skin keeps its own arm model");
        assertEquals("GET", sent.getFirst().method());
        assertEquals("MineSkin e51d0e34", SkinSigner.label("https://minesk.in/e51d0e34"));
        assertEquals("MineSkin 6ca0b9ed", SkinSigner.label("https://mineskin.org/skins/6ca0b9ed49174a3398469ebbbbf7c6b6"));
    }

    @Test void find_unknownSkin_saysSoInOneLine() {
        replies.add(new MineSkin.Reply(404, "{\"success\":false,\"errors\":[{\"code\":\"skin_not_found\",\"message\":\"Skin not found\"}]}", -1));
        assertEquals("MineSkin has no skin e51d0e34", refusal(client().find("e51d0e34")));
        assertEquals("Not a MineSkin link or id", refusal(client().find("../admin")));
    }

    @Test void find_isRefusedWhereSigningIsOff() {
        assertEquals("Signing is off", refusal(SkinSigner.off("Signing is off").find("e51d0e34")));
    }

    @Test void label_fromFileOrAddress_isTheLastPartWithoutPng() {
        assertEquals("guard", SkinSigner.label("town/guard.png"));
        assertEquals("guard", SkinSigner.label("https://example.com/skins/guard.png?x=1"));
        assertEquals("Signed skin", SkinSigner.label("https://example.com/"));
    }
}
