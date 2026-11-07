package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Finds the signed skin of a Minecraft account by name. The answer completes off the server thread; callers hand it
 * back to the player's owner.
 */
@FunctionalInterface interface SkinLookup {
    String NAME = "[A-Za-z0-9_]{1,16}";

    /** What a lookup found: the account's own spelling and its skin, or why there is none. */
    record Found(Optional<String> name, Optional<BodySkin> skin, String problem) {
        static Found skin(String name, BodySkin skin) { return new Found(Optional.of(name), Optional.of(skin), ""); }
        static Found missing(String problem) { return new Found(Optional.empty(), Optional.empty(), problem); }
    }

    CompletableFuture<Found> find(String name);

    /** Mojang's public profile API: the name to an account id, then the id to its signed textures. */
    static SkinLookup mojang() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        return (String name) -> {
            if (!name.matches(NAME)) return CompletableFuture.completedFuture(Found.missing(name + " is not a Minecraft name (1-16 letters, digits or _)"));
            return get(http, "https://api.mojang.com/users/profiles/minecraft/" + name).thenCompose((Optional<JsonObject> account) -> {
                if (account.isEmpty() || !account.get().has("id")) return CompletableFuture.completedFuture(Found.missing("No Minecraft account is named " + name));
                String id = account.get().get("id").getAsString(), spelled = account.get().has("name") ? account.get().get("name").getAsString() : name;
                return get(http, "https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false")
                        .thenApply((Optional<JsonObject> profile) -> profile.map((JsonObject found) -> textures(spelled, found)).orElse(Found.missing(spelled + " has no profile on Mojang's session server")));
            }).exceptionally((Throwable failure) -> {
                Throwable cause = failure instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure;
                return Found.missing("Could not reach Mojang to look up " + name + " (" + cause.getClass().getSimpleName() + "); the server needs internet access");
            });
        };
    }

    private static CompletableFuture<Optional<JsonObject>> get(HttpClient http, String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply((HttpResponse<String> response) -> {
            if (response.statusCode() == 204 || response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new CompletionException(new IOException("HTTP " + response.statusCode()));
            try { return Optional.of(JsonDocuments.object(response.body())); }
            catch (IOException unreadable) { throw new CompletionException(unreadable); }
        });
    }

    private static Found textures(String name, JsonObject profile) {
        JsonElement properties = profile.get("properties");
        if (properties != null && properties.isJsonArray()) for (JsonElement property : properties.getAsJsonArray()) {
            if (!property.isJsonObject()) continue;
            JsonObject entry = property.getAsJsonObject();
            if (!entry.has("name") || !"textures".equals(entry.get("name").getAsString()) || !entry.has("value")) continue;
            try { return Found.skin(name, new BodySkin(entry.get("value").getAsString(), entry.has("signature") ? entry.get("signature").getAsString() : null)); }
            catch (IllegalArgumentException invalid) { return Found.missing(name + "'s skin from Mojang is unreadable"); }
        }
        return Found.missing(name + " has no skin on Mojang's session server");
    }
}
