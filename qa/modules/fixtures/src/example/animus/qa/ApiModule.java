package example.animus.qa;

import art.arcane.animus.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Public synthetic acceptance fixture; no platform classes or private engine dependencies. */
public final class ApiModule implements TrustedModule {
    private static final Set<Platform> PLATFORMS=Set.of(Platform.PAPER,Platform.SPIGOT,Platform.FOLIA);
    private final AtomicReference<DefinitionSnapshot> cached=new AtomicReference<>();
    private DefinitionService definitions;
    static {System.out.println("Animus module QA initialized qaapi");}

    public ModuleManifest manifest(){return new ModuleManifest("qaapi",new Version(1,0,0),new ApiRange(1,1),PLATFORMS,List.of());}
    public void enable(ModuleContext context)throws Exception {
        if(TrustedModule.class.getClassLoader()==getClass().getClassLoader())throw new AssertionError("Public API identity duplicated");
        try{Class.forName("art.arcane.animus.core.extension.ModuleHost",false,getClass().getClassLoader());throw new AssertionError("Private engine class visible");}catch(ClassNotFoundException expected){}
        if(getClass().getClassLoader().getResource("art/arcane/animus/core/extension/ModuleHost.class")!=null)throw new AssertionError("Private engine resource visible");
        definitions=context.definitions();
        context.onClose(()->System.out.println("Animus module QA resource qaapi first"));
        context.onClose(()->System.out.println("Animus module QA resource qaapi second"));
        register(context,"inspect",i->find(i).thenApply(ApiModule::summary));
        register(context,"cache",i->find(i).thenApply(outcome->{if(outcome.status()==Outcome.Status.OK)cached.set(outcome.value().orElseThrow());return summary(outcome);}));
        register(context,"edit",i->find(i).thenCompose(outcome->edit(outcome,"rename",true)));
        register(context,"invalid",i->find(i).thenCompose(outcome->edit(outcome,"invalid",false)));
        register(context,"identity",i->find(i).thenCompose(outcome->edit(outcome,"identity",false)));
        register(context,"activate",i->find(i).thenCompose(outcome->edit(outcome,"activate",false)));
        register(context,"apply_cached",i->{DefinitionSnapshot before=cached.get();if(before==null||!before.npc().equals(i.npc()))return CompletableFuture.completedFuture("{\"status\":\"NOT_FOUND\"}");return edit(Outcome.ok(before),"rename",false);});
        context.registrations().condition(new Capabilities.Descriptor(new Capabilities.Key("qaapi","false"),Capabilities.Kind.CONDITION,"Synthetic false condition","{}",PLATFORMS,false),i->CompletableFuture.completedFuture(false));
        context.registrations().condition(new Capabilities.Descriptor(new Capabilities.Key("qaapi","true"),Capabilities.Kind.CONDITION,"Synthetic true condition","{}",PLATFORMS,false),i->CompletableFuture.completedFuture(true));
        context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key("qaapi","privileged"),Capabilities.Kind.ACTION,"Synthetic privileged action","{}",PLATFORMS,true),i->{System.out.println("Animus module QA ERROR privileged callback unexpectedly executed");return CompletableFuture.completedFuture("{}");});
        System.out.println("Animus module QA enabled qaapi parent-api=true private-access=false");
    }
    private static void register(ModuleContext context,String action,Capabilities.Action handler){context.registrations().action(new Capabilities.Descriptor(new Capabilities.Key("qaapi",action),Capabilities.Kind.ACTION,"Synthetic QA "+action,"{\"type\":\"object\"}",PLATFORMS,false),handler);}
    private CompletionStage<Outcome<DefinitionSnapshot>> find(Capabilities.Invocation invocation){
        return definitions.find(invocation.npc()).thenApply(outcome->{
            if(outcome.status()!=Outcome.Status.OK)return outcome;DefinitionSnapshot snapshot=outcome.value().orElseThrow();
            if(!snapshot.group().equals("qa")||!snapshot.id().startsWith("module-qa-"))return Outcome.rejected(Outcome.Status.INVALID,"qa-ownership","Synthetic fixture refuses an unrelated NPC");
            return outcome;
        });
    }
    private CompletionStage<String> edit(Outcome<DefinitionSnapshot> found,String operation,boolean validateFirst){
        if(found.status()!=Outcome.Status.OK)return CompletableFuture.completedFuture(summary(found));DefinitionSnapshot before=found.value().orElseThrow();String original=before.json();
        String changed=switch(operation){
            case "rename"->original.replaceFirst("\"name\"\\s*:\\s*\"[^\"]*\"","\"name\":\"Module Edited\"");
            case "invalid"->original.replaceFirst("\"x\"\\s*:\\s*-?[0-9]+(?:\\.[0-9]+)?","\"x\":\"bad\"");
            case "identity"->original.replaceFirst("\"id\"\\s*:\\s*\"[^\"]*\"","\"id\":\"module-qa-other\"");
            case "activate"->original.replaceFirst("\"spawned\"\\s*:\\s*false","\"spawned\":true");
            default->throw new IllegalArgumentException("Unknown QA operation");
        };
        if(changed.equals(original))return CompletableFuture.completedFuture("{\"status\":\"FIXTURE_NO_CHANGE\"}");
        DefinitionEdit edit=new DefinitionEdit(before.npc(),before.token(),changed);
        if(validateFirst)return definitions.validate(edit).thenCompose(validation->validation.status()==Outcome.Status.OK?definitions.apply(edit).thenApply(ApiModule::summary):CompletableFuture.completedFuture(status(validation)));
        return definitions.apply(edit).thenApply(ApiModule::summary);
    }
    private static String summary(Outcome<DefinitionSnapshot> outcome){
        if(outcome.status()!=Outcome.Status.OK)return status(outcome);DefinitionSnapshot snapshot=outcome.value().orElseThrow();
        return "{\"status\":\"OK\",\"id\":"+quote(snapshot.id())+",\"uuid\":"+quote(snapshot.npc().uuid().toString())+",\"revision\":"+snapshot.token().revision()+",\"sourceSha256\":"+quote(snapshot.token().sourceSha256())+"}";
    }
    private static String status(Outcome<?> outcome){String code=outcome.diagnostics().isEmpty()?"":outcome.diagnostics().get(0).code();String field=outcome.diagnostics().isEmpty()?"":outcome.diagnostics().get(0).field();return "{\"status\":"+quote(outcome.status().name())+",\"code\":"+quote(code)+",\"field\":"+quote(field)+"}";}
    private static String quote(String text){StringBuilder out=new StringBuilder("\"");for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\"'||c=='\\')out.append('\\').append(c);else if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();}
    public void disable(){cached.set(null);System.out.println("Animus module QA disabled qaapi");}
}
