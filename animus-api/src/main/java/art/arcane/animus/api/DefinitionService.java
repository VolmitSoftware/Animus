package art.arcane.animus.api;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Engine-owned bounded asynchronous I/O. Completion threads grant no platform ownership. */
public interface DefinitionService {
    /** Most NPCs one {@link #list} page holds. @since 1.2 */
    int PAGE_SIZE = 100;

    /**
     * One NPC as a list shows it. {@code group} is its folder below {@code npcs/}, such as {@code world/town}, and is
     * empty for a file directly in {@code npcs/}.
     *
     * @param npc NPC identity
     * @param id NPC id
     * @param group folder of the NPC's file
     * @param spawned whether the definition asks for the NPC to be active
     * @since 1.2
     */
    record Summary(NpcHandle npc, String id, String group, boolean spawned) {
        public Summary { Objects.requireNonNull(npc); ApiText.id(id); ApiText.group(group); }
    }
    /**
     * One page of NPCs in id order.
     *
     * @param npcs NPCs on this page, at most {@link #PAGE_SIZE}
     * @param page page number, from 1
     * @param pages number of pages, at least 1
     * @param total NPCs across all pages
     * @since 1.2
     */
    record Page(List<Summary> npcs, int page, int pages, int total) {
        public Page {
            npcs = List.copyOf(npcs);
            if (npcs.size() > PAGE_SIZE || page < 1 || pages < 1 || total < npcs.size()) throw new IllegalArgumentException("Invalid page");
        }
    }

    CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc);
    CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit);
    CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit);

    /**
     * The NPC with this id, such as {@code guard}. NOT_FOUND when none has it and INVALID for a malformed id.
     *
     * @param id NPC id
     * @return the NPC's handle
     * @since 1.2
     */
    default CompletionStage<Outcome<NpcHandle>> resolve(String id) { return CompletableFuture.completedFuture(unsupported()); }

    /**
     * One page of NPCs in id order, of every folder or only of {@code group} (not its subfolders). A page past the last
     * is empty; INVALID for a page below 1 or a malformed group.
     *
     * @param group folder to list, or empty for every NPC
     * @param page page number, from 1
     * @return the page
     * @since 1.2
     */
    default CompletionStage<Outcome<Page>> list(Optional<String> group, int page) { return CompletableFuture.completedFuture(unsupported()); }

    private static <T> Outcome<T> unsupported() { return Outcome.rejected(Outcome.Status.UNAVAILABLE, "unsupported", "This engine does not offer this lookup"); }
}
