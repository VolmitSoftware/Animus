package art.arcane.animus.core.extension.loader;

import art.arcane.animus.api.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Bounded top-level archive discovery. Archives are read once and never extracted or loaded by URL. */
public final class ModuleDiscovery {
    public record Limits(int modules,int directoryEntries,int archiveBytes,int expandedBytes,int entryBytes,int entries,long totalExpanded,int totalEntries) {
        public Limits {if(modules<1||modules>64||directoryEntries<modules||directoryEntries>4096||archiveBytes<1||archiveBytes>16*1024*1024||expandedBytes<1||expandedBytes>32*1024*1024||entryBytes<1||entryBytes>8*1024*1024||entries<1||entries>4096||totalExpanded<1||totalExpanded>128L*1024*1024||totalEntries<1||totalEntries>16384)throw new IllegalArgumentException("Invalid discovery limits");}
        public static Limits defaults(){return new Limits(64,4096,16*1024*1024,32*1024*1024,8*1024*1024,4096,128L*1024*1024,16384);}
    }
    public record Problem(String archive,String id,String reason) {}
    static final class Candidate {
        final String archive,sha256;final ModuleDescriptor descriptor;final Map<String,byte[]> content;final Set<String> classes;
        Candidate(String archive,String sha256,ModuleDescriptor descriptor,Map<String,byte[]> content,Set<String> classes){this.archive=archive;this.sha256=sha256;this.descriptor=descriptor;this.content=content;this.classes=classes;}
        String id(){return descriptor.manifest().id();}
    }
    public static final class Plan {
        private List<Candidate> ordered;private final List<Problem> problems;private final Platform platform;
        private final List<ModuleDescriptor> descriptors;private final Map<String,String> hashes;private boolean consumed;
        Plan(List<Candidate> ordered,List<Problem> problems,Platform platform){this.ordered=List.copyOf(ordered);this.problems=List.copyOf(problems);this.platform=platform;descriptors=ordered.stream().map(c->c.descriptor).toList();Map<String,String> map=new TreeMap<>();for(Candidate c:ordered)map.put(c.archive,c.sha256);hashes=Map.copyOf(map);}
        public List<String> order(){return descriptors.stream().map(d->d.manifest().id()).toList();}
        public List<ModuleDescriptor> modules(){return descriptors;}
        public List<Problem> problems(){return problems;}
        public Platform platform(){return platform;}
        public Map<String,String> archiveHashes(){return hashes;}
        synchronized List<Candidate> take(){if(consumed)throw new IllegalStateException("Discovery plan already consumed");consumed=true;List<Candidate> result=ordered;ordered=List.of();return result;}
        synchronized void releaseUnused(){ordered=List.of();consumed=true;}
    }
    private static final class Budget {long expanded;int entries;}
    private ModuleDiscovery(){}
    public static Plan discover(Path directory,Platform platform)throws IOException{return discover(directory,platform,Limits.defaults());}
    public static Plan discover(Path directory,Platform platform,Limits limits)throws IOException {
        Objects.requireNonNull(platform);Path root=directory.toAbsolutePath().normalize();rejectLinks(root);
        if(!Files.exists(root,NOFOLLOW_LINKS))return new Plan(List.of(),List.of(),platform);
        if(!Files.isDirectory(root,NOFOLLOW_LINKS))throw new IOException("Module root must be a directory");
        List<Path> files=new ArrayList<>();int seen=0;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(root)) {
            for(Path entry:stream){if(++seen>limits.directoryEntries())throw new IOException("Module directory entry limit");if(entry.getFileName().toString().endsWith(".jar"))files.add(entry);}
        }
        if(files.size()>limits.modules())throw new IOException("Module archive admission limit");
        files.sort(Comparator.comparing(p->p.getFileName().toString()));Budget budget=new Budget();List<Problem> problems=new ArrayList<>();List<Candidate> parsed=new ArrayList<>();
        for(Path file:files) {
            try{parsed.add(read(file,limits,budget));}
            catch(IOException|IllegalArgumentException error){problems.add(new Problem(file.getFileName().toString(),"",error.getMessage()));}
            if(budget.expanded>limits.totalExpanded()||budget.entries>limits.totalEntries())throw new IOException("Aggregate module expansion/admission limit");
        }
        Map<String,Candidate> unique=new TreeMap<>();Set<String> blocked=new HashSet<>();
        for(Candidate candidate:parsed)if(unique.putIfAbsent(candidate.id(),candidate)!=null)blocked.add(candidate.id());
        for(Candidate c:parsed) {
            String reason=blocked.contains(c.id())?"duplicate-id":c.id().equals("animus")?"reserved-id":!c.descriptor.manifest().api().accepts(AnimusApi.API_VERSION)?"api-incompatible":!c.descriptor.manifest().platforms().contains(platform)?"platform-incompatible":null;
            if(reason!=null){blocked.add(c.id());problems.add(new Problem(c.archive,c.id(),reason));}
        }
        // Iterative invalidation covers missing/incompatible dependencies and their dependents.
        boolean changed;
        do {changed=false;for(Candidate c:unique.values())if(!blocked.contains(c.id()))for(var dep:c.descriptor.manifest().dependencies()){
            Candidate target=unique.get(dep.id());
            if(target==null||blocked.contains(dep.id())||!dep.accepts(target.descriptor.manifest().version())){blocked.add(c.id());problems.add(new Problem(c.archive,c.id(),"dependency-unavailable: "+dep.id()));changed=true;break;}
        }}while(changed);
        List<Candidate> ordered=new ArrayList<>();Set<String> emitted=new HashSet<>();
        while(true){Candidate next=unique.values().stream().filter(c->!blocked.contains(c.id())&&!emitted.contains(c.id())&&c.descriptor.manifest().dependencies().stream().allMatch(d->emitted.contains(d.id()))).findFirst().orElse(null);if(next==null)break;ordered.add(next);emitted.add(next.id());}
        for(Candidate c:unique.values())if(!blocked.contains(c.id())&&!emitted.contains(c.id())){blocked.add(c.id());problems.add(new Problem(c.archive,c.id(),"dependency-cycle-or-dependent"));}
        List<Candidate> ready=new ArrayList<>();Set<String> admitted=new HashSet<>();
        for(Candidate c:ordered) {
            if(c.descriptor.manifest().dependencies().stream().anyMatch(d->!admitted.contains(d.id()))){problems.add(new Problem(c.archive,c.id(),"dependency-unavailable"));continue;}
            Set<String> visible=new HashSet<>(c.classes);boolean collision=false;
            for(var dep:c.descriptor.manifest().dependencies())for(String name:unique.get(dep.id()).classes)if(unique.get(dep.id()).descriptor.exports(name)&&!visible.add(name))collision=true;
            if(collision){problems.add(new Problem(c.archive,c.id(),"ambiguous-visible-class"));continue;}
            ready.add(c);admitted.add(c.id());
        }
        return new Plan(ready,problems,platform);
    }
    private static void rejectLinks(Path path)throws IOException {for(Path current=path;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw new IOException("Symbolic links are not accepted in module paths");}
    private static Candidate read(Path file,Limits limits,Budget budget)throws IOException {
        rejectLinks(file);BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>limits.archiveBytes())throw new IOException("Archive must be a bounded regular file");
        byte[] bytes;
        try(SeekableByteChannel channel=Files.newByteChannel(file,Set.of(StandardOpenOption.READ,NOFOLLOW_LINKS));InputStream stream=Channels.newInputStream(channel)){
            bytes=stream.readNBytes(limits.archiveBytes()+1);if(bytes.length>limits.archiveBytes())throw new IOException("Archive byte limit");
        }
        BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||before.size()!=after.size())throw new IOException("Archive changed while reading; restart with a stable binary");
        if(bytes.length<4||bytes[0]!='P'||bytes[1]!='K'||bytes[2]!=3||bytes[3]!=4)throw new IOException("Expected a ZIP/JAR archive");
        Map<String,byte[]> content=new HashMap<>();Set<String> names=new HashSet<>(),classes=new HashSet<>();long expanded=0;int count=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(++count>limits.entries()||++budget.entries>limits.totalEntries())throw new IOException("Archive entry count limit");
                String name=entry.getName();
                if(name.length()>512||name.startsWith("/")||name.contains("\\")||name.contains(":")||Arrays.stream(name.split("/",-1)).anyMatch(s->s.equals(".")||s.equals(".."))||!names.add(name))throw new IOException("Unsafe or duplicate archive path");
                if(name.startsWith("META-INF/versions/")||name.endsWith(".jar"))throw new IOException("Multi-release and nested archives are unsupported");
                // closeEntry() drains until EOF; never call it on an unbounded directory payload.
                if(entry.isDirectory()){if(zip.read()!=-1){budget.expanded++;throw new IOException("Directory entry contains payload");}zip.closeEntry();continue;}
                int maximum=name.equals(ModuleDescriptor.FILE)?65536:limits.entryBytes();
                byte[] body=zip.readNBytes(maximum+1);expanded+=body.length;budget.expanded+=body.length;
                if(body.length>maximum||expanded>limits.expandedBytes()||budget.expanded>limits.totalExpanded())throw new IOException("Archive expanded byte limit");
                if(name.endsWith(".class")){
                    String binary=name.substring(0,name.length()-6).replace('/','.');
                    if(ModuleDescriptor.reserved(binary)||binary.equals("module-info")||!ModuleDescriptor.binaryName(binary))throw new IOException("Reserved/unsupported class namespace");classes.add(binary);
                }
                content.put(name,body);zip.closeEntry();
            }
        }
        byte[] declaration=content.get(ModuleDescriptor.FILE);if(declaration==null)throw new IOException("Missing "+ModuleDescriptor.FILE);
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(declaration)).toString();
        ModuleDescriptor descriptor=ModuleDescriptor.parse(text);
        if(!classes.contains(descriptor.main()))throw new IOException("Declared entrypoint class absent");
        byte[] javaManifest=content.get("META-INF/MANIFEST.MF");
        if(javaManifest!=null){Manifest jar=new Manifest(new ByteArrayInputStream(javaManifest));if(jar.getMainAttributes().getValue("Class-Path")!=null||"true".equalsIgnoreCase(jar.getMainAttributes().getValue("Multi-Release")))throw new IOException("External Class-Path and multi-release manifests are unsupported");}
        try{return new Candidate(file.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),descriptor,Map.copyOf(content),Set.copyOf(classes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
