package example.animus.behavior.control;

import art.arcane.animus.api.*;
import java.util.*;

/** Capability-free shared dependency; it remains active when an action module is suspended. */
public final class ProbeFixture implements TrustedModule {
    @Override public ModuleManifest manifest(){return new ModuleManifest("qabprobe",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER,Platform.SPIGOT,Platform.FOLIA),List.of());}
    @Override public void enable(ModuleContext context){System.out.println("Animus behavior QA enabled qabprobe");}
    @Override public void disable(){System.out.println("Animus behavior QA dependency disable releases "+ProbeControl.releaseAll());}
}
