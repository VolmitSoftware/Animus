package art.arcane.animus.core.command;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.editor.EditorScope;
import art.arcane.animus.core.extension.ModuleHooks;
import art.arcane.animus.core.gui.exchange.GlossIds;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Tab completion values by parameter name for the whole {@code /animus} tree. Each parameter name means one thing
 * everywhere, so one switch serves every command: {@code group} is a trait group, {@code folder} an NPC folder,
 * {@code source} an entry of {@code imports/inbox/}. Numbers offer typical values; coordinates offer where the player
 * stands. Called on the actor's owner; a source that cannot answer offers nothing.
 */
final class CommandCompletions {
    private static final int LIMIT = 100;
    /** Common entity types offered for {@code type}; any {@code namespace:type} is accepted. */
    static final List<String> TYPES = List.of("minecraft:player", "minecraft:villager", "minecraft:wandering_trader", "minecraft:zombie",
            "minecraft:skeleton", "minecraft:iron_golem", "minecraft:armor_stand", "minecraft:horse", "minecraft:wolf", "minecraft:cat");
    /** The bodies the packet preview tools can show. */
    static final List<String> PROOF_BODIES = List.of("minecraft:player", "minecraft:armor_stand", "minecraft:horse", "minecraft:turtle", "minecraft:frog",
            "minecraft:llama", "minecraft:armadillo", "minecraft:ender_dragon");

    private final CommandGroup tree;

    CommandCompletions(CommandGroup tree) { this.tree = tree; }

