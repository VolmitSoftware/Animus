package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.gui.exchange.GlossExchange;
import art.arcane.animus.core.gui.exchange.GlossIds;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code /animus shop}, {@code gui}, {@code build} and {@code editor}: the services NPCs offer and the web editor. */
final class ServiceCommands {
    private ServiceCommands() {}

    static Optional<String> given(String value) { return ContentCommands.given(value); }

    @Director(name = "shop", description = "Open, reset and import NPC shops")
    static final class Shop extends CommandGroup {
        ShopImport imports;

        Shop(CommandServices services) {
            super(services);
            imports = new ShopImport(services);
        }

        @Director(description = "Show the shop catalog and economy")
        void status() { actor().tell(services.shops().status()); }

        @Director(description = "List the loaded shops")
        void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            List<String> rows = services.shops().rows();
            listing(actor(), "Shops (" + rows.size() + ")", "/" + BuildIdentity.ID + " shop list", rows, page, "No shops; save one under shops/");
        }

        @Director(description = "Open a shop for yourself or a player")
        void open(@Param(name = "shop", description = "The shop id") String shop,
                  @Param(name = "player", defaultValue = "", description = "Who sees it; you when left out") String player,
                  @Param(name = "style", defaultValue = "", description = "native or chest; the shop's own style when left out") String style) {
            services.shops().open(actor(), shop, given(player), given(style));
        }

        @Director(description = "Reload shops and chest GUIs from disk")
        void reload() { services.shops().reloadCommand(actor()); }

        @Director(description = "Clear a shop's limits and stock, for everyone or one player")
        void reset(@Param(name = "shop", description = "The shop id") String shop,
                   @Param(name = "player", defaultValue = "", description = "One player's name or UUID; everyone when left out") String player) {
            services.shops().reset(actor(), shop, given(player));
        }

