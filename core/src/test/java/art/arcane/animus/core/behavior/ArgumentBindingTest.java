package art.arcane.animus.core.behavior;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.behavior.BehaviorCompilerTest.*;
import static art.arcane.animus.core.behavior.BehaviorEngineTest.*;

class ArgumentBindingTest {
    private static JsonObject object(String json){try{return JsonDocuments.object(json);}catch(Exception failure){throw new AssertionError(failure);}}
    private static JsonObject variables(String type,Object value){JsonObject vars=new JsonObject(),v=new JsonObject();v.addProperty("type",type);v.add("initial",new Gson().toJsonTree(value));vars.add("value",v);return vars;}
    private static JsonObject bindings(String...pointers){JsonObject result=new JsonObject();for(String pointer:pointers)result.add(pointer,object("{\"variable\":\"value\"}"));return result;}
    private static JsonObject action(JsonObject args,JsonObject bindings){JsonObject node=object("{\"type\":\"action\",\"capability\":\"sample:bound\"}");node.add("arguments",args);node.add("bindings",bindings);return node;}
    private static BehaviorProgram program(JsonObject vars,JsonObject node){return compile(document(vars.toString(),"\"main\":"+node));}
    private static ArgumentTemplate template(JsonObject vars,JsonObject args,JsonObject bindings){return ((BehaviorProgram.Action)program(vars,action(args,bindings)).routines().get("main")).arguments();}
    private static String rejected(JsonObject vars,JsonObject args,JsonObject bindings){var result=BehaviorCompiler.compile(document(vars.toString(),"\"main\":"+action(args,bindings)));assertTrue(result.program().isEmpty());return result.diagnostics().getFirst().field();}
    private static Map<String,BehaviorProgram.Value> values(JsonObject vars){return program(vars,action(new JsonObject(),new JsonObject())).initial();}

