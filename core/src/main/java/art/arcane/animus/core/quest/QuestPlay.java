package art.arcane.animus.core.quest;

import art.arcane.animus.core.behavior.ArgumentTemplate;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.behavior.trigger.ServerEvent;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.RichText;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The quest flow for online players, always on the player's owner: offers and their buttons, objective counting,
 * stage changes, turn-in and rewards. Each change goes to the player's {@link QuestRecord}, the behavior stage value
 * ({@link QuestState}) and a server trigger. A stage value a behavior wrote wins over the record: the next check moves
 * the record to it.
 */
final class QuestPlay<P, S> {
    /** Seconds an offer's buttons stay valid. */
    static final long OFFER_SECONDS = 120;
    private static final String ACCEPT = "accept", DECLINE = "decline";
    private static final String PROGRESS_SOUND = "minecraft:entity.experience_orb.pickup", STAGE_SOUND = "minecraft:block.note_block.chime",
            COMPLETE_SOUND = "minecraft:ui.toast.challenge_complete";
    private record Offer(String token, String quest, String npc, long expires) {}

    private final QuestService<P, S> service;
    private final ScreenHost<P, S> host;
    private final QuestState state;
    private final QuestStore store;
    private final Supplier<ServerTriggerDispatcher> servers;
    private final Consumer<String> log;
    private final Map<UUID, Offer> offers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> reminded = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    QuestPlay(QuestService<P, S> service, ScreenHost<P, S> host, QuestState state, QuestStore store, Supplier<ServerTriggerDispatcher> servers, Consumer<String> log) {
        this.service = service; this.host = host; this.state = state; this.store = store; this.servers = servers; this.log = log;
    }

    int offers() { return offers.size(); }
    void forget(UUID player) { offers.remove(player); reminded.remove(player); }

    // ---- reading -------------------------------------------------------------------------------------------------

    /**
     * Brings the record in line with a stage value a behavior wrote and returns the active state. Only a value that
     * differs from the one last written or seen counts, so a write the state budgets refused never undoes progress. A
     * behavior that set 0 abandoned the quest; 1 to N moves it to that stage with fresh counts; past N completes it
     * with rewards.
     */
    Optional<QuestRecord.Active> active(P player, QuestDocument quest) {
        UUID id = host.id(player);
        QuestRecord record = store.record(id);
        Optional<QuestRecord.Active> active = record.active(quest.id());
        java.util.OptionalLong seen = state.stage(id, quest);
        if (seen.isEmpty()) return active;
        long stage = seen.getAsLong();
        int stages = quest.stages().size();
        if (active.isPresent()) {
            if (stage == active.get().mirror()) return active;
            if (stage <= 0) { record.drop(quest.id()); store.changed(id); return Optional.empty(); }
            if (stage > stages) { completed(player, quest, true, false); return Optional.empty(); }
            QuestRecord.Active moved = active.get().at((int) stage - 1, quest.stages().get((int) stage - 1).objectives().size(), stage);
            record.put(quest.id(), moved);
            store.changed(id);
            return Optional.of(moved);
        }
        long expected = record.finished(quest.id()).map(QuestRecord.Finished::mirror).orElse(0L);
        if (stage == expected) return Optional.empty();
        if (stage >= 1 && stage <= stages) {
            QuestRecord.Active started = new QuestRecord.Active((int) stage - 1, new int[quest.stages().get((int) stage - 1).objectives().size()], System.currentTimeMillis(), false, stage);
            record.put(quest.id(), started);
            store.changed(id);
            return Optional.of(started);
        }
        if (stage > stages && record.finished(quest.id()).isEmpty()) { record.finish(quest.id(), System.currentTimeMillis(), stage); store.changed(id); }
        return Optional.empty();
    }

    String status(UUID player, QuestDocument quest) {
        QuestRecord record = store.record(player);
        Optional<QuestRecord.Active> active = record.active(quest.id());
        if (active.isPresent()) return active.get().ready() ? "ready" : "active";
        return record.finished(quest.id()).isPresent() || state.stage(player, quest).orElse(0) > quest.stages().size() ? "finished" : "none";
    }

