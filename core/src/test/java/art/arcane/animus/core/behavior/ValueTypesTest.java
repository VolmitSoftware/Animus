package art.arcane.animus.core.behavior;

import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.behavior.BehaviorCompilerTest.*;

class ValueTypesTest {
    private static Value integer(long value){return new Value(Type.INTEGER,value);}
    private static Value text(String value){return new Value(Type.STRING,value);}
    private static Value list(Value... items){return new Value(Type.LIST,List.of(items));}
    private static JsonElement json(String text){try{return JsonDocuments.object("{\"v\":"+text+"}").get("v");}catch(java.io.IOException invalid){throw new AssertionError(invalid);}}
    private static String error(String source){var result=BehaviorCompiler.compile(source);assertTrue(result.program().isEmpty(),"expected a compile error");return result.diagnostics().getFirst().field()+" "+result.diagnostics().getFirst().message();}

    @Test void listAndMapValuesAreImmutableKeepOrderAndRefuseAny(){
        Map<String,Value> entries=new LinkedHashMap<>();entries.put("z",integer(1));entries.put("a",text("x"));
        Value map=new Value(Type.MAP,entries);entries.put("late",integer(2));
        assertEquals(List.of("z","a"),new ArrayList<>(((Map<?,?>)map.value()).keySet()));
        assertThrows(UnsupportedOperationException.class,()->((Map<?,?>)map.value()).clear());
        List<Value> items=new ArrayList<>(List.of(integer(1)));Value copied=new Value(Type.LIST,items);items.add(integer(2));
        assertEquals(1,((List<?>)copied.value()).size());
        assertThrows(IllegalArgumentException.class,()->new Value(Type.ANY,true));
        assertThrows(IllegalArgumentException.class,()->new Value(Type.LIST,List.of("raw")));
        assertThrows(IllegalArgumentException.class,()->new Value(Type.MAP,Map.of("",integer(1))));
        assertThrows(IllegalArgumentException.class,()->new Value(Type.MAP,Map.of("k".repeat(257),integer(1))));
    }
    @Test void boundsCoverItemsNestingAndCanonicalBytes(){
        List<Value> many=new ArrayList<>();for(int i=0;i<257;i++)many.add(integer(i));
        assertThrows(IllegalArgumentException.class,()->new Value(Type.LIST,many));
        assertDoesNotThrow(()->new Value(Type.LIST,many.subList(0,256)));
        Value nested=integer(0);for(int depth=0;depth<BehaviorValues.MAX_NESTING;depth++)nested=list(nested);
        Value deepest=nested;assertThrows(IllegalArgumentException.class,()->list(deepest));
        assertThrows(IllegalArgumentException.class,()->list(text("x".repeat(4094))));
        assertDoesNotThrow(()->list(text("x".repeat(4092))));
        assertDoesNotThrow(()->text("x".repeat(4096)),"strings keep their 4096 UTF-8 byte limit");
    }
    @Test void canonicalJsonKeepsDecimalsAndReadsPlainNumbers(){
        Map<String,Value> entries=new LinkedHashMap<>();entries.put("d",new Value(Type.DECIMAL,2.0));entries.put("i",integer(2));entries.put("b",new Value(Type.BOOLEAN,true));entries.put("s",text("q\"\n"));entries.put("l",list(integer(1),new Value(Type.DECIMAL,0.5)));
        Value map=new Value(Type.MAP,entries);
        assertEquals("{\"d\":2.0,\"i\":2,\"b\":true,\"s\":\"q\\\"\\n\",\"l\":[1,0.5]}",BehaviorValues.canonical(map));
        assertEquals(map,BehaviorValues.read(json(BehaviorValues.canonical(map))));
        assertEquals(new Value(Type.DECIMAL,1.0E20),BehaviorValues.read(json("1e20")));
        assertEquals(integer(-7),BehaviorValues.read(json("-7")));
        assertThrows(IllegalArgumentException.class,()->BehaviorValues.read(json("[null]")));
        assertEquals(Type.LIST,BehaviorValues.read(json("[]")).type());
    }
    @Test void listMapAndAnyDeclarationsCompileWithJsonInitials(){
        String vars="{\"names\":{\"type\":\"list\",\"initial\":[\"a\",1,2.5]},\"stats\":{\"type\":\"map\",\"initial\":{\"hp\":20}},\"loose\":{\"type\":\"any\",\"initial\":\"text\"}}";
        BehaviorProgram program=compile(document(vars,"\"main\":"+EMPTY));
        assertEquals(list(text("a"),integer(2-1),new Value(Type.DECIMAL,2.5)),program.initial().get("names"));
        assertEquals(Type.ANY,program.declared().get("loose"));assertEquals(Type.STRING,program.initial().get("loose").type());
        assertEquals(Type.MAP,program.declared().get("stats"));
        assertTrue(error(document("{\"names\":{\"type\":\"list\",\"initial\":{}}}","\"main\":"+EMPTY)).startsWith("$.variables.names.initial"));
        assertTrue(error(document("{\"x\":{\"type\":\"any\",\"initial\":null}}","\"main\":"+EMPTY)).startsWith("$.variables.x.initial"));
        assertTrue(error(document("{\"x\":{\"type\":\"tuple\",\"initial\":1}}","\"main\":"+EMPTY)).contains("boolean, integer, decimal, string, list, map or any"));
    }
    @Test void setAcceptsListAndMapLiteralsAndAnyTakesEveryVariable(){
        String vars="{\"names\":{\"type\":\"list\",\"initial\":[]},\"loose\":{\"type\":\"any\",\"initial\":0},\"count\":{\"type\":\"integer\",\"initial\":3}}";
        String main="{\"type\":\"sequence\",\"steps\":[{\"type\":\"set\",\"variable\":\"names\",\"value\":[\"x\",{\"k\":true}]},{\"type\":\"set\",\"variable\":\"loose\",\"value\":{\"variable\":\"names\"}},{\"type\":\"set\",\"variable\":\"loose\",\"value\":{\"variable\":\"count\"}}]}";
        assertNotNull(compile(document(vars,"\"main\":"+main)));
        assertTrue(error(document(vars,"\"main\":{\"type\":\"set\",\"variable\":\"count\",\"value\":{\"variable\":\"names\"}}")).contains("mismatched"));
    }
    @Test void variableBudgetIs128AndRootNamesAreReserved(){
        StringBuilder vars=new StringBuilder("{");for(int i=0;i<128;i++)vars.append(i==0?"":",").append("\"v").append(i).append("\":{\"type\":\"integer\",\"initial\":0}");
        assertNotNull(compile(document(vars+"}","\"main\":"+EMPTY)));
        assertTrue(error(document(vars+",\"v128\":{\"type\":\"integer\",\"initial\":0}}","\"main\":"+EMPTY)).contains("at most 128 variables"));
        for(String reserved:List.of("player","npc","server","context","true","false"))
            assertTrue(error(document("{\""+reserved+"\":{\"type\":\"integer\",\"initial\":0}}","\"main\":"+EMPTY)).contains("reserved"),reserved);
    }
    @Test void bindingsWriteListsAndMapsAsJsonAndAnyAsItsRuntimeType(){
        String vars="{\"names\":{\"type\":\"list\",\"initial\":[\"a\",2]},\"loose\":{\"type\":\"any\",\"initial\":{\"k\":1.5}}}";
        String action="{\"type\":\"action\",\"capability\":\"sample:greet\",\"arguments\":{\"a\":null,\"b\":null},\"bindings\":{\"/a\":{\"variable\":\"names\"},\"/b\":{\"variable\":\"loose\"}}}";
        BehaviorProgram program=compile(document(vars,"\"main\":"+action));
        assertEquals("{\"a\":[\"a\",2],\"b\":{\"k\":1.5}}",((BehaviorProgram.Action)program.routines().get("main")).arguments().resolve(program.initial()));
        Map<String,Value> changed=new HashMap<>(program.initial());changed.put("loose",new Value(Type.BOOLEAN,false));
        assertEquals("{\"a\":[\"a\",2],\"b\":false}",((BehaviorProgram.Action)program.routines().get("main")).arguments().resolve(changed));
    }
}
