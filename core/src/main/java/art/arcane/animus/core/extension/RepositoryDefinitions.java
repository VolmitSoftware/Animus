package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.core.npc.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Dormant definition edits over the engine-owned file lane. Platform activation has a separate admission path. */
public final class RepositoryDefinitions implements DefinitionGateway,AutoCloseable {
    private final NpcRepository repository;
    private final MutationLane io;
    private volatile boolean closed;
    private Map<String,NpcDefinition> indexed=Map.of();
    private Map<UUID,NpcDefinition> byUuid=Map.of();
    public RepositoryDefinitions(NpcRepository repository,MutationLane mutationLane){this.repository=Objects.requireNonNull(repository);io=Objects.requireNonNull(mutationLane);}
    public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){return find(npc,()->true);}
    private CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc,BooleanSupplier gate){return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){
        NpcDefinition definition=lookup(npc);
        if(definition==null)return Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","NPC identity is unavailable");
        try{return Outcome.ok(snapshot(definition));}catch(IOException invalid){return failure(invalid);}
    }});}
    public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){return validate(edit,()->true);}
    private CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit,BooleanSupplier gate){return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){
        Outcome<NpcRepository.Draft> draft=prepare(edit);
        return draft.status()==Outcome.Status.OK?Outcome.ok(List.of()):new Outcome<>(draft.status(),Optional.empty(),draft.diagnostics());
    }});}
    public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){return apply(edit,()->true);}
    private CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit,BooleanSupplier gate){return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();synchronized(repository){
        Outcome<NpcRepository.Draft> draft=prepare(edit);
        if(draft.status()!=Outcome.Status.OK)return new Outcome<>(draft.status(),Optional.empty(),draft.diagnostics());
        try{repository.apply(draft.value().orElseThrow());return Outcome.ok(snapshot(lookup(edit.npc())));}
        catch(IOException failure){return failure(failure);}
    }});}
    public CompletionStage<Outcome<NpcHandle>> resolve(String id){return resolve(id,()->true);}
    private CompletionStage<Outcome<NpcHandle>> resolve(String id,BooleanSupplier gate){
        if(id==null || !id.matches(art.arcane.animus.core.storage.NpcPaths.SEGMENT))return java.util.concurrent.CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.INVALID,"npc-id-invalid","NPC ids are 1-64 lowercase letters, digits, _ or -"));
        return io.submit(()->{if(closed || !gate.getAsBoolean())return unavailable();
            NpcDefinition definition=repository.snapshot().get(id);
            return definition==null?Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","No NPC has id "+id):Outcome.ok(new NpcHandle(definition.uuid()));
        });
    }
    public CompletionStage<Outcome<Page>> list(Optional<String> group,int page){return list(group,page,()->true);}
    private CompletionStage<Outcome<Page>> list(Optional<String> group,int page,BooleanSupplier gate){
        if(group==null || page<1 || group.isPresent() && !group.get().matches(art.arcane.animus.core.storage.NpcPaths.FOLDER))
            return java.util.concurrent.CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.INVALID,"list-invalid","Pages start at 1 and a group is a folder below npcs/"));
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
    private Outcome<NpcRepository.Draft> prepare(DefinitionEdit edit){
        NpcDefinition current=lookup(edit.npc());
        if(current==null)return Outcome.rejected(Outcome.Status.NOT_FOUND,"npc-missing","NPC identity is unavailable");
        try{
            NpcRepository.Draft before=repository.draft(current.id());
            if(before.expectedRevision()!=edit.expected().revision() || !Objects.equals(before.expectedHash(),edit.expected().sourceSha256()))
                return Outcome.rejected(Outcome.Status.CONFLICT,"revision-conflict","Accepted revision or source hash changed");
            var raw=art.arcane.animus.core.storage.JsonDocuments.object(edit.json());
            var errors=NpcDefinition.validate(raw);
            if(!errors.isEmpty())return new Outcome<>(Outcome.Status.INVALID,Optional.empty(),errors.stream().limit(64).map(message->{
                int separator=message.indexOf(": ");String field=separator<0?"$":message.substring(0,separator);
                return new Diagnostic(Diagnostic.Severity.ERROR,"definition-invalid",bounded(field,240),bounded(separator<0?message:message.substring(separator+2),1000));
            }).toList());
            NpcDefinition replacement=NpcDefinition.parse(edit.json());
            if(!replacement.uuid().equals(edit.npc().uuid()) || !replacement.path().equals(current.path()) || replacement.revision()!=before.expectedRevision())
                return Outcome.rejected(Outcome.Status.INVALID,"identity-change","UUID, id/group and draft revision must remain unchanged");
            if(replacement.json().get("spawned").getAsBoolean())
                return Outcome.rejected(Outcome.Status.UNAVAILABLE,"activation-unavailable","API edits currently require dormant definitions");
            return Outcome.ok(new NpcRepository.Draft(before.path(),before.expectedHash(),before.expectedRevision(),replacement.json()));
        }catch(IOException failure){return failure(failure);}
    }
    private NpcDefinition lookup(NpcHandle npc){
        Map<String,NpcDefinition> snapshot=repository.snapshot();
        if(snapshot!=indexed){Map<UUID,NpcDefinition> next=new HashMap<>();snapshot.values().forEach(value->next.put(value.uuid(),value));byUuid=Map.copyOf(next);indexed=snapshot;}
        return byUuid.get(npc.uuid());
    }
    private DefinitionSnapshot snapshot(NpcDefinition definition)throws IOException{
        NpcRepository.Draft draft=repository.draft(definition.id());
        return new DefinitionSnapshot(new NpcHandle(definition.uuid()),definition.id(),definition.group(),new RevisionToken(draft.expectedRevision(),draft.expectedHash()),art.arcane.animus.core.storage.JsonDocuments.write(draft.document()));
    }
    private static <T> Outcome<T> failure(IOException error){
        boolean conflict=String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT).contains("conflict");
        return Outcome.rejected(conflict?Outcome.Status.CONFLICT:Outcome.Status.INVALID,conflict?"revision-conflict":"definition-invalid",bounded(String.valueOf(error.getMessage()),1000));
    }
    private static String bounded(String text,int codePoints){return new String(text.codePoints().limit(codePoints).toArray(),0,(int)Math.min(text.codePointCount(0,text.length()),codePoints));}
    @Override public void close(){closed=true;}
    public CompletionStage<Void> drained(){return io.drained();}
}