    /** Why the player cannot take the quest now, or empty when they can. */
    Optional<String> refusal(P player, QuestDocument quest) {
        UUID id = host.id(player);
        QuestRecord record = store.record(id);
        if (record.active(quest.id()).isPresent()) return Optional.of("You are already on this quest.");
        Optional<Instant> wait = QuestRules.waitUntil(quest, record.finished(quest.id()), Instant.now(), ZoneId.systemDefault());
        if (wait.isPresent()) return Optional.of(wait.get().equals(Instant.MAX) ? "You have finished this quest."
                : "You can take this quest again in " + QuestRules.duration(Instant.now().until(wait.get(), java.time.temporal.ChronoUnit.SECONDS)) + ".");
        QuestDocument.Requirements needs = quest.requirements();
        if (needs.permission().isPresent() && !host.permitted(player, needs.permission().get())) return Optional.of("You may not take this quest.");
        for (String before : needs.quests())
            if (record.finished(before).isEmpty()) return Optional.of("Finish " + service.catalog().quest(before).map(QuestDocument::title).orElse(before) + "&r first.");
        if (needs.condition().isPresent() && !state.holds(id, quest, needs.condition().get())) return Optional.of("You are not ready for this quest yet.");
        return Optional.empty();
    }

    // ---- NPC clicks ----------------------------------------------------------------------------------------------

    boolean clicked(P player, UUID npc, String npcId) {
        boolean used = false;
        for (String id : service.catalog().touching(npcId)) {
            Optional<QuestDocument> quest = service.catalog().quest(id);
            if (quest.isEmpty()) continue;
            Optional<QuestRecord.Active> active = active(player, quest.get());
            if (active.isEmpty()) continue;
            used |= talkAndBring(player, quest.get(), npcId);
            active = store.record(host.id(player)).active(quest.get().id());
            if (active.isPresent() && active.get().ready() && quest.get().turnIn().filter(npcId::equals).isPresent()) {
                completed(player, quest.get(), true, true);
                used = true;
            }
        }
        if (used) return true;
        for (QuestDocument quest : service.catalog().offeredBy(npcId)) {
            if (store.record(host.id(player)).active(quest.id()).isPresent() || refusal(player, quest).isPresent()) continue;
            offer(player, quest, npcId);
            return true;
        }
        remind(player, npcId);
        return false;
    }

    /** Counts talk objectives for this NPC and hands over items for bring objectives. True when any objective was this NPC's. */
    private boolean talkAndBring(P player, QuestDocument quest, String npcId) {
        boolean[] mine = {false};
        count(player, quest, (QuestDocument.Objective objective) -> {
            if (objective.npc().filter(npcId::equals).isEmpty()) return 0;
            if (objective.type() == QuestDocument.Type.TALK) { mine[0] = true; return 1; }
            return 0;
        }, Optional.of(npcId));
        QuestRecord record = store.record(host.id(player));
        Optional<QuestRecord.Active> active = record.active(quest.id());
        Optional<QuestDocument.Stage> stage = active.flatMap((QuestRecord.Active current) -> QuestRules.stage(quest, current));
        if (active.isEmpty() || stage.isEmpty()) return mine[0];
        for (int index : QuestRules.open(stage.get(), active.get())) {
            QuestDocument.Objective objective = stage.get().objectives().get(index);
            if (objective.type() != QuestDocument.Type.BRING || objective.npc().filter(npcId::equals).isEmpty()) continue;
            mine[0] = true;
            int missing = objective.amount() - active.get().count(index);
            int given = take(player, objective.item().orElseThrow(), missing);
            if (given == 0) { tell(player, "&7You still need to bring &f" + missing + " " + QuestText.item(objective.item().orElseThrow().id()) + "&7."); continue; }
            record.put(quest.id(), active.get().counted(index, active.get().count(index) + given));
            store.changed(host.id(player));
            progressed(player, quest, objective, active.get().count(index) + given);
            active = record.active(quest.id());
        }
        settle(player, quest, Optional.of(npcId));
        return mine[0];
    }