    @Test void allTypedValuesReachNestedLeavesWithoutCoercionOrExtraVariables(){
        JsonObject vars=object("{\"flag\":{\"type\":\"boolean\",\"initial\":false},\"count\":{\"type\":\"integer\",\"initial\":9223372036854775807},\"ratio\":{\"type\":\"decimal\",\"initial\":1.25},\"label\":{\"type\":\"string\",\"initial\":\"123\"},\"secret\":{\"type\":\"string\",\"initial\":\"private\"}}");
        JsonObject args=object("{\"nested\":[null,{\"count\":null,\"ratio\":null}],\"label\":null}");
        JsonObject bound=object("{\"/nested/0\":{\"variable\":\"flag\"},\"/nested/1/count\":{\"variable\":\"count\"},\"/nested/1/ratio\":{\"variable\":\"ratio\"},\"/label\":{\"variable\":\"label\"}}");
        var template=template(vars,args,bound);JsonObject resolved=object(template.resolve(values(vars)));
        assertTrue(resolved.getAsJsonArray("nested").get(0).getAsJsonPrimitive().isBoolean());assertFalse(resolved.getAsJsonArray("nested").get(0).getAsBoolean());
        assertEquals(Long.MAX_VALUE,resolved.getAsJsonArray("nested").get(1).getAsJsonObject().get("count").getAsBigDecimal().longValueExact());
        assertEquals(1.25,resolved.getAsJsonArray("nested").get(1).getAsJsonObject().get("ratio").getAsDouble());
        assertTrue(resolved.getAsJsonPrimitive("label").isString());assertFalse(resolved.has("secret"));assertEquals(4,template.bindings());assertTrue(args.get("label").isJsonNull());
    }
    @Test void stringsEscapeSafelyAndPreserveUnicodeControlsAndExactIntegers(){
        String text="quoted \" slash \\ newline\n tab\t backspace\b formfeed\f return\r nul\0 Étoile 🐉";
        var vars=variables("string",text);String json=template(vars,object("{\"x\":null}"),bindings("/x")).resolve(values(vars));
        assertEquals(text,object(json).get("x").getAsString());assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(json));assertFalse(json.contains("\0"));
        var integer=variables("integer",Long.MIN_VALUE);assertEquals(Long.MIN_VALUE,object(template(integer,object("{\"x\":null}"),bindings("/x")).resolve(values(integer))).get("x").getAsBigDecimal().longValueExact());
    }
    @Test void pointerEscapesEmptyKeysAndObjectNumericKeysHaveExactSemantics(){
        var vars=variables("integer",7);var args=object("{\"a/b\":{\"~\":null},\"\":null,\"00\":null,\"~1\":null,\"array\":[null]}");
        var resolved=object(template(vars,args,bindings("/a~1b/~0","/","/00","/~01","/array/0")).resolve(values(vars)));
        assertEquals(7,resolved.getAsJsonObject("a/b").get("~").getAsInt());assertEquals(7,resolved.get("").getAsInt());assertEquals(7,resolved.get("00").getAsInt());assertEquals(7,resolved.get("~1").getAsInt());
    }
    @Test void malformedMissingScalarAndNoncanonicalPointersAreRejected(){
        var vars=variables("integer",1);var args=object("{\"x\":null,\"value\":1,\"array\":[null]}");
        for(String pointer:List.of("","x","/x~","/x~2","/missing","/x/child","/value","/array/-","/array/-1","/array/+0","/array/00","/array/1","/array/999999999999"))assertTrue(rejected(vars,args,bindings(pointer)).contains(".bindings"),pointer);
    }
    @Test void bindingShapeUnknownVariableAndOverlapsAreRejectedInBothOrders(){
        var vars=variables("integer",1);var args=object("{\"x\":{\"child\":null}}");
        for(String raw:List.of("null","\"value\"","{}","{\"variable\":1}","{\"variable\":\"missing\"}","{\"variable\":\"value\",\"extra\":true}")){
            JsonObject bound=new JsonObject();bound.add("/x/child",JsonParser.parseString(raw));assertTrue(rejected(vars,args,bound).contains(".bindings"));
        }
        assertTrue(rejected(vars,args,bindings("/x","/x/child")).contains(".bindings"));assertTrue(rejected(vars,args,bindings("/x/child","/x")).contains(".bindings"));
        String duplicate=document(vars.toString(),"\"main\":"+action(object("{\"x\":null}"),bindings("/x"))).replace("\"bindings\":{","\"bindings\":{\"/x\":{\"variable\":\"value\"},");
        assertTrue(BehaviorCompiler.compile(duplicate).program().isEmpty());
    }
    @Test void bindingCountAndPointerDepthHaveAcceptedAndRejectedBoundaries(){
        var vars=variables("integer",1);JsonObject args=new JsonObject(),bound=new JsonObject();
        for(int i=0;i<32;i++){args.add("v"+i,JsonNull.INSTANCE);bound.add("/v"+i,object("{\"variable\":\"value\"}"));}
        assertEquals(32,template(vars,args,bound).bindings());args.add("extra",JsonNull.INSTANCE);bound.add("/extra",object("{\"variable\":\"value\"}"));assertTrue(rejected(vars,args,bound).endsWith(".bindings"));
        for(int depth:new int[]{16,17}){
            JsonObject nested=new JsonObject(),cursor=nested;for(int i=1;i<depth;i++){JsonObject child=new JsonObject();cursor.add("a",child);cursor=child;}cursor.add("a",JsonNull.INSTANCE);
            String pointer="/a".repeat(depth);if(depth==16)assertEquals(1,template(vars,nested,bindings(pointer)).bindings());else assertTrue(rejected(vars,nested,bindings(pointer)).contains(".bindings"));
        }
    }
    @Test void pointerLimitCountsUtf8BytesAndRejectsEscapedInvalidUnicode(){
        var vars=variables("integer",1);
        for(String key:List.of("a".repeat(4095),"é".repeat(2047)+"a")){
            JsonObject args=new JsonObject();args.add(key,JsonNull.INSTANCE);assertEquals(1,template(vars,args,bindings("/"+key)).bindings());args.add(key+"x",JsonNull.INSTANCE);assertTrue(rejected(vars,args,bindings("/"+key+"x")).contains(".bindings"));
        }
        String escaped=document(vars.toString(),"\"main\":"+action(object("{\"x\":null}"),bindings("/\uD800"))).replace("\uD800","\\ud800");
        var invalid=BehaviorCompiler.compile(escaped);assertTrue(invalid.program().isEmpty());assertTrue(invalid.diagnostics().getFirst().field().contains(".bindings"));
    }
    @Test void literalObjectPayloadIsNotInterpretedAsVariableSyntaxAndTemplatesAreDetached(){
        var vars=variables("string","new");var args=object("{\"literal\":{\"variable\":\"value\"},\"x\":null}");var bound=bindings("/x");var template=template(vars,args,bound);
        args.addProperty("x","mutated");bound.remove("/x");var result=object(template.resolve(values(vars)));
        assertEquals("value",result.getAsJsonObject("literal").get("variable").getAsString());assertEquals("new",result.get("x").getAsString());
        assertThrows(ArgumentTemplate.Invalid.class,()->template.resolve(Map.of()));assertThrows(ArgumentTemplate.Invalid.class,()->template.resolve(Map.of("value",new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER,1L))));
    }
    @Test void literalOutputAndTreeBoundsAreEnforcedBeforeExecution(){
        JsonObject exact=new JsonObject();exact.addProperty("x","x".repeat(65528));var template=template(new JsonObject(),exact,new JsonObject());assertEquals(65536,template.resolve(Map.of()).getBytes(StandardCharsets.UTF_8).length);
        exact.addProperty("x","x".repeat(65529));assertTrue(rejected(new JsonObject(),exact,new JsonObject()).endsWith(".arguments"));
        JsonObject tree=new JsonObject();JsonArray array=new JsonArray();tree.add("x",array);for(int i=0;i<ArgumentTemplate.MAX_LITERAL_NODES-2;i++)array.add(0);
        template(new JsonObject(),tree,new JsonObject());array.add(0);assertTrue(rejected(new JsonObject(),tree,new JsonObject()).endsWith(".arguments"));
    }
    @Test void expandedUtf8AndEscapesAreBoundedWithoutInvokingHandler(){
        for(String value:List.of("x".repeat(4096),"é".repeat(2048),"\0".repeat(4096))){
            var vars=variables("string",value);JsonObject args=new JsonObject();String[] pointers=new String[32];for(int i=0;i<32;i++){args.add("v"+i,JsonNull.INSTANCE);pointers[i]="/v"+i;}
            var program=program(vars,action(args,bindings(pointers)));var invoker=new Fake();var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),invoker);var report=finish(engine,start(engine,program));
            assertEquals(BehaviorEngine.State.FAILED,report.state());assertTrue(report.reason().contains("arguments-invalid:$.routines.main.arguments"));assertTrue(invoker.actions.isEmpty());assertEquals(0,engine.status().callsInFlight());
        }
    }
    @Test void resolvedByteBoundaryAllowsExactlyLimitAndRejectsNextByte(){
        var vars=variables("string","x");JsonObject args=new JsonObject();args.add("x",JsonNull.INSTANCE);args.addProperty("padding","p".repeat(65513));
        var template=template(vars,args,bindings("/x"));var resolved=template.resolve(values(vars));assertEquals(65535,resolved.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(65536,template.resolve(Map.of("value",new BehaviorProgram.Value(BehaviorProgram.Type.STRING,"xx"))).getBytes(StandardCharsets.UTF_8).length);
        assertThrows(ArgumentTemplate.Invalid.class,()->template.resolve(Map.of("value",new BehaviorProgram.Value(BehaviorProgram.Type.STRING,"xxx"))));
    }
    @Test void parallelBranchesBindTheirOwnCurrentValuesBeforeConflictingMerge(){
        var vars=variables("integer",0);JsonObject action=action(object("{\"x\":null}"),bindings("/x"));
        String branch="{\"type\":\"sequence\",\"steps\":[{\"type\":\"set\",\"variable\":\"value\",\"value\":%d},"+action+"]}";
        var program=compile(document(vars.toString(),"\"main\":{\"type\":\"parallel\",\"branches\":["+branch.formatted(1)+","+branch.formatted(2)+"]}"));
        Fake invoker=new Fake();var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),invoker);var report=finish(engine,start(engine,program));
        assertEquals(List.of(1,2),invoker.actions.stream().map(i->object(i.argumentsJson()).get("x").getAsInt()).toList());assertEquals(0L,report.variables().get("value").value());assertEquals(BehaviorEngine.State.FAILED,report.state());
    }
    @Test void conditionReceivesBoundValuesAndSelectsBranchWithoutSchemaCoercion(){
        var vars=variables("boolean",true);JsonObject node=action(object("{\"enabled\":null}"),bindings("/enabled"));node.addProperty("type","condition");node.add("then",object(EMPTY));
        Fake invoker=new Fake();invoker.condition=i->new BehaviorInvoker.Call<>(java.util.concurrent.CompletableFuture.completedFuture(art.arcane.animus.api.Outcome.ok(object(i.argumentsJson()).get("enabled").getAsBoolean())),()->{});
        var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),invoker);assertEquals(BehaviorEngine.State.COMPLETED,finish(engine,start(engine,program(vars,node))).state());assertEquals(1,invoker.conditions.size());assertTrue(invoker.actions.isEmpty());
    }
    @Test void behaviorReferencesMatchNpcGrammarIncludingSlashPathsWithExplicitLengthNarrowing()throws Exception{
        for(String id:List.of("example:quests/town/greet","_pack-:/a//b/","a:"+"b".repeat(254))){
            assertEquals(id,compile(document(EMPTY).replace("example:test",id)).id());
            JsonObject npc=object("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"id\":\"test\",\"group\":\"default\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0}}");
            JsonArray refs=new JsonArray();refs.add(id);npc.add("behaviors",refs);assertTrue(NpcDefinition.validate(npc).isEmpty());
        }
        for(String id:List.of("pack:name.with.dots","Pack:name","pack:","pack:path\\name","a:"+"b".repeat(255))){var result=BehaviorCompiler.compile(document(EMPTY).replace("example:test",id.replace("\\","\\\\")));assertTrue(result.program().isEmpty());assertEquals("$.id",result.diagnostics().getFirst().field());}
        assertTrue(BehaviorCompiler.compile(document("{\"type\":\"action\",\"capability\":\"sample:path/action\"}")).program().isEmpty(),"Module capability grammar remains narrower than behavior identity");
    }
}
