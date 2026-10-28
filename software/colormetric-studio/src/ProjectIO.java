package peri;

import static peri.Model.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

final class ProjectIO {
    static final long MAX_ARCHIVE_BYTES=1_200_000_000L;
    static Document doc() throws Exception {return factory().newDocumentBuilder().newDocument();}
    static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");return f;
    }
    static Element add(Document d,Element parent,String name,String... pairs){
        Element e=d.createElement(name);if(parent==null)d.appendChild(e);else parent.appendChild(e);
        for(int i=0;i<pairs.length;i+=2)e.setAttribute(pairs[i],pairs[i+1]);return e;
    }
    static byte[] xml(Document d) throws Exception {
        TransformerFactory tf=TransformerFactory.newInstance();tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        Transformer t=tf.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");t.setOutputProperty(OutputKeys.INDENT,"yes");
        ByteArrayOutputStream out=new ByteArrayOutputStream();t.transform(new DOMSource(d),new StreamResult(out));return out.toByteArray();
    }
    static byte[] metadata(Project p) throws Exception {
        Document d=doc();Element root=add(d,null,"colormetric-project","application","ColorMetric Studio","schema",SCHEMA,"appVersion",VERSION,"algorithm",ALGORITHM,"imagej",ij.IJ.getVersion(),"subject",p.subject,"locked",Boolean.toString(p.locked),"notes",p.notes,"colorAssumption","sRGB-D65; image-derived only","areaUnit","percent");
        for(Mode m:Mode.values()){Settings s=p.settings.get(m);add(d,root,"settings","mode",m.name(),"metric",s.metric.name(),"threshold",Double.toString(s.threshold));}
        for(Photo ph:p.photos){
            Element e=add(d,root,"photo","id",ph.id,"name",ph.name,"visit",ph.visit,"mode",ph.mode.name(),"width",Integer.toString(ph.width),"height",Integer.toString(ph.height),"sha256",ph.digest,"confirmed",Boolean.toString(ph.confirmed),"referenceType",ph.pureWhiteReference?"PURE_WHITE":"SKIN_ROI","alignment",ph.alignment,"note",ph.note);
            for(Point2D.Double pt:ph.landmarks)add(d,e,"landmark","x",Double.toString(pt.x),"y",Double.toString(pt.y));
            for(Region r:ph.regions){Element rr=add(d,e,"region","id",r.id,"name",r.name,"role",r.role.name(),"reference",r.referenceId);
                for(Point2D.Double pt:r.points)add(d,rr,"point","x",Double.toString(pt.x),"y",Double.toString(pt.y));}
        }
        Element a=add(d,root,"audit");for(String s:p.audit){Element e=add(d,a,"event");e.setTextContent(s);}return xml(d);
    }
    static void entry(ZipOutputStream z,String name,byte[] b) throws IOException {z.putNextEntry(new ZipEntry(name));z.write(b);z.closeEntry();}
    static void save(Project p,Path file) throws Exception {
        Path target=file.toAbsolutePath(),tmp=Files.createTempFile(target.getParent(),"peri-save-",".tmp");
        try{
            try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(tmp),StandardCharsets.UTF_8)){
                entry(z,"project.xml",metadata(p));for(Photo ph:p.photos)entry(z,"images/"+ph.id+".bin",ph.bytes);
            }
            try{Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
    static byte[] bounded(InputStream in,long limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];long n=0;int k;
        while((k=in.read(b))!=-1){n+=k;if(n>limit)throw new IOException("Project data exceeds the size limit.");out.write(b,0,k);}return out.toByteArray();
    }
    static List<Element> children(Element e,String name){ArrayList<Element> list=new ArrayList<>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element el&&el.getTagName().equals(name))list.add(el);return list;}
    static Project load(Path path) throws Exception {
        Project p=new Project();p.audit.clear();long total=0;
        try(ZipFile z=new ZipFile(path.toFile(),StandardCharsets.UTF_8)){
            ZipEntry meta=z.getEntry("project.xml");if(meta==null)throw new IOException("This is not a ColorMetric Studio project.");
            Document d;try(InputStream in=z.getInputStream(meta)){d=factory().newDocumentBuilder().parse(new ByteArrayInputStream(bounded(in,10_000_000)));}
            Element root=d.getDocumentElement();boolean knownRoot=root.getTagName().equals("colormetric-project")||root.getTagName().equals("periorbital-project");
            if(!knownRoot||!(root.getAttribute("schema").equals(SCHEMA)||root.getAttribute("schema").equals("1")))throw new IOException("Incompatible project format version.");
            p.subject=root.getAttribute("subject");p.notes=root.getAttribute("notes");p.locked=Boolean.parseBoolean(root.getAttribute("locked"));
            for(Element e:children(root,"settings")){double t=Double.parseDouble(e.getAttribute("threshold"));if(!Double.isFinite(t)||t<=0)throw new IOException("Invalid threshold.");p.settings.put(Mode.valueOf(e.getAttribute("mode")),new Settings(Metric.valueOf(e.getAttribute("metric")),t));}
            Set<String> ids=new HashSet<>();
            for(Element e:children(root,"photo")){
                Photo ph=new Photo();ph.id=e.getAttribute("id");UUID.fromString(ph.id);if(!ids.add(ph.id))throw new IOException("Duplicate image ID.");
                ph.name=e.getAttribute("name");ph.visit=e.getAttribute("visit");ph.mode=Mode.valueOf(e.getAttribute("mode"));ph.digest=e.getAttribute("sha256");
                String referenceType=e.getAttribute("referenceType");
                if(root.getAttribute("schema").equals("2")&&referenceType.isEmpty())throw new IOException("Missing reference type.");
                if(!referenceType.isEmpty()&&!referenceType.equals("PURE_WHITE")&&!referenceType.equals("SKIN_ROI"))throw new IOException("Unknown reference type.");
                ph.pureWhiteReference=referenceType.equals("PURE_WHITE");
                ph.alignment=e.getAttribute("alignment");ph.note=e.getAttribute("note");ph.confirmed=Boolean.parseBoolean(e.getAttribute("confirmed"));
                ZipEntry img=z.getEntry("images/"+ph.id+".bin");if(img==null)throw new IOException("Original image missing from project.");
                try(InputStream in=z.getInputStream(img)){ph.bytes=bounded(in,60_000_000);total+=ph.bytes.length;if(total>MAX_ARCHIVE_BYTES)throw new IOException("Project is too large.");}
                if(!sha(ph.bytes).equals(ph.digest))throw new IOException("Original image checksum failed.");
                try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(ph.bytes))){var it=ImageIO.getImageReaders(in);if(!it.hasNext())throw new IOException("Invalid image.");var r=it.next();try{r.setInput(in);ph.width=r.getWidth(0);ph.height=r.getHeight(0);}finally{r.dispose();}}
                if((long)ph.width*ph.height>40_000_000||ph.width<1||ph.height<1)throw new IOException("Invalid image dimensions.");
                if(ph.width!=Integer.parseInt(e.getAttribute("width"))||ph.height!=Integer.parseInt(e.getAttribute("height")))throw new IOException("Image dimensions do not match project metadata.");
                for(Element pt:children(e,"landmark"))ph.landmarks.add(point(pt));
                Set<String> regionIds=new HashSet<>();for(Element el:children(e,"region")){
                    Region r=new Region(el.getAttribute("name"),Role.valueOf(el.getAttribute("role")));r.id=el.getAttribute("id");UUID.fromString(r.id);if(!regionIds.add(r.id))throw new IOException("Duplicate ROI ID.");r.referenceId=el.getAttribute("reference");
                    for(Element pt:children(el,"point"))r.points.add(point(pt));if(r.points.size()>2000)throw new IOException("Too many ROI vertices.");Engine.validate(r,ph.width,ph.height);ph.regions.add(r);
                }
                p.photos.add(ph);if(p.photos.size()>300)throw new IOException("Maximum 300 images per project.");
            }
            for(Element a:children(root,"audit"))for(Element e:children(a,"event"))p.audit.add(e.getTextContent());
        }
        return p;
    }
    static Point2D.Double point(Element e) throws IOException {
        double x=Double.parseDouble(e.getAttribute("x")),y=Double.parseDouble(e.getAttribute("y"));if(!Double.isFinite(x)||!Double.isFinite(y))throw new IOException("Invalid coordinates.");return new Point2D.Double(x,y);
    }
    static void exportRois(Photo ph,Path target) throws Exception {
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(target))){int i=1;for(Region r:ph.regions){var roi=r.nativeRoi();roi.setName(r.name);entry(z,String.format(Locale.ROOT,"%03d_%s.roi",i++,r.role.name()),ij.io.RoiEncoder.saveAsByteArray(roi));}}
    }
    static final String[] HEADERS={"Subject ID","Visit","Mode","Image ID","Source filename","Source SHA256","Measurement ROI","ROI ID","Reference skin (shared per image)","Color metric","Relative color intensity (affected pixels only)","Affected area (%)","Valid ROI pixels","Affected pixels","Whole-ROI mean intensity (auxiliary)","Threshold","Reference channel mean","ROI mapping record","Notes","Quality notes","App version","ImageJ version","Algorithm version","Reference type"};
    static String key(String text){return text.strip().toLowerCase(Locale.ROOT);}
    static boolean baseline(String label){return java.util.regex.Pattern.compile("(?i)(?<![a-z])baseline(?![a-z])|基线").matcher(label).find();}
    static double visitOrder(String visit){
        return baseline(visit)?0:numberOrder(visit);
    }
    static double numberOrder(String label){var m=java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(label);return m.find()?Double.parseDouble(m.group()):Double.POSITIVE_INFINITY;}
    static double imageOrder(Photo p){double order=visitOrder(p.visit);return Double.isFinite(order)?order:numberOrder(p.name);}
    static Comparator<Engine.Result> resultOrder(){return Comparator.comparing((Engine.Result r)->key(r.region.name)).thenComparingInt(r->baseline(r.photo.visit)?0:1).thenComparingDouble(r->imageOrder(r.photo)).thenComparingInt(r->r.photo.mode.ordinal());}
    static List<Engine.Result> sorted(List<Engine.Result> results){ArrayList<Engine.Result> out=new ArrayList<>(results);out.sort(resultOrder());return out;}
    static List<Object[]> rows(Project p,List<Engine.Result> results){
        ArrayList<Object[]> out=new ArrayList<>();out.add(HEADERS);
        for(var r:sorted(results))out.add(new Object[]{p.subject,r.photo.visit,r.photo.mode.label,r.photo.id,r.photo.name,r.photo.digest,r.region.name,r.region.id,r.referenceName,r.metric.label,Double.isNaN(r.severity)?"N/A":r.severity,r.areaPercent,r.pixels,r.abnormal,r.wholeMean,r.threshold,r.referenceMean,r.photo.alignment,r.photo.note,r.warnings,VERSION,ij.IJ.getVersion(),ALGORITHM,r.pureWhiteReference?"PURE_WHITE":"SKIN_ROI"});return out;
    }
    record ExportProject(String label,String path,Project project,List<Engine.Result> results) {}
    static void exportExcel(List<ExportProject> projects,Path target) throws IOException {
        List<Object[]> combined=new ArrayList<>(),summary=new ArrayList<>();
        String[] headers=new String[HEADERS.length+2];headers[0]="Project number";headers[1]="Project";System.arraycopy(HEADERS,0,headers,2,HEADERS.length);combined.add(headers);
        summary.add(new Object[]{"Project number","Project","Project file","Subject ID","Images","Result rows","Export status"});
        int index=0;for(ExportProject item:projects){index++;List<Object[]> data=rows(item.project,item.results);
            summary.add(new Object[]{index,item.label,item.path,item.project.subject,item.project.photos.size(),item.results.size(),item.results.isEmpty()?"No cached results; analyze this project first":"Cached results exported"});
            for(int i=1;i<data.size();i++){Object[] row=new Object[headers.length];row[0]=index;row[1]=item.label;System.arraycopy(data.get(i),0,row,2,HEADERS.length);combined.add(row);}
        }
        if(combined.size()==1)throw new IOException("No analysis results to export. Analyze a project first.");
        Map<String,List<Object[]>> sheets=new LinkedHashMap<>();sheets.put("Results",combined);sheets.put("Projects",summary);
        Path file=target.toAbsolutePath(),tmp=Files.createTempFile(file.getParent(),"peri-excel-",".tmp");
        try{Files.write(tmp,xlsx(sheets));try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
    }
    static String cellText(Object o){return o==null?"":o.toString();}
    static String csvCell(Object o){String s=cellText(o);if(!(o instanceof Number)&&s.matches("^[=+@\\-].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    static byte[] csv(List<Object[]> rows){StringBuilder b=new StringBuilder("\uFEFF");for(Object[] row:rows){for(int c=0;c<row.length;c++){if(c>0)b.append(',');b.append(csvCell(row[c]));}b.append("\r\n");}return b.toString().getBytes(StandardCharsets.UTF_8);}
    static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String col(int c){StringBuilder s=new StringBuilder();for(int x=c+1;x>0;x=(x-1)/26)s.insert(0,(char)('A'+(x-1)%26));return s.toString();}
    static byte[] xlsx(List<Object[]> rows) throws IOException {return xlsx(Collections.singletonMap("Results",rows));}
    static byte[] xlsx(Map<String,List<Object[]>> sheets) throws IOException {
        for(List<Object[]> rows:sheets.values())if(rows.isEmpty()||rows.size()>1048576)throw new IOException("Excel sheet row limit exceeded or missing headers.");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){
            StringBuilder types=new StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for(int n=1;n<=sheets.size();n++)types.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");entry(z,"[Content_Types].xml",types.append("</Types>").toString().getBytes(StandardCharsets.UTF_8));
            entry(z,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>".getBytes(StandardCharsets.UTF_8));
            StringBuilder book=new StringBuilder("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"),rels=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");int n=0;
            for(String name:sheets.keySet()){n++;book.append("<sheet name=\"").append(esc(name)).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");}
            entry(z,"xl/workbook.xml",book.append("</sheets></workbook>").toString().getBytes(StandardCharsets.UTF_8));rels.append("<Relationship Id=\"rId").append(n+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");entry(z,"xl/_rels/workbook.xml.rels",rels.append("</Relationships>").toString().getBytes(StandardCharsets.UTF_8));

            entry(z,"xl/styles.xml","<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.0000\"/></numFmts><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Microsoft YaHei\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF173B4D\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>".getBytes(StandardCharsets.UTF_8));
            int sheetIndex=0;for(List<Object[]> rows:sheets.values()){sheetIndex++;int columns=rows.get(0).length;
            StringBuilder b=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\""+columns+"\" width=\"22\" customWidth=\"1\"/></cols><sheetData>");
            for(int i=0;i<rows.size();i++){b.append("<row r=\"").append(i+1).append(i==0?"\" ht=\"48\" customHeight=\"1\">":"\">");Object[] row=rows.get(i);for(int j=0;j<row.length;j++){Object o=row[j];String address=col(j)+(i+1);
                if(o instanceof Number)b.append("<c r=\"").append(address).append("\" s=\"").append(o instanceof Integer||o instanceof Long?0:2).append("\"><v>").append(o).append("</v></c>");
                else b.append("<c r=\"").append(address).append("\" s=\"").append(i==0?1:0).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(cellText(o))).append("</t></is></c>");}
                b.append("</row>");}
            b.append("</sheetData><autoFilter ref=\"A1:"+col(columns-1)+"").append(rows.size()).append("\"/></worksheet>");entry(z,"xl/worksheets/sheet"+sheetIndex+".xml",b.toString().getBytes(StandardCharsets.UTF_8));}
        }return bytes.toByteArray();
    }
    static byte[] png(BufferedImage im) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();}
    static BufferedImage overlay(Photo p,List<Engine.Result> results) throws Exception {
        BufferedImage out=new BufferedImage(p.width,p.height,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();g.drawImage(p.image(),0,0,null);
        for(var r:results)if(r.photo==p){for(int i=r.abnormalMask.nextSetBit(0);i>=0;i=r.abnormalMask.nextSetBit(i+1)){int x=i%p.width,y=i/p.width,c=out.getRGB(x,y);int rr=(c>>16)&255,gg=(c>>8)&255,bb=c&255;out.setRGB(x,y,(255<<16)|((int)(gg*.6)<<8)|(int)(bb*.6));}}
        g.setStroke(new BasicStroke(Math.max(2,p.width/600f)));g.setFont(new Font(Font.DIALOG,Font.BOLD,Math.max(14,p.width/85)));
        for(Region r:p.regions){g.setColor(r.role==Role.TARGET?new Color(70,230,240):r.role==Role.REFERENCE?new Color(80,240,140):Color.ORANGE);java.awt.geom.Path2D path=new java.awt.geom.Path2D.Double();path.moveTo(r.points.get(0).x,r.points.get(0).y);for(int i=1;i<r.points.size();i++)path.lineTo(r.points.get(i).x,r.points.get(i).y);path.closePath();g.draw(path);g.drawString(r.name,(float)r.points.get(0).x,(float)Math.max(20,r.points.get(0).y-8));}
        g.dispose();return out;
    }
    static BufferedImage roiPreview(Photo p) throws Exception {
        BufferedImage out=new BufferedImage(p.width,p.height,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();
        try{
            g.drawImage(p.image(),0,0,null);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(Math.max(2,p.width/600f)));g.setFont(new Font(Font.DIALOG,Font.BOLD,Math.max(14,p.width/85)));
            for(Region r:p.regions){Color color=r.role==Role.TARGET?new Color(0,215,230):r.role==Role.REFERENCE?new Color(50,220,120):new Color(255,174,40);g.setColor(color);
                java.awt.geom.Path2D path=new java.awt.geom.Path2D.Double();path.moveTo(r.points.get(0).x,r.points.get(0).y);for(int i=1;i<r.points.size();i++)path.lineTo(r.points.get(i).x,r.points.get(i).y);path.closePath();g.draw(path);
            }
            FontMetrics fm=g.getFontMetrics();int padding=Math.max(3,p.width/500),height=fm.getHeight()+padding*2;
            for(Region r:p.regions){Color color=r.role==Role.TARGET?new Color(0,215,230):r.role==Role.REFERENCE?new Color(50,220,120):new Color(255,174,40);
                int width=Math.min(p.width,fm.stringWidth(r.name)+padding*2),x=Math.max(0,Math.min(p.width-width,(int)Math.round(r.points.get(0).x))),y=(int)Math.round(r.points.get(0).y)-height-padding;
                if(y<0)y=(int)Math.round(r.points.get(0).y)+padding;y=Math.max(0,Math.min(p.height-height,y));
                g.setColor(new Color(10,35,44,210));g.fillRoundRect(x,y,width,height,padding*2,padding*2);g.setColor(color);g.drawString(r.name,x+padding,y+padding+fm.getAscent());
            }
        }finally{g.dispose();}return out;
    }
    static String exportImageName(Photo photo) {
        // Normal imports already store a single Windows filename. Keep Unicode and case.
        // Legacy/project metadata must never introduce ZIP paths or reserved device names.
        String name=photo.name==null?"":photo.name.replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]","_").replaceAll("[. ]+$","");
        if(name.isBlank())name="image-"+photo.id+".png";
        if(name.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"))name="_"+name;
        return name;
    }
    static String exportNameKey(String name) {return java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    static byte[] exportImage(Photo photo,BufferedImage image,String name) throws IOException {
        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String format=switch(extension){case "jpg","jpeg"->"jpeg";case "tif","tiff"->"tiff";case "png"->"png";default->null;};
        if(format==null){
            try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(photo.bytes))){
                var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("Cannot determine image export format: "+name);
                var reader=readers.next();try{format=reader.getFormatName();}finally{reader.dispose();}
            }
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if(format.equalsIgnoreCase("jpeg")||format.equalsIgnoreCase("jpg")){
            BufferedImage rgb=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=rgb.createGraphics();try{g.setColor(Color.WHITE);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(image,0,0,null);}finally{g.dispose();}
            var writers=ImageIO.getImageWritersByFormatName("jpeg");if(!writers.hasNext())throw new IOException("JPEG export is unavailable.");
            var writer=writers.next();
            try(var out=ImageIO.createImageOutputStream(bytes)){
                writer.setOutput(out);var param=writer.getDefaultWriteParam();param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);param.setCompressionQuality(1f);
                writer.write(null,new javax.imageio.IIOImage(rgb,null,null),param);
            }finally{writer.dispose();}
        }else if(!ImageIO.write(image,format,bytes))throw new IOException("Image export format is unavailable: "+name);
        return bytes.toByteArray();
    }
    static void export(Project p,List<Engine.Result> results,Path target) throws Exception {
        if(results.isEmpty())throw new IOException("No analysis results to export.");
        Path file=target.toAbsolutePath(),tmp=Files.createTempFile(file.getParent(),"peri-export-",".tmp");
        try{
            try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(tmp),StandardCharsets.UTF_8)){
                List<Object[]> table=rows(p,results);entry(z,"results.xlsx",xlsx(table));entry(z,"results.csv",csv(table));entry(z,"analysis-manifest.xml",metadata(p));
                Map<String,Photo> photos=new LinkedHashMap<>();for(var r:results)photos.putIfAbsent(r.photo.id,r.photo);
                Map<String,Integer> nameCounts=new HashMap<>();for(Photo ph:photos.values())nameCounts.merge(exportNameKey(exportImageName(ph)),1,Integer::sum);
                for(Photo ph:photos.values()){
                    String name=exportImageName(ph),roiFolder="imagej-rois/"+ph.id+"/";
                    String overlayFolder="overlays/"+(nameCounts.get(exportNameKey(name))>1?ph.id+"/":"");
                    entry(z,overlayFolder+name,exportImage(ph,overlay(ph,results),name));
                    entry(z,roiFolder+name,exportImage(ph,roiPreview(ph),name));
                    int j=1;for(Region reg:ph.regions)entry(z,roiFolder+j+++"_"+reg.role+".roi",ij.io.RoiEncoder.saveAsByteArray(reg.nativeRoi()));
                }
                entry(z,"README.txt",("ColorMetric Studio "+VERSION+"\nColor values are image-derived relative signals, not melanin/hemoglobin concentrations.\nArea = affected pixels / valid ROI pixels * 100; not mm². Defaults are exploratory and require validation.\nRed overlay = thresholded pixels.\nImage previews retain the source filename, including Chinese characters, case and the original JPG/PNG/TIFF extension; encoded formats match those extensions.\noverlays/<source-filename> shows analyzed segmentation. Duplicate or case-colliding filenames use overlays/<image-id>/<source-filename> so every image is retained.\nimagej-rois/<image-id>/<source-filename> shows original colors with all ROI boundaries and names only, without segmentation tint. Adjacent .roi files retain editable ImageJ geometry. One preview is exported for each image represented in results.\nJPG previews are re-encoded at maximum quality; PNG/TIFF previews are lossless. Original project image bytes and all measurements remain unchanged. Unsafe legacy/project filenames are made safe for ZIP extraction.\nReference type distinguishes PURE_WHITE (sRGB #FFFFFF) from SKIN_ROI. Do not mix reference methods across visits.\nresults.xlsx retains numeric precision. CSV uses UTF-8 BOM. analysis-manifest.xml records original checksums, all ROIs, parameters and audit history.\nLab conversion assumes sRGB/D65 and is not physical color calibration.\n").getBytes(StandardCharsets.UTF_8));
            }
            try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
}