    private void remind(P player, String npcId) {
        UUID id = host.id(player);
        long now = System.currentTimeMillis();
        Long last = reminded.get(id);
        if (last != null && now - last < 10_000) return;
        for (QuestDocument quest : service.catalog().offeredBy(npcId)) {
            Optional<QuestRecord.Active> active = store.record(id).active(quest.id());
            if (active.isEmpty()) continue;
            Optional<QuestDocument.Stage> stage = QuestRules.stage(quest, active.get());
            String next = active.get().ready() ? "Return to " + service.npcName(quest.turnIn().orElse(npcId)) + "&7."
                    : stage.map(QuestDocument.Stage::description).filter((String text) -> !text.isBlank()).orElse("Keep going.");
            tell(player, quest.title() + "&8: &7" + next);
            reminded.put(id, now);
            return;
        }
    }

    // ---- offers --------------------------------------------------------------------------------------------------

    private void offer(P player, QuestDocument quest, String npcId) {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        offers.put(host.id(player), new Offer(token, quest.id(), npcId, System.currentTimeMillis() + OFFER_SECONDS * 1000));
        String name = host.name(player);
        host.message(player, RichText.colorize(QuestText.HEADER + quest.title()));
        String text = quest.messages().offer().orElse(quest.description().isBlank() ? "I have a task for you." : quest.description());
        host.message(player, DialogueFormat.speech(service.npcName(npcId), QuestText.fill(text, name, quest)));
        host.parts(player, DialogueFormat.buttons(art.arcane.animus.core.BuildIdentity.ID + " quest", token,
                List.of(new ChoiceOption(ACCEPT, "Accept"), new ChoiceOption(DECLINE, "Decline"))));
    }

    void choice(P player, String token, String option) {
        UUID id = host.id(player);
        Offer offer = offers.get(id);
        if (offer == null || !offer.token().equals(token) || offer.expires() < System.currentTimeMillis()) { tell(player, "&7That offer has run out; ask again."); return; }
        offers.remove(id, offer);
        Optional<QuestDocument> quest = service.catalog().quest(offer.quest());
        if (quest.isEmpty()) { tell(player, "&7That quest is no longer offered."); return; }
        String name = host.name(player);
        if (option.equals(DECLINE)) {
            host.message(player, DialogueFormat.speech(service.npcName(offer.npc()), QuestText.fill(quest.get().messages().decline().orElse("Perhaps another time."), name, quest.get())));
            return;
        }
        if (!option.equals(ACCEPT)) return;
        Optional<String> refused = refusal(player, quest.get());
        if (refused.isPresent()) { tell(player, "&c" + refused.get()); return; }
        started(player, quest.get());
        quest.get().messages().accept().ifPresent((String text) -> host.message(player, DialogueFormat.speech(service.npcName(offer.npc()), QuestText.fill(text, name, quest.get()))));
    }

    // ---- counting ------------------------------------------------------------------------------------------------

    /** On the check timer: behavior stage changes, reach and collect objectives, and expired offers. */
    void check(P player) {
        UUID id = host.id(player);
        Offer offer = offers.get(id);
        if (offer != null && offer.expires() < System.currentTimeMillis()) offers.remove(id, offer);
        Optional<ScreenHost.Pose> pose = Optional.empty();
        boolean posed = false;
        for (QuestDocument quest : service.catalog().quests().values()) {
            Optional<QuestRecord.Active> active = active(player, quest);
            if (active.isEmpty() || active.get().ready()) continue;
            Optional<QuestDocument.Stage> stage = QuestRules.stage(quest, active.get());
            if (stage.isEmpty()) continue;
            boolean needsPose = false;
            for (QuestDocument.Objective objective : stage.get().objectives()) needsPose |= objective.type() == QuestDocument.Type.REACH;
            if (needsPose && !posed) { pose = host.pose(player); posed = true; }
            Optional<ScreenHost.Pose> at = pose;
            count(player, quest, (QuestDocument.Objective objective) -> objective.type() == QuestDocument.Type.REACH && at.isPresent() && reached(at.get(), objective.point().orElseThrow()) ? 1 : 0);
            collect(player, quest);
        }
    }

