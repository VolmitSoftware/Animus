package art.arcane.animus.core.text;

/** The wiki sections a reply points to with {@code [Docs]} instead of explaining in chat. Each anchor is a heading on its page. */
public enum DocsLink {
    ARGUMENTS("02-commands-permissions", "writing-arguments"),
    PERMISSIONS("02-commands-permissions", "permissions"),
    COMMANDS("02-commands-permissions", "status-and-npc-management"),
    IMPORTS("02-commands-permissions", "imports"),
    MOVEMENT("02-commands-permissions", "movement-and-riding"),
    COMBAT("02-commands-permissions", "combat"),
    CONTENT("02-commands-permissions", "rigs-and-resource-pack"),
    SERVICES("02-commands-permissions", "shops-and-guis"),
    BEHAVIORS("02-commands-permissions", "behaviors"),
    GROUPS("02-commands-permissions", "trait-groups"),
    DIAGNOSTICS("02-commands-permissions", "diagnostics"),
    TOOLS("02-commands-permissions", "tools"),
    DEFINITIONS("04-npc-definitions", "definition-file"),
    ACTIVATION("04-npc-definitions", "activation-and-editing"),
    RECOVERY("12-storage-recovery", "last-valid-recovery"),
    ROLLBACK("12-storage-recovery", "rollback"),
    CITIZENS("13-migrating-from-citizens", "converting-on-a-running-server"),
    COLLISIONS("13-migrating-from-citizens", "collision-modes"),
    PAIRING("14-web-editor", "pairing"),
    TOKENS("14-web-editor", "managing-tokens"),
    CONNECTING("14-web-editor", "connecting-the-server"),
    DEBUG("17-debug-react-metrics", "animus-debug");

    private final String page;
    private final String anchor;

    DocsLink(String page, String anchor) {
        this.page = page;
        this.anchor = anchor;
    }

    public String page() { return page; }

    public String anchor() { return anchor; }

    public String url() { return ChatLine.docsUrl(page, anchor); }
}
