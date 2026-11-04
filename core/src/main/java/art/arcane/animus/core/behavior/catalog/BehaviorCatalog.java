package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.behavior.BehaviorProgram.*;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Accepted programs with durable last-valid checkpoints. Sources stay read-only; checkpoints are generated data.
 * Construction and reload belong on the shared engine I/O lane, never a platform owner thread.
 */
public final class BehaviorCatalog {
    public record Limits(int maximumDocuments,int maximumEntries,int maximumDepth,int maximumDocumentBytes,
                         long maximumAggregateBytes,int maximumCapabilityChecks){
        public Limits{
            if(maximumDocuments<1||maximumDocuments>4096||maximumEntries<1||maximumEntries>32768||maximumDepth<2||maximumDepth>16
                    ||maximumDocumentBytes<1||maximumDocumentBytes>BehaviorCompiler.MAX_BYTES||maximumAggregateBytes<1||maximumAggregateBytes>64L*1024*1024
                    ||maximumCapabilityChecks<1||maximumCapabilityChecks>16384)throw new IllegalArgumentException("Invalid behavior catalog limits");
        }
        public static Limits defaults(){return new Limits(1024,4096,8,BehaviorCompiler.MAX_BYTES,16L*1024*1024,8192);}
    }
    public record Requirement(Key key,Kind kind){public Requirement{Objects.requireNonNull(key);Objects.requireNonNull(kind);}}
    public record Capability(boolean available,String reason){
        public Capability{if(reason==null||reason.isBlank()||reason.length()>256||!StandardCharsets.UTF_8.newEncoder().canEncode(reason)||reason.getBytes(StandardCharsets.UTF_8).length>256)throw new IllegalArgumentException("Expected bounded capability reason");}
        public static Capability present(){return new Capability(true,"available");}
    }
    /** Engine-owned, quick registry observation only. Actual invocations must recheck capability authority. */
    @FunctionalInterface public interface CapabilityResolver {Capability resolve(Requirement requirement);}
    public enum SourceState { VALID, INVALID, CONFLICT, DUPLICATE, MISSING }
    public record Accepted(long revision,String sourceHash,long sourceBytes,BehaviorProgram program,Set<Requirement> requirements){
        public Accepted{if(revision<1||sourceBytes<1||!sourceHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid accepted behavior token");Objects.requireNonNull(program);requirements=Set.copyOf(requirements);}
    }
    public record Entry(String path,Optional<String> declaredId,Optional<String> observedHash,OptionalLong observedRevision,
                        SourceState state,Optional<Accepted> accepted,List<Diagnostic> diagnostics){
        public Entry{Objects.requireNonNull(path);Objects.requireNonNull(declaredId);Objects.requireNonNull(observedHash);Objects.requireNonNull(observedRevision);Objects.requireNonNull(state);Objects.requireNonNull(accepted);diagnostics=List.copyOf(diagnostics);}
    }
    public record Resolution(String id,boolean available,Optional<Accepted> accepted,List<Diagnostic> diagnostics){
        public Resolution{Objects.requireNonNull(id);Objects.requireNonNull(accepted);diagnostics=List.copyOf(diagnostics);}
    }
    public record Snapshot(long generation,Map<String,Entry> entries,Map<String,Resolution> byId,List<Diagnostic> diagnostics){
        public Snapshot{entries=Collections.unmodifiableMap(new TreeMap<>(entries));byId=Collections.unmodifiableMap(new TreeMap<>(byId));diagnostics=List.copyOf(diagnostics);}
        public Optional<Resolution> find(String id){return Optional.ofNullable(byId.get(id));}
    }
    /** A checked candidate observation; it never replaces this catalog's accepted snapshot. */
    public record Validation(boolean scanned,boolean valid,Snapshot candidate,List<Diagnostic> diagnostics,int observedDocuments,long observedBytes){
        public Validation{Objects.requireNonNull(candidate);diagnostics=List.copyOf(diagnostics);}
    }
    /** Explicit in-memory removal of an accepted program whose source is absent; never deletes a file. */
    public record Removal(String path,long acceptedRevision,String acceptedSourceHash){
        public Removal{BehaviorPath.idFor(path);if(acceptedRevision<1||acceptedSourceHash==null||!acceptedSourceHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid removal token");}
        public static Removal of(Entry entry){Accepted value=entry.accepted().orElseThrow();return new Removal(entry.path(),value.revision(),value.sourceHash());}
    }
    public record Reload(boolean published,Snapshot snapshot,List<Diagnostic> diagnostics,int scannedDocuments,long scannedBytes){
        public Reload{diagnostics=List.copyOf(diagnostics);}
    }
    private final BehaviorSourceReader reader;private final Limits limits;private final CapabilityResolver resolver;private final BehaviorCheckpoints checkpoints;
    private final Map<String,Diagnostic> checkpointProblems=new TreeMap<>();
    private boolean reloading;
    private volatile Snapshot published=new Snapshot(0,Map.of(),Map.of(),List.of());
    /** Last-valid programs join generation zero, so the first reload treats them exactly like programs accepted earlier in this process. */
    public BehaviorCatalog(Path dataRoot,Limits limits,CapabilityResolver resolver,BehaviorCheckpoints checkpoints)throws IOException{
        this.limits=Objects.requireNonNull(limits);this.resolver=Objects.requireNonNull(resolver);this.checkpoints=Objects.requireNonNull(checkpoints);reader=new BehaviorSourceReader(dataRoot,limits);
        BehaviorCheckpoints.Loaded loaded=checkpoints.load();
        Map<String,Entry> entries=new TreeMap<>();
        loaded.problems().forEach((source,problem)->checkpointProblems.put(source,checkpointProblem(problem)));
        loaded.saved().forEach((source,saved)->{
            try{
                entries.put(source,recovered(source,saved));
            }catch(IOException | IllegalArgumentException invalid){
                checkpointProblems.put(source,checkpointProblem(invalid.getMessage()));
            }
        });
        published=new Snapshot(0,entries,Map.of(),List.of());
    }
    private BehaviorCatalog(BehaviorSourceReader reader,Limits limits,CapabilityResolver resolver,Snapshot accepted){
        this.reader=reader;this.limits=limits;this.resolver=resolver;this.published=accepted;checkpoints=BehaviorCheckpoints.disabled();
    }
    private static Diagnostic checkpointProblem(String problem){
        return new Diagnostic(Diagnostic.Severity.WARNING,"behavior-checkpoint-invalid","$","Last-valid checkpoint is unusable and was ignored: "+safe(problem,600));
    }
    private static Entry recovered(String source,BehaviorCheckpoints.Saved saved)throws IOException{
        JsonObject object=JsonDocuments.object(saved.source());
        object.remove("revision");
        BehaviorCompiler.Result result=BehaviorCompiler.compile(object.toString());
        BehaviorProgram program=result.program().orElseThrow(()->new IllegalArgumentException("Checkpoint no longer compiles: "+result.diagnostics().getFirst().field()));
        if(!BehaviorPath.forId(program.id()).equals(source))throw new IllegalArgumentException("Checkpoint identity does not match its source path");
        long bytes=saved.source().getBytes(StandardCharsets.UTF_8).length;
        Accepted accepted=new Accepted(saved.revision(),saved.sourceHash(),bytes,program,requirements(program));
        return new Entry(source,Optional.of(program.id()),Optional.empty(),OptionalLong.empty(),SourceState.VALID,Optional.of(accepted),List.of());
    }
    public Snapshot snapshot(){return published;}
    /** Validate current files against accepted revision/hash state without publishing, writing or forgetting programs. */
    public synchronized Validation validate(long expectedGeneration){
        Snapshot before=published;
        if(reloading||before.generation()!=expectedGeneration){
            Diagnostic error=error(reloading?"catalog-reentrant-reload":"catalog-generation-conflict","behaviors","Catalog observation is already running or its generation changed");
            return new Validation(false,false,before,List.of(error),0,0);
        }
        reloading=true;
        try{
            Reload checked=new BehaviorCatalog(reader,limits,resolver,before).reload(expectedGeneration,Set.of());
            if(!checked.published())return new Validation(false,false,before,checked.diagnostics(),checked.scannedDocuments(),checked.scannedBytes());
            Snapshot candidate=new Snapshot(before.generation(),checked.snapshot().entries(),checked.snapshot().byId(),checked.snapshot().diagnostics());
            boolean valid=candidate.entries().values().stream().allMatch(entry->entry.state()==SourceState.VALID
                    &&entry.diagnostics().stream().noneMatch(problem->problem.severity()==Diagnostic.Severity.ERROR));
            return new Validation(true,valid,candidate,checked.diagnostics(),checked.scannedDocuments(),checked.scannedBytes());
        }finally{reloading=false;}
    }
    /** Expected generation is process-local. Structural/read-budget conflicts leave the entire snapshot unchanged. */
    public synchronized Reload reload(long expectedGeneration,Set<Removal> removals){
        Objects.requireNonNull(removals);Snapshot before=published;
        if(reloading)return rejected("catalog-reentrant-reload","Reload must not reenter registry observation",before);
        if(expectedGeneration!=before.generation())return rejected("catalog-generation-conflict","Catalog generation changed",before);
        if(removals.size()>limits.maximumDocuments())return rejected("catalog-removal-budget","Too many removal tokens",before);
        Set<String> removed=new HashSet<>();
        for(Removal token:removals){
            Entry entry=before.entries().get(token.path());Accepted accepted=entry==null?null:entry.accepted().orElse(null);
            if(!removed.add(token.path())||accepted==null||accepted.revision()!=token.acceptedRevision()||!accepted.sourceHash().equals(token.acceptedSourceHash()))return rejected("catalog-source-conflict","Removal revision/source hash no longer matches",before);
        }
        reloading=true;
        try{
            BehaviorSourceReader.Scan scan=reader.scan(true);
            for(String path:removed)if(scan.sources().containsKey(path))return rejected("catalog-removal-source-present","Removal requires the source to be absent",before);
            Map<String,Candidate> candidates=new TreeMap<>();
            for(BehaviorSourceReader.Source source:scan.sources().values())candidates.put(source.path(),parse(source,before.entries().get(source.path())));
            for(Entry previous:before.entries().values())if(previous.accepted().isPresent()&&!candidates.containsKey(previous.path())&&!removed.contains(previous.path()))candidates.put(previous.path(),Candidate.missing(previous));
            if(candidates.size()>limits.maximumDocuments())return rejected("catalog-retained-document-budget","Observed and retained entries exceed document budget; explicitly remove missing accepted programs",before);
            Map<String,Set<String>> claims=new TreeMap<>();
            for(Candidate candidate:candidates.values()){
                if(candidate.identitySafe)claims.computeIfAbsent(candidate.declaredId,id->new TreeSet<>()).add(candidate.path);
                if(candidate.accepted!=null)claims.computeIfAbsent(candidate.accepted.program().id(),id->new TreeSet<>()).add(candidate.path);
            }
            Set<String> duplicateIds=new TreeSet<>();claims.forEach((id,paths)->{if(paths.size()>1)duplicateIds.add(id);});
            for(Candidate candidate:candidates.values()){
                boolean duplicate=candidate.declaredId!=null&&duplicateIds.contains(candidate.declaredId)||candidate.accepted!=null&&duplicateIds.contains(candidate.accepted.program().id());
                if(duplicate){candidate.state=SourceState.DUPLICATE;candidate.add(error("behavior-id-duplicate","$.id","Duplicate identity; no file wins catalog resolution"));}
                else accept(candidate);
                Diagnostic checkpoint=checkpointProblems.get(candidate.path);
                if(checkpoint!=null)candidate.add(checkpoint);
            }
            long retainedBytes=0;
            for(Candidate candidate:candidates.values())if(candidate.accepted!=null){retainedBytes+=candidate.accepted.sourceBytes();if(retainedBytes>limits.maximumAggregateBytes())return rejected("catalog-retained-byte-budget","Accepted and retained programs exceed aggregate source-byte budget",before);}
            Map<Requirement,Capability> capabilities=new HashMap<>();
            Map<String,Resolution> resolutions=new TreeMap<>();
            for(String id:claims.keySet()){
                if(duplicateIds.contains(id)){resolutions.put(id,new Resolution(id,false,Optional.empty(),List.of(error("behavior-id-duplicate","$.id","Duplicate identity; inspect per-document retained programs"))));continue;}
                Candidate owner=null;
                for(String path:claims.get(id)){Candidate candidate=candidates.get(path);if(candidate.accepted!=null&&candidate.accepted.program().id().equals(id))owner=candidate;}
                if(owner==null){resolutions.put(id,new Resolution(id,false,Optional.empty(),List.of(error("behavior-unaccepted","$","No accepted compiled program"))));continue;}
                boolean available=owner.state!=SourceState.MISSING&&owner.state!=SourceState.DUPLICATE;
                for(Requirement requirement:owner.accepted.requirements()){
                    Capability capability=capabilities.get(requirement);
                    if(capability==null){
                        if(capabilities.size()>=limits.maximumCapabilityChecks())return rejected("catalog-capability-budget","Unique capability checks exceed reload budget",before);
                        try{capability=Objects.requireNonNull(resolver.resolve(requirement));}
                        catch(RuntimeException | AssertionError | LinkageError failure){capability=new Capability(false,"registry-check-failed");}
                        capabilities.put(requirement,capability);
                    }
                    if(!capability.available()){available=false;owner.add(error("behavior-capability-unavailable","$.routines",requirement.kind().name().toLowerCase(Locale.ROOT)+" "+requirement.key()+": "+capability.reason()));}
                }
                resolutions.put(id,new Resolution(id,available,Optional.of(owner.accepted),owner.diagnostics()));
            }
            BehaviorSourceReader.Scan checked=reader.scan(false);
            if(!scan.manifest().equals(checked.manifest()))return rejected("catalog-source-changed","Behavior source tree changed during reload; retry after edits settle",before);
            Map<String,Entry> entries=new TreeMap<>();candidates.forEach((path,candidate)->entries.put(path,candidate.entry()));
            List<Diagnostic> global=removed.isEmpty()?List.of():List.of(new Diagnostic(Diagnostic.Severity.INFO,"catalog-programs-removed","behaviors","Explicitly removed "+removed.size()+" absent accepted programs; no files written"));
            Map<String,BehaviorCheckpoints.Pending> durable=new TreeMap<>();Set<String> present=new HashSet<>();
            for(Candidate candidate:candidates.values()){
                if(candidate.source!=null)present.add(candidate.path);
                if(candidate.source==null||candidate.accepted==null)continue;
                boolean fresh=candidate.accepted.sourceHash().equals(candidate.source.hash());
                durable.put(candidate.path,new BehaviorCheckpoints.Pending(candidate.accepted.revision(),candidate.accepted.sourceHash(),fresh?candidate.source.text():null));
            }
            try{checkpoints.update(durable,present);}
            catch(IOException | RuntimeException failure){return rejected("catalog-checkpoint-failed",safe(failure.getClass().getSimpleName()+": "+failure.getMessage(),240),before);}
            checkpointProblems.clear();
            published=new Snapshot(Math.incrementExact(before.generation()),entries,resolutions,global);
            return new Reload(true,published,global,scan.sources().size(),scan.bytes());
        }catch(IOException invalid){return rejected("catalog-scan-unavailable",safe(invalid.getMessage(),240),before);}
        finally{reloading=false;}
    }
    private Candidate parse(BehaviorSourceReader.Source source,Entry previous){
        Candidate candidate=new Candidate(source.path(),source,previous==null?null:previous.accepted().orElse(null));
        try{BehaviorPath.idFor(source.path());}catch(IllegalArgumentException invalid){candidate.add(error("behavior-path-invalid","$",invalid.getMessage()));}
        if(source.error()!=null){candidate.add(error(source.error(),"$","Source is unreadable under document/UTF-8 limits; bytes left unchanged"));return candidate;}
        try{
            JsonObject object=JsonDocuments.object(source.text());candidate.object=object;
            JsonElement id=object.get("id");
            if(id!=null&&id.isJsonPrimitive()&&id.getAsJsonPrimitive().isString()&&id.getAsString().length()<=256){
                candidate.declaredId=id.getAsString();
                try{String expectedPath=BehaviorPath.forId(candidate.declaredId);candidate.identitySafe=true;if(!expectedPath.equals(source.path()))candidate.add(error("behavior-path-mismatch","$.id","Logical ID does not exactly match its source path"));}
                catch(IllegalArgumentException invalid){candidate.add(error("behavior-id-path-invalid","$.id",invalid.getMessage()));}
            }else candidate.add(error("behavior-id-invalid","$.id","Expected bounded namespace:path identity"));
            JsonElement revision=object.get("revision");
            try{if(revision==null||!revision.isJsonPrimitive()||!revision.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long value=revision.getAsBigDecimal().longValueExact();if(value<1)throw new ArithmeticException();candidate.revision=value;}
            catch(RuntimeException invalid){candidate.add(error("behavior-revision-invalid","$.revision","Expected positive signed 64-bit document revision"));}
        }catch(IOException invalid){candidate.add(error("behavior-json-invalid","$","Expected strict JSON object with unique keys"));}
        return candidate;
    }
    private void accept(Candidate candidate){
        if(candidate.source==null)return;
        if(!candidate.errors.isEmpty())return;
        if(candidate.accepted!=null){
            if(candidate.source.hash().equals(candidate.accepted.sourceHash())){candidate.state=SourceState.VALID;return;}
            if(candidate.revision<=candidate.accepted.revision()){
                candidate.state=SourceState.CONFLICT;candidate.add(error("behavior-revision-conflict","$.revision","Changed source bytes require a revision greater than the last accepted revision"));return;
            }
        }
        candidate.object.remove("revision");BehaviorCompiler.Result result=BehaviorCompiler.compile(candidate.object.toString());
        if(result.program().isEmpty()){result.diagnostics().forEach(candidate::add);return;}
        BehaviorProgram program=result.program().orElseThrow();
        candidate.accepted=new Accepted(candidate.revision,candidate.source.hash(),candidate.source.bytes(),program,requirements(program));candidate.state=SourceState.VALID;
    }
    private static Set<Requirement> requirements(BehaviorProgram program){
        Set<Requirement> result=new LinkedHashSet<>();ArrayDeque<Node> pending=new ArrayDeque<>(program.routines().values());
        while(!pending.isEmpty())switch(pending.removeFirst()){
            case Action action->result.add(new Requirement(action.capability(),Kind.ACTION));
            case Condition condition->{result.add(new Requirement(condition.capability(),Kind.CONDITION));pending.add(condition.thenNode());pending.add(condition.elseNode());}
            case Sequence sequence->pending.addAll(sequence.steps());
            case Repeat repeat->pending.add(repeat.body());
            case Parallel parallel->pending.addAll(parallel.branches());
            default->{} // Calls name routines already scanned once; do not recursively follow them.
        }
        return Set.copyOf(result);
    }
    private static Reload rejected(String code,String message,Snapshot unchanged){return new Reload(false,unchanged,List.of(error(code,"behaviors",message)),0,0);}
    private static Diagnostic error(String code,String field,String message){return new Diagnostic(Diagnostic.Severity.ERROR,code,field,safe(message,800));}
    private static String safe(String value,int maximum){
        if(value==null||value.isBlank())return "Unavailable";
        StringBuilder result=new StringBuilder();int bytes=0;
        for(int offset=0;offset<value.length();){int point=value.codePointAt(offset);offset+=Character.charCount(point);if(point>=0xd800&&point<=0xdfff)point='?';int length=point<128?1:point<2048?2:point<65536?3:4;if(bytes+length>maximum)break;result.appendCodePoint(point);bytes+=length;}
        return result.toString();
    }
    private static final class Candidate {
        final String path;final BehaviorSourceReader.Source source;final List<Diagnostic> errors=new ArrayList<>();
        String declaredId;boolean identitySafe;int omitted;Long revision;JsonObject object;Accepted accepted;SourceState state=SourceState.INVALID;
        Candidate(String path,BehaviorSourceReader.Source source,Accepted accepted){this.path=path;this.source=source;this.accepted=accepted;}
        static Candidate missing(Entry previous){Candidate candidate=new Candidate(previous.path(),null,previous.accepted().orElseThrow());candidate.state=SourceState.MISSING;candidate.add(error("behavior-source-missing","$","Source is absent; retained program is unavailable until restored or explicitly removed"));return candidate;}
        void add(Diagnostic diagnostic){if(errors.size()<31)errors.add(diagnostic);else omitted++;}
        List<Diagnostic> diagnostics(){if(omitted==0)return List.copyOf(errors);List<Diagnostic> result=new ArrayList<>(errors);result.add(error("behavior-diagnostics-truncated","$",omitted+" additional diagnostics omitted"));return List.copyOf(result);}
        Entry entry(){return new Entry(path,Optional.ofNullable(declaredId),Optional.ofNullable(source==null?null:source.hash()),revision==null?OptionalLong.empty():OptionalLong.of(revision),state,Optional.ofNullable(accepted),diagnostics());}
    }
}