    private static boolean reached(ScreenHost.Pose pose, QuestDocument.Point point) {
        if (!pose.in(point.world())) return false;
        double dx = pose.x() - point.x(), dy = pose.y() - point.y(), dz = pose.z() - point.z();
        return dx * dx + dy * dy + dz * dz <= point.radius() * point.radius();
    }

    /** Collect objectives follow what the inventory holds now, so dropping items lowers them again until the stage is done. */
    private void collect(P player, QuestDocument quest) {
        QuestRecord record = store.record(host.id(player));
        Optional<QuestRecord.Active> active = record.active(quest.id());
        Optional<QuestDocument.Stage> stage = active.flatMap((QuestRecord.Active current) -> QuestRules.stage(quest, current));
        if (stage.isEmpty()) return;
        boolean changed = false;
        QuestRecord.Active current = active.get();
        for (int index : QuestRules.open(stage.get(), current)) {
            QuestDocument.Objective objective = stage.get().objectives().get(index);
            if (objective.type() != QuestDocument.Type.COLLECT) continue;
            int held = Math.min(objective.amount(), held(player, objective.item().orElseThrow()));
            if (held == current.count(index)) continue;
            current = current.counted(index, held);
            changed = true;
            if (held > 0) progressed(player, quest, objective, held);
        }
        if (!changed) return;
        record.put(quest.id(), current);
        store.changed(host.id(player));
        settle(player, quest);
    }

    void killed(P player, String entity, Optional<String> npcId) {
        for (QuestDocument quest : service.catalog().quests().values()) {
            if (active(player, quest).isEmpty()) continue;
            count(player, quest, (QuestDocument.Objective objective) -> {
                if (objective.type() != QuestDocument.Type.KILL) return 0;
                if (objective.npc().isPresent()) return npcId.equals(objective.npc()) ? 1 : 0;
                return npcId.isEmpty() && objective.entity().filter(entity::equals).isPresent() ? 1 : 0;
            });
        }
    }

    int custom(P player, QuestDocument quest, String objectiveId, int amount) {
        if (active(player, quest).isEmpty()) throw new QuestControl.Refused("quest-not-active", "The player is not on " + quest.id());
        QuestRecord.Active before = store.record(host.id(player)).active(quest.id()).orElseThrow();
        QuestDocument.Stage stage = QuestRules.stage(quest, before).orElseThrow(() -> new QuestControl.Refused("quest-not-active", "The quest has no such stage"));
        int index = -1;
        for (int at = 0; at < stage.objectives().size(); at++)
            if (stage.objectives().get(at).id().equals(objectiveId) && stage.objectives().get(at).type() == QuestDocument.Type.CUSTOM) index = at;
        if (index < 0) throw new QuestControl.Refused("objective-unknown", "The current stage of " + quest.id() + " has no custom objective " + objectiveId);
        int target = index;
        count(player, quest, (QuestDocument.Objective objective) -> objective == stage.objectives().get(target) ? amount : 0);
        return store.record(host.id(player)).active(quest.id()).map((QuestRecord.Active after) -> after.stage() == before.stage() ? after.count(target) : stage.objectives().get(target).amount())
                .orElse(stage.objectives().get(target).amount());
    }

    /** Adds what {@code amount} says to each open objective of the active stage, then moves the stage on when it is done. */
    private void count(P player, QuestDocument quest, ToIntFunction<QuestDocument.Objective> amount) { count(player, quest, amount, Optional.empty()); }

