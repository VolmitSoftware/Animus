package art.arcane.animus.core.command;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.editor.EditorScope;
import art.arcane.animus.core.extension.ModuleHooks;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.gui.exchange.GlossIds;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.owner.NpcOwnership;
import art.arcane.animus.core.toolkit.ToolkitMode;
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
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Tab completion values by parameter name for the whole {@code /animus} tree. Each parameter name means one thing
 * everywhere, so one switch serves every command: {@code group} is a trait group, {@code folder} an NPC folder; the few
 * a single command narrows, such as the {@code source} of {@code import shops preview}, go through {@link #kind}. NPC
 * ids are only those the actor may act on. Numbers offer typical values; coordinates offer where the player
 * stands. Lists are whole: the director cuts them only after the typed prefix, so ids late in the alphabet still
 * complete. Called on the actor's owner; a source that cannot answer offers nothing.
 */
final class CommandCompletions {
    /** Real entities an NPC commonly rides, offered after the NPC ids; any entity type may be typed. */
    static final List<String> VEHICLES = List.of("minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:camel", "minecraft:pig", "minecraft:strider",
            "minecraft:llama", "minecraft:oak_boat", "minecraft:minecart", "minecraft:happy_ghast");
    /** The bodies the packet preview tools can show. */
    static final List<String> PROOF_BODIES = List.of("minecraft:player", "minecraft:armor_stand", "minecraft:horse", "minecraft:turtle", "minecraft:frog",
            "minecraft:llama", "minecraft:armadillo", "minecraft:ender_dragon");

    private final AnimusCommandTree tree;

    CommandCompletions(AnimusCommandTree tree) { this.tree = tree; }

    /**
     * Values for {@code parameter} of {@code command}, the words after the root such as {@code gui create}: what that
     * argument accepts there, which for most parameters is what the name means everywhere.
     */
    Collection<String> values(String command, String parameter, CommandActor actor, Map<String, String> given) {
        return values(kind(command, parameter), actor, given);
    }

    /**
     * The values a command's parameter takes, where its name means something narrower than everywhere else: a GUI or
     * hologram id is {@code namespace:path}, a module condition is a condition, a shop or Gloss source is the inbox entry
     * that import reads, and a state delete's UUID is a player's.
     */
    static String kind(String command, String parameter) {
        return switch (command + " " + parameter) {
            case "gui create id", "hologram create id" -> GUI_ID;
            case "modules action capability" -> ACTION;
            case "modules condition capability" -> CONDITION;
            case "import shops preview source" -> SHOP_SOURCE;
            case "import gloss file source" -> GLOSS_SOURCE;
            case "import preview file" -> IMPORT_FILE;
            case "behaviors state delete player uuid", "behaviors state delete relationship uuid" -> PLAYER_UUID;
            default -> parameter;
        };
    }

    static final String GUI_ID = " gui-id", ACTION = " action", CONDITION = " condition", SHOP_SOURCE = " shop-source", GLOSS_SOURCE = " gloss-source",
            IMPORT_FILE = " import-file", PLAYER_UUID = " player-uuid";

    /** Values for {@code parameter}, or for a {@link #kind} of one; {@code given} holds what was typed already, by parameter name. */
    Collection<String> values(String parameter, CommandActor actor, Map<String, String> given) {
        CommandServices services = tree.services;
        return switch (parameter) {
            case GUI_ID -> namespaces(Stream.concat(services.guis().guiIds().stream(), services.guis().menuIds().stream()).toList());
            case ACTION -> capabilities(services.extensions() == null ? List.of() : services.extensions().capabilities(), Capabilities.Kind.ACTION);
            case CONDITION -> capabilities(services.extensions() == null ? List.of() : services.extensions().capabilities(), Capabilities.Kind.CONDITION);
            case SHOP_SOURCE -> CompletionFiles.inboxFolders(data());
            case GLOSS_SOURCE -> CompletionFiles.glossSources(data());
            case IMPORT_FILE -> CompletionFiles.inboxFiles(data());
            case PLAYER_UUID -> Stream.concat(actor.player().stream(), actor.nearbyPlayers().stream()).map(UUID::toString).distinct().toList();
            case "npc" -> npcs(actor);
            // Blocks for mount set (1-32) and speak (1-64).
            case "range" -> List.of("4", "8", "16", "32");
            case "ids" -> npcs(actor);
            case "id" -> List.of(freeId());
            case "build" -> Stream.concat(services.builds().buildIds().stream(), npcs(actor).stream()).distinct().toList();
            case "behavior" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.behaviorIds(); }
            case "state" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.stateBehaviors(); }
            case "execution" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.executionIds(); }
            case "group" -> List.copyOf(tree.runtime().npcs().groups().groups().keySet());
            case "folder", "folders" -> folders();
            case "tag" -> tree.runtime().npcs().all().stream().flatMap((NpcDefinition definition) -> definition.tags().stream()).distinct().sorted().toList();
            case "attribute" -> NpcCommands.ids();
            case "setting" -> HealthSetting.NAMES;
            case "part" -> ModelSetting.NAMES;
            case "to" -> ModelSetting.values(given.getOrDefault("part", ""));
            case "value" -> given.containsKey("setting") ? healthValues(given.get("setting")) : List.of(NpcCommands.RESET);
            case "disguise" -> List.of(NpcCommands.RESET);
            case "shop" -> shops(actor);
            case "quest" -> services.quests().questIds();
            case "stage" -> List.of("1", "2", "3");
            case "objective" -> services.quests().customObjectives(given.getOrDefault("quest", ""));
            case "amount" -> List.of("1", "5", "10");
            case "gui" -> services.guis().guiIds();
            case "menu" -> services.guis().menuIds();
            case "document" -> Stream.concat(Stream.of("all"), Stream.concat(services.guis().guiIds().stream(), services.guis().menuIds().stream())).distinct().toList();
            case "namespace" -> List.of(GlossIds.DEFAULT_NAMESPACE);
            case "schematic" -> services.builds().schematicNames();
            case "animation" -> List.copyOf(tree.runtime().rigs().catalog().animations().keySet());
            case "capability" -> services.extensions() == null ? List.of() : services.extensions().capabilities().stream()
                    .map((Capabilities.Availability capability) -> capability.descriptor().key().toString()).toList();
            case "module" -> services.extensions() == null ? List.of() : services.extensions().modules().stream()
                    .map((ModuleHost.ModuleStatus module) -> module.manifest().id()).toList();
            case "converter" -> tree.runtime().moduleHooks().converters().stream().map((ModuleHooks.Converter converter) -> converter.descriptor().key().toString()).toList();
            case "input", "patch" -> List.of("{}");
            case "token" -> services.editor().revocable();
            case "scopes" -> Arrays.stream(EditorScope.values()).map(EditorScope::wireName).toList();
            case "expires" -> List.of("30m", "12h", "1d", "7d", "30d");
            case "label" -> List.of("laptop", "desktop");
            case "player" -> services.players().get();
            case "mythic-mob" -> tree.runtime().templates().names(art.arcane.animus.core.integration.NpcTemplates.MYTHIC);
            case "viewer" -> services.debug().viewers();
            // Only /animus speak types text before its target, and it speaks to players, never NPCs.
            case "target" -> given.containsKey("text") ? speakers(actor) : targets(actor, false);
            case "bubble" -> List.of("40", "60", "100");
            case "charge" -> targets(actor, true);
            case "mob" -> nearby(actor);
            case "enabled" -> List.of("on", "off", DiagnosticCommands.TOGGLE);
            case "confirm" -> List.of("confirm");
            case "action" -> List.of("list", "add", "remove");
            case "style" -> List.of("native", "chest");
            case "mode" -> List.of("buy", "sell", "trade");
            case ToolkitCommands.MENU_MODE -> Arrays.stream(ToolkitMode.values()).map(ToolkitMode::word).toList();
            case ToolkitCommands.STEP -> ToolkitCommands.STEPS;
            case "price" -> List.of("1", "10", "100");
            case "rows" -> List.of("1", "2", "3", "4", "5", "6");
            case "recording" -> services.recordings().ids();
            case "type" -> tree.runtime().bodyTypes().stream().sorted().toList();
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
            case "file" -> CompletionFiles.skinImages(data());
            case "follow", "from-name" -> List.of("true", "false");
            case "source" -> CompletionFiles.inbox(data());
            case "saves" -> CompletionFiles.inboxFiles(data()).stream().filter((String name) -> name.endsWith(".yml")).toList();
            case "preview" -> CompletionFiles.previews(data());
            case "application" -> CompletionFiles.applications(data());
            case "transaction" -> CompletionFiles.transactions(data());
            case "change" -> actor.player().map(tree.changes()::ids).orElse(List.of());
            case "skin" -> CompletionFiles.skins(data());
            case "time" -> List.of("day", "noon", "night", "midnight", "1000", "13000");
            case "chance" -> List.of("100", "50", "25");
            case "stay" -> List.of("60", "60-120", "300");
            case "wander" -> List.of("0", "4", "8");
            case "pose" -> List.of("standing", "sneaking", "sleeping", "sitting");
            case "turn" -> List.of("on", "off");
            case "ride" -> { List<String> vehicles = new java.util.ArrayList<>(npcs(actor)); vehicles.addAll(VEHICLES); yield vehicles; }
            case "location" -> ScheduleCommands.references(tree.runtime().npcs().find(given.getOrDefault("npc", "")).map(NpcDefinition::json).orElse(new JsonObject()));
            default -> List.of();
        };
    }

    /**
     * Shop ids for staff with {@code animus.shops}; a player who only runs their own shops gets those, by the names
     * {@code /animus shop manage} takes.
     */
    private List<String> shops(CommandActor actor) {
        if (actor.player().isEmpty() || actor.permitted(PermissionNodes.SHOPS)) return tree.services.shops().shopIds();
        return tree.services.shops().players().names(actor.player().get());
    }

    /** The ids of the NPCs the actor may act on, as {@code /animus list} shows them, and {@code @selected} when the player has one NPC selected. */
    private List<String> npcs(CommandActor actor) {
        return ids(owned(tree.runtime().npcs().all(), actor), actor.player().map((UUID player) -> tree.selection(player).size() == 1).orElse(false));
    }

    /** The NPCs the actor may act on: every one for the console, admins and {@code animus.npc.others}, else the player's own. */
    static List<NpcDefinition> owned(Collection<NpcDefinition> all, CommandActor actor) {
        return all.stream().filter((NpcDefinition definition) -> NpcOwnership.mayAct(actor.player(), actor::permitted, definition)).toList();
    }

    /** {@code namespace:} for each namespace the ids use, and the Gloss default, for a new id to start from. */
    static List<String> namespaces(Collection<String> ids) {
        TreeSet<String> namespaces = new TreeSet<>();
        namespaces.add(GlossIds.DEFAULT_NAMESPACE);
        for (String id : ids) { int colon = id.indexOf(':'); if (colon > 0) namespaces.add(id.substring(0, colon)); }
        return namespaces.stream().map((String namespace) -> namespace + ":").toList();
    }

    /** The keys of the capabilities of one kind that can run here. */
    static List<String> capabilities(Collection<Capabilities.Availability> all, Capabilities.Kind kind) {
        return all.stream().filter((Capabilities.Availability capability) -> capability.available() && capability.descriptor().kind() == kind)
                .map((Capabilities.Availability capability) -> capability.descriptor().key().toString()).toList();
    }

    /** Every id, then {@code @selected} when it names one NPC. */
    static List<String> ids(Collection<NpcDefinition> all, boolean selected) {
        List<String> ids = new ArrayList<>(all.stream().map(NpcDefinition::id).toList());
        if (selected) ids.add(CommandGroup.SELECTED);
        return ids;
    }

    /** The values a health setting takes, then {@code reset}; only {@code reset} for a setting not known. */
    static List<String> healthValues(String setting) {
        List<String> values = new ArrayList<>(HealthSetting.values(setting));
        values.add(NpcCommands.RESET);
        return values;
    }

    /** Players by name, NPC ids and nearby entities, for a combat target; {@code stop} first where it ends the command. */
    private List<String> targets(CommandActor actor, boolean stop) {
        List<String> players = List.copyOf(tree.services.players().get());
        List<String> npcs = tree.runtime().npcs().all().stream().map(NpcDefinition::id).toList();
        return WorldCommands.Targets.offered(stop, actor.player().isPresent(), players, npcs, actor.nearby());
    }

    /** The players a line can be spoken to: {@code @me} for a player, then the online names. */
    private List<String> speakers(CommandActor actor) {
        return WorldCommands.Targets.offered(false, actor.player().isPresent(), List.copyOf(tree.services.players().get()), List.of(), List.of());
    }

    /** {@code npc1}, {@code npc2} and so on: the first id no NPC uses. */
    private String freeId() {
        for (int index = tree.runtime().npcs().all().size() + 1; ; index++) {
            String id = "npc" + index;
            if (tree.runtime().npcs().find(id).isEmpty()) return id;
        }
    }

    private List<String> folders() {
        return tree.runtime().npcs().all().stream().map(NpcDefinition::group).filter((String folder) -> !folder.isEmpty()).distinct().sorted().toList();
    }

    /** The player's world first, then every world an NPC stands in. */
    private List<String> worlds(CommandActor actor) {
        TreeSet<String> worlds = new TreeSet<>();
        actor.position().ifPresent((WorldPosition position) -> worlds.add(position.world()));
        for (NpcDefinition definition : tree.runtime().npcs().all()) {
            JsonObject position = definition.json().getAsJsonObject("position");
            if (position != null && position.has("world")) worlds.add(position.get("world").getAsString());
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

    /** {@code stop}, then nearby entity UUIDs, nearest first: a mob has no name to give. */
    private static List<String> nearby(CommandActor actor) { return WorldCommands.Targets.offered(true, false, List.of(), List.of(), actor.nearby()); }

    /** The Animus data folder; completion reads the folders under it. */
    private Path data() {
        try { return tree.runtime().documents().resolve("imports").getParent(); }
        catch (IOException unresolved) { throw new UncheckedIOException(unresolved); }
    }
}
