package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.extension.ModuleHost;
import java.util.Objects;

/** Host handle adapter; source guards carry immutable authority, never platform/world access or privileged grants. */
public final class HostBehaviorInvoker implements BehaviorInvoker {
    private final ModuleHost host;
    public HostBehaviorInvoker(ModuleHost host){this.host=Objects.requireNonNull(host);}
    public boolean available(Capabilities.Key key,Capabilities.Kind kind){
        return host.capabilityAvailable(key,kind,false,false);
    }
    public Call<String> action(Invocation invocation){
        var handle=host.actionHandle(invocation.capability(),invocation.npc(),invocation.player(),invocation.argumentsJson(),invocation.preview(),false,invocation.sourceCurrent());
        return new Call<>(handle.result(),handle::cancel,handle::isQuiescent);
    }
    public Call<Boolean> condition(Invocation invocation){
        var handle=host.conditionHandle(invocation.capability(),invocation.npc(),invocation.player(),invocation.argumentsJson(),invocation.preview(),invocation.sourceCurrent());
        return new Call<>(handle.result(),handle::cancel,handle::isQuiescent);
    }
}
