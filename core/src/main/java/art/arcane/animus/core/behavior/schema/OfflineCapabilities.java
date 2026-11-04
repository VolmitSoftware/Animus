package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.ModuleHost;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The built-in capability registry without a server: the same {@link BuiltinCapabilities} descriptors, schemas and
 * argument checks, installed into a real {@link ModuleHost}. Nothing can run; only registration and validation are used.
 * Capabilities of other namespaces belong to Java modules that are not loaded here and are left unchecked.
 */
public final class OfflineCapabilities implements AutoCloseable {
    public static final String NO_SERVER = "offline validation has no server";
    private final BuiltinCapabilities builtins;
    private final ModuleHost host;

    public OfflineCapabilities() {
        builtins = new BuiltinCapabilities(unavailable(BehaviorPlatform.class), BehaviorState.UNAVAILABLE, choice -> { },
                BuiltinActionSettings.DEFAULT, line -> { });
        host = new ModuleHost(Platform.PAPER, unavailable(DefinitionGateway.class), 4, Duration.ofSeconds(1));
        Outcome<ModuleHost.ModuleStatus> installed = host.installBuiltin(builtins, builtins);
        if (installed.status() != Outcome.Status.OK) {
            close();
            throw new IllegalStateException("Built-in capabilities did not register: " + installed.diagnostics());
        }
    }

    /** Descriptors of every built-in capability, in registry order. */
    public List<Capabilities.Availability> capabilities() { return host.capabilities(); }

    /** Registration and argument checks of the built-ins. Platform availability and grants are not judged offline. */
    public BehaviorCatalog.CapabilityResolver resolver() {
        return new BehaviorCatalog.CapabilityResolver() {
            @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) {
                if (!builtin(requirement.key())) return BehaviorCatalog.Capability.present();
                Optional<String> reason = host.unavailableReason(requirement.key(), requirement.kind());
                if (reason.isPresent() && reason.get().startsWith("capability-not-registered"))
                    return new BehaviorCatalog.Capability(false, reason.get().length() > 200 ? reason.get().substring(0, 200) : reason.get());
                return BehaviorCatalog.Capability.present();
            }
            @Override public List<Diagnostic> validate(BehaviorCatalog.Requirement requirement, String argumentsJson) {
                return host.validateArguments(requirement.key(), requirement.kind(), argumentsJson);
            }
        };
    }

    public static boolean builtin(Capabilities.Key key) { return key.namespace().equals(BuiltinCapabilities.NAMESPACE); }

    @Override public void close() {
        builtins.close();
        host.close();
    }

    /** Stands in for platform services: reports unavailable and refuses any work. */
    private static <T> T unavailable(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, arguments) -> switch (method.getName()) {
            case "unavailable" -> Optional.of(NO_SERVER);
            case "ownerTicks" -> (AutoCloseable) () -> { };
            case "scoped" -> self;
            case "toString" -> "Offline " + type.getSimpleName();
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == arguments[0];
            default -> throw new UnsupportedOperationException(NO_SERVER);
        });
        return type.cast(proxy);
    }
}
