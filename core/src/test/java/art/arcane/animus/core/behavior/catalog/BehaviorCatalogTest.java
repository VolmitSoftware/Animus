package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorCatalogTest {
    @TempDir Path root;
    private static final String EMPTY="{\"type\":\"sequence\",\"steps\":[]}",ACTION="{\"type\":\"action\",\"capability\":\"sample:greet\"}";
    private static String source(String id,long revision,String node){return "{\"schemaVersion\":1,\"revision\":"+revision+",\"id\":\""+id+"\",\"entry\":\"main\",\"routines\":{\"main\":"+node+"}}";}
    private Path put(String id,long revision,String node)throws Exception{return write(BehaviorPath.forId(id),source(id,revision,node));}
    private Path write(String relative,String text)throws Exception{Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;}
    private BehaviorCatalog catalog()throws Exception{return catalog(BehaviorCatalog.Limits.defaults(),r->BehaviorCatalog.Capability.present());}
    private BehaviorCatalog catalog(BehaviorCatalog.Limits limits,BehaviorCatalog.CapabilityResolver resolver)throws Exception{return new BehaviorCatalog(root,limits,resolver,BehaviorCheckpoints.disabled());}
    private static BehaviorCatalog.Reload reload(BehaviorCatalog catalog){return catalog.reload(catalog.snapshot().generation(),Set.of());}
    private static BehaviorCatalog.Entry entry(BehaviorCatalog catalog,String id){return catalog.snapshot().entries().get(BehaviorPath.forId(id));}
    private static BehaviorCatalog.Accepted accepted(BehaviorCatalog catalog,String id){return entry(catalog,id).accepted().orElseThrow();}
    private static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static Map<String,String> tree(Path root)throws Exception{Map<String,String> result=new TreeMap<>();try(var paths=Files.walk(root)){for(Path path:paths.toList())result.put(root.relativize(path).toString(),Files.isDirectory(path)?"directory":hash(Files.readAllBytes(path)));}return result;}

    @Test void portableMappingIsExactAndRejectsAmbiguousOrReservedNames(){
        for(String id:List.of("example:quests/town/greet","_pack-:_entry","a:com10/auxiliary"))assertEquals(id,BehaviorPath.idFor(BehaviorPath.forId(id)));
        for(String id:List.of("pack:/name","pack:name/","pack:one//two","pack:../name","pack:a.b","Pack:name","pack:con","aux:name","pack:lpt9/a","pack:a\\b","pack:"+"a".repeat(65),"a:"+"b".repeat(255)))assertThrows(IllegalArgumentException.class,()->BehaviorPath.forId(id),id);
        for(String path:List.of("behaviors/a.json","behaviors/a/name.JSON","behaviors/a/name.json.json","behaviors/a//name.json","../behaviors/a/name.json"))assertThrows(IllegalArgumentException.class,()->BehaviorPath.idFor(path));
    }
    @Test void reusableRoutesLiveOutsideTheBehaviorTreeAndNeverValidateAsBehaviors()throws Exception{
        put("sample:test",1,EMPTY);write("routes/demo-terrain.json","{\"schemaVersion\":1,\"mode\":\"walk\",\"points\":[{\"x\":1,\"y\":64,\"z\":1}]}");
        var catalog=catalog();assertTrue(reload(catalog).published());var validation=catalog.validate(catalog.snapshot().generation());
        assertTrue(validation.valid());assertEquals(Set.of(BehaviorPath.forId("sample:test")),catalog.snapshot().entries().keySet());
    }
    @Test void initialLoadIsReadOnlyAndRevisionEnvelopeIsDistinctFromStandaloneCompiler()throws Exception{
        Map<String,String> empty=tree(root);var catalog=catalog();assertTrue(reload(catalog).published());assertEquals(empty,tree(root));assertTrue(catalog.snapshot().entries().isEmpty());
        Path file=put("example:quests/greet",1,EMPTY);Map<String,String> before=tree(root);var loaded=reload(catalog);assertTrue(loaded.published());assertEquals(before,tree(root));
        assertTrue(catalog.snapshot().find("example:quests/greet").orElseThrow().available());assertEquals(hash(Files.readAllBytes(file)),accepted(catalog,"example:quests/greet").sourceHash());
        assertTrue(BehaviorCompiler.compile(Files.readString(file)).program().isEmpty(),"Catalog strips only revision before the frozen compiler");assertFalse(Files.exists(root.resolve("state")));
    }
    @Test void validationChecksChangedBytesAndRevisionWithoutPublishingOrWriting()throws Exception{
        Path file=put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var before=catalog.snapshot();var accepted=accepted(catalog,"sample:test");
        put("sample:test",2,"{\"type\":\"wait\",\"ticks\":2}");var treeBefore=tree(root);var validation=catalog.validate(before.generation());
        assertTrue(validation.scanned());assertTrue(validation.valid());assertSame(before,catalog.snapshot());assertSame(accepted,accepted(catalog,"sample:test"));assertEquals(treeBefore,tree(root));
        assertEquals(2,validation.candidate().find("sample:test").orElseThrow().accepted().orElseThrow().revision());assertEquals(before.generation(),validation.candidate().generation());
        put("sample:test",1,"{\"type\":\"wait\",\"ticks\":2}");var conflict=catalog.validate(before.generation());
        assertTrue(conflict.scanned());assertFalse(conflict.valid());assertEquals(BehaviorCatalog.SourceState.CONFLICT,conflict.candidate().entries().get(BehaviorPath.forId("sample:test")).state());
        Files.writeString(file,"{bad");var invalid=catalog.validate(before.generation());assertTrue(invalid.scanned());assertFalse(invalid.valid());assertSame(before,catalog.snapshot());assertEquals("{bad",Files.readString(file));
    }
    @Test void validationRetainsMissingProgramsAndRejectsStaleOrReentrantPublication()throws Exception{
        Path file=put("sample:test",1,ACTION);AtomicReference<BehaviorCatalog> reference=new AtomicReference<>();AtomicBoolean reenter=new AtomicBoolean();
        var catalog=catalog(BehaviorCatalog.Limits.defaults(),requirement->{
            if(reenter.get())assertFalse(reference.get().reload(reference.get().snapshot().generation(),Set.of()).published());
            return BehaviorCatalog.Capability.present();
        });reference.set(catalog);reload(catalog);var before=catalog.snapshot();reenter.set(true);
        assertTrue(catalog.validate(before.generation()).valid());assertSame(before,catalog.snapshot());
        assertFalse(catalog.validate(before.generation()-1).scanned());assertSame(before,catalog.snapshot());
        Files.delete(file);var missing=catalog.validate(before.generation());assertTrue(missing.scanned());assertFalse(missing.valid());assertSame(before,catalog.snapshot());
        assertFalse(missing.candidate().find("sample:test").orElseThrow().available());assertTrue(before.find("sample:test").orElseThrow().available());
    }
    @Test void noOpReloadPreservesAcceptedIdentityAndSnapshotsAreImmutable()throws Exception{
        put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var first=catalog.snapshot();var program=accepted(catalog,"sample:test");reload(catalog);
        assertSame(program,accepted(catalog,"sample:test"));assertEquals(first.generation()+1,catalog.snapshot().generation());assertEquals(1,first.generation());
        assertThrows(UnsupportedOperationException.class,()->first.entries().clear());assertThrows(UnsupportedOperationException.class,()->first.byId().clear());assertThrows(UnsupportedOperationException.class,()->first.entries().values().iterator().next().diagnostics().clear());
    }
    @Test void changedBytesRequireIncreasingRevisionIncludingWhitespaceEdits()throws Exception{
        Path path=put("sample:test",3,EMPTY);var catalog=catalog();reload(catalog);var previous=accepted(catalog,"sample:test");
        for(String changed:List.of(source("sample:test",2,EMPTY),source("sample:test",3,EMPTY)+"\n")){
            Files.writeString(path,changed);assertTrue(reload(catalog).published());assertEquals(BehaviorCatalog.SourceState.CONFLICT,entry(catalog,"sample:test").state());assertSame(previous,accepted(catalog,"sample:test"));assertTrue(catalog.snapshot().find("sample:test").orElseThrow().available());assertEquals(changed,Files.readString(path));
        }
        put("sample:test",4,"{\"type\":\"wait\",\"ticks\":1}");reload(catalog);assertEquals(4,accepted(catalog,"sample:test").revision());assertNotSame(previous,accepted(catalog,"sample:test"));
    }
    @Test void invalidEditRetainsLastValidButNewProcessHasNoRecoveryCheckpoint()throws Exception{
        Path file=put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var previous=accepted(catalog,"sample:test");String invalid="{broken source unchanged";Files.writeString(file,invalid);reload(catalog);
        assertSame(previous,accepted(catalog,"sample:test"));assertEquals(BehaviorCatalog.SourceState.INVALID,entry(catalog,"sample:test").state());assertTrue(catalog.snapshot().find("sample:test").orElseThrow().available());assertEquals(invalid,Files.readString(file));
        var restarted=catalog();reload(restarted);assertTrue(entry(restarted,"sample:test").accepted().isEmpty());assertTrue(restarted.snapshot().byId().isEmpty());assertFalse(Files.exists(root.resolve("state")));
    }
    @Test void missingCapabilitiesRemainCompiledAndCanBecomeAvailableWithoutSourceChange()throws Exception{
        AtomicBoolean available=new AtomicBoolean();put("sample:test",1,ACTION);var catalog=catalog(BehaviorCatalog.Limits.defaults(),r->new BehaviorCatalog.Capability(available.get(),"module missing"));reload(catalog);var previous=accepted(catalog,"sample:test");
        assertEquals(BehaviorCatalog.SourceState.VALID,entry(catalog,"sample:test").state());assertFalse(catalog.snapshot().find("sample:test").orElseThrow().available());assertTrue(entry(catalog,"sample:test").diagnostics().stream().anyMatch(d->d.code().equals("behavior-capability-unavailable")));
        available.set(true);reload(catalog);assertSame(previous,accepted(catalog,"sample:test"));assertTrue(catalog.snapshot().find("sample:test").orElseThrow().available());
    }
    @Test void duplicateInitialIdsNeverChooseCanonicalPathAsWinner()throws Exception{
        put("sample:test",1,EMPTY);write("behaviors/other/copy.json",source("sample:test",2,ACTION));var catalog=catalog();reload(catalog);
        assertEquals(2,catalog.snapshot().entries().size());assertTrue(catalog.snapshot().entries().values().stream().allMatch(e->e.state()==BehaviorCatalog.SourceState.DUPLICATE&&e.accepted().isEmpty()));
        var resolution=catalog.snapshot().find("sample:test").orElseThrow();assertFalse(resolution.available());assertTrue(resolution.accepted().isEmpty());
    }
    @Test void duplicateLaterIdRetainsInspectableProgramButRevokesResolutionUntilConflictRemoved()throws Exception{
        put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var previous=accepted(catalog,"sample:test");Path duplicate=write("behaviors/other/copy.json",source("sample:test",9,ACTION));reload(catalog);
        assertSame(previous,accepted(catalog,"sample:test"));assertFalse(catalog.snapshot().find("sample:test").orElseThrow().available());assertTrue(catalog.snapshot().find("sample:test").orElseThrow().accepted().isEmpty());
        Files.delete(duplicate);reload(catalog);assertSame(previous,accepted(catalog,"sample:test"));assertTrue(catalog.snapshot().find("sample:test").orElseThrow().available());assertEquals(1,catalog.snapshot().entries().size());
    }
    @Test void missingAcceptedSourceRequiresExplicitMatchingRemovalAndCanBeRestored()throws Exception{
        Path file=put("sample:test",1,EMPTY);String original=Files.readString(file);var catalog=catalog();reload(catalog);var token=BehaviorCatalog.Removal.of(entry(catalog,"sample:test"));var prior=accepted(catalog,"sample:test");
        assertFalse(catalog.reload(catalog.snapshot().generation(),Set.of(token)).published());Files.delete(file);reload(catalog);assertEquals(BehaviorCatalog.SourceState.MISSING,entry(catalog,"sample:test").state());assertFalse(catalog.snapshot().find("sample:test").orElseThrow().available());assertSame(prior,accepted(catalog,"sample:test"));
        Files.writeString(file,original);reload(catalog);assertSame(prior,accepted(catalog,"sample:test"));assertTrue(catalog.snapshot().find("sample:test").orElseThrow().available());Files.delete(file);reload(catalog);
        var snapshot=catalog.snapshot();assertFalse(catalog.reload(snapshot.generation(),Set.of(new BehaviorCatalog.Removal(token.path(),2,token.acceptedSourceHash()))).published());assertSame(snapshot,catalog.snapshot());
        assertFalse(catalog.reload(snapshot.generation(),Set.of(new BehaviorCatalog.Removal(token.path(),1,"0".repeat(64)))).published());assertSame(snapshot,catalog.snapshot());
        assertFalse(catalog.reload(snapshot.generation()-1,Set.of(token)).published());assertSame(snapshot,catalog.snapshot());assertTrue(catalog.reload(snapshot.generation(),Set.of(token)).published());assertTrue(catalog.snapshot().entries().isEmpty());assertTrue(catalog.snapshot().byId().isEmpty());assertFalse(Files.exists(file));
        Files.writeString(file,"broken replacement after explicit removal");reload(catalog);assertTrue(entry(catalog,"sample:test").accepted().isEmpty());assertTrue(catalog.snapshot().byId().isEmpty());
    }
    @Test void invalidPathAndEnvelopeAreVisibleAndOriginalBytesPreserved()throws Exception{
        for(String relative:List.of("behaviors/sample/UPPER.json","behaviors/sample/test.JSON","behaviors/sample/con.json","behaviors/loose.json"))write(relative,source("sample:test",1,EMPTY));
        var catalog=catalog();var before=tree(root);reload(catalog);assertEquals(before,tree(root));assertEquals(4,catalog.snapshot().entries().size());assertTrue(catalog.snapshot().entries().values().stream().allMatch(e->e.accepted().isEmpty()));
        for(String revision:List.of("null","\"1\"","[1]","0","1.5","9223372036854775808")){
            Path clean=write("behaviors/isolated/test.json",source("isolated:test",1,EMPTY).replace("\"revision\":1","\"revision\":"+revision));reload(catalog);assertTrue(entry(catalog,"isolated:test").accepted().isEmpty());assertTrue(entry(catalog,"isolated:test").diagnostics().stream().anyMatch(d->d.field().equals("$.revision")));Files.delete(clean);
        }
    }
    @Test void malformedUtf8AndDuplicateJsonKeysRetainAcceptedValueAndExactRawHash()throws Exception{
        Path file=put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var previous=accepted(catalog,"sample:test");byte[] invalid={(byte)0xc3,(byte)0x28};Files.write(file,invalid);reload(catalog);
        assertSame(previous,accepted(catalog,"sample:test"));assertEquals(hash(invalid),entry(catalog,"sample:test").observedHash().orElseThrow());assertArrayEquals(invalid,Files.readAllBytes(file));
        Files.writeString(file,source("sample:test",2,EMPTY).replace("\"revision\":2","\"revision\":2,\"revision\":3"));reload(catalog);assertSame(previous,accepted(catalog,"sample:test"));assertTrue(entry(catalog,"sample:test").diagnostics().stream().anyMatch(d->d.code().equals("behavior-json-invalid")));
    }
    @Test void documentEntryAndDepthBudgetsIncludeUnrelatedTreeEntries()throws Exception{
        put("sample:test",1,EMPTY);var base=BehaviorCatalog.Limits.defaults();
        for(var limits:List.of(new BehaviorCatalog.Limits(1,10,8,base.maximumDocumentBytes(),base.maximumAggregateBytes(),8192),new BehaviorCatalog.Limits(10,3,8,base.maximumDocumentBytes(),base.maximumAggregateBytes(),8192),new BehaviorCatalog.Limits(10,20,2,base.maximumDocumentBytes(),base.maximumAggregateBytes(),8192))){
            var catalog=catalog(limits,r->BehaviorCatalog.Capability.present());assertTrue(reload(catalog).published());var previous=catalog.snapshot();
            Path extra=limits.maximumDocuments()==1?put("sample:second",1,EMPTY):write(limits.maximumDepth()==2?"behaviors/sample/nested/ignored.txt":"behaviors/sample/ignored.txt","ignored");
            assertFalse(reload(catalog).published());assertSame(previous,catalog.snapshot());Files.delete(extra);if(limits.maximumDepth()==2)Files.delete(extra.getParent());
        }
    }
    @Test void perFileAndAggregateByteLimitsHaveDifferentRetainedOutcomes()throws Exception{
        String original=source("sample:test",1,EMPTY);Path file=put("sample:test",1,EMPTY);int length=original.getBytes(StandardCharsets.UTF_8).length;
        var catalog=catalog(new BehaviorCatalog.Limits(4,20,8,length,length*3L,8192),r->BehaviorCatalog.Capability.present());reload(catalog);var previous=accepted(catalog,"sample:test");Files.writeString(file,original+" ");assertTrue(reload(catalog).published());assertSame(previous,accepted(catalog,"sample:test"));assertTrue(entry(catalog,"sample:test").observedHash().isEmpty());
        var snapshot=catalog.snapshot();Files.writeString(file," ".repeat(length*3+1));assertFalse(reload(catalog).published());assertSame(snapshot,catalog.snapshot());
    }
    @Test void retainedDocumentsAndBytesCannotGrowAcrossMissingFileGenerations()throws Exception{
        String first=source("sample:first",1,EMPTY),second=source("sample:second",1,EMPTY);Path file=write(BehaviorPath.forId("sample:first"),first);
        var one=catalog(new BehaviorCatalog.Limits(1,20,8,262144,1024*1024,8192),r->BehaviorCatalog.Capability.present());reload(one);var token=BehaviorCatalog.Removal.of(entry(one,"sample:first"));Files.delete(file);Path next=write(BehaviorPath.forId("sample:second"),second);var before=one.snapshot();assertFalse(reload(one).published());assertSame(before,one.snapshot());assertTrue(one.reload(before.generation(),Set.of(token)).published());
        Files.delete(next);write(BehaviorPath.forId("sample:first"),first);var bytes=catalog(new BehaviorCatalog.Limits(2,20,8,262144,first.length()+second.length()-1L,8192),r->BehaviorCatalog.Capability.present());reload(bytes);var remove=BehaviorCatalog.Removal.of(entry(bytes,"sample:first"));Files.delete(file);write(BehaviorPath.forId("sample:second"),second);var previous=bytes.snapshot();assertFalse(reload(bytes).published());assertSame(previous,bytes.snapshot());assertTrue(bytes.reload(previous.generation(),Set.of(remove)).published());
    }
    @Test void symlinkLeavesDirectoriesAndDanglingNonJsonEntriesRejectWholeScan()throws Exception{
        put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var before=catalog.snapshot();Path outside=Files.createDirectory(root.resolve("outside"));Files.writeString(outside.resolve("external.json"),source("outside:external",1,EMPTY));
        for(Path target:List.of(outside.resolve("external.json"),outside,root.resolve("missing"))){Path link=root.resolve("behaviors/link");Files.createSymbolicLink(link,target);assertFalse(reload(catalog).published());assertSame(before,catalog.snapshot());Files.delete(link);}
        Path link=root.resolve("linked-root");Files.createSymbolicLink(link,outside);assertThrows(java.io.IOException.class,()->new BehaviorCatalog(link,BehaviorCatalog.Limits.defaults(),r->BehaviorCatalog.Capability.present(),BehaviorCheckpoints.disabled()));
    }
    @Test void pendingJournalIsNeverRecoveredOrModifiedByCatalog()throws Exception{
        put("sample:test",1,EMPTY);var catalog=catalog();reload(catalog);var previous=catalog.snapshot();Path pending=write("state/pending.json","unparsed pending journal");var before=tree(root);assertFalse(reload(catalog).published());assertSame(previous,catalog.snapshot());assertEquals(before,tree(root));Files.delete(pending);assertTrue(reload(catalog).published());
    }
    @Test void revalidationDetectsSameLengthContentMutationEvenWithRestoredTimestamp()throws Exception{
        String node="{\"type\":\"action\",\"capability\":\"sample:greet\",\"arguments\":{\"value\":1}}";Path file=put("sample:test",1,node);AtomicBoolean mutate=new AtomicBoolean();
        var catalog=catalog(BehaviorCatalog.Limits.defaults(),r->{if(mutate.getAndSet(false))try{FileTime time=Files.getLastModifiedTime(file);String text=Files.readString(file);Files.writeString(file,text.replace("\"value\":1","\"value\":9"));Files.setLastModifiedTime(file,time);}catch(Exception e){throw new AssertionError(e);}return BehaviorCatalog.Capability.present();});
        reload(catalog);var previous=catalog.snapshot();put("sample:test",2,node);mutate.set(true);assertFalse(reload(catalog).published());assertSame(previous,catalog.snapshot());assertEquals(1,accepted(catalog,"sample:test").revision());assertTrue(reload(catalog).published());assertEquals(2,accepted(catalog,"sample:test").revision());
    }
    @Test void generationSerializesConcurrentReloadsAndRejectsResolverReentry()throws Exception{
        put("sample:test",1,EMPTY);var catalog=catalog();var first=CompletableFuture.supplyAsync(()->catalog.reload(0,Set.of()));var second=CompletableFuture.supplyAsync(()->catalog.reload(0,Set.of()));assertEquals(1,List.of(first.get(3,TimeUnit.SECONDS),second.get(3,TimeUnit.SECONDS)).stream().filter(BehaviorCatalog.Reload::published).count());
        put("sample:test",2,ACTION);AtomicReference<BehaviorCatalog> reference=new AtomicReference<>();AtomicReference<BehaviorCatalog.Reload> nested=new AtomicReference<>();var reentrant=catalog(BehaviorCatalog.Limits.defaults(),r->{nested.set(reference.get().reload(0,Set.of()));return BehaviorCatalog.Capability.present();});reference.set(reentrant);assertTrue(reload(reentrant).published());assertFalse(nested.get().published());assertEquals("catalog-reentrant-reload",nested.get().diagnostics().getFirst().code());assertEquals(1,reentrant.snapshot().generation());
    }
    @Test void capabilityChecksDeduplicateAndBoundDiagnosticsAndWork()throws Exception{
        AtomicInteger checks=new AtomicInteger();put("sample:first",1,ACTION);put("sample:second",1,ACTION);var catalog=catalog(BehaviorCatalog.Limits.defaults(),r->{checks.incrementAndGet();return new BehaviorCatalog.Capability(false,"missing");});reload(catalog);assertEquals(1,checks.get());
        JsonArray steps=new JsonArray();for(int i=0;i<40;i++){JsonObject step=new JsonObject();step.addProperty("type","action");step.addProperty("capability","sample:a"+i);steps.add(step);}JsonObject sequence=new JsonObject();sequence.addProperty("type","sequence");sequence.add("steps",steps);put("sample:first",2,sequence.toString());reload(catalog);assertEquals(32,entry(catalog,"sample:first").diagnostics().size());assertEquals("behavior-diagnostics-truncated",entry(catalog,"sample:first").diagnostics().getLast().code());
        var limited=catalog(new BehaviorCatalog.Limits(4,20,8,262144,1024*1024,1),r->BehaviorCatalog.Capability.present());var empty=limited.snapshot();assertFalse(reload(limited).published());assertSame(empty,limited.snapshot());
    }
    @Test void resolverFailureIsUnavailableAndRoutineReferencesAreScannedWithoutRecursing()throws Exception{
        String document=source("sample:test",1,"{\"type\":\"call\",\"routine\":\"main\"}");document=document.replace("\"routines\":{","\"routines\":{\"unused\":"+ACTION+",");write(BehaviorPath.forId("sample:test"),document);
        var catalog=catalog(BehaviorCatalog.Limits.defaults(),r->{throw new AssertionError("synthetic registry failure");});assertTrue(reload(catalog).published());assertEquals(Set.of(new BehaviorCatalog.Requirement(new Capabilities.Key("sample","greet"),Capabilities.Kind.ACTION)),accepted(catalog,"sample:test").requirements());assertFalse(catalog.snapshot().find("sample:test").orElseThrow().available());assertTrue(entry(catalog,"sample:test").diagnostics().getFirst().message().contains("registry-check-failed"));
    }
    @Test void unsupportedFilesystemProvidersFailExplicitlyWithoutWritingCatalogState()throws Exception{
        Path zip=root.resolve("unsupported.zip");try(var fs=FileSystems.newFileSystem(zip,Map.of("create","true"))){Path directory=fs.getPath("/");assertThrows(java.io.IOException.class,()->new BehaviorCatalog(directory,BehaviorCatalog.Limits.defaults(),r->BehaviorCatalog.Capability.present(),BehaviorCheckpoints.disabled()));assertFalse(Files.exists(directory.resolve("state")));}
    }
}