    /** {@code at} is the NPC the player clicked, so a quest done there completes without being sent back to it. */
    private void count(P player, QuestDocument quest, ToIntFunction<QuestDocument.Objective> amount, Optional<String> at) {
        UUID id = host.id(player);
        QuestRecord record = store.record(id);
        Optional<QuestRecord.Active> active = record.active(quest.id());
        if (active.isEmpty() || active.get().ready()) return;
        Optional<QuestDocument.Stage> stage = QuestRules.stage(quest, active.get());
        if (stage.isEmpty()) return;
        QuestRecord.Active current = active.get();
        boolean changed = false;
        for (int index : QuestRules.open(stage.get(), current)) {
            QuestDocument.Objective objective = stage.get().objectives().get(index);
            int added = amount.applyAsInt(objective);
            if (added <= 0) continue;
            int count = Math.min(objective.amount(), current.count(index) + added);
            current = current.counted(index, count);
            changed = true;
            progressed(player, quest, objective, count);
        }
        if (!changed) return;
        record.put(quest.id(), current);
        store.changed(id);
        settle(player, quest, at);
    }

    private void progressed(P player, QuestDocument quest, QuestDocument.Objective objective, int count) {
        String line = QuestText.objective(objective, service::npcName) + QuestText.count(objective, count);
        host.actionBar(player, RichText.colorize((count >= objective.amount() ? "&a✔ " : "&e") + line));
        host.sound(player, PROGRESS_SOUND, "master", 0.6f, 1.4f);
    }

    private void settle(P player, QuestDocument quest) { settle(player, quest, Optional.empty()); }

    /** Moves a finished stage on: the next stage, the turn-in wait, or completion, at once when the player stands at the turn-in NPC. */
    private void settle(P player, QuestDocument quest, Optional<String> at) {
        UUID id = host.id(player);
        QuestRecord record = store.record(id);
        Optional<QuestRecord.Active> active = record.active(quest.id());
        if (active.isEmpty() || active.get().ready()) return;
        Optional<QuestDocument.Stage> stage = QuestRules.stage(quest, active.get());
        if (stage.isEmpty() || !QuestRules.done(stage.get(), active.get())) return;
        int next = active.get().stage() + 1;
        if (next < quest.stages().size()) { staged(player, quest, next); return; }
        if (quest.turnIn().isPresent() && !quest.turnIn().equals(at)) {
            record.put(quest.id(), active.get().readied());
            store.changed(id);
            tell(player, quest.title() + "&8: &7return to &f" + service.npcName(quest.turnIn().get()) + "&7.");
            host.sound(player, STAGE_SOUND, "master", 0.8f, 1.2f);
            return;
        }
        completed(player, quest, true, true);
    }

    // ---- changes -------------------------------------------------------------------------------------------------

    /** Starts the quest at stage 1. */
    private void started(P player, QuestDocument quest) {
        UUID id = host.id(player);
        long before = state.stage(id, quest).orElse(0);
        long mirror = write(id, quest, 1) ? 1 : before;
        store.record(id).put(quest.id(), new QuestRecord.Active(0, new int[quest.stages().getFirst().objectives().size()], System.currentTimeMillis(), false, mirror));
        store.changed(id);
        host.message(player, RichText.colorize(QuestText.HEADER + "&aStarted &r" + quest.title()));
        describe(player, quest, 0);
        host.sound(player, STAGE_SOUND, "master", 0.8f, 1f);
        publish(TriggerKind.QUEST_STARTED, id, quest, 1);
    }

    private void staged(P player, QuestDocument quest, int stage) {
        UUID id = host.id(player);
        QuestRecord record = store.record(id);
        QuestRecord.Active active = record.active(quest.id()).orElseThrow();
        long mirror = write(id, quest, stage + 1L) ? stage + 1L : active.mirror();
        record.put(quest.id(), active.at(stage, quest.stages().get(stage).objectives().size(), mirror));
        store.changed(id);
        host.message(player, RichText.colorize(QuestText.HEADER + quest.title() + " &8(&7stage " + (stage + 1) + "/" + quest.stages().size() + "&8)"));
        describe(player, quest, stage);
        host.sound(player, STAGE_SOUND, "master", 0.8f, 1.2f);
        publish(TriggerKind.QUEST_STAGE, id, quest, stage + 1L);
        settle(player, quest);
    }

