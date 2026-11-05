package art.arcane.animus.core.behavior.catalog;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Secure relative opens, bounded scans and raw byte hashes over {@code behaviors/} and each {@code packs/<pack>/} manifest
 * and behavior tree. Other pack contents are not read. Does not create, lock or recover any file.
 */
final class BehaviorSourceReader {
    static final String MANIFEST="pack.json";
    record Fingerprint(Object key,long size,long modifiedNanos,boolean directory,String hash){}
    record Source(String path,String hash,long bytes,String text,String error){}
    /** {@code manifests} maps every pack directory to its manifest, or to null when the directory has none. */
    record Scan(Map<String,Source> sources,Map<String,Source> manifests,Map<String,Fingerprint> manifest,int entries,long bytes){
        Scan{sources=Map.copyOf(sources);manifests=Collections.unmodifiableMap(new TreeMap<>(manifests));manifest=Map.copyOf(manifest);}
    }
    private final Path root;private final Object rootKey;private BehaviorCatalog.Limits limits; // the catalog's lock
    void limits(BehaviorCatalog.Limits next){limits=Objects.requireNonNull(next);}
    BehaviorSourceReader(Path dataRoot,BehaviorCatalog.Limits limits)throws IOException{
        if(Files.isSymbolicLink(dataRoot))throw new IOException("Data root symlinks are not allowed");
        root=dataRoot.toRealPath();BasicFileAttributes attributes=Files.readAttributes(root,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!attributes.isDirectory()||attributes.fileKey()==null)throw new IOException("Data root requires a directory with stable file identity");
        rootKey=attributes.fileKey();this.limits=limits;
    }
    Scan scan(boolean retainText)throws IOException{
        BasicFileAttributes before=Files.readAttributes(root,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!before.isDirectory()||!rootKey.equals(before.fileKey()))throw new IOException("Data root identity changed");
        try(DirectoryStream<Path> opened=Files.newDirectoryStream(root)){
            if(!(opened instanceof SecureDirectoryStream<Path> directory))throw new IOException("Filesystem provider lacks secure directory scanning");
            if(!rootKey.equals(directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))throw new IOException("Data root changed during open");
            pending(directory);
            ScanBuilder builder=new ScanBuilder(retainText);
            builder.tree(directory,Path.of("behaviors"),"behaviors",true);
            builder.packs(directory);
            pending(directory);
            verifyRoot();
            return new Scan(builder.sources,builder.manifests,builder.manifest,builder.entries,builder.bytes);
        }catch(DirectoryIteratorException error){throw error.getCause();}
    }
    private void verifyRoot()throws IOException{
        BasicFileAttributes current=Files.readAttributes(root,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!current.isDirectory()||!rootKey.equals(current.fileKey()))throw new IOException("Data root identity changed during scan");
    }
    private static void pending(SecureDirectoryStream<Path> root)throws IOException{
        BasicFileAttributes state=attributes(root,Path.of("state"));if(state==null)return;
        if(state.isSymbolicLink()||!state.isDirectory())throw new IOException("State path must be a real directory");
        try(SecureDirectoryStream<Path> directory=root.newDirectoryStream(Path.of("state"),NOFOLLOW_LINKS)){
            if(attributes(directory,Path.of("pending.json"))!=null)throw new IOException("Pending transaction requires recovery; catalog leaves all files unchanged");
        }
    }
    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory,Path name)throws IOException{
        try{return directory.getFileAttributeView(name,BasicFileAttributeView.class,NOFOLLOW_LINKS).readAttributes();}
        catch(NoSuchFileException missing){return null;}
    }
    private static BasicFileAttributes required(SecureDirectoryStream<Path> directory,Path name)throws IOException{
        BasicFileAttributes found=attributes(directory,name);if(found==null)throw new IOException("Entry disappeared during scan");return found;
    }
    private static Fingerprint fingerprint(BasicFileAttributes attributes,String hash){
        return new Fingerprint(attributes.fileKey(),attributes.size(),attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),attributes.isDirectory(),hash);
    }
    private static void realDirectory(BasicFileAttributes attributes,String relative)throws IOException{
        if(attributes.isSymbolicLink()||!attributes.isDirectory()||attributes.fileKey()==null)throw new IOException(relative+" must be a real directory with stable file identity");
    }
    private final class ScanBuilder {
        final boolean retainText;final Map<String,Source> sources=new TreeMap<>(),manifests=new TreeMap<>();final Map<String,Fingerprint> manifest=new TreeMap<>();int entries;long bytes;
        ScanBuilder(boolean retainText){this.retainText=retainText;}
        /** Walks one behavior tree if present. */
        void tree(SecureDirectoryStream<Path> parent,Path name,String relative,boolean budgetRoot)throws IOException{
            BasicFileAttributes attributes=attributes(parent,name);
            if(attributes==null)return;
            realDirectory(attributes,relative);
            manifest.put(relative,fingerprint(attributes,null));if(budgetRoot)entry();
            try(SecureDirectoryStream<Path> child=parent.newDirectoryStream(name,NOFOLLOW_LINKS)){
                if(!Objects.equals(attributes.fileKey(),child.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))throw new IOException("Behavior directory changed during open");
                walk(child,relative,0);
            }
            if(!fingerprint(attributes,null).equals(fingerprint(required(parent,name),null)))throw new IOException("Behavior directory changed during scan");
        }
        void packs(SecureDirectoryStream<Path> root)throws IOException{
            BasicFileAttributes attributes=attributes(root,Path.of("packs"));
            if(attributes==null)return;
            realDirectory(attributes,"packs");
            manifest.put("packs",fingerprint(attributes,null));entry();
            try(SecureDirectoryStream<Path> packs=root.newDirectoryStream(Path.of("packs"),NOFOLLOW_LINKS)){
                if(!Objects.equals(attributes.fileKey(),packs.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))throw new IOException("Pack directory changed during open");
                for(Path name:names(packs)){
                    String relative="packs/"+name;BasicFileAttributes pack=required(packs,name);
                    if(pack.isSymbolicLink())throw new IOException("Pack symlinks are not allowed");
                    if(!pack.isDirectory()){manifest.put(relative,fingerprint(pack,null));continue;}
                    realDirectory(pack,relative);manifest.put(relative,fingerprint(pack,null));
                    try(SecureDirectoryStream<Path> directory=packs.newDirectoryStream(name,NOFOLLOW_LINKS)){
                        if(!pack.fileKey().equals(directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))throw new IOException("Pack directory changed during open");
                        BasicFileAttributes file=attributes(directory,Path.of(MANIFEST));
                        Source found=null;
                        if(file!=null){
                            if(file.isSymbolicLink()||!file.isRegularFile())throw new IOException(relative+"/"+MANIFEST+" must be a regular file");
                            found=read(directory,Path.of(MANIFEST),relative+"/"+MANIFEST,file);
                        }
                        manifests.put(name.toString(),found);
                        tree(directory,Path.of("behaviors"),relative+"/behaviors",false);
                    }
                    if(!fingerprint(pack,null).equals(fingerprint(required(packs,name),null)))throw new IOException("Pack directory changed during scan");
                }
            }
            if(!fingerprint(attributes,null).equals(fingerprint(required(root,Path.of("packs")),null)))throw new IOException("Pack directory changed during scan");
        }
        private void entry()throws IOException{if(++entries>limits.maximumEntries())throw new IOException("Behavior tree entry budget exceeded");}
        private List<Path> names(SecureDirectoryStream<Path> directory)throws IOException{
            List<Path> names=new ArrayList<>();
            for(Path path:directory){entry();names.add(path.getFileName());}
            names.sort(Comparator.comparing(Path::toString));
            return names;
        }
        void walk(SecureDirectoryStream<Path> directory,String parent,int depth)throws IOException{
            for(Path name:names(directory)){
                if(depth+1>limits.maximumDepth())throw new IOException("Behavior tree depth budget exceeded");
                String relative=parent+"/"+name;BasicFileAttributes before=required(directory,name);
                if(manifest.containsKey(relative))throw new IOException("Ambiguous filesystem path representation");
                if(before.fileKey()==null)throw new IOException("Behavior entry has no stable file identity");
                if(before.isSymbolicLink())throw new IOException("Behavior tree symlinks are not allowed");
                if(before.isDirectory()){
                    manifest.put(relative,fingerprint(before,null));
                    try(SecureDirectoryStream<Path> child=directory.newDirectoryStream(name,NOFOLLOW_LINKS)){
                        if(!before.fileKey().equals(child.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))throw new IOException("Behavior directory changed during open");
                        walk(child,relative,depth+1);
                    }
                    if(!fingerprint(before,null).equals(fingerprint(required(directory,name),null)))throw new IOException("Behavior directory changed during scan");
                }else if(before.isRegularFile()){
                    if(!name.toString().toLowerCase(Locale.ROOT).endsWith(".json")){manifest.put(relative,fingerprint(before,null));continue;}
                    if(sources.size()>=limits.maximumDocuments())throw new IOException("Behavior document budget exceeded");
                    sources.put(relative,read(directory,name,relative,before));
                }else throw new IOException("Behavior tree contains a non-regular filesystem entry");
            }
        }
        private Source read(SecureDirectoryStream<Path> directory,Path name,String relative,BasicFileAttributes before)throws IOException{
            if(before.size()>limits.maximumAggregateBytes()-bytes)throw new IOException("Behavior aggregate byte budget exceeded");bytes+=before.size();
            String hash=null,text=null,error=null;
            if(before.size()>limits.maximumDocumentBytes())error="behavior-document-too-large";
            else{
                byte[] raw;
                try(var channel=directory.newByteChannel(name,Set.of(StandardOpenOption.READ,NOFOLLOW_LINKS));InputStream input=Channels.newInputStream(channel)){
                    raw=input.readNBytes(limits.maximumDocumentBytes()+1);
                }
                if(raw.length!=before.size())throw new IOException("Behavior file changed during read");
                hash=hash(raw);
                if(retainText)try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}
                catch(CharacterCodingException invalid){error="behavior-utf8-invalid";}
            }
            BasicFileAttributes after=required(directory,name);
            if(!fingerprint(before,null).equals(fingerprint(after,null)))throw new IOException("Behavior file changed during read");
            manifest.put(relative,fingerprint(after,hash));
            return new Source(relative,hash,before.size(),text,error);
        }
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
