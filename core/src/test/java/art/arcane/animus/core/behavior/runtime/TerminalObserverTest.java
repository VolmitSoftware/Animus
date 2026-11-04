package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalObserverTest {
    @Test void observerSeesEveryTerminalReportBeforeSmallHistoryEvictsIt(){
        var limits=new BehaviorEngine.Limits(4,8,4,32,100,100,4,8,1);List<BehaviorEngine.Report> reports=new ArrayList<>();
        var engine=new BehaviorEngine(limits,new ManualBehaviorRuntimeTest.Fake(),reports::add);
        var program=ManualBehaviorRuntimeTest.program(ManualBehaviorRuntimeTest.EMPTY);
        for(int i=0;i<4;i++)assertEquals(Outcome.Status.OK,engine.start(program,new BehaviorEngine.Context(new NpcHandle(UUID.randomUUID()),Optional.empty(),false)).status());
        engine.tick();assertEquals(4,reports.size());assertTrue(reports.stream().allMatch(r->r.state()==BehaviorEngine.State.COMPLETED));assertEquals(1,engine.status().history());assertEquals(0,engine.status().activeExecutions());
    }
    @Test void throwingObserverIsCountedAndCannotReenterStartOrTick(){
        AtomicReference<BehaviorEngine> reference=new AtomicReference<>();AtomicInteger visits=new AtomicInteger();var program=ManualBehaviorRuntimeTest.program(ManualBehaviorRuntimeTest.EMPTY);
        var context=new BehaviorEngine.Context(new NpcHandle(UUID.randomUUID()),Optional.empty(),false);
        var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),new ManualBehaviorRuntimeTest.Fake(),report->{
            visits.incrementAndGet();assertEquals(Outcome.Status.BUSY,reference.get().start(program,context).status());assertThrows(IllegalStateException.class,()->reference.get().tick());
            assertEquals(report,reference.get().inspect(report.id()).orElseThrow());throw new AssertionError("synthetic observer failure");
        });reference.set(engine);engine.start(program,context);engine.tick();assertEquals(1,visits.get());assertEquals(1,engine.status().terminalObserverFailures());assertEquals(0,engine.status().activeExecutions());engine.close();
    }
}