        @Director(description = "List shop and chest GUI problems")
        void problems(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            List<String> rows = services.shops().problemRows();
            listing(actor(), "Shop and GUI problems (" + rows.size() + ")", "/" + BuildIdentity.ID + " shop problems", rows, page, "No shop or GUI problems");
        }
    }

    @Director(name = "import", description = "Import dtlTraders and Shopkeepers shops")
    static final class ShopImport extends CommandGroup {
        ShopImport(CommandServices services) { super(services); }

        @Director(description = "Preview a shop folder from imports/inbox/")
        void preview(@Param(name = "folder", description = "A folder directly under imports/inbox/") String folder,
                     @Param(name = "saves", defaultValue = "", description = "A .yml saves file directly under imports/inbox/") String saves) {
            services.shops().importPreview(actor(), folder, given(saves));
        }

        @Director(description = "Install a shop preview")
        void apply(@Param(name = "preview", description = "The import id preview reported") String preview) {
            services.shops().importApply(actor(), preview);
        }
    }

    @Director(name = "gui", description = "Chest GUIs, display menus, holograms and the Gloss exchange")
    static final class Gui extends CommandGroup {
        GuiImport imports;
        GuiExport exports;

        Gui(CommandServices services) {
            super(services);
            imports = new GuiImport(services);
            exports = new GuiExport(services);
        }

        private HoloService<?, ?> guis() { return services.guis(); }

        @Director(description = "Show the GUI, menu and hologram catalogs")
        void status() { guis().statusCommand(actor()); }

        @Director(description = "List chest GUIs, menus, holograms or animations")
        void list(@Param(name = "kind", defaultValue = "all", description = "all, inventory, menu, hologram or animation") HoloService.Listing kind,
                  @Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            List<String> rows = guis().rows(kind);
            listing(actor(), "GUIs (" + rows.size() + ")", "/" + BuildIdentity.ID + " gui list kind=" + kind.name().toLowerCase(java.util.Locale.ROOT), rows, page, "None loaded");
        }

        @Director(description = "Open a chest GUI for yourself or a player")
        void open(@Param(name = "gui", description = "The chest GUI id") String gui,
                  @Param(name = "player", defaultValue = "", description = "Who sees it; you when left out") String player) {
            guis().open(actor(), gui, given(player));
        }

        @Director(description = "Open a display menu for yourself or a player")
        void menu(@Param(name = "menu", description = "The menu id") String menu,
                  @Param(name = "player", defaultValue = "", description = "Who sees it; you when left out") String player) {
            guis().menu(actor(), menu, given(player));
        }

        @Director(description = "Close the display menu you or a player has open")
        void close(@Param(name = "player", defaultValue = "", description = "Whose menu; yours when left out") String player) {
            guis().closeMenu(actor(), given(player));
        }

        @Director(description = "Reload GUIs, shops, menus, holograms and animations")
        void reload() { guis().reloadAll(actor()); }

        @Director(description = "List GUI, menu and hologram problems")
        void problems(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            List<String> rows = guis().problemRows();
            listing(actor(), "GUI problems (" + rows.size() + ")", "/" + BuildIdentity.ID + " gui problems", rows, page, "No GUI problems");
        }

        /** {@code kind} with {@code id}: empty kind is all, and an id needs a kind. */
        static Optional<art.arcane.animus.core.gui.GuiKind> kind(HoloService.Listing kind) {
            return GlossExchange.kind(kind.name());
        }
    }

    @Director(name = "import", description = "Bring Gloss documents into Animus")
    static final class GuiImport extends CommandGroup {
        GuiImport(CommandServices services) { super(services); }

        @Director(description = "Copy Gloss's live documents into Animus")
        void gloss(@Param(name = "kind", description = "all, inventory, menu, hologram or animation") HoloService.Listing kind,
                   @Param(name = "id", defaultValue = "all", description = "One document id of that kind, or all") String id,
                   @Param(name = "namespace", defaultValue = GlossIds.DEFAULT_NAMESPACE, description = "The Animus namespace to write into") String namespace,
                   @Param(name = "replace", defaultValue = "false", description = "Overwrite documents that exist") boolean replace) {
            CommandActor actor = actor();
            services.guis().exchange().importGloss(actor, Gui.kind(kind), id.equalsIgnoreCase("all") ? Optional.empty() : Optional.of(id), namespace, replace,
                    () -> services.guis().reloadAll(actor));
        }

        @Director(description = "Import a Gloss folder or .json bundle from imports/inbox/")
        void file(@Param(name = "name", description = "A folder or .json bundle directly under imports/inbox/") String name,
                  @Param(name = "namespace", defaultValue = GlossIds.DEFAULT_NAMESPACE, description = "The Animus namespace to write into") String namespace,
                  @Param(name = "replace", defaultValue = "false", description = "Overwrite documents that exist") boolean replace) {
            CommandActor actor = actor();
            services.guis().exchange().importFile(actor, name, namespace, replace, () -> services.guis().reloadAll(actor));
        }
    }

    @Director(name = "export", description = "Send Animus GUIs to Gloss or a file")
    static final class GuiExport extends CommandGroup {
        GuiExport(CommandServices services) { super(services); }

        @Director(description = "Write Animus documents into Gloss's live folders")
        void gloss(@Param(name = "kind", description = "all, inventory, menu, hologram or animation") HoloService.Listing kind,
                   @Param(name = "id", defaultValue = "all", description = "One document id of that kind, or all") String id,
                   @Param(name = "replace", defaultValue = "false", description = "Overwrite Gloss documents that exist") boolean replace) {
            services.guis().exchange().exportGloss(actor(), Gui.kind(kind), id.equalsIgnoreCase("all") ? Optional.empty() : Optional.of(id), replace);
        }

        @Director(description = "Write a folder or .json bundle under exports/gui/")
        void file(@Param(name = "name", description = "The folder or .json bundle name") String name,
                  @Param(name = "format", defaultValue = "", description = "folder or bundle; follows the name when left out") String format) {
            services.guis().exchange().exportFile(actor(), name, given(format));
        }
    }

    @Director(name = "build", description = "NPC builders: schematics, progress and teams")
    static final class Build extends CommandGroup {
        Team team;

        Build(CommandServices services) {
            super(services);
            team = new Team(services);
        }

        private BuildCommands builds() { return services.builds(); }

        @Director(description = "Show the builder, or one build by its id or NPC")
        void status(@Param(name = "build", defaultValue = "", description = "A build id or its NPC") String build) { builds().status(actor(), given(build)); }

        @Director(description = "List running and paused builds")
        void list() { builds().list(actor()); }

        @Director(description = "List the schematics in the schematics folder")
        void schematics() { builds().schematics(actor()); }

        @Director(description = "Read a schematic's size and blocks")
        void inspect(@Param(name = "schematic", description = "The schematic file name") String schematic) { builds().inspect(actor(), schematic); }

        @Director(description = "Start an NPC building its saved build or a schematic")
        void start(@Param(name = "npc", description = "The builder NPC id, or @selected") String npc,
                   @Param(name = "schematic", defaultValue = "", description = "The schematic; the NPC's saved build when left out") String schematic,
                   @Param(name = "at", defaultValue = "", description = "Origin block x,y,z; the NPC's position when left out") String at,
                   @Param(name = "anchor", defaultValue = "", description = "center, corner or schematic") String anchor,
                   @Param(name = "rotation", defaultValue = "", description = "0, 90, 180 or 270") String rotation,
                   @Param(name = "mirror", defaultValue = "", description = "none, x or z") String mirror,
                   @Param(name = "offset", defaultValue = "", description = "x,y,z added to the origin") String offset,
                   @Param(name = "yoffset", defaultValue = "", description = "Y added to the origin") String yoffset,
                   @Param(name = "pattern", defaultValue = "", description = "The order blocks are placed in") String pattern,
                   @Param(name = "layers", defaultValue = "", description = "Layers placed per pass, 1..256") String layers,
                   @Param(name = "groupall", defaultValue = "", description = "Place every block of a kind together") String groupall,
                   @Param(name = "ignoreair", defaultValue = "", description = "Skip the schematic's air") String ignoreair,
                   @Param(name = "ignoreliquids", defaultValue = "", description = "Skip the schematic's liquids") String ignoreliquids,
                   @Param(name = "excavate", defaultValue = "", description = "Clear the site first") String excavate,
                   @Param(name = "speed", defaultValue = "", description = "Walking speed, 1..5 blocks per second") String speed,
                   @Param(name = "interval", defaultValue = "", description = "Ticks between placements, 1..200") String interval,
                   @Param(name = "reach", defaultValue = "", description = "Placing reach, 2..6 blocks") String reach,
                   @Param(name = "timeout", defaultValue = "", description = "Seconds to reach a spot, 1..300") String timeout,
                   @Param(name = "hold", defaultValue = "", description = "Show the block being placed in hand") String hold,
                   @Param(name = "silent", defaultValue = "", description = "Place without sounds") String silent,
                   @Param(name = "materials", defaultValue = "", description = "creative, donate or container") String materials,
                   @Param(name = "container", defaultValue = "", description = "The x,y,z chest materials=container draws from") String container,
                   @Param(name = "scaffolding", defaultValue = "", description = "Build scaffolding to reach high blocks") String scaffolding) {
            CommandActor actor = actor();
            Map<String, String> options = new LinkedHashMap<>();
            String[] words = {"anchor", "rotation", "mirror", "offset", "yoffset", "pattern", "layers", "groupall", "ignoreair", "ignoreliquids", "excavate",
                    "speed", "interval", "reach", "timeout", "hold", "silent", "materials", "container", "scaffolding"};
            String[] values = {anchor, rotation, mirror, offset, yoffset, pattern, layers, groupall, ignoreair, ignoreliquids, excavate,
                    speed, interval, reach, timeout, hold, silent, materials, container, scaffolding};
            for (int index = 0; index < words.length; index++) if (!values[index].isBlank()) options.put(words[index], values[index]);
            npc(actor, npc).ifPresent((String id) -> builds().start(actor, id, given(schematic), given(at), options));
        }

        @Director(description = "Pause a build")
        void pause(@Param(name = "build", description = "A build id or its NPC") String build) { builds().pause(actor(), build); }

        @Director(description = "Resume a paused build")
        void resume(@Param(name = "build", description = "A build id or its NPC") String build) { builds().resume(actor(), build); }

        @Director(description = "Cancel a build; placed blocks stay")
        void cancel(@Param(name = "build", description = "A build id or its NPC") String build) { builds().cancel(actor(), build); }

        @Director(description = "Remove the blocks a build placed")
        void undo(@Param(name = "build", description = "A build id or its NPC") String build) { builds().undo(actor(), build); }

        @Director(description = "List what a build still needs")
        void survey(@Param(name = "build", description = "A build id or its NPC") String build) { builds().survey(actor(), build); }

        @Director(description = "Give your held items to a build that takes donations", origin = DirectorOrigin.PLAYER)
        @AnimusPermission(BuildCommands.DONATE_PERMISSION)
        void donate(@Param(name = "build", description = "A build id or its NPC") String build) { builds().donate(actor(), build); }
    }

    @Director(name = "team", description = "Add or remove helper NPCs on a build")
    static final class Team extends CommandGroup {
        Team(CommandServices services) { super(services); }

        @Director(description = "Add a helper NPC to a build")
        void add(@Param(name = "build", description = "A build id or its NPC") String build,
                 @Param(name = "npc", description = "The helper NPC id") String npc) { services.builds().team(actor(), build, true, npc); }

        @Director(description = "Remove a helper NPC from a build")
        void remove(@Param(name = "build", description = "A build id or its NPC") String build,
                    @Param(name = "npc", description = "The helper NPC id") String npc) { services.builds().team(actor(), build, false, npc); }
    }

    @Director(name = "editor", description = "Pair and manage the web editor")
    static final class Editor extends CommandGroup {
        Editor(CommandServices services) { super(services); }

        @Director(description = "Show the editor listener, relay and connections")
        void status() { services.editor().status(actor()); }

        @Director(description = "Make a single-use pairing link")
        void pair(@Param(name = "label", defaultValue = "", description = "A name for the token, such as laptop") String label,
                  @Param(name = "scopes", defaultValue = "", description = "Comma-separated scopes; the pairing defaults when left out") String scopes,
                  @Param(name = "groups", defaultValue = "", description = "Comma-separated NPC groups the token may edit") String groups,
                  @Param(name = "expires", defaultValue = "", description = "Lifetime such as 7d, 12h or 30m") String expires) {
            services.editor().pair(actor(), label, scopes, groups, expires);
        }

        @Director(description = "List editor tokens")
        void tokens(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
            CommandActor actor = actor();
            services.editor().tokens(actor).ifPresent((List<String> rows) ->
                    listing(actor, "Editor tokens (" + rows.size() + ")", "/" + BuildIdentity.ID + " editor tokens", rows, page, "No editor tokens."));
        }

        @Director(description = "Revoke a token, or all of them")
        void revoke(@Param(name = "token", description = "The token id, or all") String token) { services.editor().revoke(actor(), token); }
    }
}
