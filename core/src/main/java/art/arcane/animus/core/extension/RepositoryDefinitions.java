package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Definition reads and full replacements over the engine-owned file lane, for saved and temporary NPCs. A replacement
 * that goes live is first checked where the runtime would check it, through the connected activation check.
 */
public final class RepositoryDefinitions implements DefinitionGateway,AutoCloseable {
    /** A replacement checked on the lane: what to write, and the definition to check before it goes live, if it does. */
    private record Prepared(JsonObject json,String path,String hash,long revision,boolean temporary,Optional<NpcDefinition> activate) {}

    private final NpcRepository repository;
    private final MutationLane io;
    private volatile boolean closed;
    private volatile Function<NpcDefinition,CompletionStage<List<String>>> activation=definition->CompletableFuture.completedFuture(List.of());
    private Map<String,NpcDefinition> indexed=Map.of();
    private Map<UUID,NpcDefinition> byUuid=Map.of();
    public RepositoryDefinitions(NpcRepository repository,MutationLane mutationLane){this.repository=Objects.requireNonNull(repository);io=Objects.requireNonNull(mutationLane);}
    @Override public void activation(Function<NpcDefinition,CompletionStage<List<String>>> check){activation=Objects.requireNonNull(check);}
    public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){return find(npc,()->true);}
    private CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc,BooleanSupplier gate){return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){
        NpcDefinition definition=lookup(npc);
        if(definition==null)return Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","NPC identity is unavailable");
        try{return Outcome.ok(snapshot(definition));}catch(IOException invalid){return failure(invalid);}
    }});}
    public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){return validate(edit,()->true);}
    private CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit,BooleanSupplier gate){
        CompletionStage<Outcome<Prepared>> prepared=io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){return prepare(edit);}});
        return prepared.thenCompose(outcome->{
            if(outcome.status()!=Outcome.Status.OK)return CompletableFuture.completedFuture(new Outcome<List<Diagnostic>>(outcome.status(),Optional.empty(),outcome.diagnostics()));
            Optional<NpcDefinition> live=outcome.value().orElseThrow().activate();
            if(live.isEmpty())return CompletableFuture.completedFuture(Outcome.ok(List.<Diagnostic>of()));
            return activation.apply(live.get()).thenApply(problems->problems.isEmpty()?Outcome.ok(List.<Diagnostic>of()):activationRefused(problems));
        });
    }
    public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){return apply(edit,()->true);}
    /** One lane step of an apply: the saved snapshot, or the definition to check before it may go live. */
    private record Step(Optional<DefinitionSnapshot> saved,Optional<NpcDefinition> check) {}
    private CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit,BooleanSupplier gate){
        // A replacement that stays dormant, or does not change the live body, is checked and written in one lane task.
        CompletionStage<Outcome<Step>> first=io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){
            Outcome<Prepared> prepared=prepare(edit);
            if(prepared.status()!=Outcome.Status.OK)return new Outcome<>(prepared.status(),Optional.empty(),prepared.diagnostics());
            Prepared write=prepared.value().orElseThrow();
            if(write.activate().isPresent())return Outcome.ok(new Step(Optional.empty(),write.activate()));
            Outcome<DefinitionSnapshot> saved=write(edit,write);
            return saved.status()==Outcome.Status.OK?Outcome.ok(new Step(saved.value(),Optional.empty())):new Outcome<>(saved.status(),Optional.empty(),saved.diagnostics());
        }});
        return first.thenCompose(outcome->{
            if(outcome.status()!=Outcome.Status.OK)return CompletableFuture.completedFuture(new Outcome<DefinitionSnapshot>(outcome.status(),Optional.empty(),outcome.diagnostics()));
            Step step=outcome.value().orElseThrow();
            if(step.saved().isPresent())return CompletableFuture.completedFuture(Outcome.ok(step.saved().get()));
            return activation.apply(step.check().orElseThrow()).thenCompose(problems->problems.isEmpty()?commit(edit,gate):CompletableFuture.completedFuture(activationRefused(problems)));
        });
    }
    /** After the activation check: prepares again on the lane, so a change since the first look is a conflict, then writes. */
    private CompletionStage<Outcome<DefinitionSnapshot>> commit(DefinitionEdit edit,BooleanSupplier gate){return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){
        Outcome<Prepared> prepared=prepare(edit);
        if(prepared.status()!=Outcome.Status.OK)return new Outcome<>(prepared.status(),Optional.empty(),prepared.diagnostics());
        return write(edit,prepared.value().orElseThrow());
    }});}
    private Outcome<DefinitionSnapshot> write(DefinitionEdit edit,Prepared write){
        try{
            if(write.temporary())repository.replaceTemporary(lookup(edit.npc()).id(),write.json());
            else repository.apply(new NpcRepository.Draft(write.path(),write.hash(),write.revision(),write.json()));
            return Outcome.ok(snapshot(lookup(edit.npc())));
        }catch(IOException failure){return failure(failure);}
    }
    public CompletionStage<Outcome<NpcHandle>> resolve(String id){return resolve(id,()->true);}
    private CompletionStage<Outcome<NpcHandle>> resolve(String id,BooleanSupplier gate){
        if(id==null || !id.matches(art.arcane.animus.core.storage.NpcPaths.SEGMENT))return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.INVALID,"npc-id-invalid","NPC ids are 1-64 lowercase letters, digits, _ or -"));
        return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();
            NpcDefinition definition=repository.snapshot().get(id);
            return definition==null?Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","No NPC has id "+id):Outcome.ok(new NpcHandle(definition.uuid()));
        });
    }
    public CompletionStage<Outcome<Page>> list(Optional<String> group,int page){return list(group,page,()->true);}
    private CompletionStage<Outcome<Page>> list(Optional<String> group,int page,BooleanSupplier gate){
        if(group==null || page<1 || group.isPresent() && !group.get().matches(art.arcane.animus.core.storage.NpcPaths.FOLDER))
            return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.INVALID,"list-invalid","Pages start at 1 and a group is a folder below npcs/"));
        return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();
            List<NpcDefinition> matching=repository.snapshot().values().stream().filter(definition->group.isEmpty() || definition.group().equals(group.get()))
                    .sorted(Comparator.comparing(NpcDefinition::id)).toList();
            int pages=Math.max(1,(matching.size()+PAGE_SIZE-1)/PAGE_SIZE);
            int from=(int)Math.min((long)(page-1)*PAGE_SIZE,matching.size());
            List<Summary> npcs=matching.subList(from,Math.min(from+PAGE_SIZE,matching.size())).stream()
                    .map(definition->new Summary(new NpcHandle(definition.uuid()),definition.id(),definition.group(),definition.spawned())).toList();
            return Outcome.ok(new Page(npcs,page,pages,matching.size()));
        });
    }
    @Override public DefinitionService scoped(BooleanSupplier available){
        Objects.requireNonNull(available);
        return new DefinitionService(){
            public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){return RepositoryDefinitions.this.find(npc,available);}
            public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){return RepositoryDefinitions.this.validate(edit,available);}
            public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){return RepositoryDefinitions.this.apply(edit,available);}
            public CompletionStage<Outcome<NpcHandle>> resolve(String id){return RepositoryDefinitions.this.resolve(id,available);}
            public CompletionStage<Outcome<Page>> list(Optional<String> group,int page){return RepositoryDefinitions.this.list(group,page,available);}
        };
    }
    private static <T> Outcome<T> unavailable(){return Outcome.rejected(Outcome.Status.UNAVAILABLE,"definition-access-unavailable","Definition service or module scope is unavailable");}
    private static <T> Outcome<T> activationRefused(List<String> problems){
        return new Outcome<>(Outcome.Status.INVALID,Optional.empty(),problems.stream().limit(64).map(message->{
            int separator=message.indexOf(": ");String field=separator<0||!message.startsWith("$")?"$":message.substring(0,separator);
            return new Diagnostic(Diagnostic.Severity.ERROR,"activation-refused",bounded(field,240),bounded(separator<0||!message.startsWith("$")?message:message.substring(separator+2),1000));
        }).toList());
    }
    private Outcome<Prepared> prepare(DefinitionEdit edit){
        NpcDefinition current=lookup(edit.npc());
        if(current==null)return Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","NPC identity is unavailable");
        try{
            Optional<NpcRepository.Temporary> temporary=repository.temporary(current.id());
            NpcDefinition base=temporary.map(NpcRepository.Temporary::definition).orElse(null);
            NpcRepository.Draft before=temporary.isPresent()?null:repository.draft(current.id());
            long revision=temporary.isPresent()?base.revision():before.expectedRevision();
            String hash=temporary.isPresent()?JsonDocuments.hash(base.serialize()):before.expectedHash();
            if(revision!=edit.expected().revision() || !Objects.equals(hash,edit.expected().sourceSha256()))
                return Outcome.rejected(Outcome.Status.CONFLICT,"revision-conflict","Accepted revision or source hash changed");
            var raw=JsonDocuments.object(edit.json());
            var errors=NpcDefinition.validate(raw);
            if(!errors.isEmpty())return new Outcome<>(Outcome.Status.INVALID,Optional.empty(),errors.stream().limit(64).map(message->{
                int separator=message.indexOf(": ");String field=separator<0?"$":message.substring(0,separator);
                return new Diagnostic(Diagnostic.Severity.ERROR,"definition-invalid",bounded(field,240),bounded(separator<0?message:message.substring(separator+2),1000));
            }).toList());
            NpcDefinition replacement=NpcDefinition.parse(edit.json());
            String path=temporary.isPresent()?base.path():before.path();
            if(!replacement.uuid().equals(edit.npc().uuid()) || !replacement.path().equals(path) || replacement.revision()!=revision)
                return Outcome.rejected(Outcome.Status.INVALID,"identity-change","UUID, id/group and draft revision must remain unchanged");
            NpcDefinition stored=temporary.isPresent()?base:NpcDefinition.parse(JsonDocuments.write(before.document()));
            boolean live=replacement.spawned() && !replacement.runtimeForm().equals(stored.runtimeForm());
            return Outcome.ok(new Prepared(replacement.json(),path,hash,revision,temporary.isPresent(),live?Optional.of(replacement):Optional.empty()));
        }catch(IOException failure){return failure(failure);}
    }
    private NpcDefinition lookup(NpcHandle npc){
        Map<String,NpcDefinition> snapshot=repository.snapshot();
        if(snapshot!=indexed){Map<UUID,NpcDefinition> next=new HashMap<>();snapshot.values().forEach(value->next.put(value.uuid(),value));byUuid=Map.copyOf(next);indexed=snapshot;}
        return byUuid.get(npc.uuid());
    }
    private DefinitionSnapshot snapshot(NpcDefinition definition)throws IOException{
        Optional<NpcRepository.Temporary> temporary=repository.temporary(definition.id());
        if(temporary.isPresent()){
            NpcDefinition held=temporary.get().definition();
            return new DefinitionSnapshot(new NpcHandle(held.uuid()),held.id(),held.group(),new RevisionToken(held.revision(),JsonDocuments.hash(held.serialize())),held.serialize());
        }
        NpcRepository.Draft draft=repository.draft(definition.id());
        return new DefinitionSnapshot(new NpcHandle(definition.uuid()),definition.id(),definition.group(),new RevisionToken(draft.expectedRevision(),draft.expectedHash()),JsonDocuments.write(draft.document()));
    }
    private static <T> Outcome<T> failure(IOException error){
        boolean conflict=String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT).contains("conflict");
        return Outcome.rejected(conflict?Outcome.Status.CONFLICT:Outcome.Status.INVALID,conflict?"revision-conflict":"definition-invalid",bounded(String.valueOf(error.getMessage()),1000));
    }
    private static String bounded(String text,int codePoints){return new String(text.codePoints().limit(codePoints).toArray(),0,(int)Math.min(text.codePointCount(0,text.length()),codePoints));}
    @Override public void close(){closed=true;}
    public CompletionStage<Void> drained(){return io.drained();}
}
