package art.arcane.animus.core.metrics;

/** Work Animus times. Server-thread subsystems run on the owner (main thread or a Folia region); the rest on worker threads. */
public enum Subsystem {
    PRESENTATION("presentation", "Presentation", true),
    NAVIGATION("navigation", "Navigation", true),
    COMBAT("combat", "Combat", true),
    BEHAVIORS("behaviors", "Behaviors and triggers", true),
    RIGS("rigs", "Rigs", true),
    GUI("gui", "GUIs and holograms", true),
    RUNTIME("runtime", "Definitions and activation", true),
    HOTLOAD("hotload", "Hotload and reloads", false),
    EDITOR("editor", "Editor API", false),
    IMPORTS("imports", "Imports", false);

    private final String key, label;
    private final boolean serverThread;

    Subsystem(String key, String label, boolean serverThread) { this.key = key; this.label = label; this.serverThread = serverThread; }

    public String key() { return key; }
    public String label() { return label; }
    /** True when this work runs on a server tick thread and so counts against the 50 ms tick. */
    public boolean serverThread() { return serverThread; }
}
