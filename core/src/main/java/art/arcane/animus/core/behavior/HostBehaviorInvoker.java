package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.text.Placeholders;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Host handle adapter. Privileged authority comes only from the allow-list, keyed by the accepted program's identity, and
 * text interpolated into a privileged capability's arguments stays in the safe set unless the behavior has a raw text grant.
 * Text interpolated into what players read cannot carry a placeholder.
 */
public final class HostBehaviorInvoker implements BehaviorInvoker {
    private final ModuleHost host;
    private final Predicate<String> privilegedGrants;
    private final Predicate<String> rawTextGrants;

    public HostBehaviorInvoker(ModuleHost host){this(host,behavior->false,behavior->false);}
    public HostBehaviorInvoker(ModuleHost host,Predicate<String> privilegedGrants,Predicate<String> rawTextGrants){
        this.host=Objects.requireNonNull(host);this.privilegedGrants=Objects.requireNonNull(privilegedGrants);this.rawTextGrants=Objects.requireNonNull(rawTextGrants);
    }
    @Override public boolean available(Capabilities.Key key,Capabilities.Kind kind){
        return host.capabilityAvailable(key,kind,false,false);
    }
    @Override public boolean available(Capabilities.Key key,Capabilities.Kind kind,String behavior){
        return host.capabilityAvailable(key,kind,false,privilegedGrants.test(behavior));
    }
    @Override public ArgumentTemplate.Holes holes(Capabilities.Key key,String behavior){
        if(host.privileged(key))return rawTextGrants.test(behavior)?ArgumentTemplate.Holes.RAW:ArgumentTemplate.Holes.SAFE;
        return Placeholders.FILLED_CAPABILITIES.contains(key.toString())?ArgumentTemplate.Holes.PLAIN:ArgumentTemplate.Holes.RAW;
    }
    @Override public Call<String> action(Invocation invocation){
        ModuleHost.InvocationHandle<String> handle=host.actionHandle(invocation.capability(),invocation.npc(),invocation.player(),invocation.argumentsJson(),
                invocation.preview(),privilegedGrants.test(invocation.behavior()),invocation.sourceCurrent(),Optional.of(invocation.behavior()));
        return new Call<>(handle.result(),handle::cancel,handle::isQuiescent);
    }
    @Override public Call<Boolean> condition(Invocation invocation){
        ModuleHost.InvocationHandle<Boolean> handle=host.conditionHandle(invocation.capability(),invocation.npc(),invocation.player(),invocation.argumentsJson(),
                invocation.preview(),invocation.sourceCurrent(),Optional.of(invocation.behavior()));
        return new Call<>(handle.result(),handle::cancel,handle::isQuiescent);
    }
}
