package art.arcane.animus.bukkit;

import art.arcane.animus.core.editor.EditorHost;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BukkitOfflineAdminTest {
    @Test void anOperatorOrAVaultGrantIsAnAdmin() {
        assertEquals(EditorHost.Admin.ADMIN, BukkitOfflineAdmin.decide(true, Optional.empty(), true));
        assertEquals(EditorHost.Admin.ADMIN, BukkitOfflineAdmin.decide(false, Optional.of(true), true));
    }

    @Test void vaultSayingNoIsDefinitive() {
        assertEquals(EditorHost.Admin.NOT_ADMIN, BukkitOfflineAdmin.decide(false, Optional.of(false), true));
    }

    @Test void withoutAPermissionPluginTheServerDefaultIsDefinitive() {
        assertEquals(EditorHost.Admin.NOT_ADMIN, BukkitOfflineAdmin.decide(false, Optional.empty(), false), "a de-opped admin on plain Bukkit permissions");
    }

    @Test void aPermissionPluginVaultCannotReachLeavesItUnknown() {
        assertEquals(EditorHost.Admin.UNKNOWN, BukkitOfflineAdmin.decide(false, Optional.empty(), true));
    }
}
