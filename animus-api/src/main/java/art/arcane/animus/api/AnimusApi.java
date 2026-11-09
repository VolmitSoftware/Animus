package art.arcane.animus.api;

/**
 * Stable package and explicit contract version; plugin release numbers are independent.
 * <p>
 * A major version may remove or change contracts. A minor version stays binary compatible with every earlier minor of
 * the same major, so a module built against 1.0 runs on every 1.x engine. Within a major:
 * <ul>
 *   <li>New types, and new methods on interfaces the engine implements ({@link ModuleContext}, {@link DefinitionService}
 *   and the registrars), can arrive in any minor.</li>
 *   <li>Interfaces a module implements ({@link TrustedModule}, {@link Capabilities.Action}, {@link Capabilities.Condition},
 *   {@link InteractionNotice.Listener}, {@link ImportConverters.Converter}, {@link Integrations.Protection} and
 *   {@link Integrations.Economy}) only gain {@code default} methods in a minor.</li>
 *   <li>Enums, such as {@link Integrations.ProtectionAction}, {@link Outcome.Status}, {@link Platform} and
 *   {@link Capabilities.Kind}, can gain constants in a minor. A {@code switch} over one must have a {@code default}
 *   branch, because an exhaustive switch without one throws on a constant added later.</li>
 * </ul>
 * A module that calls a newer addition either declares that minor as its minimum with {@link ApiRange#atLeast(Version)}
 * or checks {@code AnimusApi.API_VERSION.minor()} at enable time.
 */
public final class AnimusApi {
    public static final Version API_VERSION = new Version(1, 2, 0);
    private AnimusApi() {}
}
