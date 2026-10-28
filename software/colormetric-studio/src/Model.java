package peri;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.ref.SoftReference;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import ij.gui.PolygonRoi;
import ij.gui.Roi;

final class Model {
    static final String VERSION="0.1.0", SCHEMA="2", ALGORITHM="affected-lab-threshold-v2";
    enum Mode {
        PL("PL"), RED("Redness"), BROWN("Brown"), INFLAMMATION("Inflammation");
        final String label; Mode(String s){label=s;} public String toString(){return label;}
        static Mode guess(String s){String lower=s.toLowerCase(Locale.ROOT);return s.contains("红血")||lower.contains("redness")||lower.contains("vascular")?RED:s.contains("棕色")||lower.contains("brown")?BROWN:s.contains("红色区")||s.contains("炎症")||lower.contains("inflamm")||lower.contains("red_area")||lower.contains("red-area")||lower.contains("red area")?INFLAMMATION:PL;}
    }
    enum Role {
        TARGET("Measurement ROI"), REFERENCE("Reference skin"), EXCLUDE("Exclusion ROI");
        final String label; Role(String s){label=s;} public String toString(){return label;}
    }
    enum Metric {
        DARKNESS("Relative darkness: reference L* - ROI L*"), REDNESS("Relative redness: ROI a* - reference a*");
        final String label; Metric(String s){label=s;} public String toString(){return label;}
    }
    static final class Settings {
        Metric metric; double threshold;
        Settings(Metric m,double t){metric=m;threshold=t;}
    }
    static final class Region {
        String id=UUID.randomUUID().toString(),name,referenceId="";
        Role role;
        ArrayList<Point2D.Double> points=new ArrayList<>();
        Region(String name,Role role){this.name=name;this.role=role;}
        PolygonRoi nativeRoi(){
            float[] x=new float[points.size()],y=x.clone();
            for(int i=0;i<x.length;i++){x[i]=(float)points.get(i).x;y[i]=(float)points.get(i).y;}
            PolygonRoi r=new PolygonRoi(x,y,x.length,Roi.POLYGON);r.setName(name);r.setProperty("peri.role",role.name());r.setProperty("peri.id",id);r.setProperty("peri.reference",referenceId);return r;
        }
        Region copy(){
            Region r=new Region(name,role);r.id=id;r.referenceId=referenceId;
            for(Point2D.Double p:points)r.points.add(new Point2D.Double(p.x,p.y));return r;
        }
        public String toString(){return role+" · "+name;}
    }
    static final class Photo {
        String id=UUID.randomUUID().toString(),visit="Baseline",name,digest,alignment="Original ROI geometry",note="";
        Mode mode; int width,height; byte[] bytes; boolean confirmed=false,pureWhiteReference=false;
        ArrayList<Region> regions=new ArrayList<>();
        ArrayList<Point2D.Double> landmarks=new ArrayList<>();
        SoftReference<BufferedImage> cache=new SoftReference<>(null);
        BufferedImage image() throws IOException {
            BufferedImage im=cache.get();
            if(im==null){im=ImageIO.read(new ByteArrayInputStream(bytes));if(im==null)throw new IOException("Unsupported image format.");cache=new SoftReference<>(im);}
            return im;
        }
        static Photo read(Path path,Mode mode,String visit) throws Exception {
            long size=Files.size(path);if(size>60_000_000)throw new IOException("Each image must be no larger than 60 MB.");
            Photo p=new Photo();p.name=path.getFileName().toString();p.mode=mode;p.visit=visit;p.bytes=Files.readAllBytes(path);
            p.digest=sha(p.bytes);
            try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(p.bytes))){
                var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("Cannot read this image.");
                var r=readers.next();try{r.setInput(in);p.width=r.getWidth(0);p.height=r.getHeight(0);}finally{r.dispose();}
            }
            if(p.width<1||p.height<1||(long)p.width*p.height>40_000_000)throw new IOException("Images up to 40 megapixels are supported.");
            return p;
        }
        public String toString(){return visit+" / "+mode+" / "+name;}
    }
    static final class Project {
        String subject="Anonymous subject",notes="";
        boolean locked=false;
        ArrayList<Photo> photos=new ArrayList<>();
        EnumMap<Mode,Settings> settings=new EnumMap<>(Mode.class);
        ArrayList<String> audit=new ArrayList<>();
        Project(){settings.put(Mode.PL,new Settings(Metric.DARKNESS,5));settings.put(Mode.RED,new Settings(Metric.REDNESS,3));settings.put(Mode.BROWN,new Settings(Metric.DARKNESS,5));settings.put(Mode.INFLAMMATION,new Settings(Metric.REDNESS,3));}
        void log(String s){audit.add(java.time.Instant.now()+" | "+s);}
    }
    static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static double[] transform(ArrayList<Point2D.Double> src,ArrayList<Point2D.Double> dst,boolean scale){
        if(src.size()!=2||dst.size()!=2)throw new IllegalArgumentException("Both source and target images need two landmarks.");
        double sx=src.get(1).x-src.get(0).x,sy=src.get(1).y-src.get(0).y;
        double dx=dst.get(1).x-dst.get(0).x,dy=dst.get(1).y-dst.get(0).y;
        double a=Math.hypot(sx,sy),b=Math.hypot(dx,dy);
        if(a<5||b<5)throw new IllegalArgumentException("Landmarks must be at least five pixels apart.");
        double k=scale?b/a:1,angle=Math.atan2(dy,dx)-Math.atan2(sy,sx),c=k*Math.cos(angle),s=k*Math.sin(angle);
        return new double[]{c,s,dst.get(0).x-c*src.get(0).x+s*src.get(0).y,dst.get(0).y-s*src.get(0).x-c*src.get(0).y,k,Math.abs(b-k*a)};
    }
    static ArrayList<Region> mapped(Photo src,double[] t){
        ArrayList<Region> out=new ArrayList<>();
        for(Region r:src.regions){Region n=r.copy();for(Point2D.Double p:n.points){double x=p.x,y=p.y;p.x=t[0]*x-t[1]*y+t[2];p.y=t[1]*x+t[0]*y+t[3];}out.add(n);}return out;
    }
}
