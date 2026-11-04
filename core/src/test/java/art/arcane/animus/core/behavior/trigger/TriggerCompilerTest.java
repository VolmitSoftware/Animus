package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class TriggerCompilerTest {
    private static final String ROUTINES="\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]},\"greet\":{\"type\":\"sequence\",\"steps\":[]}}";
    private static String document(String triggers){return "{\"schemaVersion\":1,\"id\":\"example:greeter\",\"entry\":\"main\","+ROUTINES+",\"triggers\":"+triggers+"}";}

    @Test void compilesEveryEventKindIntoItsTypedBinding(){
        BehaviorCompiler.Result result=BehaviorCompiler.compile(document("""
                [{"id":"click","event":"interaction","routine":"greet","cooldownTicks":40},
                 {"id":"hit","event":"interaction","action":"attack"},
                 {"id":"near","event":"proximity","phase":"enter","radius":8.5},
                 {"id":"far","event":"proximity","phase":"leave","radius":8},
                 {"id":"born","event":"spawn"},{"id":"gone","event":"despawn"},
                 {"id":"hurt","event":"damage"},{"id":"dead","event":"death"},
                 {"id":"any-stop","event":"waypoint"},{"id":"stop-three","event":"waypoint","waypoint":3},
                 {"id":"clock","event":"timer","intervalTicks":200},
                 {"id":"answer","event":"dialogue_choice","dialogue":"intro","choice":"yes"},
                 {"id":"door","event":"module","key":"sample:door_open"}]"""));
        assertEquals(List.of(),result.diagnostics());
        List<TriggerBinding> triggers=result.program().orElseThrow().triggers();
        assertEquals(13,triggers.size());
        assertEquals(new TriggerBinding.Interaction(TriggerBinding.InteractionFilter.INTERACT),triggers.get(0).spec());
        assertEquals("greet",triggers.get(0).routine().orElseThrow());
        assertEquals(OptionalInt.of(40),triggers.get(0).cooldownTicks());
        assertEquals(new TriggerBinding.Interaction(TriggerBinding.InteractionFilter.ATTACK),triggers.get(1).spec());
        assertEquals(new TriggerBinding.Proximity(TriggerBinding.Phase.ENTER,8.5),triggers.get(2).spec());
        assertEquals(new TriggerBinding.Proximity(TriggerBinding.Phase.LEAVE,8),triggers.get(3).spec());
        assertEquals(new TriggerBinding.Lifecycle(TriggerKind.SPAWN),triggers.get(4).spec());
        assertEquals(new TriggerBinding.Lifecycle(TriggerKind.DEATH),triggers.get(7).spec());
        assertEquals(new TriggerBinding.Waypoint(OptionalInt.empty()),triggers.get(8).spec());
        assertEquals(new TriggerBinding.Waypoint(OptionalInt.of(3)),triggers.get(9).spec());
        assertEquals(new TriggerBinding.Timer(200),triggers.get(10).spec());
        assertEquals("dialogue_choice",triggers.get(11).spec().kind().jsonName());
        assertEquals(new TriggerBinding.ModuleEvent(new Capabilities.Key("sample","door_open")),triggers.get(12).spec());
    }
    @Test void documentsWithoutTriggersCompileWithNoBindings(){
        BehaviorProgram program=BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"example:plain\",\"entry\":\"main\","+ROUTINES+"}").program().orElseThrow();
        assertEquals(List.of(),program.triggers());
    }
    record Rejected(String triggers,String field){@Override public String toString(){return field+" <- "+triggers;}}
    static Stream<Rejected> rejected(){
        StringJoiner tooMany=new StringJoiner(",","[","]");for(int i=0;i<33;i++)tooMany.add("{\"id\":\"t"+i+"\",\"event\":\"spawn\"}");
        return Stream.of(
                new Rejected("{}","$.triggers"),
                new Rejected(tooMany.toString(),"$.triggers"),
                new Rejected("[1]","$.triggers[0]"),
                new Rejected("[{\"event\":\"spawn\"}]","$.triggers[0].id"),
                new Rejected("[{\"id\":\"Bad Id\",\"event\":\"spawn\"}]","$.triggers[0].id"),
                new Rejected("[{\"id\":\"a\",\"event\":\"spawn\"},{\"id\":\"a\",\"event\":\"death\"}]","$.triggers[1].id"),
                new Rejected("[{\"id\":\"a\"}]","$.triggers[0].event"),
                new Rejected("[{\"id\":\"a\",\"event\":\"nope\"}]","$.triggers[0].event"),
                new Rejected("[{\"id\":\"a\",\"event\":\"spawn\",\"extra\":1}]","$.triggers[0].extra"),
                new Rejected("[{\"id\":\"a\",\"event\":\"spawn\",\"radius\":1}]","$.triggers[0].radius"),
                new Rejected("[{\"id\":\"a\",\"event\":\"spawn\",\"routine\":\"missing\"}]","$.triggers[0].routine"),
                new Rejected("[{\"id\":\"a\",\"event\":\"spawn\",\"cooldownTicks\":-1}]","$.triggers[0].cooldownTicks"),
                new Rejected("[{\"id\":\"a\",\"event\":\"interaction\",\"action\":\"poke\"}]","$.triggers[0].action"),
                new Rejected("[{\"id\":\"a\",\"event\":\"proximity\",\"radius\":8}]","$.triggers[0].phase"),
                new Rejected("[{\"id\":\"a\",\"event\":\"proximity\",\"phase\":\"near\",\"radius\":8}]","$.triggers[0].phase"),
                new Rejected("[{\"id\":\"a\",\"event\":\"proximity\",\"phase\":\"enter\"}]","$.triggers[0].radius"),
                new Rejected("[{\"id\":\"a\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":0}]","$.triggers[0].radius"),
                new Rejected("[{\"id\":\"a\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":64.5}]","$.triggers[0].radius"),
                new Rejected("[{\"id\":\"a\",\"event\":\"waypoint\",\"waypoint\":-2}]","$.triggers[0].waypoint"),
                new Rejected("[{\"id\":\"a\",\"event\":\"timer\"}]","$.triggers[0].intervalTicks"),
                new Rejected("[{\"id\":\"a\",\"event\":\"timer\",\"intervalTicks\":0}]","$.triggers[0].intervalTicks"),
                new Rejected("[{\"id\":\"a\",\"event\":\"timer\",\"intervalTicks\":72001}]","$.triggers[0].intervalTicks"),
                new Rejected("[{\"id\":\"a\",\"event\":\"dialogue_choice\"}]","$.triggers[0].dialogue"),
                new Rejected("[{\"id\":\"a\",\"event\":\"dialogue_choice\",\"dialogue\":\"Bad Id\"}]","$.triggers[0].dialogue"),
                new Rejected("[{\"id\":\"a\",\"event\":\"dialogue_choice\",\"dialogue\":\"d\",\"choice\":\"Bad Id\"}]","$.triggers[0].choice"),
                new Rejected("[{\"id\":\"a\",\"event\":\"module\"}]","$.triggers[0].key"),
                new Rejected("[{\"id\":\"a\",\"event\":\"module\",\"key\":\"nonamespace\"}]","$.triggers[0].key"));
    }
    @ParameterizedTest @MethodSource("rejected") void reportsTheExactInvalidField(Rejected rejected){
        BehaviorCompiler.Result result=BehaviorCompiler.compile(document(rejected.triggers()));
        assertTrue(result.program().isEmpty());
        Diagnostic diagnostic=result.diagnostics().getFirst();
        assertEquals(rejected.field(),diagnostic.field());
        assertEquals("behavior-invalid",diagnostic.code());
    }
    @Test void catalogRequiresTheModuleTriggerCapabilityAndReportsItsField(@TempDir Path root)throws Exception{
        Path path=root.resolve("behaviors/example/greeter.json");Files.createDirectories(path.getParent());
        Files.writeString(path,"{\"schemaVersion\":1,\"revision\":1,\"id\":\"example:greeter\",\"entry\":\"main\","+ROUTINES+",\"triggers\":[{\"id\":\"door\",\"event\":\"module\",\"key\":\"sample:door_open\"}]}");
        List<BehaviorCatalog.Requirement> seen=new CopyOnWriteArrayList<>();
        BehaviorCatalog catalog=new BehaviorCatalog(root,BehaviorCatalog.Limits.defaults(),requirement->{seen.add(requirement);return new BehaviorCatalog.Capability(false,"module absent");});
        BehaviorCatalog.Snapshot snapshot=catalog.reload(0,Set.of()).snapshot();
        assertEquals(List.of(new BehaviorCatalog.Requirement(new Capabilities.Key("sample","door_open"),Capabilities.Kind.TRIGGER)),seen);
        BehaviorCatalog.Resolution resolution=snapshot.find("example:greeter").orElseThrow();
        assertFalse(resolution.available());
        assertEquals("$.triggers",resolution.diagnostics().getFirst().field());
    }
}
