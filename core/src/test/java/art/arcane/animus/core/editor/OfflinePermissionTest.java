package art.arcane.animus.core.editor;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfflinePermissionTest {
    @Test void anOperatorOrAPluginGrantHoldsTheNode() {
        assertEquals(EditorHost.Admin.ADMIN, OfflinePermission.decide(true, Optional.empty(), true));
        assertEquals(EditorHost.Admin.ADMIN, OfflinePermission.decide(false, Optional.of(true), true));
    }

    @Test void aPermissionPluginSayingNoIsDefinitive() {
        assertEquals(EditorHost.Admin.NOT_ADMIN, OfflinePermission.decide(false, Optional.of(false), true));
    }

    @Test void withoutAPermissionPluginTheServerDefaultIsDefinitive() {
        assertEquals(EditorHost.Admin.NOT_ADMIN, OfflinePermission.decide(false, Optional.empty(), false), "a de-opped admin on plain permissions or a mod server without a permission mod");
    }

    @Test void aPermissionPluginTheServerCannotAskLeavesItUnknown() {
        assertEquals(EditorHost.Admin.UNKNOWN, OfflinePermission.decide(false, Optional.empty(), true));
    }
}
