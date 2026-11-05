package art.arcane.animus.core.behavior;

import art.arcane.animus.api.NpcHandle;
import java.util.UUID;

/** The NPC handle a server-wide behavior runs with. It names no NPC, so actions that need an NPC body refuse it. */
public final class ServerNpc {
    public static final NpcHandle HANDLE = new NpcHandle(new UUID(0L, 0L));

    private ServerNpc() {}

    public static boolean is(NpcHandle npc) { return HANDLE.equals(npc); }
}