    Collection<String> values(String parameter, CommandActor actor) {
        CommandServices services = tree.services;
        return switch (parameter) {
            case "npc" -> npcs(actor);
            case "ids" -> npcs(actor);
            case "id" -> List.of(freeId());
            case "build" -> Stream.concat(services.builds().buildIds().stream(), npcs(actor).stream()).distinct().limit(LIMIT).toList();
            case "behavior" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.behaviorIds(); }
            case "state" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.stateBehaviors(); }
            case "execution" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.executionIds(); }
            case "group" -> tree.runtime().npcs().groups().groups().keySet().stream().limit(LIMIT).toList();
            case "folder", "folders" -> folders();
            case "tag" -> tree.runtime().npcs().all().stream().flatMap((NpcDefinition definition) -> definition.tags().stream()).distinct().sorted().limit(LIMIT).toList();
            case "attribute" -> NpcCommands.ids();
            case "value" -> List.of(NpcCommands.RESET);
            case "shop" -> services.shops().shopIds();
            case "gui" -> services.guis().guiIds();
            case "menu" -> services.guis().menuIds();
            case "document" -> Stream.concat(Stream.of("all"), Stream.concat(services.guis().guiIds().stream(), services.guis().menuIds().stream())).distinct().toList();
            case "namespace" -> List.of(GlossIds.DEFAULT_NAMESPACE);
            case "schematic" -> services.builds().schematicNames();
            case "animation" -> tree.runtime().rigs().catalog().animations().keySet().stream().limit(LIMIT).toList();
            case "capability" -> services.extensions() == null ? List.of() : services.extensions().capabilities().stream()
                    .map((Capabilities.Availability capability) -> capability.descriptor().key().toString()).limit(LIMIT).toList();
            case "converter" -> tree.runtime().moduleHooks().converters().stream().map((ModuleHooks.Converter converter) -> converter.descriptor().key().toString()).limit(LIMIT).toList();
            case "input", "patch" -> List.of("{}");
            case "token" -> services.editor().revocable();
            case "scopes" -> Arrays.stream(EditorScope.values()).map(EditorScope::wireName).toList();
            case "expires" -> List.of("30m", "12h", "1d", "7d", "30d");
            case "label" -> List.of("laptop", "desktop");
            case "player" -> services.players().get();
            case "viewer" -> services.debug().viewers();
            case "uuid" -> Stream.concat(actor.player().stream(), actor.nearby().stream()).map(UUID::toString).distinct().toList();
            case "target" -> nearby(actor, false);
            case "mob", "charge" -> nearby(actor, true);
            case "enabled" -> List.of("on", "off", DiagnosticCommands.TOGGLE);
            case "style" -> List.of("native", "chest");
            case "recording" -> services.recordings().ids();
            case "type" -> TYPES;
            case "body" -> PROOF_BODIES;
            case "world" -> worlds(actor);
            case "worlds" -> worlds(actor).stream().map((String world) -> world + ":" + world).toList();
            case "x", "y", "z" -> coordinate(actor, parameter);
            case "at", "container" -> blocks(actor);
            case "offset" -> List.of("0,0,0");
            case "yoffset", "dx", "dy", "dz" -> List.of("0", "1", "-1");
            case "speed" -> List.of("1", "2", "3", "5");
            case "damage" -> List.of("1", "2", "5");
            case "layer" -> List.of("0", "1", "2");
            case "fade" -> List.of("0", "5", "10", "20");
            case "layers" -> List.of("1", "4", "16");
            case "interval" -> List.of("1", "5", "20");
            case "reach" -> List.of("2", "4", "6");
            case "timeout" -> List.of("30", "60", "120");
            case "page" -> List.of("1", "2", "3");
            case "anchor" -> List.of("center", "corner", "schematic");
            case "rotation" -> List.of("0", "90", "180", "270");
            case "mirror" -> List.of("none", "x", "z");
            case "pattern" -> List.of("spiral", "reversespiral", "linear", "reverselinear");
            case "materials" -> List.of("creative", "donate", "container");
            case "groupall", "ignoreair", "ignoreliquids", "excavate", "hold", "silent", "scaffolding" -> List.of("true", "false");
            case "format" -> List.of("folder", "bundle");
            case "copy" -> List.of("copy");
            case "entity", "vehicle", "riders" -> services.proof().bodies(actor).stream().map(String::valueOf).toList();
            case "file" -> CompletionFiles.inboxFiles(data());
            case "source" -> CompletionFiles.inbox(data());
            case "saves" -> CompletionFiles.inboxFiles(data()).stream().filter((String name) -> name.endsWith(".yml")).toList();
            case "preview" -> CompletionFiles.previews(data());
            case "application" -> CompletionFiles.applications(data());
            case "transaction" -> CompletionFiles.transactions(data());
            case "skin" -> CompletionFiles.skins(data());
            case "categories" -> { List<String> categories = new ArrayList<>(CitizensDropIn.CATEGORIES); categories.add("all"); categories.add(CitizensDropIn.COMMANDS); yield categories; }
            default -> List.of();
        };
    }

    /** NPC ids, and {@code @selected} when the player has a selection. */
    private List<String> npcs(CommandActor actor) {
        List<String> ids = new ArrayList<>(tree.runtime().npcs().all().stream().map(NpcDefinition::id).limit(LIMIT).toList());
        if (actor.player().isPresent() && tree.living().selection(actor.player().get()).isPresent()) ids.add(CommandGroup.SELECTED);
        return ids;
    }

    /** {@code npc1}, {@code npc2} and so on: the first id no NPC uses. */
    private String freeId() {
        for (int index = tree.runtime().npcs().all().size() + 1; ; index++) {
            String id = "npc" + index;
            if (tree.runtime().npcs().find(id).isEmpty()) return id;
        }
    }

    private List<String> folders() {
        return tree.runtime().npcs().all().stream().map(NpcDefinition::group).filter((String folder) -> !folder.isEmpty()).distinct().sorted().limit(LIMIT).toList();
    }

    /** The player's world first, then every world an NPC stands in. */
    private List<String> worlds(CommandActor actor) {
        TreeSet<String> worlds = new TreeSet<>();
        actor.position().ifPresent((WorldPosition position) -> worlds.add(position.world()));
        for (NpcDefinition definition : tree.runtime().npcs().all()) {
            JsonObject position = definition.json().getAsJsonObject("position");
            if (position != null && position.has("world")) worlds.add(position.get("world").getAsString());
            if (worlds.size() >= LIMIT) break;
        }
        return List.copyOf(worlds);
    }

    /** {@code ~}, then the looked-at block's coordinate on that axis, or the player's own block; nothing for the console. */
    private static List<String> coordinate(CommandActor actor, String axis) {
        Optional<Vec3> block = actor.target().or(() -> actor.position().map(WorldPosition::point));
        if (block.isEmpty()) return List.of();
        Vec3 point = block.get();
        double value = switch (axis) { case "x" -> point.x(); case "y" -> point.y(); default -> point.z(); };
        return List.of("~", String.valueOf((long) Math.floor(value)));
    }

    /** {@code ~,~,~}, then the looked-at block, or the player's own block; nothing for the console. */
    private static List<String> blocks(CommandActor actor) {
        return actor.target().or(() -> actor.position().map(WorldPosition::point)).map((Vec3 point) -> List.of("~,~,~", block(point))).orElse(List.of());
    }

    static String block(Vec3 point) { return (long) Math.floor(point.x()) + "," + (long) Math.floor(point.y()) + "," + (long) Math.floor(point.z()); }

    /** Nearby entity UUIDs, nearest first; {@code stop} first for commands it ends. */
    private static List<String> nearby(CommandActor actor, boolean stop) {
        List<String> values = new ArrayList<>();
        if (stop) values.add(WorldCommands.STOP);
        actor.nearby().forEach((UUID id) -> values.add(id.toString()));
        return values;
    }

    /** The Animus data folder; completion reads the folders under it. */
    private Path data() {
        try { return tree.runtime().documents().resolve("imports").getParent(); }
        catch (IOException unresolved) { throw new UncheckedIOException(unresolved); }
    }
}