    private void describe(P player, QuestDocument quest, int stage) {
        QuestDocument.Stage shown = quest.stages().get(stage);
        if (!shown.description().isBlank()) host.message(player, RichText.colorize("&7" + shown.description()));
        for (QuestDocument.Objective objective : shown.objectives()) host.message(player, RichText.colorize("  " + QuestText.line(objective, 0, service::npcName)));
    }

    /** Ends the quest, pays it when {@code rewards}, and writes the finished stage unless a behavior already did. */
    private void completed(P player, QuestDocument quest, boolean rewards, boolean writeStage) {
        UUID id = host.id(player);
        long mirror = writeStage && write(id, quest, quest.finishedStage()) ? quest.finishedStage() : state.stage(id, quest).orElse(quest.finishedStage());
        store.record(id).finish(quest.id(), System.currentTimeMillis(), mirror);
        store.changed(id);
        String name = host.name(player);
        host.message(player, RichText.colorize(QuestText.HEADER + "&aCompleted &r" + quest.title()));
        quest.messages().complete().ifPresent((String text) -> host.message(player, quest.turnIn().map((String npc) -> DialogueFormat.speech(service.npcName(npc), QuestText.fill(text, name, quest)))
                .orElse(RichText.colorize(QuestText.fill(text, name, quest)))));
        if (rewards) reward(player, quest);
        host.sound(player, COMPLETE_SOUND, "master", 0.7f, 1f);
        publish(TriggerKind.QUEST_COMPLETED, id, quest, quest.finishedStage());
    }

    private void reward(P player, QuestDocument quest) {
        QuestDocument.Rewards rewards = quest.rewards();
        if (!rewards.items().isEmpty()) {
            List<String> errors = new ArrayList<>();
            InventoryTransaction<S> transaction = InventoryTransaction.begin(host.inventory(player), host.items());
            for (QuestDocument.Goods goods : rewards.items()) {
                Optional<S> stack = host.stack(goods.item(), Optional.empty(), List.of(), errors);
                if (stack.isPresent()) for (S overflow : transaction.addOrOverflow(stack.get(), goods.amount())) host.drop(player, overflow);
            }
            transaction.commit();
            errors.forEach((String error) -> log.accept("Quest " + quest.id() + " reward item skipped: " + error));
        }
        if (rewards.money().isPresent()) {
            Economy economy = host.economy();
            Optional<String> unavailable = economy.unavailable();
            if (unavailable.isPresent()) log.accept("Quest " + quest.id() + " money reward skipped: " + unavailable.get());
            else if (!economy.deposit(host.id(player), rewards.money().get())) log.accept("Quest " + quest.id() + " money reward was refused by the economy");
            else tell(player, "&7You received &f" + economy.format(rewards.money().get()) + "&7.");
        }
        if (rewards.experience() > 0 && !host.experience(player, rewards.experience())) log.accept("Quest " + quest.id() + " experience reward skipped: this platform cannot give experience");
        String name = host.name(player);
        for (QuestDocument.Command command : rewards.commands()) {
            if (command.command().contains("{player}") && !ArgumentTemplate.commandSafeName(name)) { log.accept("Quest " + quest.id() + " command skipped: the player name is not safe in a command"); continue; }
            host.command(command.console() ? Optional.empty() : Optional.of(player), command.command().replace("{player}", name));
        }
        for (QuestDocument.Flag flag : rewards.flags()) {
            try { state.flag(host.id(player), quest, flag); }
            catch (BehaviorState.Rejected refused) { log.accept("Quest " + quest.id() + " flag " + flag.name() + " not set: " + refused.getMessage()); }
        }
    }

    /** False when the behavior state budgets refused the write; the record keeps the quest either way. */
    private boolean write(UUID player, QuestDocument quest, long stage) {
        try { state.stage(player, quest, stage); return true; }
        catch (BehaviorState.Rejected refused) { log.accept("Quest " + quest.id() + " stage not written to behavior state: " + refused.getMessage()); return false; }
    }

