package peri;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Per-user, version-independent folder history; never stored in a patient project. */
final class DirectoryHistory {
    static final String IMPORT="importDirectory", OUTPUT="outputDirectory";
    final Path file;
    DirectoryHistory(Path file){this.file=file.toAbsolutePath().normalize();}
    static DirectoryHistory user(){
        String override=System.getProperty("colormetric.settings.file");
        if(override!=null&&!override.isBlank())return new DirectoryHistory(Path.of(override));
        String local=System.getenv("LOCALAPPDATA");
        Path root=local!=null&&!local.isBlank()?Path.of(local,"ColorMetricStudio"):Path.of(System.getProperty("user.home"),".colormetric-studio");
        // Read-only compatibility with folder history saved before the product rename.
        Path legacy=local!=null&&!local.isBlank()?Path.of(local,"PeriorbitalStudio","settings.properties"):Path.of(System.getProperty("user.home"),".periorbital-studio","settings.properties");
        return migrated(root.resolve("settings.properties"),legacy);
    }
    static DirectoryHistory migrated(Path target,Path legacy){
        DirectoryHistory current=new DirectoryHistory(target);
        if(!Files.exists(current.file)){
            DirectoryHistory previous=new DirectoryHistory(legacy);
            for(String key:new String[]{IMPORT,OUTPUT}){
                File remembered=previous.recalled(key);
                if(remembered!=null)current.remember(key,remembered);
            }
        }
        return current;
    }
    Properties read(){Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}catch(IOException|IllegalArgumentException ignored){}return p;}
    File recalled(String key){
        String value=read().getProperty(key);if(value==null||value.isBlank())return null;
        try{File folder=Path.of(value).toAbsolutePath().normalize().toFile();return folder.isDirectory()?folder:null;}
        catch(InvalidPathException|SecurityException e){return null;}
    }
    synchronized boolean remember(String key,File folder){
        if(folder==null||!folder.isDirectory())return false;
        Path temporary=null;
        try{
            Properties p=read();p.setProperty(key,folder.toPath().toAbsolutePath().normalize().toString());
            Files.createDirectories(file.getParent());temporary=Files.createTempFile(file.getParent(),"folder-history-",".tmp");
            try(OutputStream out=Files.newOutputStream(temporary)){p.store(out,"ColorMetric Studio folder history");}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
            return true;
        }catch(IOException|SecurityException e){return false;}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException|SecurityException ignored){}}
    }
}
