package art.arcane.animus.core.quest;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.RichText;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /quests}: a six-row chest of the viewer's active quests, with their stage and objectives, or of the quests
 * they finished and when each comes back. The bottom row switches tabs and pages.
 */
final class QuestJournal<P, S> implements Screen {
    static final int ROWS = 6, LIST = 45, PREVIOUS = 45, ACTIVE_TAB = 47, CLOSE = 49, FINISHED_TAB = 51, NEXT = 53;
    private final QuestService<P, S> service;
    private final ScreenManager<P, S>.Viewer viewer;
    private boolean finished;
    private int page;

    QuestJournal(QuestService<P, S> service, ScreenManager<P, S>.Viewer viewer) { this.service = service; this.viewer = viewer; }

    @Override public ScreenFrame render() {
        Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
        List<ScreenFrame.Item> entries = finished ? finished() : active();
        int pages = Math.max(1, (entries.size() + LIST - 1) / LIST);
        page = Math.min(page, pages - 1);
        for (int index = 0; index < LIST && page * LIST + index < entries.size(); index++) items.put(index, entries.get(page * LIST + index));
        if (entries.isEmpty()) items.put(22, item("minecraft:paper", finished ? "&7No finished quests yet" : "&7No active quests", List.of("&8Talk to NPCs marked with &e! &8to find one.")));
        items.put(ACTIVE_TAB, item(finished ? "minecraft:writable_book" : "minecraft:enchanted_book", (finished ? "&7" : "&e") + "Active", List.of("&8Quests you are on")));
        items.put(FINISHED_TAB, item(finished ? "minecraft:enchanted_book" : "minecraft:book", (finished ? "&e" : "&7") + "Finished", List.of("&8Quests you completed")));
        items.put(CLOSE, item("minecraft:barrier", "&cClose", List.of()));
        if (page > 0) items.put(PREVIOUS, item("minecraft:arrow", "&fPrevious page", List.of("&8Page " + page + " of " + pages)));
        if (page < pages - 1) items.put(NEXT, item("minecraft:arrow", "&fNext page", List.of("&8Page " + (page + 2) + " of " + pages)));
        return new ScreenFrame(RichText.colorize("&8Quests"), "9x" + ROWS, 9, ROWS, items);
    }

    @Override public void click(int slot, GuiModel.Click click) {
        switch (slot) {
            case ACTIVE_TAB -> { finished = false; page = 0; }
            case FINISHED_TAB -> { finished = true; page = 0; }
            case PREVIOUS -> page = Math.max(0, page - 1);
            case NEXT -> page++;
            case CLOSE -> { viewer.close(); return; }
            default -> { return; }
        }
        viewer.refresh();
    }

    /** Objective counts change while the journal is open; redraw once a second. */
    @Override public boolean tick(long tick) { return tick % 20 == 0; }

    private List<ScreenFrame.Item> active() {
        UUID player = viewer.id();
        List<ScreenFrame.Item> entries = new ArrayList<>();
        for (Map.Entry<String, QuestRecord.Active> entry : service.store().record(player).active().entrySet()) {
            Optional<QuestDocument> quest = service.catalog().quest(entry.getKey());
            if (quest.isEmpty()) continue;
            QuestRecord.Active active = entry.getValue();
            List<String> lore = new ArrayList<>(QuestText.wrap(quest.get().description(), "&7"));
            Optional<QuestDocument.Stage> stage = QuestRules.stage(quest.get(), active);
            if (!lore.isEmpty()) lore.add("");
            lore.add("&6Stage " + Math.min(active.stage() + 1, quest.get().stages().size()) + "/" + quest.get().stages().size());
            if (active.ready()) lore.add("&aReturn to &f" + service.npcName(quest.get().turnIn().orElse("")));
            else if (stage.isPresent()) {
                lore.addAll(QuestText.wrap(stage.get().description(), "&f"));
                for (int index = 0; index < stage.get().objectives().size(); index++)
                    lore.add(QuestText.line(stage.get().objectives().get(index), active.count(index), service::npcName));
            }
            entries.add(item(quest.get().icon().map(NpcItem::id).orElse("minecraft:writable_book"), "&r" + quest.get().title(), lore));
        }
        return entries;
    }

    private List<ScreenFrame.Item> finished() {
        UUID player = viewer.id();
        List<ScreenFrame.Item> entries = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<String, QuestRecord.Finished> entry : service.store().record(player).finished().entrySet()) {
            Optional<QuestDocument> quest = service.catalog().quest(entry.getKey());
            if (quest.isEmpty()) continue;
            List<String> lore = new ArrayList<>(QuestText.wrap(quest.get().description(), "&7"));
            if (!lore.isEmpty()) lore.add("");
            int count = entry.getValue().count();
            lore.add("&aFinished" + (count > 1 ? " &7" + count + " times" : ""));
            if (quest.get().repeat().mode() != QuestDocument.RepeatMode.NEVER) {
                Optional<Instant> wait = QuestRules.waitUntil(quest.get(), Optional.of(entry.getValue()), now, ZoneId.systemDefault());
                lore.add(wait.map((Instant at) -> "&7Again in &f" + QuestRules.duration(now.until(at, ChronoUnit.SECONDS))).orElse("&eAvailable again"));
            }
            entries.add(item(quest.get().icon().map(NpcItem::id).orElse("minecraft:book"), "&r" + quest.get().title(), lore));
        }
        return entries;
    }

    private static ScreenFrame.Item item(String id, String name, List<String> lore) {
        return new ScreenFrame.Item(new NpcItem(id, 1, Optional.empty(), Optional.empty()), Optional.of(RichText.colorize(name)),
                lore.stream().map(RichText::colorize).toList(), Optional.empty());
    }
}
