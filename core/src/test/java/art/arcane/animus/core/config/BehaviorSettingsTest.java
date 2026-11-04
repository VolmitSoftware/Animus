package art.arcane.animus.core.config;

import art.arcane.animus.core.*;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorSettingsTest {
    @TempDir Path root;
    record Budget(String table,String field,long minimum,long maximum,ToLongFunction<BehaviorSettings> value){
        @Override public String toString(){return table+"."+field;}
    }
    static Stream<Budget> budgets(){return Stream.of(
            new Budget("behaviors","maximumOperations",1,256,BehaviorSettings::maximumOperations),
            new Budget("behaviors","operationsPerTick",1,64,BehaviorSettings::operationsPerTick),
            new Budget("behaviors","tickMillis",50,1000,BehaviorSettings::tickMillis),
            new Budget("behaviors","deliveryBudgetMillis",1,60000,BehaviorSettings::deliveryBudgetMillis),
            new Budget("behaviors.engine","executions",1,128,s->s.engine().executions()),
            new Budget("behaviors.engine","fibers",1,512,s->s.engine().fibers()),
            new Budget("behaviors.engine","inFlight",1,64,s->s.engine().inFlight()),
            new Budget("behaviors.engine","workPerTick",1,4096,s->s.engine().workPerTick()),
            new Budget("behaviors.engine","totalSteps",1,1000000,s->s.engine().totalSteps()),
            new Budget("behaviors.engine","lifetimeTicks",1,72000,s->s.engine().lifetimeTicks()),
            new Budget("behaviors.engine","callDepth",1,32,s->s.engine().callDepth()),
            new Budget("behaviors.engine","traceEntries",1,256,s->s.engine().traceEntries()),
            new Budget("behaviors.engine","historyEntries",1,256,s->s.engine().historyEntries()),
            new Budget("behaviors.catalog","maximumDocuments",1,4096,s->s.catalog().maximumDocuments()),
            new Budget("behaviors.catalog","maximumEntries",1,32768,s->s.catalog().maximumEntries()),
            new Budget("behaviors.catalog","maximumDepth",2,16,s->s.catalog().maximumDepth()),
            new Budget("behaviors.catalog","maximumDocumentBytes",1,262144,s->s.catalog().maximumDocumentBytes()),
            new Budget("behaviors.catalog","maximumAggregateBytes",1,67108864,s->s.catalog().maximumAggregateBytes()),
            new Budget("behaviors.catalog","maximumCapabilityChecks",1,16384,s->s.catalog().maximumCapabilityChecks()));}
    private static String config(Budget budget,String literal){
        Map<String,String> coordinator=new LinkedHashMap<>();coordinator.put("maximumOperations","256");coordinator.put("operationsPerTick","1");
        if(budget.table().equals("behaviors"))coordinator.put(budget.field(),literal);
        StringBuilder source=new StringBuilder("# Exact operator text\nstatusMessage = 'Test'\n[behaviors]\n");coordinator.forEach((key,value)->source.append(key).append(" = ").append(value).append('\n'));
        if(!budget.table().equals("behaviors"))source.append('[').append(budget.table()).append("]\n").append(budget.field()).append(" = ").append(literal).append('\n');
        return source.toString();
    }
    @Test void newDefaultsGenerateNestedTomlAndUseActualEngineAndCatalogDefaults()throws Exception{
        try(DocumentStore store=new DocumentStore(root)){
            var settings=PluginSettings.load(store);assertEquals(BehaviorSettings.DEFAULT,settings.behaviors());assertEquals(BehaviorEngine.Limits.defaults(),settings.behaviors().engine());assertEquals(BehaviorCatalog.Limits.defaults(),settings.behaviors().catalog());
            String source=Files.readString(root.resolve("config.toml"));assertTrue(source.contains("[behaviors]"));assertTrue(source.contains("[behaviors.engine]"));assertTrue(source.contains("[behaviors.catalog]"));assertFalse(Files.exists(root.resolve("config.json")));assertFalse(Files.exists(root.resolve("behaviors")));
            assertEquals(settings,PluginSettings.load(store));assertEquals(source,Files.readString(root.resolve("config.toml")));
        }
    }
    @ParameterizedTest @MethodSource("budgets")
    void acceptedEndpointsMapToTheirActualRuntimeLimitAndOutOfRangeFilesStayUntouched(Budget budget)throws Exception{
        try(DocumentStore store=new DocumentStore(root)){
            for(long value:new long[]{budget.minimum(),budget.maximum()}){
                String source=config(budget,Long.toString(value));Files.writeString(root.resolve("config.toml"),source);var settings=PluginSettings.load(store).behaviors();assertEquals(value,budget.value().applyAsLong(settings));assertEquals(source,Files.readString(root.resolve("config.toml")));
            }
            for(long value:new long[]{budget.minimum()-1,budget.maximum()+1}){
                String source=config(budget,Long.toString(value));Files.writeString(root.resolve("config.toml"),source);IOException failure=assertThrows(IOException.class,()->PluginSettings.load(store));assertTrue(failure.getMessage().contains("behaviors"));assertTrue(failure.getMessage().contains(budget.field()),failure.getMessage());assertEquals(source,Files.readString(root.resolve("config.toml")));
            }
        }
    }
    @Test void partialExistingSettingsPreserveCommentsUnknownDataAndJsonBehaviorSources()throws Exception{
        String source="# Administrator comments\nstatusMessage = 'Configured'\n[behaviors]\nenabled = false\nmaximumOperations = 5\noperationsPerTick = 2\ntickMillis = 100\ndeliveryBudgetMillis = 300\n"
                +"[behaviors.engine]\nexecutions = 3\n[behaviors.catalog]\nmaximumDocuments = 7\n[behaviors.future]\nkeep = 'untouched'\n";
        Files.writeString(root.resolve("config.toml"),source);
        try(DocumentStore store=new DocumentStore(root)){
            String behavior="{\"revision\":1,\"id\":\"missing:example\",\"routines\":{\"data\":\"preserved\"}}";DocumentStore.atomic(store.resolve("behaviors/missing/example.json"),behavior);
            var settings=PluginSettings.load(store).behaviors();assertFalse(settings.enabled());assertEquals(5,settings.maximumOperations());assertEquals(2,settings.operationsPerTick());assertEquals(100,settings.tickMillis());assertEquals(300,settings.deliveryBudgetMillis());assertEquals(3,settings.engine().executions());assertEquals(7,settings.catalog().maximumDocuments());assertEquals(BehaviorSettings.DEFAULT.engine().inFlight(),settings.engine().inFlight());assertEquals(BehaviorSettings.DEFAULT.catalog().maximumAggregateBytes(),settings.catalog().maximumAggregateBytes());
            assertEquals(source,Files.readString(root.resolve("config.toml")));assertEquals(behavior,store.read("behaviors/missing/example.json"));assertFalse(Files.exists(root.resolve("config.json")));
        }
    }
    @ParameterizedTest @ValueSource(strings={"behaviors = []","[behaviors]\nenabled = 'true'","[behaviors]\nengine = false","[behaviors]\ncatalog = []",
            "[behaviors]\nmaximumOperations = '64'","[behaviors]\noperationsPerTick = 1.5","[behaviors.engine]\nexecutions = [1]","[behaviors.engine]\nworkPerTick = '256'",
            "[behaviors.catalog]\nmaximumAggregateBytes = '16777216'","[behaviors.catalog]\nmaximumAggregateBytes = 1.5","[behaviors.catalog]\nmaximumDepth = true"})
    void malformedBehaviorSettingsNeverFallBackOrRewriteExistingToml(String body)throws Exception{
        String source="statusMessage = 'Test'\n"+body+"\n";Files.writeString(root.resolve("config.toml"),source);Files.writeString(root.resolve("config.json"),"{\"statusMessage\":\"Fallback forbidden\"}");
        try(DocumentStore store=new DocumentStore(root)){assertTrue(assertThrows(IOException.class,()->PluginSettings.load(store)).getMessage().contains("behaviors"));assertEquals(source,Files.readString(root.resolve("config.toml")));}
    }
    @Test void perTickAdmissionCannotExceedMaximumOperationsEvenWhenDisabled()throws Exception{
        String source="statusMessage = 'Test'\n[behaviors]\nenabled = false\nmaximumOperations = 2\noperationsPerTick = 3\n";Files.writeString(root.resolve("config.toml"),source);
        try(DocumentStore store=new DocumentStore(root)){assertTrue(assertThrows(IOException.class,()->PluginSettings.load(store)).getMessage().contains("must not exceed maximumOperations"));assertEquals(source,Files.readString(root.resolve("config.toml")));}
    }
    @Test void legacyMigrationPreservesBehaviorValuesAndUnknownJsonExactly()throws Exception{
        String source="{\"statusMessage\":\"Legacy\",\"behaviors\":{\"enabled\":false,\"maximumOperations\":8,\"operationsPerTick\":2,\"engine\":{\"executions\":4},\"future\":{\"keep\":true}}}";Files.writeString(root.resolve("config.json"),source);
        try(DocumentStore store=new DocumentStore(root)){
            var settings=PluginSettings.load(store).behaviors();assertFalse(settings.enabled());assertEquals(8,settings.maximumOperations());assertEquals(2,settings.operationsPerTick());assertEquals(4,settings.engine().executions());assertEquals(source,store.read("config.json"));assertEquals(source,store.read("backups/config-"+JsonDocuments.hash(source)+".json"));assertTrue(store.read("config.toml").contains("keep"));assertTrue(store.read("config.toml").contains("[behaviors.engine]"));
        }
    }
    @Test void runtimeAccessorCachesSettingsAndRejectsBeforeStartAndAfterClose()throws Exception{
        String source="statusMessage = 'Test'\n[behaviors]\nenabled = false\nmaximumOperations = 4\noperationsPerTick = 2\n";Files.writeString(root.resolve("config.toml"),source);
        try(AnimusRuntime runtime=new AnimusRuntime(RuntimeEnvironment.bukkit("26.3",root))){
            assertThrows(IllegalStateException.class,runtime::behaviorSettings);runtime.start();var settings=runtime.behaviorSettings();assertFalse(settings.enabled());assertEquals(4,settings.maximumOperations());
            Files.writeString(root.resolve("config.toml"),source.replace("enabled = false","enabled = true"));assertSame(settings,runtime.behaviorSettings());runtime.closeAsync();assertThrows(IllegalStateException.class,runtime::behaviorSettings);runtime.close();assertTrue(runtime.awaitFileQuiescence(Duration.ofSeconds(3)));runtime.start();assertTrue(runtime.behaviorSettings().enabled());
        }
    }
}
