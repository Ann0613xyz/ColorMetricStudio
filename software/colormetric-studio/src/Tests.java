package peri;

import static peri.Model.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.io.*;

final class Tests {
    static ArrayList<String> checks=new ArrayList<>();
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks.add("PASS | "+name);}
    static void near(double x,double expected,double epsilon,String name){check(Math.abs(x-expected)<epsilon,name+" ["+x+"]");}
    interface Operation {void run() throws Exception;}
    static void fails(Operation action,String name) throws Exception {boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException|IOException e){failed=true;}check(failed,name);}
    static Region rect(String name,Role role,int x,int y,int w,int h){Region r=new Region(name,role);r.points.add(new Point2D.Double(x,y));r.points.add(new Point2D.Double(x+w,y));r.points.add(new Point2D.Double(x+w,y+h));r.points.add(new Point2D.Double(x,y+h));return r;}
    static Photo photo(BufferedImage image,Mode mode,String visit) throws Exception {Photo p=new Photo();p.bytes=ProjectIO.png(image);p.digest=sha(p.bytes);p.width=image.getWidth();p.height=image.getHeight();p.name="synthetic_"+mode.name()+"_"+visit+".png";p.mode=mode;p.visit=visit;p.confirmed=true;return p;}
    static Photo fixture() throws Exception {
        BufferedImage im=new BufferedImage(80,80,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.setColor(new Color(200,200,200));g.fillRect(0,0,80,80);g.setColor(new Color(120,120,120));g.fillRect(30,20,20,40);g.dispose();
        Photo p=photo(im,Mode.PL,"Baseline");Region ref=rect("Reference skin",Role.REFERENCE,2,2,20,20),r=rect("Measurement ROI",Role.TARGET,30,20,40,40);r.referenceId=ref.id;p.regions.add(ref);p.regions.add(r);return p;
    }
    static void run(Path out) throws Exception {
        out=out.toAbsolutePath().normalize();
        // Self-tests never read or overwrite the user's real remembered folders.
        System.setProperty("colormetric.settings.file",out.resolve("app-test-settings.properties").toString());
        Files.createDirectories(out);Files.deleteIfExists(Path.of(System.getProperty("colormetric.settings.file")));
        Files.createDirectories(out);checks.clear();Photo p=fixture();Settings s=new Settings(Metric.DARKNESS,5);
        var r=Engine.analyze(p,s).get(0);check(r.pixels==1600&&r.abnormal==800,"ImageJ polygon mask counts");near(r.areaPercent,50,1e-10,"Known area fraction 50%");
        var cv=new ij.process.ColorSpaceConverter("D65");near(cv.RGBtoLAB(0xFFFFFF)[0],100,.02,"Native ImageJ white L*=100");near(cv.RGBtoLAB(0)[0],0,.0001,"Native ImageJ black L*=0");
        double delta=cv.RGBtoLAB(0xC8C8C8)[0]-cv.RGBtoLAB(0x787878)[0];near(r.severity,delta,1e-4,"Primary intensity uses affected pixels only");near(r.wholeMean,delta/2,1e-4,"Whole-ROI mean retained as auxiliary only");
        p.regions.add(rect("Lash exclusion",Role.EXCLUDE,30,20,10,40));r=Engine.analyze(p,s).get(0);check(r.pixels==1200&&r.abnormal==400,"Exclusion removes denominator and numerator");near(r.areaPercent,100.0/3,1e-10,"Excluded area fraction");
        p.regions.remove(2);p.regions.add(rect("Duplicate exclusion A",Role.EXCLUDE,30,20,10,40));p.regions.add(rect("Duplicate exclusion B",Role.EXCLUDE,30,20,10,40));near(Engine.analyze(p,s).get(0).areaPercent,100.0/3,1e-10,"Overlapping exclusions use union");p.regions.subList(2,4).clear();
        Photo partial=fixture();partial.regions.add(rect("Cross-boundary exclusion",Role.EXCLUDE,20,20,20,40));var partialResult=Engine.analyze(partial,s).get(0);check(partialResult.pixels==1200&&partialResult.abnormal==400,"Exclusion extending outside target removes only overlapping target pixels");
        partial.regions.remove(2);partial.regions.add(rect("Unrelated exclusion",Role.EXCLUDE,2,55,10,10));near(Engine.analyze(partial,s).get(0).areaPercent,50,1e-10,"Exclusion outside target and reference does not affect results");
        partial.regions.remove(2);BufferedImage refImage=partial.image();Graphics2D refGraphics=refImage.createGraphics();refGraphics.setColor(new Color(100,100,100));refGraphics.fillRect(2,2,10,20);refGraphics.dispose();
        partial.regions.add(rect("Reference exclusion",Role.EXCLUDE,2,2,10,20));near(Engine.analyze(partial,s).get(0).referenceMean,r.referenceMean,1e-10,"Exclusion removes dark reference pixels without requiring target containment");
        var none=Engine.analyze(p,new Settings(Metric.DARKNESS,100)).get(0);check(none.abnormal==0&&none.areaPercent==0&&Double.isNaN(none.severity),"No affected pixels has 0% area and N/A primary intensity");
        var noneRow=ProjectIO.rows(new Project(),List.of(none)).get(1);check(noneRow[10].equals("N/A")&&((Number)noneRow[11]).doubleValue()==0,"No affected pixels exports N/A text and numeric zero area");
        var other=rect("Second target",Role.TARGET,55,30,10,10);other.referenceId=p.regions.get(0).id;p.regions.add(other);check(Engine.analyze(p,s).size()==2,"Multiple target regions measured separately");p.regions.remove(other);
        Photo unconfirmed=fixture();unconfirmed.confirmed=false;check(Engine.analyze(unconfirmed,s).size()==1,"Analysis requires no legacy position confirmation");
        Photo overlap=fixture();overlap.regions.get(1).points=rect("",Role.TARGET,5,5,10,10).points;fails(()->Engine.analyze(overlap,s),"Reference and target overlap rejected");
        Region outside=rect("Out of bounds",Role.TARGET,-1,1,10,10);fails(()->Engine.mask(outside,80,80),"Out-of-bounds ROI rejected");
        Region bow=rect("Self crossing",Role.TARGET,10,10,20,20);Collections.swap(bow.points,1,2);fails(()->Engine.mask(bow,80,80),"Self-intersecting polygon rejected");
        Photo missing=fixture();missing.regions.remove(0);fails(()->Engine.analyze(missing,s),"Image without shared reference rejected");
        Photo shared=fixture();shared.regions.get(1).referenceId="";Region sharedTarget=rect("Second unlinked target",Role.TARGET,52,20,15,20);shared.regions.add(sharedTarget);
        var sharedResults=Engine.analyze(shared,s);check(sharedResults.size()==2&&sharedResults.get(0).referenceMean==sharedResults.get(1).referenceMean,"Multiple unlinked targets automatically use one shared reference");
        shared.regions.get(1).referenceId=UUID.randomUUID().toString();near(Engine.analyze(shared,s).get(0).severity,delta,1e-4,"Legacy reference IDs are ignored when one shared reference exists");
        shared.regions.add(rect("Extra legacy reference",Role.REFERENCE,2,50,10,10));fails(()->Engine.analyze(shared,s),"Legacy multiple references require explicit cleanup instead of guessing");
        Photo brighter=fixture();Graphics2D bg=brighter.image().createGraphics();bg.setColor(Color.WHITE);bg.fillRect(30,20,40,40);bg.dispose();var brightResult=Engine.analyze(brighter,s).get(0);check(brightResult.areaPercent==0&&Double.isNaN(brightResult.severity)&&brightResult.wholeMean<0,"Brighter-than-reference target has N/A primary, not negative intensity");
        p.landmarks.add(new Point2D.Double(5,5));p.landmarks.add(new Point2D.Double(25,5));Photo dst=fixture();dst.landmarks.add(new Point2D.Double(10,15));dst.landmarks.add(new Point2D.Double(10,35));
        double[] t=Model.transform(p.landmarks,dst.landmarks,false);near(t[4],1,1e-12,"Rigid transfer preserves geometric scale");near(t[5],0,1e-12,"Rigid two-point correspondence");
        var moved=Model.mapped(p,t);near(moved.get(0).points.get(0).x,13,1e-9,"Rigid ROI rotation x");near(moved.get(0).points.get(0).y,12,1e-9,"Rigid ROI rotation y");check(moved.get(1).referenceId.equals(moved.get(0).id),"ROI transfer preserves reference association");
        dst.landmarks.set(1,new Point2D.Double(10,55));near(Model.transform(p.landmarks,dst.landmarks,true)[4],2,1e-12,"Optional scale recorded explicitly");near(Model.transform(p.landmarks,dst.landmarks,false)[5],20,1e-12,"Strict-size mapping reports residual");
        BufferedImage redIm=new BufferedImage(80,80,BufferedImage.TYPE_INT_RGB);Graphics2D g=redIm.createGraphics();g.setColor(new Color(200,180,170));g.fillRect(0,0,80,80);g.setColor(new Color(230,140,140));g.fillRect(30,20,20,40);g.dispose();Photo red=photo(redIm,Mode.RED,"Baseline");for(Region reg:p.regions)red.regions.add(reg.copy());var redResult=Engine.analyze(red,new Settings(Metric.REDNESS,3)).get(0);near(redResult.areaPercent,50,1e-10,"Redness channel identifies known 50% region");near(redResult.severity,cv.RGBtoLAB(0xE68C8C)[1]-cv.RGBtoLAB(0xC8B4AA)[1],1e-4,"Affected-only redness matches known a* difference");
        Photo brown=fixture();brown.mode=Mode.BROWN;near(Engine.analyze(brown,s).get(0).severity,delta,1e-4,"Affected-only Brown darkness matches known L* difference");
        Photo mixed=fixture();Graphics2D mg=mixed.image().createGraphics();mg.setColor(new Color(160,160,160));mg.fillRect(50,20,20,40);mg.dispose();double weak=cv.RGBtoLAB(0xC8C8C8)[0]-cv.RGBtoLAB(0xA0A0A0)[0];Engine.Result mixedLow=Engine.analyze(mixed,s).get(0),mixedHigh=Engine.analyze(mixed,new Settings(Metric.DARKNESS,(weak+delta)/2)).get(0);
        near(mixedLow.severity,(weak+delta)/2,1e-4,"Affected intensity averages both detected signal levels");near(mixedHigh.severity,delta,1e-4,"Raising threshold excludes weak signal from the primary intensity");check(mixedLow.areaPercent==100&&mixedHigh.areaPercent==50,"Threshold changes affected area consistently with its selected pixels");
        Project project=new Project();project.subject="Anonymous ID <01> & quotes \"";project.locked=true;project.photos.add(p);project.photos.add(red);project.log("Test: preserve parameters and original bytes");
        Path saved=out.resolve("roundtrip.pips");ProjectIO.save(project,saved);Project loaded=ProjectIO.load(saved);check(loaded.photos.size()==2&&loaded.locked&&loaded.subject.equals(project.subject),"Portable project Unicode/XML round trip");
        check(Arrays.equals(loaded.photos.get(0).bytes,p.bytes)&&loaded.photos.get(0).digest.equals(p.digest),"Original image bytes and SHA256 preserved");near(Engine.analyze(loaded.photos.get(0),s).get(0).severity,Engine.analyze(p,s).get(0).severity,1e-12,"Saved project reproduces analysis");
        ProjectIO.exportRois(p,out.resolve("rois.zip"));try(ZipFile z=new ZipFile(out.resolve("rois.zip").toFile())){var en=z.entries();int count=0;while(en.hasMoreElements()){var ent=en.nextElement();byte[] data=z.getInputStream(ent).readAllBytes();var roi=new ij.io.RoiDecoder(data,ent.getName()).getRoi();check(roi!=null&&roi.getName()!=null,"ImageJ ROI decoder compatibility #"+(++count));}check(count==2,"All region types exported");}
        var result=Engine.analyze(p,s);ProjectIO.export(project,result,out.resolve("results.zip"));try(ZipFile z=new ZipFile(out.resolve("results.zip").toFile())){check(z.getEntry("results.xlsx")!=null&&z.getEntry("analysis-manifest.xml")!=null,"Results and reproducibility manifest exported");Files.write(out.resolve("results.xlsx"),z.getInputStream(z.getEntry("results.xlsx")).readAllBytes());}
        check(ProjectIO.csvCell("=SUM(A1)").startsWith("\"'="),"CSV formula injection escaped");
        Path hostile=out.resolve("hostile.pips");try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(hostile))){ProjectIO.entry(z,"project.xml","<!DOCTYPE x [<!ENTITY ex SYSTEM 'file:///C:/Windows/win.ini'>]><periorbital-project schema='1' subject='&ex;'></periorbital-project>".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        boolean safe=false;try{ProjectIO.load(hostile);}catch(Exception ex){safe=true;}check(safe,"External XML entities rejected");
        Project d=demo();ProjectIO.save(d,out.resolve("demo.pips"));ArrayList<Engine.Result> dr=new ArrayList<>();for(Photo ph:d.photos)dr.addAll(Engine.analyze(ph,d.settings.get(ph.mode)));check(dr.size()==16,"Four modes x two visits x two regions");ProjectIO.export(d,dr,out.resolve("demo-results.zip"));
        inflammationChecks(out);
        uiChecks();
        folderHistoryChecks(out);
        tabChecks(out);
        workerChecks();
        copyMoveChecks(out);
        dragPriorityChecks();
        unicodeChecks(out);
        release130Checks(out);
        roiPreviewChecks(out);
        exportFilenameChecks(out);
        crossProjectCopyChecks(out);
        imageOrderChecks(out);
        numericVisitChecks(out);
        roiUndoChecks(out);
        whiteReferenceChecks(out);
        brandingChecks(out);
        Files.writeString(out.resolve("test-report.txt"),"ColorMetric Studio "+VERSION+" / ImageJ "+ij.IJ.getVersion()+"\n"+String.join("\n",checks)+"\nTOTAL: "+checks.size()+" passed\n",java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("PASS: "+checks.size()+" checks. Output: "+out.toAbsolutePath());
    }
    static void brandingChecks(Path out) throws Exception {
        check(VERSION.equals("0.1.0"),"ColorMetric Studio release version is 0.1.0");
        Project project=demo();String xml=new String(ProjectIO.metadata(project),java.nio.charset.StandardCharsets.UTF_8);
        check(xml.contains("<colormetric-project")&&xml.contains("application=\"ColorMetric Studio\"")&&xml.contains("appVersion=\"0.1.0\""),"Saved metadata identifies ColorMetric Studio 0.1.0");
        Path legacy=out.resolve("legacy-brand-project.pips");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(legacy))){
            ProjectIO.entry(zip,"project.xml",xml.replace("colormetric-project","periorbital-project").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for(Photo photo:project.photos)ProjectIO.entry(zip,"images/"+photo.id+".bin",photo.bytes);
        }
        Project loaded=ProjectIO.load(legacy);
        check(loaded.photos.size()==project.photos.size(),"Pre-rename project XML roots remain readable");
        near(Engine.analyze(loaded.photos.get(0),loaded.settings.get(loaded.photos.get(0).mode)).get(0).severity,
                Engine.analyze(project.photos.get(0),project.settings.get(project.photos.get(0).mode)).get(0).severity,1e-12,"Legacy-brand projects retain identical measurements");
        try(ZipFile result=new ZipFile(out.resolve("demo-results.zip").toFile())){
            String readme=new String(result.getInputStream(result.getEntry("README.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(readme.startsWith("ColorMetric Studio 0.1.0\n"),"Result ZIP readme carries the new product name and version");
        }
        Path root=out.resolve("rename-settings"),input=root.resolve("Import 中文"),output=root.resolve("Export 中文"),next=root.resolve("New import");
        Files.createDirectories(input);Files.createDirectories(output);Files.createDirectories(next);
        Path oldFile=root.resolve("old/settings.properties"),newFile=root.resolve("new/settings.properties");
        Files.deleteIfExists(newFile);
        DirectoryHistory old=new DirectoryHistory(oldFile);old.remember(DirectoryHistory.IMPORT,input.toFile());old.remember(DirectoryHistory.OUTPUT,output.toFile());
        byte[] oldBytes=Files.readAllBytes(oldFile);DirectoryHistory current=DirectoryHistory.migrated(newFile,oldFile);
        check(current.recalled(DirectoryHistory.IMPORT).equals(input.toFile())&&current.recalled(DirectoryHistory.OUTPUT).equals(output.toFile()),"Renamed application migrates both remembered folders");
        check(Arrays.equals(oldBytes,Files.readAllBytes(oldFile)),"Folder-history migration leaves the old settings untouched");
        current.remember(DirectoryHistory.IMPORT,next.toFile());DirectoryHistory.migrated(newFile,oldFile);
        check(current.recalled(DirectoryHistory.IMPORT).equals(next.toFile()),"Existing ColorMetric folder history is not overwritten by legacy values");
        SwingUtilities.invokeAndWait(()->{App app=null;try{App.style();app=new App();
            check(app.getTitle().contains("ColorMetric Studio")&&app.getTitle().contains("0.1.0"),"Window title displays the renamed product and release version");
            check(app.outputChooser("Results","xlsx").getSelectedFile().getName().startsWith("ColorMetric-"),"Default export filenames use the new product name");
        }finally{if(app!=null)app.dispose();}});
    }
    static void whiteReferenceChecks(Path out) throws Exception {
        var cv=new ij.process.ColorSpaceConverter("D65");Photo p=fixture();p.pureWhiteReference=true;p.regions.removeIf(r->r.role==Role.REFERENCE);
        var dark=Engine.analyze(p,new Settings(Metric.DARKNESS,5)).get(0);
        double whiteL=cv.RGBtoLAB(0xFFFFFF)[0],darkL=cv.RGBtoLAB(0x787878)[0],lightL=cv.RGBtoLAB(0xC8C8C8)[0];
        near(dark.referenceMean,whiteL,1e-10,"Pure white uses native ImageJ sRGB white L channel");
        near(dark.severity,whiteL-(darkL+lightL)/2,1e-8,"White-referenced affected intensity matches known darkness values");
        check(dark.areaPercent==100&&dark.referenceName.equals("Pure white (sRGB #FFFFFF)")&&dark.pureWhiteReference,"Pure white needs no drawn reference and records its basis");
        Region inactive=rect("Legacy reference",Role.REFERENCE,35,25,5,5);p.regions.add(inactive);
        near(Engine.analyze(p,new Settings(Metric.DARKNESS,5)).get(0).severity,dark.severity,1e-12,"Inactive reference overlapping target cannot alter white measurements");
        p.regions.add(rect("Exclude",Role.EXCLUDE,30,20,20,40));var excluded=Engine.analyze(p,new Settings(Metric.DARKNESS,5)).get(0);
        check(excluded.pixels==800&&excluded.abnormal==800,"White reference retains measurement exclusions and valid denominator");
        near(excluded.severity,whiteL-lightL,1e-10,"White exclusion does not change its constant reference channel");
        for(Mode mode:Mode.values()){
            BufferedImage image=new BufferedImage(50,50,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(new Color(230,140,140));g.fillRect(0,0,50,50);g.dispose();
            Photo ph=photo(image,mode,"Baseline");ph.pureWhiteReference=true;ph.regions.add(rect("target",Role.TARGET,10,10,20,20));Settings st=new Project().settings.get(mode);
            var result=Engine.analyze(ph,st).get(0);double channel=Engine.channel(cv,0xE68C8C,st.metric),white=Engine.channel(cv,0xFFFFFF,st.metric);
            near(result.referenceMean,white,1e-10,"White reference channel for "+mode);near(result.severity,st.metric==Metric.DARKNESS?white-channel:channel-white,1e-10,"White affected-only metric for "+mode);
        }
        Project project=new Project();project.photos.add(p);project.photos.add(fixture());Path file=out.resolve("white-reference.pips");ProjectIO.save(project,file);Project loaded=ProjectIO.load(file);
        check(loaded.photos.get(0).pureWhiteReference&&!loaded.photos.get(1).pureWhiteReference,"Reference type survives project round trip independently per image");
        near(Engine.analyze(loaded.photos.get(0),loaded.settings.get(Mode.PL)).get(0).severity,excluded.severity,1e-10,"White-reference project reproduces analysis");
        var row=ProjectIO.rows(project,List.of(excluded)).get(1);check(row[8].equals("Pure white (sRGB #FFFFFF)")&&row[row.length-1].equals("PURE_WHITE"),"Excel and CSV rows identify white reference explicitly");
        ProjectIO.export(project,List.of(excluded),out.resolve("white-results.zip"));
        try(ZipFile zip=new ZipFile(file.toFile())){
            String xml=new String(zip.getInputStream(zip.getEntry("project.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(xml.contains("schema=\"2\"")&&xml.contains("referenceType=\"PURE_WHITE\""),"Project schema 2 protects white-reference settings from older software");
            String legacy=xml.replace("schema=\"2\"","schema=\"1\"").replace(" referenceType=\"PURE_WHITE\"","").replace(" referenceType=\"SKIN_ROI\"","");
            Path old=out.resolve("legacy-schema1.pips");try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(old))){ProjectIO.entry(z,"project.xml",legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8));for(Photo ph:project.photos)ProjectIO.entry(z,"images/"+ph.id+".bin",ph.bytes);}
            check(ProjectIO.load(old).photos.stream().noneMatch(ph->ph.pureWhiteReference),"Old schema-1 projects default to drawn skin reference");
        }
        SwingUtilities.invokeAndWait(()->{App app=null;try{
            App.style();app=new App();Photo ph=fixture();Project pr=new Project();pr.photos.add(ph);pr.photos.add(fixture());app.addSession(pr,null);App active=app;
            check(!app.whiteReference.isSelected(),"New images default to existing skin-reference behavior");
            app.role.setSelectedItem(Role.REFERENCE);check(app.roiName.getText().equals("reference")&&!app.roiName.isEditable(),"Reference role automatically supplies read-only reference name");
            app.results.add(Engine.analyze(ph,pr.settings.get(ph.mode)).get(0));app.whiteReference.doClick();
            check(ph.pureWhiteReference&&app.results.isEmpty()&&app.dirty&&ph.regions.size()==2,"Switching to white invalidates results without deleting existing ROIs");
            app.role.setSelectedItem(Role.REFERENCE);check(app.role.getSelectedItem()==Role.TARGET,"White mode prevents selecting Reference skin role");
            fails(()->active.ensureSingleReference(ph,null,Role.REFERENCE),"Drawing or role conversion cannot add a reference in white mode");
            app.rois.setSelectedValue(ph.regions.get(0),true);check(app.role.isEnabled()&&app.role.getSelectedItem()==Role.TARGET,"Selecting inactive reference leaves measurement drawing available");
            app.photos.setSelectedValue(pr.photos.get(1),true);check(!app.whiteReference.isSelected(),"White checkbox follows the selected image");app.role.setSelectedItem(Role.REFERENCE);app.photos.setSelectedValue(ph,true);check(app.whiteReference.isSelected()&&app.role.getSelectedItem()==Role.TARGET,"Returning to white image restores its choice and clears the skin-only drawing role");
            Photo source=pr.photos.get(1);app.copySelectedRois(source,new ArrayList<>(source.regions),List.of(ph));check(ph.pureWhiteReference&&ph.regions.stream().filter(r->r.role==Role.REFERENCE).count()==1,"Batch copy to white image skips source references and retains its inactive reference");
            app.whiteReference.doClick();check(!ph.pureWhiteReference&&ph.regions.stream().filter(r->r.role==Role.REFERENCE).count()==1,"Switching back reuses retained skin reference");
            Photo empty=fixture();empty.regions.clear();pr.photos.add(empty);app.refreshPhotos();app.photos.setSelectedValue(empty,true);app.role.setSelectedItem(Role.REFERENCE);app.roiName.setText("manual name ignored");app.canvas.pending.addAll(rect("",Role.REFERENCE,2,2,15,15).points);app.canvas.finish();check(empty.regions.get(0).name.equals("reference"),"Completed reference polygon always gets default reference name");
            app.setWhiteReference(true);Path preview=out.resolve("white-reference-ui.png");app.getRootPane().setSize(1500,980);layout(app.getRootPane());app.canvas.fit();BufferedImage im=new BufferedImage(1500,980,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.setColor(App.BG);g.fillRect(0,0,1500,980);app.getRootPane().printAll(g);g.dispose();ImageIO.write(im,"png",preview.toFile());
            app.busy=true;app.updateSettingsControls();check(!app.whiteReference.isEnabled(),"Reference method cannot change while analysis is running");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{if(app!=null)app.dispose();}});
    }
    static void uiChecks() throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=null;try{
            App.style();app=new App();app.addSession(demo(),null);Photo target=app.project.photos.get(3);target.regions.clear();target.confirmed=false;app.refreshPhotos();Photo src=app.project.photos.get(0);
            app.photos.setSelectedValue(target,true);app.source.setSelectedItem(src);app.copyToCurrent(false);
            check(target.regions.size()==3&&!target.confirmed,"GUI exact transfer copies shared reference and targets and resets verification");
            near(target.regions.get(2).points.get(0).x,src.regions.get(2).points.get(0).x,1e-12,"GUI transfer preserves vertex coordinates");
            check(!hasVerificationCheckbox(app.getContentPane()),"Position verification checkbox is absent from the UI");
            app.locked.doClick();check(app.project.locked&&!app.threshold.isEnabled()&&!app.metric.isEnabled(),"GUI parameter lock disables editors");
            app.locked.doClick();check(!app.project.locked&&app.threshold.isEnabled(),"GUI parameter unlock restores editors");
            app.role.setSelectedItem(Role.EXCLUDE);app.roiName.setText("Test exclusion");app.canvas.pending.addAll(rect("",Role.EXCLUDE,130,320,10,10).points);app.canvas.finish();
            check(target.regions.size()==4&&target.regions.get(3).role==Role.EXCLUDE,"GUI polygon closure adds selected ROI role");check(!target.confirmed,"GUI polygon creation invalidates verification");
            check(app.getJMenuBar().getMenu(0).getText().equals("File")&&app.project.photos.get(0).mode.toString().equals("PL"),"English menu and mode labels");
            app.canvas.pending.addAll(rect("",Role.TARGET,130,320,10,10).points);
            Object undoKey=app.canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ctrl Z"));
            Action undo=app.canvas.getActionMap().get(undoKey);check(undo!=null,"Ctrl+Z is bound while the drawing canvas has focus");
            var first=app.canvas.pending.get(0);var third=app.canvas.pending.get(2);undo.actionPerformed(null);
            check(app.canvas.pending.size()==3&&app.canvas.pending.get(0)==first&&app.canvas.pending.get(2)==third&&target.regions.size()==4,"Ctrl+Z removes only latest unfinished vertex and preserves completed ROIs");
            app.busy=true;undo.actionPerformed(null);check(app.canvas.pending.size()==3,"Undo is disabled during analysis");app.busy=false;
            for(int i=0;i<4;i++)undo.actionPerformed(null);check(app.canvas.pending.isEmpty()&&target.regions.size()==4,"Repeated undo to empty is safe and does not delete completed ROIs");
            app.lastImportDirectory=new File(System.getProperty("java.io.tmpdir"));JFileChooser picker=app.importChooser();
            check(picker.getCurrentDirectory().getAbsoluteFile().equals(app.lastImportDirectory.getAbsoluteFile()),"Import picker starts in remembered import directory");
            picker.setCurrentDirectory(new File(System.getProperty("user.home")));app.rememberImportDirectory(picker);
            check(app.importChooser().getCurrentDirectory().equals(picker.getCurrentDirectory()),"Latest browsed directory is retained for the next import even without importing files");
            App fresh=new App();try{check(fresh.lastImportDirectory.equals(picker.getCurrentDirectory()),"A restarted application reloads the import folder from disk");}finally{fresh.dispose();}
            app.lastImportDirectory=new File(System.getProperty("java.io.tmpdir"),UUID.randomUUID().toString());check(!app.importChooser().getCurrentDirectory().equals(app.lastImportDirectory),"Missing remembered import directory falls back safely");
            JFileChooser output=app.outputChooser("Project","pips");File folder=new File(System.getProperty("java.io.tmpdir"));output.setCurrentDirectory(folder);app.rememberOutputDirectory(output,false);
            JFileChooser excel=app.outputChooser("Results","xlsx");check(excel.getCurrentDirectory().getAbsoluteFile().equals(folder.getAbsoluteFile())&&excel.getSelectedFile().getParentFile().equals(folder.getAbsoluteFile()),"Save and Excel export reuse the last browsed output folder even after cancellation");
            File selected=new File(System.getProperty("user.home"),"example.zip");excel.setSelectedFile(selected);app.rememberOutputDirectory(excel,true);check(app.outputChooser("Project","pips").getCurrentDirectory().equals(selected.getParentFile()),"Approved output selection shares its actual parent with subsequent project saving");
            File remembered=app.lastOutputDirectory,importFolder=app.lastImportDirectory;app.addSession(new Project(),null);check(app.outputChooser("Results-package","zip").getCurrentDirectory().equals(remembered)&&app.lastImportDirectory==importFolder,"Output folder persists across project tabs independently of import navigation");
            App restarted=new App();try{check(restarted.lastOutputDirectory.equals(selected.getParentFile()),"A restarted application reloads its shared save/export folder");}finally{restarted.dispose();}
            app.lastOutputDirectory=new File(folder,UUID.randomUUID().toString());check(!app.outputChooser("Results","xlsx").getCurrentDirectory().equals(app.lastOutputDirectory),"Removed remembered output directory falls back safely");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{if(app!=null)app.dispose();}});
    }
    static void folderHistoryChecks(Path out) throws Exception {
        Path root=out.resolve("folder-history"),input=root.resolve("Import images 中文"),output=root.resolve("Export results 中文"),next=root.resolve("Another folder");
        Files.createDirectories(input);Files.createDirectories(output);Files.createDirectories(next);
        Path file=root.resolve("history.properties");Files.deleteIfExists(file);DirectoryHistory history=new DirectoryHistory(file);
        check(history.recalled(DirectoryHistory.IMPORT)==null,"Missing history file starts without a stored folder");
        check(history.remember(DirectoryHistory.IMPORT,input.toFile())&&history.remember(DirectoryHistory.OUTPUT,output.toFile()),"Import and output paths persist to one per-user settings file");
        check(new DirectoryHistory(file).recalled(DirectoryHistory.IMPORT).equals(input.toFile())&&new DirectoryHistory(file).recalled(DirectoryHistory.OUTPUT).equals(output.toFile()),"Chinese folder paths and spaces reload without encoding loss");
        check(!history.remember(DirectoryHistory.IMPORT,root.resolve("Not present").toFile())&&history.recalled(DirectoryHistory.IMPORT).equals(input.toFile()),"Invalid remembered directory never replaces the last valid one");
        new DirectoryHistory(file).remember(DirectoryHistory.IMPORT,next.toFile());check(history.recalled(DirectoryHistory.OUTPUT).equals(output.toFile()),"Updating import history retains independent export history");
        Path corrupt=root.resolve("corrupt.properties");Files.writeString(corrupt,"importDirectory=\\uZZZZ");check(new DirectoryHistory(corrupt).recalled(DirectoryHistory.IMPORT)==null,"Malformed settings fall back safely");
        Path blocked=root.resolve("not-a-folder");Files.writeString(blocked,"fixture");check(!new DirectoryHistory(blocked.resolve("settings.properties")).remember(DirectoryHistory.IMPORT,input.toFile()),"Unavailable settings storage fails gracefully without blocking file selection");
        Path removed=root.resolve("Removed folder");Files.createDirectory(removed);history.remember(DirectoryHistory.IMPORT,removed.toFile());Files.delete(removed);check(history.recalled(DirectoryHistory.IMPORT)==null,"A saved folder deleted between runs falls back safely");
        history.remember(DirectoryHistory.IMPORT,input.toFile());
        Process probe=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),"peri.Tests$HistoryProbe",file.toString(),input.toString(),output.toString()).redirectErrorStream(true).start();
        boolean exited=probe.waitFor(20,java.util.concurrent.TimeUnit.SECONDS);if(!exited)probe.destroyForcibly();
        check(exited&&probe.exitValue()==0,"A separate JVM process reloads both saved folder paths");
        SwingUtilities.invokeAndWait(()->{App app=null;try{
            App.style();app=new App();
            FolderFileChooser chooser=(FolderFileChooser)app.importChooser();
            check(chooser.folderPath.getFont().canDisplayUpTo("中文 folder")==-1,"Folder address supports Chinese glyphs while labels remain English");
            check(chooser.isMultiSelectionEnabled()&&chooser.getFileFilter().accept(new File("sample.png")),"Folder-address import chooser retains image multi-selection and filtering");
            int[] approvals={0};chooser.addActionListener(e->{if(JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand()))approvals[0]++;});
            chooser.folderPath.setText("\""+input+"\"");chooser.folderPath.postActionEvent();
            check(chooser.getCurrentDirectory().equals(input.toFile())&&approvals[0]==0,"Enter on quoted Chinese path opens its folder without approving or importing a file");
            check(app.lastImportDirectory.equals(input.toFile())&&new AppHistoryView().importFolder().equals(input.toFile()),"Typed path is persisted immediately, before closing the chooser");
            chooser.folderPath.setText(next.toString());chooser.go.doClick();check(chooser.getCurrentDirectory().equals(next.toFile()),"Go button navigates a typed folder path");
            chooser.folderPath.setText("..");check(chooser.navigate()&&chooser.getCurrentDirectory().equals(root.toFile()),"Relative folder paths resolve from the current folder");
            File before=chooser.getCurrentDirectory();chooser.folderPath.setText(root.resolve("Missing folder").toString());check(!chooser.navigate()&&chooser.getCurrentDirectory().equals(before)&&approvals[0]==0&&!chooser.folderStatus.getText().isBlank(),"Invalid typed paths show an inline English error and leave the folder unchanged");
            chooser.folderPath.setText(input.toString());chooser.navigate();
            Path image=input.resolve("中文 image.png");Files.write(image,fixture().bytes);chooser.setSelectedFile(image.toFile());chooser.folderPath.setText(next.toString());chooser.navigate();
            check(chooser.getSelectedFiles().length==0&&chooser.getSelectedFile()==null,"Changing import folders clears stale file selections");
            chooser.setCurrentDirectory(input.toFile());check(chooser.folderPath.getText().equals(input.toString()),"Mouse-based folder navigation updates the editable folder address");
            check(chooser.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("ctrl L")).equals("folderAddress"),"Ctrl+L targets the folder address field");
            for(String ext:List.of("pips","xlsx","zip")){
                FolderFileChooser save=(FolderFileChooser)app.outputChooser("Results",ext);String filename=save.getSelectedFile().getName();save.folderPath.setText(output.toString());save.folderPath.postActionEvent();
                check(save.getCurrentDirectory().equals(output.toFile())&&save.getSelectedFile().equals(output.resolve(filename).toFile()),"Typed save folder retains the "+ext+" filename and extension");
                String custom="Custom report 中文."+ext;((javax.swing.plaf.basic.BasicFileChooserUI)save.getUI()).setFileName(custom);save.folderPath.setText(next.toString());save.go.doClick();
                check(save.getSelectedFile().equals(next.resolve(custom).toFile()),"Folder navigation preserves a user-edited "+ext+" filename before it is committed");
            }
            app.rememberFolder(false,output.toFile());
            App restarted=new App();try{check(restarted.lastImportDirectory.equals(input.toFile())&&restarted.lastOutputDirectory.equals(output.toFile()),"Both navigation histories survive app recreation and remain separate");}finally{restarted.dispose();}
            FolderFileChooser preview=(FolderFileChooser)app.outputChooser("Results","xlsx");preview.setSize(980,660);layout(preview);
            BufferedImage shot=new BufferedImage(980,660,BufferedImage.TYPE_INT_RGB);Graphics2D g=shot.createGraphics();g.setColor(App.BG);g.fillRect(0,0,980,660);preview.printAll(g);g.dispose();ImageIO.write(shot,"png",out.resolve("folder-address-preview.png").toFile());
        }catch(Exception e){throw new RuntimeException(e);}finally{if(app!=null)app.dispose();}});
    }
    static final class AppHistoryView {File importFolder(){return DirectoryHistory.user().recalled(DirectoryHistory.IMPORT);}}
    public static final class HistoryProbe {public static void main(String[] args){DirectoryHistory h=new DirectoryHistory(Path.of(args[0]));if(!Objects.equals(h.recalled(DirectoryHistory.IMPORT),new File(args[1]))||!Objects.equals(h.recalled(DirectoryHistory.OUTPUT),new File(args[2])))throw new AssertionError("Persisted paths did not reload");}}
    static void tabChecks(Path out) throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=null;try{
            App.style();app=new App();check(app.sessions.size()==1&&app.projectTabs.getTabCount()==1,"Application starts with one project tab");
            Project first=new Project();first.subject="SUBJECT-A";Photo ph=fixture();first.photos.add(ph);app.addSession(first,null);int a=app.activeSession;
            app.photos.setSelectedValue(ph,true);app.rois.setSelectedValue(ph.regions.get(1),true);app.project.settings.get(Mode.PL).threshold=7;app.changed("A-only edit");app.results.addAll(Engine.analyze(ph,app.project.settings.get(Mode.PL)));app.refreshResults();
            app.canvas.pending.add(new Point2D.Double(12,34));app.canvas.zoom=.7;app.canvas.ox=24;app.canvas.oy=31;app.roiName.setText("Unfinished name A");app.visit.setText("Unapplied visit A");app.threshold.setValue(9.0);app.showMask.setSelected(false);app.allowScale.setSelected(true);
            Project second=new Project();second.subject="SUBJECT-B";Photo pb=fixture();pb.visit="Week 26";second.photos.add(pb);app.addSession(second,null);int b=app.activeSession;
            check(app.project==second&&!app.dirty&&app.results.isEmpty()&&app.canvas.pending.isEmpty(),"New tab has independent project, dirty flag, results and unfinished polygon");
            check(app.project.settings.get(Mode.PL).threshold==5,"New tab has independent mode parameters");
            app.changed("B-only edit");app.results.addAll(Engine.analyze(pb,app.project.settings.get(Mode.PL)));app.refreshResults();
            app.projectTabs.setSelectedIndex(a);check(app.project==first&&app.current()==ph&&app.selectedRegion()==ph.regions.get(1),"Top tab selection restores correct project, image and selected ROI");
            check(app.results.size()==1&&app.tableModel.getRowCount()==1&&app.results.get(0).photo==ph,"Tab switching restores only that project's analysis results");
            check(app.dirty&&app.project.settings.get(Mode.PL).threshold==7&&((Number)app.threshold.getValue()).doubleValue()==9,"Tab switching preserves applied and unapplied parameters separately");
            check(app.canvas.pending.size()==1&&app.canvas.pending.get(0).x==12&&app.canvas.zoom==.7&&app.canvas.ox==24&&app.canvas.oy==31,"Tab switching preserves unfinished vertices and image view");
            check(app.roiName.getText().equals("Unfinished name A")&&app.visit.getText().equals("Unapplied visit A")&&!app.showMask.isSelected()&&app.allowScale.isSelected(),"Tab switching preserves unapplied form fields and view options");
            check(first.audit.stream().noneMatch(x->x.contains("B-only"))&&second.audit.stream().noneMatch(x->x.contains("A-only")),"Project audit histories stay independent");
            Path fileA=out.resolve("tab-A.pips"),fileB=out.resolve("tab-B.pips");app.saveTo(fileA);check(!app.dirty&&app.projectPath.equals(fileA.toAbsolutePath().normalize()),"Saving active tab updates only its file path and dirty state");
            app.activateSession(b);check(app.dirty&&app.projectPath==null&&app.results.get(0).photo==pb,"Saving another tab preserves this tab's dirty state and results");app.saveTo(fileB);
            Project readA=ProjectIO.load(fileA),readB=ProjectIO.load(fileB);check(readA.subject.equals("SUBJECT-A")&&readB.subject.equals("SUBJECT-B")&&readA.photos.get(0).visit.equals("Baseline")&&readB.photos.get(0).visit.equals("Week 26"),"Separate saved files contain the correct subjects and image visits");
            App activeApp=app;fails(()->activeApp.saveTo(fileA),"Save as cannot overwrite a file open in another tab");
            int count=app.sessions.size();app.openPath(fileA);check(app.sessions.size()==count&&app.activeSession==a,"Reopening an already open path focuses its tab without duplicate state");
            Path another=out.resolve("tab-C.pips");ProjectIO.save(second,another);app.openPath(another);check(app.sessions.size()==count+1&&app.project.subject.equals("SUBJECT-B"),"Opening a different project adds a tab without replacing existing projects");
            JPanel header=(JPanel)app.projectTabs.getTabComponentAt(app.activeSession);for(Component component:header.getComponents())if(component instanceof JButton close)close.doClick();check(app.sessions.size()==count&&app.projectTabs.getTabCount()==count,"Tab close button closes a saved tab and preserves other projects");
            app.activateSession(a);app.busy=true;fails(()->activeApp.activateSession(b),"Analysis blocks switching to a different project");fails(()->activeApp.addSession(new Project(),null),"Analysis blocks adding a project");app.busy=false;
            app.role.setSelectedItem(Role.REFERENCE);app.roiName.setText("Duplicate reference");app.canvas.pending.clear();app.canvas.pending.addAll(rect("",Role.REFERENCE,5,50,10,10).points);fails(()->activeApp.canvas.finish(),"Drawing a second shared reference is rejected without adding it");check(ph.regions.size()==2&&app.canvas.pending.size()==4,"Rejected reference creation preserves existing ROI and pending vertices");
            app.rois.setSelectedValue(ph.regions.get(1),true);app.role.setSelectedItem(Role.REFERENCE);fails(()->activeApp.applyRegionDetails(),"Converting a target to a second reference is rejected");check(ph.regions.get(1).role==Role.TARGET,"Rejected role conversion leaves the original target unchanged");
            app.results.clear();app.results.addAll(Engine.analyze(ph,new Settings(Metric.DARKNESS,100)));app.refreshResults();check(app.tableModel.getValueAt(0,3).equals("N/A"),"Results table displays N/A when no affected pixels are found");
            ProjectIO.export(first,app.results,out.resolve("no-affected-results.zip"));try(ZipFile zip=new ZipFile(out.resolve("no-affected-results.zip").toFile())){Files.write(out.resolve("no-affected.xlsx"),zip.getInputStream(zip.getEntry("results.xlsx")).readAllBytes());}
            while(app.sessions.size()>1){app.dirty=false;app.removeSession(app.activeSession);}app.dirty=false;app.removeSession(0);check(app.sessions.size()==1&&app.project.photos.isEmpty()&&app.results.isEmpty(),"Closing the last tab creates a clean empty project");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{if(app!=null)app.dispose();}});
    }
    static void workerChecks() throws Exception {
        App[] holder={null};SwingUtilities.invokeAndWait(()->{try{App app=new App();holder[0]=app;Project p=new Project();Photo ph=fixture();ph.confirmed=false;p.photos.add(ph);app.addSession(p,null);app.analyze(false);check(app.busy&&!app.projectTabs.isEnabled(),"Background analysis starts without confirmation and disables the project tab bar");}catch(Exception ex){throw new RuntimeException(ex);}});
        try{
            holder[0].worker.get();boolean[] done={false};for(int i=0;i<100&&!done[0];i++){SwingUtilities.invokeAndWait(()->done[0]=!holder[0].busy);if(!done[0])Thread.sleep(50);}
            SwingUtilities.invokeAndWait(()->{App app=holder[0];check(!app.busy&&app.projectTabs.isEnabled()&&app.results.size()==1,"Completed background analysis restores tabs and updates the active project");int analyzed=app.activeSession;app.activateSession(0);check(app.results.isEmpty(),"Switching after analysis does not leak results into another project");app.activateSession(analyzed);check(app.results.size()==1&&app.results.get(0).photo==app.current()&&app.dirty,"Worker-produced results and unsaved state survive a round trip between tabs");});
        }finally{SwingUtilities.invokeAndWait(()->holder[0].dispose());}
    }
    static ArrayList<Point2D.Double> vertices(Region r){ArrayList<Point2D.Double> points=new ArrayList<>();for(var p:r.points)points.add(new Point2D.Double(p.x,p.y));return points;}
    static boolean hasVerificationCheckbox(Container root){for(Component c:root.getComponents()){if(c instanceof JCheckBox box&&box.getText().equals("Position and mode verified"))return true;if(c instanceof Container child&&hasVerificationCheckbox(child))return true;}return false;}
    static void roiPreviewChecks(Path out) throws Exception {
        Photo ph=fixture();ph.regions.get(0).name="Ref";ph.regions.get(1).name="Target";byte[] original=ph.bytes.clone();var points=vertices(ph.regions.get(1));
        BufferedImage preview=ProjectIO.roiPreview(ph),source=ph.image();check(preview.getWidth()==ph.width&&preview.getHeight()==ph.height,"ROI-only preview retains native image dimensions");
        check(preview.getRGB(35,53)==source.getRGB(35,53)&&preview.getRGB(65,53)==source.getRGB(65,53),"ROI-only preview preserves dark and light source pixels without segmentation tint");
        check(preview.getRGB(30,53)!=source.getRGB(30,53),"ROI-only preview includes measurement boundaries");check(Arrays.equals(original,ph.bytes)&&translated(ph.regions.get(1),points,0,0),"ROI preview does not mutate original bytes or polygon vertices");
        Project p=new Project();p.photos.add(ph);List<Engine.Result> results=Engine.analyze(ph,p.settings.get(ph.mode));Path zip=out.resolve("roi-preview-results.zip");ProjectIO.export(p,results,zip);
        try(ZipFile z=new ZipFile(zip.toFile())){
            List<String> names=z.stream().map(java.util.zip.ZipEntry::getName).toList();check(names.stream().noneMatch(n->n.startsWith("valid-rois/")||n.startsWith("masks/")),"Result package omits both valid-rois and masks directories");
            String file="imagej-rois/"+ph.id+"/"+ph.name;check(names.contains(file)&&names.stream().filter(n->n.startsWith("imagej-rois/")&&!n.endsWith(".roi")).count()==1,"Result package includes one source-named ROI-only preview beside each image's ROI files");
            BufferedImage exported=ImageIO.read(z.getInputStream(z.getEntry(file)));check(exported.getRGB(35,53)==source.getRGB(35,53),"Exported ROI-only PNG has no affected-pixel overlay");
            BufferedImage analyzed=ImageIO.read(z.getInputStream(z.getEntry("overlays/"+ph.name)));check(analyzed.getRGB(35,53)!=source.getRGB(35,53),"Analyzed segmentation overlay remains available separately");
            long rois=names.stream().filter(n->n.startsWith("imagej-rois/")&&n.endsWith(".roi")).count();check(rois==ph.regions.size(),"ImageJ ROI geometry files are retained alongside PNG previews");
            String readme=new String(z.getInputStream(z.getEntry("README.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);check(readme.contains("<source-filename>")&&!readme.contains("valid-rois")&&!readme.contains("masks"),"Result package README documents source-named ROI-only previews without removed folders");
        }
        Project demo=demo();Path partial=out.resolve("partial-roi-preview-results.zip");Photo image=demo.photos.get(3);ProjectIO.export(demo,List.of(Engine.analyze(image,demo.settings.get(image.mode)).get(0)),partial);
        try(ZipFile z=new ZipFile(partial.toFile())){check(z.stream().filter(e->e.getName().endsWith(".roi")).count()==image.regions.size(),"Partial result export retains all regions in the represented image");}
        BufferedImage before=ProjectIO.roiPreview(image),after=ProjectIO.overlay(image,Engine.analyze(image,demo.settings.get(image.mode)));ImageIO.write(before,"png",out.resolve("roi-only-preview.png").toFile());ImageIO.write(after,"png",out.resolve("analyzed-preview.png").toFile());
        try(ZipFile z=new ZipFile(out.resolve("demo-results.zip").toFile())){check(z.stream().filter(e->e.getName().startsWith("imagej-rois/")&&!e.getName().endsWith(".roi")).count()==demo.photos.size(),"Four-mode multi-region export produces one preview per image without duplicates");}
    }
    static void exportFilenameChecks(Path out) throws Exception {
        Project project=new Project();ArrayList<Engine.Result> results=new ArrayList<>();
        List<String> names=List.of("模拟患者_W18_红色区.JPG","baseline with spaces.png","Week26.棕色.TiF","Patient.JPEG","patient.jpeg","同名.png","同名.png");
        for(String name:names){Photo ph=fixture();ph.name=name;project.photos.add(ph);results.addAll(Engine.analyze(ph,project.settings.get(ph.mode)));}
        byte[] original=project.photos.get(0).bytes.clone();double severity=results.get(0).severity,area=results.get(0).areaPercent;
        Path path=out.resolve("source-filename-results.zip");ProjectIO.export(project,results,path);
        try(ZipFile zip=new ZipFile(path.toFile(),java.nio.charset.StandardCharsets.UTF_8)){
            check(zip.stream().filter(e->e.getName().startsWith("overlays/")).count()==names.size(),"Duplicate source filenames retain every analyzed overlay");
            check(zip.stream().filter(e->e.getName().startsWith("imagej-rois/")&&!e.getName().endsWith(".roi")).count()==names.size(),"Duplicate source filenames retain every ROI-only preview");
            for(int i=0;i<names.size();i++){
                Photo ph=project.photos.get(i);String name=names.get(i);
                String overlay="overlays/"+(i>=3?ph.id+"/":"")+name,preview="imagej-rois/"+ph.id+"/"+name;
                check(zip.getEntry(overlay)!=null&&zip.getEntry(preview)!=null,"Source filename and original extension preserved in both preview folders: "+name);
                for(String entry:List.of(overlay,preview)){
                    byte[] bytes=zip.getInputStream(zip.getEntry(entry)).readAllBytes();
                    try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
                        var readers=ImageIO.getImageReaders(in);check(readers.hasNext(),"Source-named preview is a readable image: "+entry);
                        var reader=readers.next();try{reader.setInput(in);String actual=reader.getFormatName().toLowerCase(Locale.ROOT);String expected=i==0||i==3||i==4?"jpeg":i==2?"tif":"png";
                            check(actual.startsWith(expected)&&reader.getWidth(0)==ph.width&&reader.getHeight(0)==ph.height,"Encoded format matches extension and native dimensions: "+entry);
                        }finally{reader.dispose();}
                    }
                }
            }
            check(zip.stream().filter(e->e.getName().endsWith(".roi")).count()==14,"Editable ROI files remain complete with source-named previews");
            String manifest=new String(zip.getInputStream(zip.getEntry("analysis-manifest.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(names.stream().allMatch(manifest::contains),"Manifest retains each original Unicode source filename");
        }
        near(Engine.analyze(project.photos.get(0),project.settings.get(Mode.PL)).get(0).severity,severity,1e-12,"Preview re-encoding does not change primary intensity");
        near(Engine.analyze(project.photos.get(0),project.settings.get(Mode.PL)).get(0).areaPercent,area,1e-12,"Preview re-encoding does not change affected area");
        check(Arrays.equals(project.photos.get(0).bytes,original),"Preview re-encoding never modifies original project image bytes");
        Photo legacy=fixture();legacy.name="../../outside/中文.png";check(ProjectIO.exportImageName(legacy).equals("中文.png"),"Legacy filenames cannot introduce ZIP path traversal");
        legacy.name="CON.jpg";check(ProjectIO.exportImageName(legacy).equals("_CON.jpg"),"Legacy reserved Windows device names are safe for extraction");
        legacy.name="";check(ProjectIO.exportImageName(legacy).equals("image-"+legacy.id+".png"),"Missing legacy names get an explicit image filename");
        Path saved=out.resolve("source-filename.pips");ProjectIO.save(project,saved);Project reopened=ProjectIO.load(saved);
        check(reopened.photos.stream().map(ph->ph.name).toList().equals(names),"Original export filenames survive save and reopen");
    }
    static void release130Checks(Path out) throws Exception {
        Project p=new Project();p.subject="Same subject & name";ArrayList<Engine.Result> input=new ArrayList<>();
        for(Mode mode:List.of(Mode.BROWN,Mode.PL))for(String visit:List.of("W26","Baseline","Week 18")){
            Photo ph=fixture();ph.mode=mode;ph.visit=visit;ph.confirmed=false;ph.regions.get(1).name="Medial canthus";ph.regions.add(rect("Upper eyelid",Role.TARGET,52,22,15,20));p.photos.add(ph);input.addAll(Engine.analyze(ph,p.settings.get(mode)));
        }
        var sorted=ProjectIO.sorted(input);check(sorted.size()==12,"Sorting retains every region/mode/visit result");
        for(String region:List.of("Medial canthus","Upper eyelid"))for(Mode mode:List.of(Mode.PL,Mode.BROWN)){var sequence=sorted.stream().filter(x->x.region.name.equals(region)&&x.photo.mode==mode).map(x->x.photo.visit).toList();check(sequence.equals(List.of("Baseline","Week 18","W26")),"Each region/mode retains chronological visits: "+region+" / "+mode);}
        check(sorted.subList(0,6).stream().allMatch(r->r.region.name.equals("Medial canthus"))&&sorted.subList(6,12).stream().allMatch(r->r.region.name.equals("Upper eyelid")),"Region groups stay together across modes");
        check(input.get(0).photo.visit.equals("W26"),"Export sorting does not mutate the input result list");
        check(ProjectIO.visitOrder("w 18")==18&&ProjectIO.visitOrder("Week-26")==26&&ProjectIO.visitOrder("W2")==2&&ProjectIO.visitOrder("W10")==10&&ProjectIO.visitOrder("基线")==0,"Visit labels sort numerically with baseline and week aliases");
        var unknown=Engine.analyze(fixture(),new Settings(Metric.DARKNESS,5)).get(0);unknown.region.name="Medial canthus";unknown.photo.visit="Follow-up";var withUnknown=new ArrayList<>(sorted);withUnknown.add(unknown);var ordered=ProjectIO.sorted(withUnknown);check(ordered.indexOf(unknown)==6,"Unknown visit remains after recognized visits in its region group");
        SwingUtilities.invokeAndWait(()->{App app=null;try{
            app=new App();app.addSession(p,null);int first=app.activeSession;app.results.addAll(input);app.refreshResults();
            check(app.tableModel.getRowCount()==12&&app.tableModel.getValueAt(0,0).equals("Baseline")&&app.tableModel.getValueAt(1,0).equals("Baseline")&&app.tableModel.getValueAt(2,0).equals("Week 18"),"Results table puts baseline rows before numbered visits across modes");
            app.table.setRowSelectionInterval(2,2);check(app.current()==app.results.get(2).photo&&app.selectedRegion()==app.results.get(2).region,"Selecting a sorted result shows its correct image and ROI");
            Project second=new Project();second.subject=p.subject;Photo ph=fixture();ph.visit="W26";second.photos.add(ph);app.addSession(second,null);app.results.addAll(Engine.analyze(ph,second.settings.get(Mode.PL)));app.refreshResults();
            List<ProjectIO.ExportProject> current=app.exportProjects(false);check(current.size()==1&&current.get(0).project()==second&&current.get(0).results().size()==1,"Current-project export includes only the active tab's displayed results");
            ProjectIO.exportExcel(current,out.resolve("current-project.xlsx"));
            List<ProjectIO.ExportProject> all=app.exportProjects(true);check(all.size()==3&&all.get(0).results().isEmpty()&&all.get(1).results().size()==12&&all.get(2).results().size()==1,"All-open export captures inactive and active results plus the empty project");
            ProjectIO.exportExcel(all,out.resolve("all-projects.xlsx"));check(all.get(1).label().equals(all.get(2).label()),"Duplicate project labels are retained and distinguished by project number");
            app.activateSession(first);check(app.results.size()==12&&app.tableModel.getRowCount()==12,"Batch export preserves inactive project results and table order");
            check(app.exportProjects(false).get(0).project()==p,"Current-project export follows tab switching");
            app.changed("Invalidate cached results after ROI edit");check(app.exportProjects(true).get(1).results().isEmpty(),"Batch export does not reuse invalidated stale results");
            check(app.importChooser().isMultiSelectionEnabled(),"Existing multi-image import remains enabled without UI changes");
            check(app.importChooser().getAccessory()==null,"Import chooser retains its original interface");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{if(app!=null)app.dispose();}});
        Path protectedFile=out.resolve("empty-export.xlsx");Files.writeString(protectedFile,"existing file");fails(()->ProjectIO.exportExcel(List.of(new ProjectIO.ExportProject("Empty","",new Project(),List.of())),protectedFile),"Empty export reports missing results");check(Files.readString(protectedFile).equals("existing file"),"Rejected empty export leaves an existing file unchanged");
        try(ZipFile z=new ZipFile(out.resolve("all-projects.xlsx").toFile())){check(z.getEntry("xl/worksheets/sheet2.xml")!=null,"Batch Excel contains a Projects worksheet");String xml=new String(z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);check(xml.contains("A1:"+ProjectIO.col(ProjectIO.HEADERS.length+1)+"14")&&!xml.contains("Position verification"),"Excel filter covers dynamic columns and removes obsolete confirmation column");}
    }
    static boolean translated(Region r,List<Point2D.Double> before,double dx,double dy){if(r.points.size()!=before.size())return false;for(int i=0;i<before.size();i++)if(Math.abs(r.points.get(i).x-before.get(i).x-dx)>1e-9||Math.abs(r.points.get(i).y-before.get(i).y-dy)>1e-9)return false;return true;}
    static void mouse(App.Canvas canvas,int type,int x,int y,int button){int modifiers=type==java.awt.event.MouseEvent.MOUSE_DRAGGED?java.awt.event.InputEvent.BUTTON1_DOWN_MASK:0;canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas,type,System.currentTimeMillis(),modifiers,x,y,1,false,button));}
    static void drag(App.Canvas canvas,int x,int y,int dx,int dy){mouse(canvas,java.awt.event.MouseEvent.MOUSE_PRESSED,x,y,java.awt.event.MouseEvent.BUTTON1);mouse(canvas,java.awt.event.MouseEvent.MOUSE_DRAGGED,x+dx,y+dy,java.awt.event.MouseEvent.NOBUTTON);mouse(canvas,java.awt.event.MouseEvent.MOUSE_RELEASED,x+dx,y+dy,java.awt.event.MouseEvent.BUTTON1);}
    static void copyMoveChecks(Path out) throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=null;try{
            App.style();app=new App();Project project=new Project();Photo src=fixture(),a=fixture(),b=fixture();src.regions.add(rect("Other measurement",Role.TARGET,55,65,15,10));a.visit="Week 18";b.visit="Week 26";Region existing=rect("Destination exclusion",Role.EXCLUDE,72,3,5,5);a.regions.add(existing);project.photos.addAll(List.of(src,a,b));app.addSession(project,null);
            App.BatchCopyPanel panel=app.new BatchCopyPanel(src);check(panel.regions.getSelectedValuesList().size()==3&&panel.regions.getSelectionMode()==ListSelectionModel.MULTIPLE_INTERVAL_SELECTION&&panel.images.getSelectionMode()==ListSelectionModel.MULTIPLE_INTERVAL_SELECTION,"Batch copy dialog supports multiple source ROIs and destination images");
            panel.regions.setSelectedIndices(new int[]{1,2});panel.images.setSelectedIndices(new int[]{0,1});check(panel.regions.getSelectedValuesList().equals(List.of(src.regions.get(1),src.regions.get(2)))&&panel.targets().equals(List.of(a,b)),"Batch copy selections identify only the requested ROIs and destinations");
            panel.setSize(960,460);layout(panel);BufferedImage preview=new BufferedImage(960,460,BufferedImage.TYPE_INT_RGB);Graphics2D pg=preview.createGraphics();panel.printAll(pg);pg.dispose();ImageIO.write(preview,"png",out.resolve("batch-copy-dialog.png").toFile());
            Region refA=a.regions.get(0),refB=b.regions.get(0);ArrayList<Point2D.Double> sourceBefore=vertices(src.regions.get(1));app.copySelectedRois(src,panel.regions.getSelectedValuesList(),panel.targets());
            check(a.regions.contains(existing)&&a.regions.contains(refA)&&b.regions.contains(refB),"Partial copy retains unrelated destination ROIs and the existing shared reference");
            Region copied=a.regions.stream().filter(r->r.id.equals(src.regions.get(1).id)).findFirst().orElseThrow();Region copiedB=b.regions.stream().filter(r->r.id.equals(src.regions.get(1).id)).findFirst().orElseThrow();check(copied!=src.regions.get(1)&&copied!=copiedB&&copied.points.get(0)!=copiedB.points.get(0),"Each destination receives independent ROI objects and vertex copies");
            check(translated(copied,sourceBefore,0,0)&&translated(copiedB,sourceBefore,0,0),"Selected ROI copy preserves exact shape, size and original coordinates in all destinations");
            check(!a.confirmed&&!b.confirmed&&src.confirmed,"Batch copy invalidates destination verification without modifying source verification");
            int count=a.regions.size();app.copySelectedRois(src,List.of(src.regions.get(1)),List.of(a));check(a.regions.size()==count&&a.regions.stream().filter(r->r.id.equals(src.regions.get(1).id)).count()==1,"Repeating selected copy updates matching IDs without creating duplicate ROIs");
            app.copySelectedRois(src,List.of(src.regions.get(0)),List.of(a,b));check(a.regions.stream().filter(r->r.role==Role.REFERENCE).count()==1&&!a.regions.contains(refA)&&a.regions.contains(existing)&&b.regions.stream().filter(r->r.role==Role.REFERENCE).count()==1,"Copying the shared reference replaces only destination references");
            App active=app;int audit=project.audit.size();fails(()->active.copySelectedRois(src,List.of(),List.of(a)),"Empty source ROI selection is rejected");fails(()->active.copySelectedRois(src,List.of(src.regions.get(1)),List.of()),"Empty destination selection is rejected");fails(()->active.copySelectedRois(src,List.of(src.regions.get(1)),List.of(src)),"Copy cannot overwrite its source image");
            Region extra=rect("Extra old reference",Role.REFERENCE,2,50,8,8);src.regions.add(extra);fails(()->active.copySelectedRois(src,List.of(src.regions.get(0),extra),List.of(a,b)),"Selecting multiple legacy references is rejected before modifying destinations");src.regions.remove(extra);check(project.audit.size()==audit&&a.regions.contains(existing),"Rejected copies do not alter project history or unrelated destination ROIs");
            app.photos.setSelectedValue(a,true);copied=a.regions.stream().filter(r->r.id.equals(src.regions.get(1).id)).findFirst().orElseThrow();Region other=a.regions.stream().filter(r->r.id.equals(src.regions.get(2).id)).findFirst().orElseThrow();var before=vertices(copied);var otherBefore=vertices(other);app.rois.setSelectedValue(a.regions.stream().filter(r->r.role==Role.REFERENCE).findFirst().orElseThrow(),true);app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;a.confirmed=true;app.results.addAll(Engine.analyze(a,project.settings.get(a.mode)));app.refreshResults();app.dirty=false;
            drag(app.canvas,35,35,9,7);check(app.selectedRegion()==copied&&translated(copied,before,9,7),"Mouse dragging inside an unselected copied ROI selects and translates its entire polygon");
            check(translated(other,otherBefore,0,0)&&translated(src.regions.get(1),sourceBefore,0,0)&&translated(copiedB,sourceBefore,0,0),"Whole-ROI drag leaves source, other destination and unrelated ROIs unchanged");
            boolean lengths=true;for(int i=0;i<before.size();i++)lengths&=Math.abs(before.get(i).distance(before.get((i+1)%before.size()))-copied.points.get(i).distance(copied.points.get((i+1)%before.size())))<1e-9;check(lengths&&copied.nativeRoi().getBounds().width==40&&copied.nativeRoi().getBounds().height==40,"Whole-ROI drag preserves edge lengths and bounding dimensions");
            check(app.dirty&&!a.confirmed&&app.results.isEmpty()&&project.audit.stream().anyMatch(x->x.contains("without shape change")),"ROI translation immediately marks unsaved, invalidates results/verification and records history");
            before=vertices(copied);app.canvas.zoom=2;app.canvas.ox=10;app.canvas.oy=20;drag(app.canvas,110,110,12,-6);check(translated(copied,before,6,-3),"Whole-ROI translation respects zoom and image offsets");
            app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;Region tiny=rect("Tiny reference-like ROI",Role.EXCLUDE,5,50,8,8);a.regions.add(tiny);app.syncPhoto();app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;app.rois.setSelectedValue(tiny,true);var tinyBefore=vertices(tiny);drag(app.canvas,9,54,2,3);check(translated(tiny,tinyBefore,2,3),"Dragging at a small ROI center moves the whole polygon without distorting it");
            var tinyNow=vertices(tiny);int oldAudit=project.audit.size();mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_PRESSED,9,55,java.awt.event.MouseEvent.BUTTON1);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_RELEASED,9,55,java.awt.event.MouseEvent.BUTTON1);check(translated(tiny,tinyNow,0,0)&&project.audit.size()==oldAudit,"Clicking without dragging does not change ROI geometry or audit history");
            app.busy=true;drag(app.canvas,9,55,1,1);app.busy=false;check(translated(tiny,tinyNow,0,0),"ROI dragging is blocked during analysis");
            var polygonBefore=vertices(copied);app.rois.setSelectedValue(copied,true);Point2D.Double vertex=copied.points.get(0);drag(app.canvas,(int)vertex.x-2,(int)vertex.y-2,-3,-3);check(copied.points.get(0).distance(polygonBefore.get(0))>0&&copied.points.get(1).equals(polygonBefore.get(1)),"Dragging a selected vertex handle from outside still supports explicit shape editing");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{if(app!=null)app.dispose();}});
    }
    static void crossProjectCopyChecks(Path out) throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=new App();try{
            Project a=new Project(),b=new Project(),c=new Project();a.subject="COPY-SOURCE";b.subject="COPY-TARGET";c.subject="OTHER";
            Photo empty=fixture(),src=fixture(),a2=fixture(),b0=fixture(),b1=fixture(),c0=fixture();empty.regions.clear();a.photos.addAll(List.of(empty,src,a2));b.photos.addAll(List.of(b0,b1));c.photos.add(c0);Region extra=rect("Retained exclusion",Role.EXCLUDE,72,3,5,5);b0.regions.add(extra);b.settings.get(Mode.PL).threshold=7;
            app.addSession(a,null);int ai=app.activeSession;app.results.addAll(Engine.analyze(src,a.settings.get(Mode.PL)));app.refreshResults();app.addSession(b,null);int bi=app.activeSession;app.results.addAll(Engine.analyze(b0,b.settings.get(Mode.PL)));app.refreshResults();app.addSession(c,null);app.results.addAll(Engine.analyze(c0,c.settings.get(Mode.PL)));app.refreshResults();app.activateSession(ai);
            app.photos.setSelectedValue(src,true);app.source.setSelectedItem(src);app.refreshPhotos();check(app.source.getSelectedItem()==src,"Refreshing images preserves a non-first source image selection");
            App.BatchCopyPanel panel=app.new BatchCopyPanel(src);check(panel.sourceImage.getSelectedItem()==src&&panel.regions.getModel().getSize()==2,"Copy dialog starts with the requested non-first source and its ROI list");
            panel.sourceProject.setSelectedItem(app.owner(b1));panel.sourceImage.setSelectedItem(b1);check(panel.sourceImage.getSelectedItem()==b1&&panel.images.getModel().getSize()==5&&panel.targets().stream().noneMatch(x->x==b1),"Copy dialog can choose an arbitrary source in another project and excludes it from targets");
            panel.sourceProject.setSelectedItem(app.owner(src));panel.sourceImage.setSelectedItem(src);panel.images.clearSelection();for(int i=0;i<panel.images.getModel().getSize();i++)if(List.of(b0,b1).contains(panel.images.getModel().getElementAt(i).photo()))panel.images.addSelectionInterval(i,i);check(panel.targets().equals(List.of(b0,b1))&&app.project==a,"Cross-project target selection does not switch the active project");
            panel.setSize(960,460);layout(panel);BufferedImage shot=new BufferedImage(960,460,BufferedImage.TYPE_INT_RGB);Graphics2D g=shot.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(shot,"png",out.resolve("cross-project-copy.png").toFile());
            var original=vertices(src.regions.get(1));SessionSnapshot sourceState=new SessionSnapshot(app.owner(src).dirty,app.results.size(),src.alignment);app.copySelectedRois(src,panel.regions.getSelectedValuesList(),panel.targets());
            check(app.project==a&&app.results.size()==sourceState.results&&!app.dirty&&src.alignment.equals(sourceState.alignment)&&translated(src.regions.get(1),original,0,0),"Copying to inactive projects preserves the source's results, clean state and geometry");
            check(app.owner(b0).dirty&&app.owner(b0).results.isEmpty()&&app.owner(c0).results.size()==1&&!app.owner(c0).dirty,"Only changed destination projects become dirty and lose cached results");
            check(b0.regions.contains(extra)&&b0.regions.stream().filter(r->r.role==Role.REFERENCE).count()==1&&b.settings.get(Mode.PL).threshold==7,"Cross-project copy retains unrelated exclusions, one reference and destination parameters");
            Region copied0=b0.regions.stream().filter(r->r.id.equals(src.regions.get(1).id)).findFirst().orElseThrow(),copied1=b1.regions.stream().filter(r->r.id.equals(src.regions.get(1).id)).findFirst().orElseThrow();check(copied0!=copied1&&copied0.points.get(0)!=copied1.points.get(0)&&translated(copied0,original,0,0)&&translated(copied1,original,0,0),"Cross-project copies have exact coordinates and independent point objects");
            copied0.points.get(0).x+=1;check(copied1.points.get(0).equals(original.get(0))&&src.regions.get(1).points.get(0).equals(original.get(0)),"Editing one destination does not mutate the source or another destination");copied0.points=verticesFrom(original);
            app.activateSession(bi);check(app.roiModel.size()==b0.regions.size()&&app.results.isEmpty()&&app.dirty,"Switching to a changed project refreshes its ROIs and shows invalidated results");app.saveTo(out.resolve("cross-copy-target.pips"));Project saved=ProjectIO.load(out.resolve("cross-copy-target.pips"));check(saved.photos.get(0).regions.stream().anyMatch(r->r.id.equals(copied0.id))&&saved.photos.get(0).alignment.contains("COPY-SOURCE"),"Copied ROIs and source-project provenance persist after save/reopen");
            app.results.addAll(Engine.analyze(b0,b.settings.get(Mode.PL)));app.refreshResults();app.dirty=false;int audit=b.audit.size(),cached=app.results.size();app.copySelectedRois(src,src.regions,List.of(a2,c0));check(app.results.size()==cached&&!app.dirty&&b.audit.size()==audit&&app.owner(a2).results.isEmpty()&&app.owner(c0).results.isEmpty(),"One copy can change several other projects while preserving the caller project's cache");
            App active=app;int before=b.audit.size();fails(()->active.copySelectedRois(src,src.regions,List.of(b1,fixture())),"Foreign destination is rejected before any cross-project changes");check(b.audit.size()==before,"Rejected multi-target copy leaves destination history unchanged");
            app.busy=true;fails(()->active.copySelectedRois(src,src.regions,List.of(b1)),"Cross-project copy is blocked while analysis is running");app.busy=false;
        }catch(Exception ex){throw new RuntimeException(ex);}finally{app.dispose();}});
    }
    record SessionSnapshot(boolean dirty,int results,String alignment) {}
    static void imageOrderChecks(Path out) throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=new App();try{
            Project p=new Project();Photo first=fixture(),second=fixture(),third=fixture();first.name="first.png";second.name="second.png";third.name="third.png";p.photos.addAll(List.of(first,second,third));app.addSession(p,null);int pi=app.activeSession;app.results.addAll(Engine.analyze(second,p.settings.get(Mode.PL)));app.refreshResults();app.photos.setSelectedValue(second,true);app.rois.setSelectedValue(second.regions.get(1),true);app.source.setSelectedItem(third);app.canvas.pending.add(new Point2D.Double(7,8));app.canvas.zoom=.75;app.canvas.ox=11;app.visit.setText("Unapplied label");
            check(app.photos.getDragEnabled()&&app.photos.getDropMode()==DropMode.INSERT&&app.photos.getTransferHandler() instanceof App.ImageOrderTransferHandler,"Image list has native MOVE drag-and-drop with insertion feedback");
            var handler=(App.ImageOrderTransferHandler)app.photos.getTransferHandler();var packet=handler.createTransferable(app.photos);App.ImageDrag drag=(App.ImageDrag)packet.getTransferData(App.IMAGE_ORDER_FLAVOR);check(app.moveImage(drag,0)&&p.photos.equals(List.of(second,first,third)),"Dragging a later image before the first updates project order without duplicates");
            check(app.current()==second&&app.selectedRegion()==second.regions.get(1)&&app.source.getSelectedItem()==third&&app.canvas.pending.size()==1&&app.canvas.zoom==.75&&app.canvas.ox==11&&app.visit.getText().equals("Unapplied label"),"Reordering preserves selected image, ROI, copy source, pending vertices, view and unapplied fields");
            check(app.results.size()==1&&app.tableModel.getRowCount()==1&&app.dirty,"Reordering marks the project unsaved while retaining valid analysis results");
            check(app.moveImage(drag,3)&&p.photos.equals(List.of(first,third,second)),"Downward image dragging uses the correct post-removal insertion index");
            check(app.moveImage(drag,0)&&p.photos.equals(List.of(second,first,third)),"Dragging the last image upward restores the intended order");
            int audit=p.audit.size();app.moveImage(drag,1);check(p.audit.size()==audit&&p.photos.equals(List.of(second,first,third)),"Dropping at the same position has no history or order side effects");
            App active=app;app.busy=true;check(!app.moveImage(drag,3)&&handler.getSourceActions(app.photos)==javax.swing.TransferHandler.NONE,"Image reordering is blocked during analysis");app.busy=false;
            check(!handler.canImport(new javax.swing.TransferHandler.TransferSupport(app.photos,packet)),"Non-drop transfers are not accepted as image reordering");
            Project other=new Project();other.photos.add(fixture());app.addSession(other,null);check(!app.moveImage(drag,0)&&other.photos.size()==1,"A drag started in another project cannot move images across projects");app.activateSession(pi);
            app.saveTo(out.resolve("image-order.pips"));Project loaded=ProjectIO.load(out.resolve("image-order.pips"));check(loaded.photos.stream().map(x->x.name).toList().equals(List.of("second.png","first.png","third.png")),"Dragged image order persists through project save/reopen");
            check(app.photoModel.get(0)==second&&app.photoModel.get(1)==first&&app.photoModel.get(2)==third,"Visible image list and project order stay synchronized after tab switching");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{app.dispose();}});
    }
    static void numericVisitChecks(Path out) throws Exception {
        Project project=new Project();project.subject="Numeric order demo";List<String> labels=List.of("W26","10","BASELINE","week 2","6w","W1.5","Follow-up","After 18","Visit 0","Unknown");ArrayList<Engine.Result> input=new ArrayList<>();
        for(String label:labels){Photo p=fixture();p.visit=label;p.name=label.equals("Follow-up")?"image_3.png":"image.png";p.mode=label.equals("BASELINE")?Mode.BROWN:Mode.PL;p.regions.get(1).name="Medial canthus";project.photos.add(p);input.addAll(Engine.analyze(p,project.settings.get(p.mode)));}
        List<String> expected=List.of("BASELINE","Visit 0","W1.5","week 2","Follow-up","6w","10","After 18","W26","Unknown");var sorted=ProjectIO.sorted(input);check(sorted.stream().map(r->r.photo.visit).toList().equals(expected),"Region rows put baseline first then numeric labels, with filename fallback and unknowns last");
        check(ProjectIO.numberOrder("18w")==18&&ProjectIO.numberOrder("follow-up_6")==6&&ProjectIO.numberOrder("10")==10&&ProjectIO.numberOrder("2")==2,"Numeric ordering recognizes labels without a fixed W prefix");
        List<Object[]> rows=ProjectIO.rows(project,input);check(rows.subList(1,rows.size()).stream().map(r->r[1]).toList().equals(expected),"Excel/CSV row data uses the same expanded numeric ordering");
        ProjectIO.exportExcel(List.of(new ProjectIO.ExportProject("Numeric order demo","Unsaved project",project,input)),out.resolve("numeric-order.xlsx"));
        SwingUtilities.invokeAndWait(()->{App app=new App();try{app.addSession(project,null);app.results.addAll(input);app.refreshResults();ArrayList<String> visits=new ArrayList<>();for(int i=0;i<app.tableModel.getRowCount();i++)visits.add((String)app.tableModel.getValueAt(i,0));check(visits.equals(expected),"Results UI uses baseline-first numeric ordering independently of image-list order");}finally{app.dispose();}});
    }
    static void ctrlZ(App app){Object key=app.canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ctrl Z"));app.canvas.getActionMap().get(key).actionPerformed(null);}
    static void roiUndoChecks(Path out) throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=new App();try{
            Project p=new Project();Photo ph=fixture(),other=fixture();p.photos.addAll(List.of(ph,other));app.addSession(p,null);int pi=app.activeSession;Region r=ph.regions.get(1);app.photos.setSelectedValue(ph,true);app.rois.setSelectedValue(r,true);app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;var original=vertices(r);var reference=vertices(ph.regions.get(0));byte[] bytes=ph.bytes.clone();
            mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_PRESSED,50,40,java.awt.event.MouseEvent.BUTTON1);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_DRAGGED,53,42,java.awt.event.MouseEvent.NOBUTTON);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_DRAGGED,55,44,java.awt.event.MouseEvent.NOBUTTON);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_RELEASED,55,44,java.awt.event.MouseEvent.BUTTON1);check(app.owner(ph).edits.size()==1&&translated(r,original,5,4),"One complete whole-ROI drag creates one undo step regardless of mouse frames");
            app.results.addAll(Engine.analyze(ph,p.settings.get(Mode.PL)));app.refreshResults();app.dirty=false;ctrlZ(app);check(App.pointsEqual(r.points,original)&&app.owner(ph).edits.isEmpty(),"Ctrl+Z restores the exact pre-movement ROI coordinates");
            check(app.results.isEmpty()&&app.dirty&&p.audit.stream().anyMatch(x->x.contains("movement undone")),"Movement undo invalidates measurements, marks unsaved and records history");check(translated(ph.regions.get(0),reference,0,0)&&Arrays.equals(bytes,ph.bytes),"ROI undo preserves other regions and original image bytes");
            drag(app.canvas,32,22,3,4);var pointEdited=vertices(r);check(app.owner(ph).edits.size()==1&&!app.owner(ph).edits.peekLast().whole(),"A boundary-point gesture creates its own undo step");drag(app.canvas,50,40,4,2);check(app.owner(ph).edits.size()==2,"Vertex editing and whole-shape movement share ordered undo history");ctrlZ(app);check(App.pointsEqual(r.points,pointEdited),"First Ctrl+Z reverses only the most recent whole-ROI move");ctrlZ(app);check(App.pointsEqual(r.points,original),"Second Ctrl+Z reverses the preceding boundary-point adjustment");
            app.photos.setSelectedValue(other,true);app.rois.setSelectedValue(other.regions.get(1),true);app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;var otherBefore=vertices(other.regions.get(1));drag(app.canvas,50,40,2,1);app.photos.setSelectedValue(ph,true);ctrlZ(app);check(app.current()==other&&App.pointsEqual(other.regions.get(1).points,otherBefore),"Undo selects the correct image and restores its last edited ROI within the project");
            app.photos.setSelectedValue(ph,true);app.rois.setSelectedValue(r,true);app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;drag(app.canvas,50,40,1,2);var moved=vertices(r);Project second=new Project();second.photos.add(fixture());app.addSession(second,null);app.canvas.tool=1;ctrlZ(app);check(App.pointsEqual(r.points,moved)&&app.sessions.get(pi).edits.size()==1,"Ctrl+Z in another project cannot undo this project's ROI changes");app.activateSession(pi);ctrlZ(app);check(App.pointsEqual(r.points,original),"Switching back restores the project's independent undo history");
            app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;drag(app.canvas,50,40,1,1);moved=vertices(r);app.busy=true;ctrlZ(app);check(App.pointsEqual(r.points,moved)&&app.owner(ph).edits.size()==1,"ROI undo is blocked while analysis is running");app.busy=false;ctrlZ(app);
            mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_PRESSED,50,40,java.awt.event.MouseEvent.BUTTON1);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_RELEASED,50,40,java.awt.event.MouseEvent.BUTTON1);check(app.owner(ph).edits.isEmpty(),"A click without movement does not create an undo step");
            drag(app.canvas,50,40,2,1);r.points.get(0).x+=1;var stale=vertices(r);ctrlZ(app);check(App.pointsEqual(r.points,stale)&&app.owner(ph).edits.isEmpty(),"Undo refuses stale history after an unrecorded geometry change");r.points=verticesFrom(original);
            drag(app.canvas,50,40,1,1);app.copySelectedRois(other,other.regions,List.of(ph));check(app.owner(ph).edits.isEmpty(),"Copying ROIs clears obsolete edit history for destination images");
            Path file=out.resolve("undo-session.pips");app.saveTo(file);app.addSession(ProjectIO.load(file),null);check(app.sessions.get(app.activeSession).edits.isEmpty(),"ROI edit history is session-only and is not restored from a project file");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{app.dispose();}});
    }
    static void dragPriorityChecks() throws Exception {
        SwingUtilities.invokeAndWait(()->{App app=new App();try{
            Project project=new Project();Photo ph=fixture();project.photos.add(ph);app.addSession(project,null);Region target=ph.regions.get(1);app.rois.setSelectedValue(target,true);app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;
            var original=vertices(target);drag(app.canvas,32,22,3,4);check(target.points.get(0).equals(new Point2D.Double(33,24))&&target.points.get(1).equals(original.get(1))&&target.points.get(2).equals(original.get(2))&&target.points.get(3).equals(original.get(3)),"Inside boundary-point drag edits only that vertex without snapping it to the initial click");
            target.points=verticesFrom(original);app.rois.setSelectedValue(ph.regions.get(0),true);drag(app.canvas,32,22,4,3);check(app.selectedRegion()==target&&target.points.get(0).equals(new Point2D.Double(34,23))&&target.points.get(1).equals(original.get(1)),"Dragging an unselected ROI's nearby vertex selects it and edits the point instead of the whole ROI");
            target.points=verticesFrom(original);app.rois.setSelectedValue(target,true);app.canvas.zoom=2;app.canvas.ox=10;app.canvas.oy=20;drag(app.canvas,74,64,6,8);check(target.points.get(0).equals(new Point2D.Double(33,24))&&target.points.get(1).equals(original.get(1)),"Vertex priority and displacement respect zoom and pan");
            target.points=verticesFrom(original);app.canvas.zoom=.5;app.canvas.ox=10;app.canvas.oy=20;drag(app.canvas,26,31,2,3);check(target.points.get(0).equals(new Point2D.Double(34,26))&&target.points.get(2).equals(original.get(2)),"Boundary-point editing works while zoomed out");
            target.points=verticesFrom(original);app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_MOVED,32,22,java.awt.event.MouseEvent.NOBUTTON);check(app.canvas.getCursor().getType()==Cursor.CROSSHAIR_CURSOR,"Hover near a vertex shows the point-edit cursor");mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_MOVED,50,40,java.awt.event.MouseEvent.NOBUTTON);check(app.canvas.getCursor().getType()==Cursor.MOVE_CURSOR,"Hover in the ROI center shows the whole-ROI move cursor");
            drag(app.canvas,50,40,3,2);check(translated(target,original,3,2),"Dragging at the large ROI center still preserves all vertex offsets and shape");
            target.points=verticesFrom(original);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_PRESSED,32,22,java.awt.event.MouseEvent.BUTTON1);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_DRAGGED,50,40,java.awt.event.MouseEvent.NOBUTTON);mouse(app.canvas,java.awt.event.MouseEvent.MOUSE_RELEASED,50,40,java.awt.event.MouseEvent.BUTTON1);check(target.points.get(0).equals(new Point2D.Double(48,38))&&target.points.get(1).equals(original.get(1)),"A gesture started on a vertex remains point editing when the pointer crosses the interior");
            target.points=verticesFrom(original);Region small=rect("Small ROI",Role.EXCLUDE,5,50,8,8);ph.regions.add(small);app.syncPhoto();app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;app.rois.setSelectedValue(small,true);var before=vertices(small);drag(app.canvas,6,51,1,1);check(small.points.get(0).equals(new Point2D.Double(6,51))&&small.points.get(1).equals(before.get(1)),"A small ROI near-corner drag edits the corner instead of translating the polygon");
            small.points=verticesFrom(before);drag(app.canvas,9,54,2,3);check(translated(small,before,2,3),"Adaptive vertex tolerance leaves the center of small ROIs available for whole-shape movement");
            ph.regions.remove(small);target.points=verticesFrom(original);target.points.add(1,new Point2D.Double(34,20));app.syncPhoto();app.canvas.tool=1;app.canvas.zoom=1;app.canvas.ox=0;app.canvas.oy=0;app.rois.setSelectedValue(target,true);var dense=vertices(target);drag(app.canvas,34,21,0,3);check(target.points.get(1).equals(new Point2D.Double(34,23))&&target.points.get(0).equals(dense.get(0)),"The nearest of closely spaced boundary points is edited, not the first point in the polygon");
        }catch(Exception ex){throw new RuntimeException(ex);}finally{app.dispose();}});
    }
    static ArrayList<Point2D.Double> verticesFrom(List<Point2D.Double> points){ArrayList<Point2D.Double> out=new ArrayList<>();for(var p:points)out.add(new Point2D.Double(p.x,p.y));return out;}
    static void inflammationChecks(Path out) throws Exception {
        check(Mode.values().length==4,"Inflammation is a separate fourth image mode");
        check(Mode.guess("sample_\u7ea2\u8272\u533a.jpg")==Mode.INFLAMMATION,"Device red-area filename is recognized as Inflammation");
        check(Mode.guess("sample_\u708e\u75c7.png")==Mode.INFLAMMATION,"Chinese inflammation filename is recognized");
        check(Mode.guess("sample_INFLAMMATION.jpg")==Mode.INFLAMMATION&&Mode.guess("sample_red_area.jpg")==Mode.INFLAMMATION,"English Inflammation and red_area filenames are recognized");
        check(Mode.guess("sample_\u7ea2\u8840\u4e1d.jpg")==Mode.RED&&Mode.guess("sample_Redness.jpg")==Mode.RED,"Inflammation naming does not replace the existing vascular Redness mode");
        check(Mode.guess("sample_\u68d5\u8272\u533a.jpg")==Mode.BROWN&&Mode.guess("sample_PL.jpg")==Mode.PL,"Brown and PL filename inference is retained");
        Project p=new Project();Settings settings=p.settings.get(Mode.INFLAMMATION);check(settings.metric==Metric.REDNESS&&settings.threshold==3&&settings!=p.settings.get(Mode.RED),"Inflammation uses independent relative-redness parameters with initial threshold 3");
        BufferedImage image=new BufferedImage(80,80,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(new Color(200,180,170));g.fillRect(0,0,80,80);g.setColor(new Color(230,140,140));g.fillRect(30,20,20,40);g.dispose();Photo ph=photo(image,Mode.INFLAMMATION,"Baseline");ph.regions.addAll(List.of(rect("Shared reference",Role.REFERENCE,2,2,20,20),rect("Measurement",Role.TARGET,30,20,40,40)));p.photos.add(ph);
        var converter=new ij.process.ColorSpaceConverter("D65");double expected=converter.RGBtoLAB(0xE68C8C)[1]-converter.RGBtoLAB(0xC8B4AA)[1];var result=Engine.analyze(ph,settings).get(0);near(result.severity,expected,1e-4,"Inflammation intensity matches affected-pixel a* minus shared-reference a*");near(result.areaPercent,50,1e-10,"Inflammation segmentation detects the known 50% affected region");
        var none=Engine.analyze(ph,new Settings(Metric.REDNESS,100)).get(0);check(Double.isNaN(none.severity)&&none.areaPercent==0,"Inflammation with no detected pixels has N/A intensity and zero area");
        ph.regions.add(rect("Exclusion",Role.EXCLUDE,30,20,10,40));near(Engine.analyze(ph,settings).get(0).areaPercent,100.0/3,1e-10,"Inflammation honors shared exclusion masks");ph.regions.remove(2);
        settings.threshold=4;check(p.settings.get(Mode.RED).threshold==3,"Editing inflammation threshold leaves vascular Redness parameters unchanged");Path saved=out.resolve("inflammation.pips");ProjectIO.save(p,saved);Project loaded=ProjectIO.load(saved);check(loaded.photos.get(0).mode==Mode.INFLAMMATION&&loaded.settings.get(Mode.INFLAMMATION).threshold==4&&loaded.settings.get(Mode.RED).threshold==3,"Inflammation mode and independent parameters survive project save/reopen");
        result=Engine.analyze(loaded.photos.get(0),loaded.settings.get(Mode.INFLAMMATION)).get(0);var row=ProjectIO.rows(loaded,List.of(result)).get(1);check(row[2].equals("Inflammation")&&row[9].equals(Metric.REDNESS.label)&&row[10] instanceof Number,"Inflammation export has the English mode and numeric affected-only intensity");ProjectIO.export(loaded,List.of(result),out.resolve("inflammation-results.zip"));try(ZipFile zip=new ZipFile(out.resolve("inflammation-results.zip").toFile())){Files.write(out.resolve("inflammation.xlsx"),zip.getInputStream(zip.getEntry("results.xlsx")).readAllBytes());}
        Project old=new Project();old.photos.add(fixture());old.settings.get(Mode.RED).threshold=8;var xml=ProjectIO.factory().newDocumentBuilder().parse(new ByteArrayInputStream(ProjectIO.metadata(old)));for(var element:ProjectIO.children(xml.getDocumentElement(),"settings"))if(element.getAttribute("mode").equals("INFLAMMATION"))xml.getDocumentElement().removeChild(element);
        Path legacy=out.resolve("legacy-three-modes.pips");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(legacy))){ProjectIO.entry(zip,"project.xml",ProjectIO.xml(xml));for(Photo x:old.photos)ProjectIO.entry(zip,"images/"+x.id+".bin",x.bytes);}Project migrated=ProjectIO.load(legacy);check(migrated.photos.get(0).mode==Mode.PL&&migrated.settings.get(Mode.RED).threshold==8&&migrated.settings.get(Mode.INFLAMMATION).threshold==3,"Older three-mode project keeps existing parameters and gains default Inflammation settings");
        Project ui=loaded;SwingUtilities.invokeAndWait(()->{App app=new App();try{app.addSession(ui,null);check(app.mode.getItemCount()==4&&app.mode.getSelectedItem()==Mode.INFLAMMATION&&app.metric.getSelectedItem()==Metric.REDNESS&&((Number)app.threshold.getValue()).doubleValue()==4,"GUI shows Inflammation and its saved relative-redness parameters");}finally{app.dispose();}});
    }
    static Project demo() throws Exception {
        Project p=new Project();p.subject="DEMO-001 (synthetic)";p.notes="Synthetic images, not real patients; for software practice only.";
        for(String visit:List.of("Baseline","Week 26"))for(Mode m:Mode.values()){
            BufferedImage im=new BufferedImage(600,740,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(31,50,61));g.fillRect(0,0,600,740);g.setColor(m==Mode.INFLAMMATION?new Color(248,211,222):m==Mode.RED?new Color(223,170,161):m==Mode.BROWN?new Color(221,186,153):new Color(227,197,177));g.fillOval(65,45,470,650);
            g.setColor(new Color(75,65,61));g.setStroke(new BasicStroke(5));g.drawArc(125,280,140,30,180,180);g.drawArc(335,280,140,30,180,180);
            int alpha=visit.equals("Baseline")?100:48;Color patch=m==Mode.INFLAMMATION?new Color(169,20,65,alpha):m==Mode.RED?new Color(218,45,55,alpha):m==Mode.BROWN?new Color(90,42,10,alpha):new Color(90,60,45,alpha);g.setColor(patch);g.fillOval(125,317,142,54);g.fillOval(333,317,142,54);
            g.setColor(new Color(146,113,100));g.setStroke(new BasicStroke(3));g.drawLine(300,330,280,450);g.drawArc(267,439,70,23,180,180);g.drawArc(230,510,145,22,180,180);
            g.setColor(Color.WHITE);g.setFont(new Font("Segoe UI",Font.BOLD,20));g.drawString("Synthetic demo / not a patient",145,718);g.dispose();
            Photo ph=photo(im,m,visit);Region r1=rect("Shared reference skin",Role.REFERENCE,122,395,60,35),t1=rect("Left lower lid demo",Role.TARGET,117,310,158,72),t2=rect("Right lower lid demo",Role.TARGET,325,310,158,72);ph.regions.addAll(List.of(r1,t1,t2));ph.alignment="Synthetic images: identical pixel coordinates";ph.landmarks.add(new Point2D.Double(130,296));ph.landmarks.add(new Point2D.Double(465,296));p.photos.add(ph);
        }
        p.log("Synthetic demo created");return p;
    }
    static void preview(Path output) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());SwingUtilities.invokeAndWait(()->{try{
            App.style();App app=new App();Project second=demo();second.subject="DEMO-002 (synthetic)";app.addSession(second,null);app.addSession(demo(),null);for(Photo ph:app.project.photos)app.results.addAll(Engine.analyze(ph,app.project.settings.get(ph.mode)));app.refreshResults();app.photos.setSelectedIndex(3);app.rois.setSelectedIndex(1);app.getRootPane().setSize(1500,980);layout(app.getRootPane());app.canvas.fit();
            BufferedImage shot=new BufferedImage(1500,980,BufferedImage.TYPE_INT_RGB);Graphics2D g=shot.createGraphics();g.setColor(App.BG);g.fillRect(0,0,1500,980);app.getRootPane().printAll(g);g.dispose();ImageIO.write(shot,"png",output.toFile());app.dispose();
        }catch(Exception ex){throw new RuntimeException(ex);}});System.out.println("Preview rendered: "+output);
    }
    static void layout(Container c){c.doLayout();for(Component comp:c.getComponents())if(comp instanceof Container child)layout(child);}
    static final String CHINESE_FOLDER="\u4e2d\u6587\u76ee\u5f55";
    static final String CHINESE_IMAGE="\u773c\u5468\u56fe\u7247_\u68d5\u8272\u533a.png";
    static void unicodeChecks(Path out) throws Exception {
        Path folder=out.resolve(CHINESE_FOLDER);Files.createDirectories(folder);
        Path image=folder.resolve(CHINESE_IMAGE);Files.write(image,fixture().bytes);
        Files.write(folder.resolve("English_PL.png"),fixture().bytes);
        Files.write(folder.resolve("\u773c\u5468\u56fe\u7247_\u7ea2\u8840\u4e1d.png"),fixture().bytes);
        Photo photo=Photo.read(image,Mode.guess(image.getFileName().toString()),"Baseline");
        check(photo.name.equals(CHINESE_IMAGE)&&photo.mode==Mode.BROWN&&photo.width==80,"Chinese image filename and directory import without renaming");
        Project project=new Project();project.subject=CHINESE_FOLDER;project.photos.add(photo);
        Path saved=folder.resolve("\u4e2d\u6587\u9879\u76ee.pips");ProjectIO.save(project,saved);
        Project loaded=ProjectIO.load(saved);
        check(loaded.subject.equals(CHINESE_FOLDER)&&loaded.photos.get(0).name.equals(CHINESE_IMAGE)&&Arrays.equals(loaded.photos.get(0).bytes,photo.bytes),"Chinese project path and embedded image name round trip");
        JFileChooser[] picker={null};
        SwingUtilities.invokeAndWait(()->{
            App.style();picker[0]=new JFileChooser(folder.toFile());
            check(UIManager.getFont("List.font").canDisplayUpTo(CHINESE_IMAGE)==-1&&UIManager.getFont("TextField.font").canDisplayUpTo(CHINESE_FOLDER)==-1,"UI logical fonts provide Chinese glyph fallback");
            check(UIManager.getString("FileChooser.openButtonText").equals("Open")&&UIManager.getString("FileChooser.cancelButtonText").equals("Cancel"),"File chooser action labels remain English");
        });
        // The chooser loads its file list asynchronously. Wait outside the EDT.
        boolean[] ready={false};
        for(int i=0;i<100&&!ready[0];i++){
            SwingUtilities.invokeAndWait(()->ready[0]=findFileList(picker[0])!=null);
            if(!ready[0])Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(()->{try{
            JFileChooser chooser=picker[0];JList<?> list=findFileList(chooser);
            check(list!=null,"File chooser discovers Chinese-named images in Chinese directory");
            check(chooserFonts(chooser),"File chooser component fonts support Chinese user data");
            for(int i=0;i<list.getModel().getSize();i++){
                Object value=list.getModel().getElementAt(i);
                if(value instanceof File file&&file.getName().equals(CHINESE_IMAGE)){
                    @SuppressWarnings("unchecked") ListCellRenderer<Object> renderer=(ListCellRenderer<Object>)list.getCellRenderer();
                    @SuppressWarnings("unchecked") JList<Object> fileList=(JList<Object>)list;
                    Component cell=renderer.getListCellRendererComponent(fileList,value,i,false,false);
                    check(cell.getFont().canDisplayUpTo(CHINESE_IMAGE)==-1&&cell instanceof JLabel label&&label.getText().equals(CHINESE_IMAGE),"Actual file list renderer preserves Chinese name with supported glyphs");
                }
            }
            chooser.setSelectedFile(image.toFile());
            check(chooser.getSelectedFile().toPath().equals(image.toFile().getAbsoluteFile().toPath()),"File chooser returns intact Chinese path");
            chooser.setSize(980,640);layout(chooser);
            BufferedImage shot=new BufferedImage(980,640,BufferedImage.TYPE_INT_RGB);Graphics2D g=shot.createGraphics();g.setColor(App.BG);g.fillRect(0,0,980,640);chooser.printAll(g);g.dispose();
            ImageIO.write(shot,"png",out.resolve("chinese-file-chooser.png").toFile());
        }catch(IOException ex){throw new RuntimeException(ex);}});
    }
    static JList<?> findFileList(Container root){
        for(Component c:root.getComponents()){
            if(c instanceof JList<?> list)for(int i=0;i<list.getModel().getSize();i++)
                if(list.getModel().getElementAt(i) instanceof File file&&file.getName().equals(CHINESE_IMAGE))return list;
            if(c instanceof Container child){JList<?> found=findFileList(child);if(found!=null)return found;}
        }return null;
    }
    static boolean chooserFonts(Container root){
        for(Component c:root.getComponents()){
            if(c instanceof JComponent&&c.getFont()!=null&&c.getFont().canDisplayUpTo(CHINESE_IMAGE)!=-1)return false;
            if(c instanceof Container child&&!chooserFonts(child))return false;
        }return true;
    }
    static void smoke(Path directory,Path output) throws Exception {
        Project p=new Project();StringBuilder report=new StringBuilder("Input compatibility test only; no clinical interpretation.\n");
        for(Mode mode:List.of(Mode.PL,Mode.RED,Mode.BROWN)){
            String suffix=mode==Mode.PL?"_PL.jpg":mode==Mode.RED?"_红血丝.jpg":"_棕色区.jpg";
            Path file;try(var paths=Files.list(directory)){file=paths.filter(x->x.getFileName().toString().endsWith(suffix)).findFirst().orElseThrow();}
            Photo ph=Photo.read(file,mode,"Compatibility test");ph.confirmed=true;
            Region ref=rect("Test reference",Role.REFERENCE,280,1930,100,80),target=rect("Test target",Role.TARGET,300,1740,440,160);target.referenceId=ref.id;ph.regions.addAll(List.of(ref,target));
            long start=System.nanoTime();var r=Engine.analyze(ph,p.settings.get(mode)).get(0);
            if(!Double.isFinite(r.severity)||r.areaPercent<0||r.areaPercent>100||r.pixels!=70400)throw new AssertionError("Input test failed");
            report.append(mode.name()).append(" | ").append(ph.width).append('x').append(ph.height).append(" | PASS | ").append(String.format(Locale.ROOT,"%.3f s",(System.nanoTime()-start)/1e9)).append('\n');
        }
        Files.writeString(output,report);System.out.println("PASS: all three original image modes; source files unchanged.");
    }
}
