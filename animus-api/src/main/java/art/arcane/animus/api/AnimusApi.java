package art.arcane.animus.api;

/**
 * Stable package and explicit contract version; plugin release numbers are independent.
 * A major change may remove or change contracts. A minor change only adds types and engine-implemented methods,
 * so a module built against 1.0 runs on every 1.x engine. A module that calls a 1.1 addition must check
 * {@code AnimusApi.API_VERSION.minor()} at enable time when it also declares support for older 1.x engines.
 */
public final class AnimusApi {
    public static final Version API_VERSION = new Version(1, 1, 0);
    private AnimusApi() {}
}
