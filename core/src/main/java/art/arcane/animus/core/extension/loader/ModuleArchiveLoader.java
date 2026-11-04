package art.arcane.animus.core.extension.loader;

import art.arcane.animus.api.TrustedModule;
import java.io.*;
import java.net.*;
import java.util.*;

/** Compatibility isolation for trusted code, NOT a Java security sandbox. No parent implementation fallback. */
final class ModuleArchiveLoader extends ClassLoader implements AutoCloseable {
    static {registerAsParallelCapable();}
    private final ModuleDescriptor descriptor;
    private final String digest;
    private final ClassLoader api=TrustedModule.class.getClassLoader();
    private final List<ModuleArchiveLoader> dependencies;
    private Map<String,byte[]> content;
    private volatile boolean closed;
    ModuleArchiveLoader(ModuleDiscovery.Candidate candidate,List<ModuleArchiveLoader> dependencies){
        super(null);descriptor=candidate.descriptor;digest=candidate.sha256;this.dependencies=List.copyOf(dependencies);content=new HashMap<>(candidate.content);
    }
    @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
        synchronized(getClassLoadingLock(name)){
            Class<?> found=findLoadedClass(name);
            if(found==null){
                if(name.startsWith("art.arcane.animus.api."))found=api.loadClass(name);
                else if(name.startsWith("art.arcane.animus."))throw new ClassNotFoundException("Private Animus classes are not module API");
                else {
                    try{found=ClassLoader.getPlatformClassLoader().loadClass(name);}catch(ClassNotFoundException ignored){}
                    if(found==null){
                        if(closed)throw new ClassNotFoundException("Module archive closed");
                        byte[] bytes; synchronized(this){bytes=content.get(name.replace('.','/')+".class");}
                        if(bytes!=null)found=defineClass(name,bytes,0,bytes.length);
                        else for(ModuleArchiveLoader dependency:dependencies)if(dependency.descriptor.exports(name)){
                            try{found=dependency.exportedClass(name);break;}catch(ClassNotFoundException ignored){}
                        }
                    }
                }
            }
            if(found==null)throw new ClassNotFoundException(name);
            if(resolve)resolveClass(found);return found;
        }
    }
    private Class<?> exportedClass(String name)throws ClassNotFoundException {
        synchronized(this){if(closed||!content.containsKey(name.replace('.','/')+".class"))throw new ClassNotFoundException(name);}
        return loadClass(name);
    }
    @Override public URL getResource(String name){
        if(!resourcePath(name)||closed)return null;
        if(name.startsWith("art/arcane/animus/api/"))return api.getResource(name);
        if(name.startsWith("art/arcane/animus/"))return null;
        URL found=localResource(name);if(found!=null)return found;
        for(ModuleArchiveLoader dependency:dependencies)if(dependency.descriptor.exports(name.replace('/','.'))){found=dependency.localResource(name);if(found!=null)return found;}
        return ClassLoader.getPlatformClassLoader().getResource(name);
    }
    @Override public Enumeration<URL> getResources(String name)throws IOException {
        URL resource=getResource(name);return Collections.enumeration(resource==null?List.of():List.of(resource));
    }
    private static boolean resourcePath(String name){return name!=null&&!name.startsWith("/")&&!name.contains("\\")&&!name.contains(":")&&Arrays.stream(name.split("/",-1)).noneMatch(p->p.equals(".")||p.equals(".."));}
    private synchronized URL localResource(String name){
        if(closed||!content.containsKey(name))return null;
        try{return new URL(null,"animus-module://"+digest+"/"+name,new URLStreamHandler(){
            protected URLConnection openConnection(URL url){return new URLConnection(url){
                public void connect()throws IOException {if(closed)throw new IOException("Module archive closed");connected=true;}
                public InputStream getInputStream()throws IOException {synchronized(ModuleArchiveLoader.this){connect();byte[] bytes=content.get(name);if(bytes==null)throw new FileNotFoundException(name);return new ByteArrayInputStream(bytes);}}
            };}
        });}catch(MalformedURLException invalid){throw new IllegalStateException(invalid);}
    }
    boolean closed(){return closed;}
    @Override public synchronized void close(){closed=true;content.clear();}
}
