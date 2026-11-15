package art.arcane.animus.core.api;

import art.arcane.animus.api.Audience;
import art.arcane.animus.api.ItemSpec;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.SkinSource;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAnimation;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.skin.SkinSigner;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.toolkit.SkinFiles;
import art.arcane.animus.core.toolkit.SkinLookup;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** The runtime half of the public service: everything that changes a live NPC without saving it. */
final class ApiControl<P, I> {
    static final double DEFAULT_SPEED = 2, EYES = 1.62;
    static final int BUBBLE_TICKS = 100, MAXIMUM_TEXT = 1024, BUBBLE_TEXT = 256;
    private final NpcApi<P, I> api;

    ApiControl(NpcApi<P, I> api) { this.api = api; }

    CompletionStage<Outcome<NpcHandle>> teleport(NpcHandle npc, NpcLocation to) {
        Optional<Outcome<NpcHandle>> wrong = checked(to);
        if (wrong.isPresent()) return api.done(wrong.get());
        WorldPosition destination = new WorldPosition(api.parts.ports().world(to.world()).orElseThrow(), new Vec3(to.x(), to.y(), to.z()));
        CompletableFuture<Outcome<NpcHandle>> moved = new CompletableFuture<>();
        api.<Boolean>onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            api.parts.ports().teleport(active.id(), destination, to.yaw(), to.pitch()).whenComplete((Boolean done, Throwable failure) -> moved.complete(failure != null ? NpcApi.refused(failure)
                    : done ? Outcome.ok(npc) : Outcome.rejected(Outcome.Status.UNAVAILABLE, "teleport-blocked",
                    "The destination is not loaded or not clear, the NPC rides, sits or carries someone, or on Folia it is in another world")));
            return Outcome.ok(true);
        }).thenAccept((Outcome<Boolean> started) -> { if (started.status() != Outcome.Status.OK) moved.complete(Outcomes.retype(started)); });
        return api.delivered(moved);
    }

    CompletionStage<Outcome<NpcLocation>> navigate(NpcHandle npc, NpcLocation to, double speed) {
        Optional<Outcome<NpcLocation>> wrong = checked(to);
        if (wrong.isPresent()) return api.done(wrong.get());
        if (speed != 0 && (!Double.isFinite(speed) || speed < 1 || speed > 5)) return api.done(NpcApi.invalid("speed-range", "$.speed", "Speed is 1 to 5 blocks per second, or 0 for 2"));
        String world = api.parts.ports().world(to.world()).orElseThrow();
        WorldPosition destination = new WorldPosition(world, new Vec3(to.x(), to.y(), to.z()));
        double blocksPerSecond = speed == 0 ? DEFAULT_SPEED : speed;
        api.follows.stop(npc.uuid(), "a route replaced the follow");
        CompletableFuture<Outcome<NpcLocation>> arrived = new CompletableFuture<>();
        api.<Boolean>onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            if (!active.state().position().world().equals(world)) return NpcApi.invalid("navigation-world", "$.world", "An NPC walks only within its own world; teleport it first");
            api.parts.living().navigate(active.id(), destination, MovementProfile.Mode.WALK, blocksPerSecond);
            api.parts.living().awaitRoute(active.id()).whenComplete((Void done, Throwable failure) -> arrived.complete(failure != null ? NpcApi.refused(failure)
                    : Outcome.ok(api.parts.living().state(npc.uuid()).map((VirtualNpcState.Snapshot state) -> location(state.position(), to.yaw(), to.pitch())).orElse(to))));
            return Outcome.ok(true);
        }).thenAccept((Outcome<Boolean> started) -> { if (started.status() != Outcome.Status.OK) arrived.complete(new Outcome<>(started.status(), Optional.empty(), started.diagnostics())); });
        return api.delivered(arrived);
    }

    CompletionStage<Outcome<NpcHandle>> stop(NpcHandle npc) {
        api.follows.stop(npc.uuid(), "stopped");
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            api.parts.living().stopNavigation(active.id());
            return Outcome.ok(npc);
        });
    }

    CompletionStage<Outcome<NpcHandle>> lookAt(NpcHandle npc, NpcLocation at) {
        Optional<Outcome<NpcHandle>> wrong = checked(at);
        if (wrong.isPresent()) return api.done(wrong.get());
        String world = api.parts.ports().world(at.world()).orElseThrow();
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            api.parts.living().lookAt(active.id(), new WorldPosition(world, new Vec3(at.x(), at.y(), at.z())));
            return Outcome.ok(npc);
        });
    }

    CompletionStage<Outcome<NpcHandle>> lookAt(NpcHandle npc, UUID entity) {
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            Optional<WorldPosition> target = position(entity);
            if (target.isEmpty()) return Outcome.rejected(Outcome.Status.NOT_FOUND, "entity-missing", "No player, mob or NPC with that UUID is near the NPC");
            api.parts.living().lookAt(active.id(), new WorldPosition(target.get().world(), target.get().point().add(new Vec3(0, EYES, 0))));
            return Outcome.ok(npc);
        });
    }

    CompletionStage<Outcome<Integer>> speak(NpcHandle npc, String text, Audience audience, NpcService.Speech speech) {
        if (text.isBlank() || text.length() > MAXIMUM_TEXT || text.codePoints().anyMatch(Character::isISOControl))
            return api.done(NpcApi.invalid("text", "$.text", "Speech is 1 to " + MAXIMUM_TEXT + " characters without control characters"));
        if (audience.player().isEmpty() && !audience.all() && (!Double.isFinite(audience.radius()) || audience.radius() < 1 || audience.radius() > 128))
            return api.done(NpcApi.invalid("radius", "$.radius", "The radius is 1 to 128 blocks"));
        BehaviorPlatform.Audience heard = new BehaviorPlatform.Audience(audience.player(), audience.player().isPresent() ? 0 : audience.all() ? 128 : audience.radius());
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            if (speech != NpcService.Speech.CHAT) api.parts.living().bubble(active.id(), text.length() > BUBBLE_TEXT ? text.substring(0, BUBBLE_TEXT) : text, BUBBLE_TICKS);
            int reached = speech == NpcService.Speech.BUBBLE ? 0 : api.parts.platform().say(npc, heard, text);
            api.parts.heard().accept(npc, RichText.plain(text));
            return Outcome.ok(reached);
        });
    }

    CompletionStage<Outcome<NpcHandle>> gesture(NpcHandle npc, NpcService.Gesture gesture) {
        NpcAnimation animation = NpcAnimation.valueOf(gesture.name());
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> { api.parts.living().animate(active.id(), animation); return Outcome.ok(npc); });
    }

    CompletionStage<Outcome<NpcHandle>> pose(NpcHandle npc, NpcSpec.Pose pose) {
        NpcPose body = NpcPose.valueOf(pose.name());
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            String type = api.definition(npc.uuid()).map((NpcDefinition definition) -> definition.json().get("type").getAsString()).orElse("");
            if (!body.availableFor(type)) return NpcApi.invalid("pose-body", "$.pose", body.key() + " is available only for " + body.bodies());
            api.parts.living().rest(active.id(), body);
            return Outcome.ok(npc);
        });
    }

    CompletionStage<Outcome<NpcHandle>> playAnimation(NpcHandle npc, String animation) {
        if (animation.isBlank() || animation.length() > 64) return api.done(NpcApi.invalid("animation", "$.animation", "An animation name is 1 to 64 characters"));
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            api.parts.living().playAnimation(active.id(), animation, 1, Optional.of("once"), 0);
            return Outcome.ok(npc);
        });
    }

    CompletionStage<Outcome<NpcHandle>> equip(NpcHandle npc, NpcSpec.Slot slot, Optional<ItemSpec> item) {
        EquipmentSlot target = EquipmentSlot.valueOf(slot.name());
        Optional<NpcItem> parsed = Optional.empty();
        if (item.isPresent()) {
            List<String> errors = new ArrayList<>();
            parsed = NpcItem.parse(NpcSpecs.item(item.get()), "$.item", errors);
            if (!errors.isEmpty()) return api.done(NpcApi.invalid(NpcSpecs.diagnostics(errors)));
        }
        Optional<NpcItem> wanted = parsed;
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            String type = api.definition(npc.uuid()).map((NpcDefinition definition) -> definition.json().get("type").getAsString()).orElse("");
            if (!BodyRules.equipmentSlots(type).contains(target)) return NpcApi.invalid("slot-body", "$.slot", BodyRules.slotRefusal(type, target));
            List<String> errors = new ArrayList<>();
            I stack = wanted.isEmpty() ? api.parts.ports().emptyItem() : api.parts.ports().item(wanted.get(), "$.item", errors);
            if (stack == null) return NpcApi.invalid(NpcSpecs.diagnostics(errors));
            api.parts.living().setEquipment(active.id(), target, stack);
            return Outcome.ok(npc);
        });
    }

    CompletionStage<Outcome<NpcHandle>> skin(String owner, NpcHandle npc, SkinSource source) {
        Optional<NpcDefinition> definition = api.definition(npc.uuid());
        if (definition.isEmpty()) return api.done(NpcApi.missing());
        if (!definition.get().json().get("type").getAsString().equals(BodyRules.PLAYER))
            return api.done(NpcApi.invalid("skin-body", "$.skin", "Skins need a minecraft:player body"));
        if (source instanceof SkinSource.Texture texture && texture.signature().isBlank())
            return api.done(NpcApi.invalid("skin-unsigned", "$.skin", "A live skin needs a signed texture; an NpcSpec gets an unsigned one signed"));
        return Outcomes.then(skinHash(owner, Optional.of(source)), (Optional<String> hash) -> api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            api.parts.living().setAppearance(active.id(), Optional.empty(), hash, false);
            return Outcome.ok(npc);
        }));
    }

    CompletionStage<Outcome<NpcHandle>> seat(NpcHandle npc, UUID player) {
        if (api.definition(npc.uuid()).isEmpty()) return api.done(NpcApi.missing());
        if (!api.parts.living().players().online(player)) return api.done(Outcome.rejected(Outcome.Status.NOT_FOUND, "player-offline", "That player is not online"));
        return api.delivered(api.parts.living().seat(player, npc.uuid()).thenApply((Optional<String> refusal) -> refusal.isEmpty() ? Outcome.ok(npc)
                : Outcome.<NpcHandle>rejected(Outcome.Status.UNAVAILABLE, "seat-refused", refusal.get())));
    }

    CompletionStage<Outcome<Boolean>> unseat(UUID player) {
        Optional<String> name = api.parts.platform().playerName(player);
        if (name.isEmpty()) return api.done(Outcome.rejected(Outcome.Status.NOT_FOUND, "player-unknown", "No player with that UUID is known here"));
        CompletableFuture<Outcome<Boolean>> result = new CompletableFuture<>();
        boolean accepted = api.parts.living().players().console(() -> {
            try { api.deliver(result, Outcome.ok(api.parts.living().stopRider(name.get()))); }
            catch (RuntimeException failure) { api.deliver(result, NpcApi.refused(failure)); }
        });
        if (!accepted) api.deliver(result, Outcome.rejected(Outcome.Status.UNAVAILABLE, "server-refused", "The server refused the work"));
        return result.minimalCompletionStage();
    }

    CompletionStage<Outcome<NpcHandle>> show(String owner, NpcHandle npc, UUID player, boolean shown) {
        if (api.definition(npc.uuid()).isEmpty()) return api.done(NpcApi.missing());
        api.hiding.set(owner, player, npc.uuid(), !shown);
        return api.done(Outcome.ok(npc));
    }

    CompletionStage<Outcome<NpcHandle>> run(NpcHandle npc, Runnable work) {
        return api.onNpc(npc, (NpcRuntime.ActiveNpc active) -> {
            try { work.run(); }
            catch (RuntimeException | LinkageError failure) {
                return Outcome.rejected(Outcome.Status.FAILED, "work-failed", failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            }
            return Outcome.ok(npc);
        });
    }

    // ---- Skins ----

    /** The skin file hash a source resolves to, saved first when it is new; empty when no skin was asked for. */
    CompletionStage<Outcome<Optional<String>>> skinHash(String owner, Optional<SkinSource> source) {
        if (source.isEmpty()) return api.done(Outcome.ok(Optional.empty()));
        return switch (source.get()) {
            case SkinSource.Player player -> api.delivered(SkinLookup.shared().find(player.name()).handle((SkinLookup.Found found, Throwable failure) -> {
                if (failure != null) return Outcome.<BodySkin>rejected(Outcome.Status.UNAVAILABLE, "skin-lookup", "Mojang could not be reached");
                if (found.skin().isPresent()) return Outcome.ok(found.skin().get());
                return Outcome.<BodySkin>rejected(found.unreachable() ? Outcome.Status.UNAVAILABLE : Outcome.Status.NOT_FOUND, "skin-lookup", found.problem());
            })).thenCompose((Outcome<BodySkin> skin) -> saved(skin, player.name(), Optional.of(player.name())));
            case SkinSource.Image image -> {
                SkinSigner signer = api.parts.runtime().skinSigner();
                if (signer == null) yield api.done(Outcome.rejected(Outcome.Status.UNAVAILABLE, "skin-signer", "Skin signing is not running"));
                yield api.delivered(signer.sign(new SkinSigner.Request(new SkinSigner.Address(image.url().strip()), image.slim(), SkinSigner.label(image.url())))
                        .handle((SkinSigner.Signed signed, Throwable failure) -> failure == null ? Outcome.ok(signed.skin())
                                : Outcome.<BodySkin>rejected(Outcome.Status.UNAVAILABLE, "skin-signing", SkinSigner.message(failure))))
                        .thenCompose((Outcome<BodySkin> skin) -> saved(skin, SkinSigner.label(image.url()), Optional.empty()));
            }
            case SkinSource.Texture texture -> {
                BodySkin skin;
                try { skin = new BodySkin(texture.value(), texture.signature().isBlank() ? null : texture.signature()); }
                catch (IllegalArgumentException invalid) { yield api.done(NpcApi.invalid("skin-texture", "$.skin", String.valueOf(invalid.getMessage()))); }
                yield saved(Outcome.ok(skin), label(owner), Optional.empty());
            }
        };
    }

    private CompletionStage<Outcome<Optional<String>>> saved(Outcome<BodySkin> skin, String label, Optional<String> player) {
        if (skin.status() != Outcome.Status.OK) return api.done(new Outcome<>(skin.status(), Optional.empty(), skin.diagnostics()));
        return api.files(() -> {
            try {
                SkinFiles.Skin file = SkinFiles.skin(skin.value().orElseThrow(), label(label), player);
                DocumentStore store = api.parts.runtime().documents();
                Optional<DocumentStore.Change> change = file.file(store);
                if (change.isPresent()) store.commit(List.of(change.get()));
                return Outcome.ok(Optional.of(file.hash()));
            } catch (IOException failure) { return Outcome.rejected(Outcome.Status.FAILED, "skin-save", String.valueOf(failure.getMessage())); }
        });
    }

    /** A skin label MineSkin and the skin files accept: 1 to 64 letters, digits, spaces, {@code _} or {@code -}. */
    private static String label(String text) {
        String cleaned = text.replaceAll("[^A-Za-z0-9 _-]", "").strip();
        if (cleaned.isEmpty()) cleaned = "Plugin skin";
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    // ---- Shared ----

    /** Where a player, mob or NPC stands, on the NPC's thread. */
    Optional<WorldPosition> position(UUID entity) {
        if (api.definition(entity).isPresent())
            return api.parts.living().state(entity).filter(VirtualNpcState.Snapshot::active).map(VirtualNpcState.Snapshot::position);
        return api.parts.ports().entity(entity);
    }

    NpcLocation location(WorldPosition position, float yaw, float pitch) {
        return new NpcLocation(api.parts.ports().worldName(position.world()), position.point().x(), position.point().y(), position.point().z(), yaw, pitch);
    }

    /** INVALID when the location's world is not loaded or a number is out of range. */
    private <T> Optional<Outcome<T>> checked(NpcLocation location) {
        for (double value : new double[] {location.x(), location.y(), location.z()})
            if (!Double.isFinite(value) || Math.abs(value) > 30_000_000) return Optional.of(NpcApi.invalid("coordinate", "$.location", "Coordinates are finite numbers within 30000000"));
        if (!Float.isFinite(location.yaw()) || !Float.isFinite(location.pitch()) || Math.abs(location.pitch()) > 90)
            return Optional.of(NpcApi.invalid("facing", "$.location", "Yaw is a finite number and pitch is -90 to 90"));
        if (api.parts.ports().world(location.world()).isEmpty()) return Optional.of(NpcApi.invalid("world-unknown", "$.location.world", "No loaded world is named " + location.world()));
        return Optional.empty();
    }

}