    private void publish(TriggerKind kind, UUID player, QuestDocument quest, long stage) {
        ServerTriggerDispatcher dispatcher = servers.get();
        if (dispatcher != null) dispatcher.publish(ServerEvent.quest(kind, player, quest.id(), stage, RichText.plain(RichText.colorize(quest.title()))));
    }

    // ---- behavior and command requests ---------------------------------------------------------------------------

    long begin(P player, QuestDocument quest, boolean force) {
        active(player, quest);
        if (store.record(host.id(player)).active(quest.id()).isPresent()) throw new QuestControl.Refused("quest-active", "The player is already on " + quest.id());
        if (!force) refusal(player, quest).ifPresent((String reason) -> { throw new QuestControl.Refused("quest-refused", RichText.plain(RichText.colorize(reason))); });
        started(player, quest);
        return 1;
    }

    long advance(P player, QuestDocument quest, OptionalInt target) {
        Optional<QuestRecord.Active> active = active(player, quest);
        if (active.isEmpty()) throw new QuestControl.Refused("quest-not-active", "The player is not on " + quest.id());
        int next = target.isPresent() ? target.getAsInt() - 1 : active.get().stage() + 1;
        if (next < 0) throw new QuestControl.Refused("stage-invalid", "Stages count from 1");
        if (next >= quest.stages().size()) { completed(player, quest, true, true); return quest.finishedStage(); }
        staged(player, quest, next);
        return store.record(host.id(player)).active(quest.id()).map((QuestRecord.Active now) -> now.stage() + 1L).orElse(quest.finishedStage());
    }

    long finish(P player, QuestDocument quest, boolean rewards) {
        if (active(player, quest).isEmpty()) throw new QuestControl.Refused("quest-not-active", "The player is not on " + quest.id());
        completed(player, quest, rewards, true);
        return quest.finishedStage();
    }

    /** Forgets the quest, or every quest when empty, for a player online or not; any thread. */
    void reset(UUID player, Optional<QuestDocument> quest) {
        QuestRecord record = store.record(player);
        List<QuestDocument> cleared = quest.map(List::of).orElseGet(() -> List.copyOf(service.catalog().quests().values()));
        for (QuestDocument each : cleared) { record.forget(each.id()); write(player, each, 0); }
        if (quest.isEmpty()) record.forgetAll();
        store.changed(player);
        offers.remove(player);
    }

    // ---- inventory -----------------------------------------------------------------------------------------------

    private Optional<S> wanted(NpcItem item) {
        List<String> errors = new ArrayList<>();
        Optional<S> stack = host.stack(item, Optional.empty(), List.of(), errors);
        errors.forEach((String error) -> log.accept("Quest item " + item.id() + " cannot be made here: " + error));
        return stack;
    }

    private static MatchRule rule(NpcItem item) { return item.components().isPresent() ? MatchRule.STRICT : MatchRule.MATERIAL; }

    private int held(P player, NpcItem item) {
        Optional<S> wanted = wanted(item);
        return wanted.map((S stack) -> InventoryTransaction.begin(host.inventory(player), host.items()).count(stack, rule(item))).orElse(0);
    }

    /** Takes up to {@code amount} matching items; returns how many it took. */
    private int take(P player, NpcItem item, int amount) {
        Optional<S> wanted = wanted(item);
        if (wanted.isEmpty() || amount <= 0) return 0;
        InventoryTransaction<S> transaction = InventoryTransaction.begin(host.inventory(player), host.items());
        int taking = Math.min(amount, transaction.count(wanted.get(), rule(item)));
        if (taking <= 0 || !transaction.remove(wanted.get(), taking, rule(item))) { transaction.rollback(); return 0; }
        transaction.commit();
        return taking;
    }

    private void tell(P player, String text) { host.message(player, RichText.colorize(QuestText.HEADER + text)); }
}
