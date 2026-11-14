package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every menu screen the harness can open, in both modes, held to the screen rules of the plan: Help in the last slot,
 * Back at the start of the last row (Close on Home), Save and Discard at their navigation slots, hints worded only as
 * Click, Right-click and Sneak-click, none of the replaced words, nothing destructive under the pointer where a window
 * opens (slots 13, 22, 31 and 34), and no Simple tooltip longer than four lines, its name included. Every Delete asks with the one confirm
 * before it does anything.
 */
class ScreenRulesTest extends ToolkitHarness {
    /** A lore line that reads as a click hint. */
    private static final Pattern HINT = Pattern.compile("(?i)^(click|left|right|shift|sneak|middle|double)[^:]{0,24}:");
    private static final Pattern HINT_WORDING = Pattern.compile("^(Click|Right-click|Sneak-click): \\S.*");
    /**
     * Words the menus no longer use, durations in ticks instead of seconds, and confirm wordings other than the one confirm.
     * "Per tick" is a rate, and {@code groups/} the folder a schema description names.
     */
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)\\b(dormant|palette|toolkit|rollback|tx)\\b|(?<!per )\\bticks?\\b|same click|again to (delete|change|apply)"
            + "|(?<!trait |permission )\\bgroups?\\b(?!/)|npcs/|\\bshift\\b");
    /** Raw keys and ids: the dark grey last lines of Advanced rows, which name the file's own key. */
    private static final String RAW = "\u00a78";
    private static final Pattern UNDER_POINTER = Pattern.compile("^(Delete|Close|Discard|Remove|Clear).*");
    /** Where the pointer rests when a window opens: the centre column, and slot 34, under an anvil's result slot when a prompt hands back to a chest. */
    private static final List<Integer> POINTER = List.of(13, 22, 31, 34);
    /** Lore lines a Simple tooltip may show under its name, hints included: four lines in all. */
    private static final int SIMPLE_LINES = 3;

    private record Visit(String name, boolean advancedOnly, Runnable open) {}

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ",\"tags\":[\"vip\"],\"groups\":[\"guards\"],\"behaviors\":[\"town:talk\"],\"hologram\":{\"lines\":[\"Welcome\"]},"
                        + "\"modules\":{\"animus:navigation\":{\"enabled\":true,\"route\":\"market\"}}"),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", BAKER, "").replace("minecraft:player", ShopFiles.VILLAGER)),
                file("groups/guards.json", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"guards\",\"description\":\"Gate watch\",\"values\":{\"tags\":[\"armed\"]}}"),
                file("behaviors/town/talk.json", BehaviorScreensTest.fixture("talk")), file("behaviors/town/patrol.json", BehaviorScreensTest.PATROL),
                file("shops/town/bakery.json", ToolkitShopScreensTest.BAKERY),
                file("gui/inventories/town/board.json", "{\"schemaVersion\":1,\"title\":\"Notice board\"}"), file("gui/menus/town/main.json", "{\"schemaVersion\":1}"),
                file("gui/holograms/town/sign.json", "{\"schemaVersion\":1,\"lines\":[\"Town board\"]}"),
                file("gui/text-animations/town/blink.json", "{\"schemaVersion\":1,\"frames\":[\"a\",\"b\"]}"),
                file("routes/market.json", ToolkitRoutesTest.MARKET), file("recordings/wave.json", ToolkitRoutesTest.recording("wave")),
                file("skins/" + SkinScreenTest.SPARE + ".json", SkinScreenTest.SPARE_TEXT)), true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
    }

    @Test void everyScreenFollowsTheScreenRulesInBothModes() {
        List<String> broken = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Economy paying = economy;
        // A server without an economy plugin adds a line to the price buttons; the rules hold there too.
        for (Economy money : List.of(paying, Economy.none("No economy plugin"))) {
            economy = money;
            for (ToolkitMode mode : ToolkitMode.values()) {
                for (Visit visit : visits()) {
                    if (visit.advancedOnly() && mode == ToolkitMode.SIMPLE) continue;
                    toolkit.mode(alex.id, mode);
                    toolkit.captures().quit(alex.id);
                    toolkit.selectOnly(alex.id, "guard");
                    String where = mode.label() + (money == paying ? "" : " without economy") + " " + visit.name();
                    try {
                        visit.open().run();
                        drain();
                    } catch (AssertionError | RuntimeException failed) {
                        String reason = String.valueOf(failed.getMessage());
                        broken.add(where + ": could not open: " + reason.substring(0, Math.min(80, reason.length())) + " on " + title() + " " + buttons().values());
                        continue;
                    }
                    seen.add(where + " = " + title());
                    check(where, mode, broken);
                    confirms(where, mode, broken);
                }
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken) + "\n\nopened:\n" + String.join("\n", seen));
    }

    /**
     * Every Help line of every screen links to a page of the docs and a heading on it, as Wiki.js makes heading anchors:
     * lowercase, punctuation dropped, spaces as dashes. Runs where the docs repository sits beside this one.
     */
    @Test void everyHelpLinkNamesAPageAndAHeadingTheDocsHave() throws IOException {
        Optional<Path> docs = docs();
        Assumptions.assumeTrue(docs.isPresent(), "the docs repository is not beside this checkout");
        Pattern link = Pattern.compile(Pattern.quote(ChatLine.DOCS) + "([0-9a-z-]+)(?:#([0-9a-z-]+))?");
        Map<String, Set<String>> anchors = new TreeMap<>();
        Set<String> links = new TreeSet<>();
        for (ToolkitMode mode : ToolkitMode.values()) {
            for (Visit visit : visits()) {
                if (visit.advancedOnly() && mode == ToolkitMode.SIMPLE) continue;
                toolkit.mode(alex.id, mode);
                toolkit.captures().quit(alex.id);
                toolkit.selectOnly(alex.id, "guard");
                visit.open().run();
                drain();
                if (alex.frame == null) continue;
                click(alex.frame.rows() * ToolScreen.WIDTH - 1, Click.LEFT);
                Matcher found = link.matcher(alex.messages.getLast());
                while (found.find()) links.add(found.group(1) + "#" + (found.group(2) == null ? "" : found.group(2)));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String each : links) {
            String page = each.substring(0, each.indexOf('#')), anchor = each.substring(each.indexOf('#') + 1);
            Path file = docs.get().resolve(page + ".md");
            if (!Files.isRegularFile(file)) { missing.add(each + ": no page " + file.getFileName()); continue; }
            Set<String> headings = anchors.computeIfAbsent(page, (String ignored) -> headings(file));
            if (!anchor.isEmpty() && !headings.contains(anchor)) missing.add(each + ": no heading; the page has " + headings);
        }
        assertTrue(links.size() > 20, "the walk found the help links: " + links);
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    /** {@code docs/animus} in the nearest folder above this one that holds it. */
    private static Optional<Path> docs() {
        for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
            Path found = at.resolve("docs").resolve("animus");
            if (Files.isRegularFile(found.resolve("21-in-game-toolkit.md"))) return Optional.of(found);
        }
        return Optional.empty();
    }

    /** The Wiki.js anchors of a page's headings, outside code blocks. */
    private static Set<String> headings(Path file) {
        Set<String> found = new TreeSet<>();
        boolean code = false;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("```")) code = !code;
                if (code || !line.startsWith("#")) continue;
                found.add(line.replaceFirst("^#+\\s*", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s-]", "").strip().replaceAll("\\s+", "-"));
            }
        } catch (IOException unread) { throw new java.io.UncheckedIOException(unread); }
        return found;
    }

    /** The screens, each opened the way a player reaches it. */
    private List<Visit> visits() {
        List<Visit> visits = new ArrayList<>();
        visits.add(new Visit("Home", false, () -> toolkit.home(alex.id)));
        visits.add(new Visit("NPC", false, () -> toolkit.open(alex.id)));
        visits.add(new Visit("NPCs", false, () -> toolkit.open(alex.id, NpcsScreen::new)));
        visits.add(new Visit("By folder", true, () -> { toolkit.open(alex.id, NpcsScreen::new); go("By folder"); }));
        visits.add(new Visit("Movement", false, () -> toolkit.open(alex.id, MovementScreen::new)));
        visits.add(new Visit("Mounts", false, () -> toolkit.open(alex.id, MountScreen::new)));
        visits.add(new Visit("Riding", false, () -> { toolkit.open(alex.id, MountScreen::new); go("Riding"); }));
        visits.add(new Visit("Routes", false, () -> toolkit.open(alex.id, RouteScreen::new)));
        visits.add(new Visit("Route", false, () -> { toolkit.open(alex.id, RouteScreen::new); go(">Market"); }));
        visits.add(new Visit("Points", false, () -> {
            toolkit.captures().start(alex, "walk", List.of("guard"));
            toolkit.captures().point(alex);
            stand(4, 0, false);
            toolkit.captures().point(alex);
            toolkit.open(alex.id, PointsScreen::new);
        }));
        visits.add(new Visit("Recordings", false, () -> toolkit.open(alex.id, RecordingScreen::new)));
        visits.add(new Visit("Recording", false, () -> { toolkit.open(alex.id, RecordingScreen::new); go(">Wave"); }));
        visits.add(new Visit("Talk", false, () -> { toolkit.open(alex.id); go("Talk"); }));
        visits.add(new Visit("Conversation step", false, () -> { toolkit.open(alex.id); go("Talk", ">Line 1"); }));
        visits.add(new Visit("Behaviors", false, () -> toolkit.open(alex.id, BehaviorScreen::new)));
        visits.add(new Visit("Behavior", false, () -> { toolkit.open(alex.id, BehaviorScreen::new); go(">town:talk"); }));
        visits.add(new Visit("Runs", false, () -> { toolkit.open(alex.id, BehaviorScreen::new); go(">town:talk", "Cancel a run"); }));
        visits.add(new Visit("Shops", false, () -> toolkit.open(alex.id, ShopBindScreen::new)));
        visits.add(new Visit("Shop", false, () -> { toolkit.open(alex.id, ShopBindScreen::new); go(">Bakery"); }));
        visits.add(new Visit("Offers", false, () -> { toolkit.open(alex.id, ShopBindScreen::new); go(">Bakery", "Offers"); }));
        visits.add(new Visit("GUIs", false, () -> toolkit.open(alex.id, GuiBindScreen::new)));
        visits.add(new Visit("GUI", false, () -> { toolkit.open(alex.id, GuiBindScreen::new); go(">town:board"); }));
        visits.add(new Visit("Combat", false, () -> toolkit.open(alex.id, CombatScreen::new)));
        visits.add(new Visit("Combat settings", false, () -> toolkit.open(alex.id,
                (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new SettingsScreen<>(kit, viewer, lent, CombatFields.combat("melee")))));
        visits.add(new Visit("Weapons", false, () -> {
            toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new SettingsScreen<>(kit, viewer, lent, CombatFields.combat("melee")));
            go(">Weapon damage");
        }));
        visits.add(new Visit("Target rules", false, () -> toolkit.open(alex.id,
                (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new TargetRulesScreen<>(kit, viewer, lent, "targets"))));
        visits.add(new Visit("Rule entries", false, () -> {
            toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new TargetRulesScreen<>(kit, viewer, lent, "targets"));
            go(">Mob types");
        }));
        visits.add(new Visit("Equipment", false, () -> toolkit.open(alex.id, EquipmentScreen::new)));
        visits.add(new Visit("Skins", false, () -> toolkit.open(alex.id, SkinScreen::new)));
        visits.add(new Visit("Skin", false, () -> { toolkit.open(alex.id, SkinScreen::new); go(">Spare"); }));
        visits.add(new Visit("Body", false, () -> toolkit.open(alex.id, BodyScreen::new)));
        visits.add(new Visit("All bodies", false, () -> { toolkit.open(alex.id, BodyScreen::new); go("All bodies"); }));
        visits.add(new Visit("Hologram", false, () -> toolkit.open(alex.id, HologramScreen::new)));
        visits.add(new Visit("Hologram line", false, () -> { toolkit.open(alex.id, HologramScreen::new); go(">Line 1"); }));
        visits.add(new Visit("Saved holograms", false, () -> toolkit.open(alex.id, SavedHologramScreen::new)));
        visits.add(new Visit("Saved hologram", false, () -> { toolkit.open(alex.id, SavedHologramScreen::new); go(">town:sign"); }));
        visits.add(new Visit("New NPC body", false, this::newNpc));
        visits.add(new Visit("New NPC skin", false, () -> { newNpc(); go("Player"); }));
        visits.add(new Visit("All fields", true, this::fields));
        visits.add(new Visit("Field section", true, () -> { fields(); go("Identity"); }));
        visits.add(new Visit("List", true, () -> { fields(); go("Identity", "Tags"); }));
        visits.add(new Visit("List entry", true, () -> { fields(); go("Identity", "Tags", ">#1 vip"); }));
        visits.add(new Visit("Choice", true, () -> { fields(); go("Body settings", "Team color"); }));
        visits.add(new Visit("Map", true, () -> { fields(); go("Body settings", "Poses"); }));
        visits.add(new Visit("Attributes", true, () -> { fields(); go("Attributes"); }));
        visits.add(new Visit("Modules", true, () -> { fields(); go("Modules"); }));
        visits.add(new Visit("Module", true, () -> { fields(); go("Modules", "Combat"); }));
        visits.add(new Visit("Folders", false, () -> toolkit.open(alex.id, FolderScreen::new)));
        visits.add(new Visit("Folder", false, () -> { toolkit.open(alex.id, FolderScreen::new); go(">town"); }));
        visits.add(new Visit("Trait groups", false, () -> toolkit.open(alex.id, GroupScreen::new)));
        visits.add(new Visit("Trait group", false, () -> { toolkit.open(alex.id, GroupScreen::new); go(">guards"); }));
        visits.add(new Visit("Members", false, () -> { toolkit.open(alex.id, GroupScreen::new); go(">guards", "Members"); }));
        visits.add(new Visit("Member", false, () -> { toolkit.open(alex.id, GroupScreen::new); go(">guards", "Members", ">guard"); }));
        visits.add(new Visit("Values", false, () -> { toolkit.open(alex.id, GroupScreen::new); go(">guards", "Values"); }));
        return visits;
    }

    private void newNpc() {
        toolkit.home(alex.id);
        drain();
        go("New NPC");
        MockScreenHost.answer(alex, Optional.of("Greeter Bob"));
        drain();
        if (toolkit.advanced(alex.id)) MockScreenHost.answer(alex, Optional.of("greeter-bob"));
        drain();
    }

    private void fields() {
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new PropertyScreen<>(kit, viewer, lent, List.of("guard")));
    }

    /** Clicks items by name in order: the exact name if one has it, else the first containing it; {@code >} right-clicks. */
    private void go(String... steps) {
        drain();
        for (String step : steps) {
            boolean right = step.startsWith(">");
            String name = right ? step.substring(1) : step;
            click(slot(name), right ? Click.RIGHT : Click.LEFT);
            drain();
        }
    }

    private int slot(String name) {
        for (Map.Entry<Integer, ScreenFrame.Item> item : alex.frame.items().entrySet())
            if (item.getValue().name().map(RichText::plain).orElse("").equals(name)) return item.getKey();
        return MockScreenHost.slot(alex, name);
    }

    /** Clicks each Delete once: it must turn into the one confirm, or refuse, within four lines in Simple; another click then cancels it. */
    private void confirms(String where, ToolkitMode mode, List<String> broken) {
        for (int slot : new java.util.TreeMap<>(buttons()).keySet()) {
            if (!"Delete".equals(name(slot))) continue;
            click(slot, Click.LEFT);
            drain();
            boolean refused = toolkit.notice(alex.id).isPresent();
            if (!refused && !(ToolScreen.CONFIRM.equals(name(slot)) && lore(slot).endsWith("Any other click cancels")))
                broken.add(where + ": Delete on " + slot + " did not ask: " + name(slot) + " / " + lore(slot).replace('\n', ' '));
            if (!refused && mode == ToolkitMode.SIMPLE && alex.frame.items().get(slot).lore().size() > SIMPLE_LINES)
                broken.add(where + ": the Delete confirm runs to " + alex.frame.items().get(slot).lore().size() + " lines: " + lore(slot).replace('\n', ' '));
            int filler = alex.frame.items().entrySet().stream().filter((Map.Entry<Integer, ScreenFrame.Item> item) -> item.getValue().item().id().equals(ToolScreen.FILLER))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            click(filler, Click.LEFT);
            drain();
            if (!"Delete".equals(name(slot))) broken.add(where + ": another click left " + name(slot) + " on " + slot);
        }
    }

    private void check(String where, ToolkitMode mode, List<String> broken) {
        ScreenFrame frame = alex.frame;
        int last = frame.rows() * ToolScreen.WIDTH - 1, back = last - (ToolScreen.HELP - ToolScreen.BACK);
        String home = "Animus".equals(title()) ? "Close" : "Back";
        if (!"Help".equals(name(last))) broken.add(where + ": no Help at " + last + " but " + name(last));
        if (!home.equals(name(back))) broken.add(where + ": no " + home + " at " + back + " but " + name(back));
        for (Map.Entry<Integer, ScreenFrame.Item> entry : frame.items().entrySet()) {
            int slot = entry.getKey();
            String name = entry.getValue().name().map(RichText::plain).orElse("");
            if (entry.getValue().item().id().equals(ToolScreen.FILLER)) continue;
            if (POINTER.contains(slot) && UNDER_POINTER.matcher(name).matches()) broken.add(where + ": " + name + " on " + slot);
            if ((name.equals("Save") || name.equals("Saved")) && slot != last - 1) broken.add(where + ": " + name + " on " + slot + ", not " + (last - 1));
            if (name.equals("Discard") && slot != last - 2) broken.add(where + ": Discard on " + slot + ", not " + (last - 2));
            if (FORBIDDEN.matcher(name).find()) broken.add(where + ": item named " + name);
            for (String line : entry.getValue().lore()) {
                if (line.startsWith(RAW)) continue;
                String plain = RichText.plain(line);
                if (HINT.matcher(plain).find() && !HINT_WORDING.matcher(plain).matches()) broken.add(where + ": " + name + " hint " + plain);
                if (FORBIDDEN.matcher(plain).find()) broken.add(where + ": " + name + " says " + plain);
            }
            List<String> shown = entry.getValue().lore().stream().map(RichText::plain).toList();
            if (mode == ToolkitMode.SIMPLE && shown.size() > SIMPLE_LINES) broken.add(where + ": " + name + " runs to " + shown.size() + " lines: " + shown);
        }
    }
}
