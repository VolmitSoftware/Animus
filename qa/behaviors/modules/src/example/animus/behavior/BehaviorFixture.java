package example.animus.behavior;

import art.arcane.animus.api.*;
import example.animus.behavior.control.ProbeControl;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class BehaviorFixture implements TrustedModule {
    private static final Set<Platform> PLATFORMS=Set.of(Platform.PAPER,Platform.SPIGOT,Platform.FOLIA);
    public static final String RECORD_SCHEMA="""
        {"type":"object","properties":{
          "tag":{"type":"string","enum":["start","loop","branch-a","branch-b","after","true","false","bound"]},
          "longValue":{"type":"integer","minimum":-9223372036854775808,"maximum":9223372036854775807},
          "decimalValue":{"type":"number"},"booleanValue":{"type":"boolean"},
          "stringValue":{"type":"string","maxLength":128}
        },"required":["tag"],"additionalProperties":false}
        """;
    @Override public ModuleManifest manifest(){return new ModuleManifest("qabehavior",new Version(1,0,0),new ApiRange(1,1),PLATFORMS,List.of(new ModuleManifest.Dependency("qabprobe",new Version(1,0,0),new Version(2,0,0))));}
    private static Capabilities.Descriptor descriptor(String name,Capabilities.Kind kind,String schema){return new Capabilities.Descriptor(new Capabilities.Key("qabehavior",name),kind,"Synthetic behavior acceptance fixture",schema,PLATFORMS,false);}
    @Override public void enable(ModuleContext context) {
        context.registrations().action(descriptor("record",Capabilities.Kind.ACTION,RECORD_SCHEMA),inv->{
            ProbeControl.record(inv.npc().uuid(),"record",inv.argumentsJson());return CompletableFuture.completedFuture(inv.argumentsJson());
        });
        for(boolean value:List.of(false,true))context.registrations().condition(descriptor(String.valueOf(value),Capabilities.Kind.CONDITION,"{\"type\":\"object\",\"additionalProperties\":false}"),inv->{
            ProbeControl.record(inv.npc().uuid(),"condition", "{\"result\":"+value+"}");return CompletableFuture.completedFuture(value);
        });
        context.registrations().action(descriptor("hold",Capabilities.Kind.ACTION,"{\"type\":\"object\",\"additionalProperties\":false}"),inv->ProbeControl.hold(inv.npc().uuid(),inv.cancellation()));
        context.registrations().action(descriptor("fail",Capabilities.Kind.ACTION,"{\"type\":\"object\",\"additionalProperties\":false}"),inv->{ProbeControl.record(inv.npc().uuid(),"fail-enter","{}");throw new IllegalStateException("Intentional behavior fixture failure");});
        context.onClose(()->System.out.println("Animus behavior QA resources closed"));
        System.out.println("Animus behavior QA enabled qabehavior");
    }
    @Override public void disable(){System.out.println("Animus behavior QA disabled qabehavior");}
}
