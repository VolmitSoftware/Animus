package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.BehaviorEngine.*;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.builtin.MemoryBehaviorState;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.behavior.BehaviorCompilerTest.*;
import static art.arcane.animus.core.behavior.BehaviorEngineTest.*;

class LanguageNodesTest {
    static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final BehaviorFacts FACTS=new BehaviorFacts(){
        public Optional<Map<String,Object>> player(UUID id){return Optional.of(Map.of("name","Steve","level",5L));}
        public Optional<Map<String,Object>> npc(UUID id){return Optional.empty();}
        public Map<String,Object> server(){return Map.of();}
    };
    static String set(String variable,String value){return "{\"type\":\"set\",\"variable\":\""+variable+"\",\"value\":"+value+"}";}
    static String seq(String... steps){return "{\"type\":\"sequence\",\"steps\":["+String.join(",",steps)+"]}";}
    static String error(String source){var result=BehaviorCompiler.compile(source);assertTrue(result.program().isEmpty(),"expected a compile error");return result.diagnostics().getFirst().field()+" "+result.diagnostics().getFirst().message();}
    static Report run(Fake invoker,BehaviorState state,String vars,String routines){
        BehaviorEngine engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},state,FACTS,new Random(3));
        UUID id=engine.start(compile(document(vars,routines)),new Context(new NpcHandle(UUID.randomUUID()),Optional.of(PLAYER),false,()->true,Map.of("message","hello"))).value().orElseThrow();
        return finish(engine,id);
    }
    static Report run(String vars,String routines){return run(new Fake(),BehaviorState.UNAVAILABLE,vars,routines);}
    static Object value(Report report,String name){return report.variables().get(name).value();}
    static JsonObject arguments(BehaviorInvoker.Invocation invocation){try{return JsonDocuments.object(invocation.argumentsJson());}catch(java.io.IOException invalid){throw new AssertionError(invalid);}}

    @Test void setTakesLiteralVariableExpressionAndTemplateOperands(){
        String vars="{\"n\":{\"type\":\"integer\",\"initial\":2},\"d\":{\"type\":\"decimal\",\"initial\":0},\"s\":{\"type\":\"string\",\"initial\":\"\"},\"a\":{\"type\":\"any\",\"initial\":0},\"l\":{\"type\":\"list\",\"initial\":[]}}";
        Report report=run(vars,"\"main\":"+seq(set("n","{\"expression\":\"n * 21\"}"),set("d","{\"expression\":\"n / 4\"}"),set("s","{\"template\":\"{{ player.name }} said {{ context.message }} at {{ n }}\"}"),
                set("a","{\"variable\":\"s\"}"),set("l","{\"expression\":\"append(l, n)\"}")));
        assertEquals(State.COMPLETED,report.state(),report.reason());
        assertEquals(42L,value(report,"n"));assertEquals(10.5,value(report,"d"));assertEquals("Steve said hello at 42",value(report,"s"));assertEquals("Steve said hello at 42",value(report,"a"));
        assertEquals(List.of(new Value(Type.INTEGER,42L)),value(report,"l"));
    }
    @Test void failedExpressionsAndCoercionsEndTheRunWithTheirField(){
        String vars="{\"n\":{\"type\":\"integer\",\"initial\":2}}";
        Report fraction=run(vars,"\"main\":"+set("n","{\"expression\":\"n / 4\"}"));
        assertEquals(State.FAILED,fraction.state());assertTrue(fraction.reason().startsWith("expression-failed:$.routines.main.value.expression:"),fraction.reason());
        assertEquals("behavior-expression-type",fraction.diagnostics().getFirst().code());
        Report missing=run(vars,"\"main\":"+set("n","{\"expression\":\"get(range(1, 2), 9)\"}"));
        assertEquals("behavior-expression-failed",missing.diagnostics().getFirst().code());
        Report fact=run(vars,"\"main\":"+set("n","{\"expression\":\"npc.health\"}"));
        assertTrue(fact.reason().contains("fact-unavailable"),fact.reason());
        assertTrue(error(document(vars,"\"main\":"+set("n","{\"template\":\"x\"}"))).contains("template"));
        assertTrue(error(document(vars,"\"main\":"+set("n","{\"expression\":\"ghost\"}"))).startsWith("$.routines.main.value.expression"));
    }
    @Test void expressionConditionsMustBeBooleanAndCannotMixCapabilities(){
        String vars="{\"n\":{\"type\":\"integer\",\"initial\":7},\"r\":{\"type\":\"string\",\"initial\":\"\"}}";
        String condition="{\"type\":\"condition\",\"expression\":\"n > 5 && same(player.name, 'steve')\",\"then\":"+set("r","\"big\"")+",\"else\":"+set("r","\"small\"")+"}";
        assertEquals("big",value(run(vars,"\"main\":"+condition),"r"));
        Report text=run(vars,"\"main\":"+condition.replace("n > 5 && same(player.name, 'steve')","'true'"));
        assertEquals(State.FAILED,text.state());assertEquals("behavior-expression-type",text.diagnostics().getFirst().code());
        assertTrue(error(document(vars,"\"main\":"+condition.replace("\"expression\"","\"capability\":\"sample:x\",\"expression\""))).contains("capability"));
    }
    @Test void actionExpressionsAndTemplatesFillPlaceholdersAsCanonicalJson(){
        Fake invoker=new Fake();String vars="{\"n\":{\"type\":\"integer\",\"initial\":3}}";
        String action="{\"type\":\"action\",\"capability\":\"sample:greet\",\"arguments\":{\"count\":null,\"half\":null,\"items\":null,\"text\":null},\"expressions\":{\"/count\":\"n * 2\",\"/half\":\"n / 2\",\"/items\":\"range(1, n)\"},\"templates\":{\"/text\":\"Hi {{ player.name }}\"}}";
        assertEquals(State.COMPLETED,run(invoker,BehaviorState.UNAVAILABLE,vars,"\"main\":"+action).state());
        assertEquals("{\"count\":6,\"half\":1.5,\"items\":[1,2,3],\"text\":\"Hi Steve\"}",invoker.actions.getFirst().argumentsJson());
        assertTrue(error(document(vars,"\"main\":"+action.replace("\"/text\":\"Hi","\"/count\":\"Hi"))).contains("overlapping"));
        assertTrue(error(document(vars,"\"main\":"+action.replace("\"text\":null","\"text\":\"set\""))).contains("null placeholder"));
    }
    @Test void computedRepeatRunsIndexedPassesWithinItsMaximum(){
        String vars="{\"n\":{\"type\":\"integer\",\"initial\":4},\"i\":{\"type\":\"integer\",\"initial\":-1},\"sum\":{\"type\":\"integer\",\"initial\":0}}";
        String loop="{\"type\":\"repeat\",\"count\":{\"variable\":\"n\"},\"index\":\"i\",\"body\":"+set("sum","{\"expression\":\"sum + i\"}")+"}";
        Report report=run(vars,"\"main\":"+loop);
        assertEquals(6L,value(report,"sum"));assertEquals(3L,value(report,"i"));
        assertEquals(0L,value(run(vars.replace("\"initial\":4","\"initial\":-3"),"\"main\":"+loop),"sum"));
        Report bound=run(vars.replace("\"initial\":4","\"initial\":200"),"\"main\":"+loop);
        assertEquals(State.FAILED,bound.state());assertEquals("behavior-loop-bound",bound.diagnostics().getFirst().code());
        assertEquals(199L*200/2,value(run(vars.replace("\"initial\":4","\"initial\":200"),"\"main\":"+loop.replace("\"index\"","\"maximum\":200,\"index\"")),"sum"));
        assertTrue(error(document(vars,"\"main\":"+loop.replace("\"index\":\"i\"","\"index\":\"missing\""))).contains("index"));
    }
    @Test void foreachWalksListsAndMapsWithIndexAndKey(){
        String vars="{\"items\":{\"type\":\"list\",\"initial\":[\"a\",\"b\",\"c\"]},\"stats\":{\"type\":\"map\",\"initial\":{\"hp\":20,\"mp\":5}},\"item\":{\"type\":\"any\",\"initial\":0},\"i\":{\"type\":\"integer\",\"initial\":0},\"k\":{\"type\":\"string\",\"initial\":\"\"},\"out\":{\"type\":\"string\",\"initial\":\"\"}}";
        String list="{\"type\":\"foreach\",\"list\":{\"variable\":\"items\"},\"as\":\"item\",\"index\":\"i\",\"body\":"+set("out","{\"template\":\"{{ out }}{{ i }}{{ item }}\"}")+"}";
        assertEquals("0a1b2c",value(run(vars,"\"main\":"+list),"out"));
        String map="{\"type\":\"foreach\",\"list\":{\"expression\":\"stats\"},\"as\":\"item\",\"key\":\"k\",\"body\":"+set("out","{\"template\":\"{{ out }}{{ k }}={{ item }};\"}")+"}";
        assertEquals("hp=20;mp=5;",value(run(vars,"\"main\":"+map),"out"));
        Report bound=run(vars,"\"main\":"+list.replace("\"as\"","\"maximum\":2,\"as\""));
        assertEquals("behavior-loop-bound",bound.diagnostics().getFirst().code());
        Report notList=run(vars,"\"main\":"+list.replace("{\"variable\":\"items\"}","{\"expression\":\"5\"}"));
        assertEquals("behavior-expression-type",notList.diagnostics().getFirst().code());
    }
    @Test void whileLoopsStopOnFalseAndFailPastTheirMaximum(){
        String vars="{\"n\":{\"type\":\"integer\",\"initial\":0}}";
        String loop="{\"type\":\"while\",\"expression\":\"n < 10\",\"body\":"+set("n","{\"expression\":\"n + 1\"}")+"}";
        assertEquals(10L,value(run(vars,"\"main\":"+loop),"n"));
        Report bound=run(vars,"\"main\":"+loop.replace("\"body\"","\"maximum\":5,\"body\""));
        assertEquals("behavior-loop-bound",bound.diagnostics().getFirst().code());assertEquals(5L,value(bound,"n"));
        assertEquals(State.FAILED,run(vars,"\"main\":"+loop.replace("n < 10","1")).state());
    }
    @Test void breakAndContinueAffectOnlyTheInnermostLoop(){
        String vars="{\"i\":{\"type\":\"integer\",\"initial\":0},\"j\":{\"type\":\"integer\",\"initial\":0},\"out\":{\"type\":\"string\",\"initial\":\"\"}}";
        String inner="{\"type\":\"repeat\",\"count\":5,\"index\":\"j\",\"body\":"+seq("{\"type\":\"condition\",\"expression\":\"j == 1\",\"then\":{\"type\":\"continue\"}}","{\"type\":\"condition\",\"expression\":\"j == 3\",\"then\":{\"type\":\"break\"}}",set("out","{\"template\":\"{{ out }}{{ i }}{{ j }} \"}"))+"}";
        String outer="{\"type\":\"repeat\",\"count\":2,\"index\":\"i\",\"body\":"+inner+"}";
        assertEquals("00 02 10 12 ",value(run(vars,"\"main\":"+outer),"out"));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"break\"}")).contains("break"));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"repeat\",\"count\":2,\"body\":{\"type\":\"parallel\",\"branches\":[{\"type\":\"continue\"}]}}")).contains("continue"));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"repeat\",\"count\":2,\"body\":{\"type\":\"call\",\"routine\":\"other\"}},\"other\":{\"type\":\"break\"}")).startsWith("$.routines.other"));
        assertNotNull(compile(document(vars,"\"main\":{\"type\":\"parallel\",\"branches\":[{\"type\":\"repeat\",\"count\":2,\"body\":{\"type\":\"break\"}}]}")));
    }
    @Test void callArgumentsResultAndReturnPassValuesBetweenRoutines(){
        String vars="{\"x\":{\"type\":\"integer\",\"initial\":0},\"r\":{\"type\":\"string\",\"initial\":\"none\"},\"after\":{\"type\":\"boolean\",\"initial\":false}}";
        String main=seq("{\"type\":\"call\",\"routine\":\"double\",\"arguments\":{\"x\":{\"expression\":\"20 + 1\"}},\"result\":\"r\"}");
        String twice=seq("{\"type\":\"return\",\"value\":{\"expression\":\"x * 2\"}}",set("after","true"));
        Report report=run(vars,"\"main\":"+main+",\"double\":"+twice);
        assertEquals(State.COMPLETED,report.state(),report.reason());assertEquals(21L,value(report,"x"));assertEquals("42",value(report,"r"));assertEquals(false,value(report,"after"));
        assertEquals("none",value(run(vars,"\"main\":"+main+",\"double\":{\"type\":\"return\"}"),"r"));
        Report early=run(vars,"\"main\":"+seq("{\"type\":\"repeat\",\"count\":3,\"body\":{\"type\":\"return\"}}",set("after","true")));
        assertEquals(State.COMPLETED,early.state());assertEquals(false,value(early,"after"));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"parallel\",\"branches\":[{\"type\":\"return\"}]}")).contains("return"));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"call\",\"routine\":\"main\",\"result\":\"ghost\"}")).contains("unknown declared variable"));
    }
    @Test void expressionsReadFlagsAndQuestsOfTheBehaviorNamespace(){
        MemoryBehaviorState state=new MemoryBehaviorState();
        BehaviorState.Subject flags=BehaviorFlags.subject(BehaviorFlags.FLAGS,"example:test",UUID.randomUUID(),Optional.of(PLAYER));
        state.write(flags,Map.of("met",new Value(Type.INTEGER,3L)),Map.of("met",BehaviorFlags.declaration("player")));
        String vars="{\"r\":{\"type\":\"any\",\"initial\":0}}";
        assertEquals(4L,value(run(new Fake(),state,vars,"\"main\":"+set("r","{\"expression\":\"flag('player', 'met') + 1\"}")),"r"));
        assertEquals("no",value(run(new Fake(),state,vars,"\"main\":"+set("r","{\"expression\":\"has_flag('global', 'met') ? 'yes' : 'no'\"}")),"r"));
        assertEquals(0L,value(run(new Fake(),state,vars,"\"main\":"+set("r","{\"expression\":\"quest('relationship', 'intro')\"}")),"r"));
    }
    @Test void existingConstructorsKeepAnEmptyEventAndNoFacts(){
        Context context=new Context(new NpcHandle(UUID.randomUUID()),Optional.empty(),true);
        assertEquals(Map.of(),context.event());
        assertThrows(IllegalArgumentException.class,()->new Context(context.npc(),Optional.empty(),true,()->true,Map.of("x",new Object())));
        BehaviorEngine engine=new BehaviorEngine(Limits.defaults(),new Fake(),ignored->{},BehaviorState.UNAVAILABLE);
        UUID id=engine.start(compile(document("{\"s\":{\"type\":\"string\",\"initial\":\"\"}}","\"main\":"+set("s","{\"expression\":\"player.name\"}"))),context).value().orElseThrow();
        assertTrue(finish(engine,id).reason().contains("fact-unavailable"));
    }
}
