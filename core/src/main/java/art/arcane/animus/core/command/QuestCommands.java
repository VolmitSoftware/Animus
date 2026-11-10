package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.quest.QuestCatalog;
import art.arcane.animus.core.quest.QuestControl;
import art.arcane.animus.core.quest.QuestService;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** {@code /animus quest}: the journal and offer buttons for players, and quest progress for staff. */
@Director(name = "quest", aliases = "quests", description = "Quests: the journal, and player progress for staff")
@AnimusPermission(PermissionNodes.QUESTS)
final class QuestCommands extends CommandGroup {
    QuestCommands(CommandServices services) { super(services); }

    private QuestService<?, ?> quests() { return services.quests(); }

    @Director(description = "Open your quest journal", origin = DirectorOrigin.PLAYER)
    @AnimusPermission(PermissionNodes.QUESTS_USE)
    void journal() {
        CommandActor actor = actor();
        if (actor.player().isEmpty() || !quests().journal(actor.player().get())) actor.tell(Palette.error("Only an online player has a journal"));
    }

    @Director(hidden = true, description = "Answer a quest offer", origin = DirectorOrigin.PLAYER)
    @AnimusPermission(PermissionNodes.QUESTS_USE)
    void choice(@Param(name = "token", description = "The offer token") String token,
                @Param(name = "option", description = "accept or decline") String option) {
        CommandActor actor = actor();
        actor.player().ifPresent((UUID player) -> quests().choice(player, token, option));
    }

    @Director(description = "Show the quest catalog")
    void status() { actor().tell(quests().status()); }

    @Director(description = "List the loaded quests")
    @Positional("page")
    void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        List<String> rows = quests().rows();
        listing(actor(), "Quests (" + rows.size() + ")", "/" + BuildIdentity.ID + " quest list", rows, page, "No quests; save one under quests/<namespace>/, then /animus quest reload");
    }

    @Director(description = "List quest files that did not load")
    @Positional("page")
    void problems(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        List<String> rows = quests().problemRows();
        listing(actor(), "Quest problems (" + rows.size() + ")", "/" + BuildIdentity.ID + " quest problems", rows, page, "No quest problems");
    }

    @Director(description = "Reload quests from disk")
    void reload() {
        CommandActor actor = actor();
        quests().reload((QuestCatalog next) -> reply(actor, "Reloaded " + next.quests().size() + " quests; " + next.problems().size() + " problems"));
    }

    @Director(description = "Show a player's active and finished quests")
    @Positional({"player", "page"})
    void progress(@Param(name = "player", defaultValue = "", description = "A player's name or UUID; you when left out") String player,
                  @Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        CommandActor actor = actor();
        Optional<UUID> target = target(actor, player, true);
        if (target.isEmpty()) return;
        List<String> rows = quests().playerRows(target.get());
        listing(actor, "Quests of " + (player.isEmpty() ? "you" : player) + " (" + rows.size() + ")", "/" + BuildIdentity.ID + " quest progress " + player, rows, page, "No quests yet");
    }

    @Director(description = "Start a quest for yourself or a player")
    void start(@Param(name = "quest", description = "The quest id, such as town:herbs") String quest,
               @Param(name = "player", defaultValue = "", description = "Who takes it; you when left out") String player,
               @Param(name = "force", defaultValue = "false", description = "Skip the quest's requirements and repeat rule") boolean force) {
        change(player, "Started " + quest, (UUID target) -> quests().start(target, quest, force));
    }

    @Director(description = "Move a player's quest to the next stage, or to stage=")
    void stage(@Param(name = "quest", description = "The quest id") String quest,
               @Param(name = "stage", defaultValue = "0", description = "The stage, from 1; the next one when left out") int stage,
               @Param(name = "player", defaultValue = "", description = "Whose quest; yours when left out") String player) {
        OptionalInt target = stage > 0 ? OptionalInt.of(stage) : OptionalInt.empty();
        change(player, "Moved " + quest, (UUID who) -> quests().advance(who, quest, target));
    }

    @Director(description = "Complete a player's quest")
    void complete(@Param(name = "quest", description = "The quest id") String quest,
                  @Param(name = "player", defaultValue = "", description = "Whose quest; yours when left out") String player,
                  @Param(name = "rewards", defaultValue = "true", description = "Pay the quest's rewards") boolean rewards) {
        change(player, "Completed " + quest, (UUID who) -> quests().complete(who, quest, rewards));
    }

    @Director(description = "Add to a custom objective of a player's quest")
    void objective(@Param(name = "quest", description = "The quest id") String quest,
                   @Param(name = "objective", description = "The custom objective id") String objective,
                   @Param(name = "player", defaultValue = "", description = "Whose quest; yours when left out") String player,
                   @Param(name = "amount", defaultValue = "1", description = "How much to add") int amount) {
        CommandActor actor = actor();
        Optional<UUID> target = target(actor, player, false);
        if (target.isEmpty()) return;
        if (!quests().onPlayer(target.get(), () -> {
            try { reply(actor, Palette.success(quest + " " + objective + " is at " + quests().objective(target.get(), quest, objective, Math.max(1, amount)))); }
            catch (QuestControl.Refused refused) { reply(actor, Palette.error(refused.getMessage())); }
        })) actor.tell(Palette.error("That player is not online; check the name with tab completion"));
    }

    @Director(description = "Forget a quest, or every quest, for a player and set the stages back to 0")
    void reset(@Param(name = "player", description = "A player's name or UUID") String player,
               @Param(name = "quest", defaultValue = "all", description = "The quest id, or all") String quest) {
        CommandActor actor = actor();
        Optional<UUID> target = target(actor, player, true);
        if (target.isEmpty()) return;
        try {
            quests().reset(target.get(), quest.equals("all") ? Optional.empty() : Optional.of(quest));
            actor.tell(Palette.success("Reset " + (quest.equals("all") ? "every quest" : quest) + " for " + player));
        } catch (QuestControl.Refused refused) { actor.tell(Palette.error(refused.getMessage())); }
    }

    /** Runs a stage change on the player's owner and reports the new stage value to the actor. */
    private void change(String player, String done, java.util.function.Function<UUID, Long> work) {
        CommandActor actor = actor();
        Optional<UUID> target = target(actor, player, false);
        if (target.isEmpty()) return;
        if (!quests().onPlayer(target.get(), () -> {
            try { reply(actor, Palette.success(done + "; stage value " + work.apply(target.get()))); }
            catch (QuestControl.Refused refused) { reply(actor, Palette.error(refused.getMessage())); }
        })) actor.tell(Palette.error("That player is not online; check the name with tab completion"));
    }

    /** The named online player, a UUID when {@code offline} allows one, or the acting player; tells the actor why not. */
    private Optional<UUID> target(CommandActor actor, String player, boolean offline) {
        if (player.isEmpty()) {
            if (actor.player().isEmpty()) actor.tell(Palette.error("Give player= from the console"));
            return actor.player();
        }
        Optional<UUID> named = quests().playerNamed(player);
        if (named.isEmpty() && offline) {
            try { named = Optional.of(UUID.fromString(player)); }
            catch (IllegalArgumentException notUuid) { named = Optional.empty(); }
        }
        if (named.isEmpty()) actor.tell(Palette.error("Unknown player " + player + (offline ? "; give an online name or a UUID" : "; check the name with tab completion")));
        return named;
    }
}
