package art.arcane.animus.core.behavior;

import com.google.gson.*;
import art.arcane.animus.core.storage.JsonDocuments;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorCompilerTest {
    static String document(String node){return document("{}","\"main\":"+node);}
    static String document(String variables,String routines){return "{\"schemaVersion\":1,\"id\":\"example:test\",\"entry\":\"main\",\"variables\":"+variables+",\"routines\":{"+routines+"}}";}
    static final String EMPTY="{\"type\":\"sequence\",\"steps\":[]}";
    static BehaviorProgram compile(String source){var result=BehaviorCompiler.compile(source);assertTrue(result.program().isPresent(),result.diagnostics().toString());return result.program().orElseThrow();}
    private static String error(String source){var result=BehaviorCompiler.compile(source);assertTrue(result.program().isEmpty());assertFalse(result.diagnostics().isEmpty());return result.diagnostics().getFirst().field();}
    @Test void strictInputRejectsDuplicateKeysTrailingDataAndMalformedUtf8(){
        error(document(EMPTY).replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"));
        error(document(EMPTY)+" {}");error("\uD800");error(" ".repeat(BehaviorCompiler.MAX_BYTES+1));
    }
    @Test void schemaAndControlCountsRequireActualIntegers(){
        assertEquals("$.schemaVersion",error(document(EMPTY).replace("\"schemaVersion\":1","\"schemaVersion\":[1]")));
        for(String count:new String[]{"1.5","129","\"2\"","-1"})assertTrue(error(document("{\"type\":\"repeat\",\"count\":"+count+",\"body\":"+EMPTY+"}")).endsWith(".count"));
        assertTrue(error(document("{\"type\":\"wait\",\"ticks\":0}")).endsWith(".ticks"));
    }
    @Test void declaredPersistenceAndUnsupportedFieldsAreExplicitDiagnostics(){
        assertEquals("$.variables.count.scope",error(document("{\"count\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\"}}","\"main\":"+EMPTY)));
        assertTrue(error(document("{\"type\":\"wait\",\"ticks\":2,\"durable\":true}")).endsWith(".durable"));
        assertTrue(error(document("{\"type\":\"console-command\",\"command\":\"say hidden\"}")).endsWith(".type"));
    }
    @Test void valuesAndAssignmentsAreStrictlyTyped(){
        String vars="{\"count\":{\"type\":\"integer\",\"initial\":0},\"text\":{\"type\":\"string\",\"initial\":\"a\"}}";
        assertTrue(error(document(vars,"\"main\":{\"type\":\"set\",\"variable\":\"count\",\"value\":1.5}")).endsWith(".value"));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"set\",\"variable\":\"count\",\"value\":{\"variable\":\"text\"}}")).endsWith(".value.variable"));
        assertEquals("$.variables.count.initial",error(document("{\"count\":{\"type\":\"integer\",\"initial\":9223372036854775808}}","\"main\":"+EMPTY)));
        assertEquals("$.variables.count.initial",error(document("{\"count\":{\"type\":\"decimal\",\"initial\":1e1000}}","\"main\":"+EMPTY)));
    }
    @Test void referencesAndCapabilityIdentifiersAreValidatedWithoutExecution(){
        assertTrue(error(document("{\"type\":\"call\",\"routine\":\"missing\"}")).endsWith(".routine"));
        assertTrue(error(document("{\"type\":\"action\",\"capability\":\"bad.namespace\"}")).endsWith(".capability"));
        compile(document("{\"type\":\"call\",\"routine\":\"main\"}")); // recursion is execution-budgeted, not silently unbounded
    }
    @Test void nodeBudgetBoundsManySmallRoutines(){
        JsonObject root=new JsonObject();root.addProperty("schemaVersion",1);root.addProperty("id","example:large");root.addProperty("entry","main");JsonObject routines=new JsonObject();root.add("routines",routines);
        for(int routine=0;routine<32;routine++){JsonObject sequence=new JsonObject();sequence.addProperty("type","sequence");JsonArray steps=new JsonArray();sequence.add("steps",steps);for(int step=0;step<128;step++)steps.add(new JsonObject());routines.add(routine==0?"main":"r"+routine,sequence);}
        for(var routine:routines.entrySet())for(var step:routine.getValue().getAsJsonObject().getAsJsonArray("steps")){step.getAsJsonObject().addProperty("type","wait");step.getAsJsonObject().addProperty("ticks",1);}
        var result=BehaviorCompiler.compile(root.toString());assertTrue(result.program().isEmpty());assertTrue(result.diagnostics().getFirst().message().contains("2048"));
    }
    @Test void nestingAndParallelBranchCountAreBounded(){
        String nested=EMPTY;for(int i=0;i<34;i++)nested="{\"type\":\"repeat\",\"count\":1,\"body\":"+nested+"}";error(document(nested));
        assertTrue(error(document("{\"type\":\"parallel\",\"branches\":["+String.join(",",java.util.Collections.nCopies(9,EMPTY))+"]}")).endsWith(".branches"));
    }
    @Test void compiledCollectionsAndTypedValuesAreImmutable(){
        var program=compile(document("{\"name\":{\"type\":\"string\",\"initial\":\"Guard\"}}","\"main\":"+EMPTY));
        assertThrows(UnsupportedOperationException.class,()->program.initial().clear());assertThrows(UnsupportedOperationException.class,()->program.routines().clear());
        assertThrows(UnsupportedOperationException.class,()->((BehaviorProgram.Sequence)program.routines().get("main")).steps().clear());
        assertThrows(IllegalArgumentException.class,()->new BehaviorProgram.Value(BehaviorProgram.Type.DECIMAL,Double.NaN));
    }
    @Test void escapedInvalidUnicodeCannotPassArgumentsOrBreakFieldDiagnostics(){
        String escaped="\\"+"ud800";
        assertTrue(error(document("{\"type\":\"action\",\"capability\":\"sample:test\",\"arguments\":{\"value\":\""+escaped+"\"}}")).endsWith(".arguments"));
        String path=error(document("{\"type\":\"wait\",\"ticks\":1,\""+escaped+"\":true}"));
        assertTrue(path.contains("\\ud800"));assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(path));
    }
}
