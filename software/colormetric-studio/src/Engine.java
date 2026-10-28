package peri;

import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.util.*;
import ij.process.*;
import ij.measure.Measurements;
import static peri.Model.*;

final class Engine {
    static final class Result {
        Photo photo; Region region; Metric metric; double threshold,referenceMean,severity,areaPercent,wholeMean,lesionMean=Double.NaN;
        int pixels,abnormal; BitSet abnormalMask; String warnings="",referenceName=""; boolean pureWhiteReference;
    }
    static void validate(Region r,int w,int h){
        int n=r.points.size();if(n<3)throw new IllegalArgumentException(r.name+": at least three vertices required.");
        double area=0;
        for(int i=0;i<n;i++){
            var p=r.points.get(i);var q=r.points.get((i+1)%n);
            if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||p.x<0||p.y<0||p.x>w||p.y>h)throw new IllegalArgumentException(r.name+": ROI extends outside the image. Recheck its position.");
            if(p.distance(q)<0.01)throw new IllegalArgumentException(r.name+": duplicate vertices found.");
            area+=p.x*q.y-q.x*p.y;
            for(int j=i+1;j<n;j++){
                if(j==i+1||(i==0&&j==n-1))continue;
                var a=r.points.get(j);var b=r.points.get((j+1)%n);
                if(Line2D.linesIntersect(p.x,p.y,q.x,q.y,a.x,a.y,b.x,b.y))throw new IllegalArgumentException(r.name+": boundary crosses itself. Redraw the polygon.");
            }
        }
        if(Math.abs(area)<1)throw new IllegalArgumentException(r.name+": ROI area is too small.");
    }
    static BitSet mask(Region r,int w,int h){
        validate(r,w,h);var roi=r.nativeRoi();Rectangle bounds=roi.getBounds();var local=roi.getMask();
        BitSet bs=new BitSet(w*h);
        for(int y=Math.max(0,bounds.y);y<Math.min(h,bounds.y+bounds.height);y++)
            for(int x=Math.max(0,bounds.x);x<Math.min(w,bounds.x+bounds.width);x++)
                if(local==null||local.get(x-bounds.x,y-bounds.y)!=0)bs.set(y*w+x);
        return bs;
    }
    static Region reference(Photo p,Region r){
        if(p.pureWhiteReference)return null;
        var refs=p.regions.stream().filter(x->x.role==Role.REFERENCE).toList();
        if(refs.isEmpty())throw new IllegalArgumentException("This image needs one Reference skin ROI shared by all measurement ROIs.");
        if(refs.size()>1)throw new IllegalArgumentException("This image has "+refs.size()+" Reference skin ROIs. Keep one and delete the extras; all measurement ROIs use the same reference.");
        return refs.get(0);
    }
    static ArrayList<Result> analyze(Photo p,Settings settings) throws Exception {
        if(!Double.isFinite(settings.threshold)||settings.threshold<=0)throw new IllegalArgumentException("Threshold must be positive.");
        if(p.regions.stream().noneMatch(r->r.role==Role.TARGET))throw new IllegalArgumentException("No measurement ROI has been defined.");
        reference(p,p.regions.stream().filter(r->r.role==Role.TARGET).findFirst().orElseThrow());
        int w=p.width,h=p.height;BufferedRgb rgb=new BufferedRgb(p.image());
        BitSet excluded=new BitSet(w*h);Map<String,BitSet> masks=new HashMap<>();
        for(Region r:p.regions){if(p.pureWhiteReference&&r.role==Role.REFERENCE)continue;BitSet m=mask(r,w,h);masks.put(r.id,m);if(r.role==Role.EXCLUDE)excluded.or(m);}
        Map<String,Double> refMeans=new HashMap<>();ArrayList<Result> results=new ArrayList<>();
        ColorSpaceConverter converter=new ColorSpaceConverter("D65");
        for(Region r:p.regions){
            if(r.role!=Role.TARGET)continue;
            Region ref=reference(p,r);
            BitSet target=(BitSet)masks.get(r.id).clone();target.andNot(excluded);
            if(target.isEmpty())throw new IllegalArgumentException(r.name+": measurement ROI has no valid pixels.");
            double mean;
            if(p.pureWhiteReference){mean=channel(converter,0xFFFFFF,settings.metric);}
            else {
                BitSet rm=(BitSet)masks.get(ref.id).clone();rm.andNot(excluded);
                if(rm.isEmpty())throw new IllegalArgumentException(r.name+": reference ROI has no valid pixels.");
                BitSet overlap=(BitSet)target.clone();overlap.and(rm);
                if(!overlap.isEmpty())throw new IllegalArgumentException(r.name+": measurement and reference ROIs overlap. Adjust them.");
                Double cached=refMeans.get(ref.id);
                if(cached==null){float[] referenceValues=new float[rm.cardinality()];int k=0;
                    for(int i=rm.nextSetBit(0);i>=0;i=rm.nextSetBit(i+1))referenceValues[k++]=(float)channel(converter,rgb.get(i),settings.metric);
                    cached=stats(referenceValues).mean;refMeans.put(ref.id,cached);
                }
                mean=cached;
            }
            float[] vals=new float[target.cardinality()];int k=0,n=0,clipped=0;double lesionSum=0;
            BitSet abnormal=new BitSet(w*h);
            for(int i=target.nextSetBit(0);i>=0;i=target.nextSetBit(i+1)){
                int pixel=rgb.get(i);double ch=channel(converter,pixel,settings.metric);
                double value=settings.metric==Metric.DARKNESS?mean-ch:ch-mean;vals[k++]=(float)value;
                if((float)value>=settings.threshold){n++;lesionSum+=value;abnormal.set(i);}
                int rr=(pixel>>16)&255,gg=(pixel>>8)&255,bb=pixel&255;
                if(rr==0||rr==255||gg==0||gg==255||bb==0||bb==255)clipped++;
            }
            Result z=new Result();z.photo=p;z.region=r;z.metric=settings.metric;z.threshold=settings.threshold;z.referenceMean=mean;z.pureWhiteReference=p.pureWhiteReference;z.referenceName=p.pureWhiteReference?"Pure white (sRGB #FFFFFF)":ref.name;
            z.pixels=vals.length;z.abnormal=n;z.wholeMean=stats(vals).mean;z.areaPercent=100.0*n/vals.length;
            z.severity=n==0?Double.NaN:lesionSum/n;z.lesionMean=z.severity;z.abnormalMask=abnormal;
            if(p.pureWhiteReference)z.warnings+="Pure-white reference (sRGB #FFFFFF); not a skin-normalized difference; ";
            if(n==0)z.warnings+="No affected pixels at the selected threshold; intensity is N/A; ";
            if(vals.length<100)z.warnings+="Fewer than 100 valid pixels; ";
            if(clipped>0.05*vals.length)z.warnings+="Over 5% of pixels have channel endpoints; inspect enhancement/clipping; ";
            if(target.cardinality()<masks.get(r.id).cardinality())z.warnings+="Exclusions removed from denominator; ";
            results.add(z);
        }
        return results;
    }
    static ImageStatistics stats(float[] x){return ImageStatistics.getStatistics(new FloatProcessor(x.length,1,x),Measurements.MEAN|Measurements.STD_DEV|Measurements.AREA,null);}
    static double channel(ColorSpaceConverter c,int rgb,Metric metric){double[] lab=c.RGBtoLAB(rgb);return lab[metric==Metric.DARKNESS?0:1];}
    static final class BufferedRgb {
        final java.awt.image.BufferedImage image;final int w;
        BufferedRgb(java.awt.image.BufferedImage i){image=i;w=i.getWidth();}
        int get(int index){return image.getRGB(index%w,index/w);}
    }
}
