package peri;

import static peri.Model.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class App extends JFrame {
    static final Color NAVY=new Color(23,59,77),TEAL=new Color(0,128,134),BG=new Color(242,246,248),MUTED=new Color(89,111,121);
    Project project=new Project();Path projectPath;boolean dirty=false,busy=false,syncing=false;
    // Per-user navigation survives application and project restarts.
    final DirectoryHistory directoryHistory=DirectoryHistory.user();
    File lastImportDirectory,lastOutputDirectory;
    ArrayList<Engine.Result> results=new ArrayList<>();
    DefaultListModel<Photo> photoModel=new DefaultListModel<>();JList<Photo> photos=new JList<>(photoModel);
    DefaultListModel<Region> roiModel=new DefaultListModel<>();JList<Region> rois=new JList<>(roiModel);
    JTextField subject=new JTextField("Anonymous subject"),visit=new JTextField("Baseline"),roiName=new JTextField("Medial canthus"),note=new JTextField();
    JComboBox<Mode> mode=new JComboBox<>(Mode.values());JComboBox<Role> role=new JComboBox<>(Role.values());
    JComboBox<Metric> metric=new JComboBox<>(Metric.values());
    JTabbedPane projectTabs=new JTabbedPane();ArrayList<Session> sessions=new ArrayList<>();int activeSession=-1;boolean switchingTabs=false;
    static final class Session {
        Project project;Path path;boolean dirty;ArrayList<Engine.Result> results=new ArrayList<>();Photo photo,source;Region region;
        ArrayDeque<RoiUndo> edits=new ArrayDeque<>();
        ArrayList<Point2D.Double> pending=new ArrayList<>();double zoom=1,ox,oy;int tool;
        String subject,visit,name,note,status;Mode mode;Role role;Metric metric;Object threshold;boolean showMask=true,allowScale;JLabel tabLabel;
        Session(Project p,Path path){project=p;this.path=path;}
    }
    record RoiUndo(Photo photo,Region region,ArrayList<Point2D.Double> before,ArrayList<Point2D.Double> after,boolean whole) {}
    static ArrayList<Point2D.Double> pointsCopy(List<Point2D.Double> points){ArrayList<Point2D.Double> copy=new ArrayList<>();for(Point2D.Double p:points)copy.add(new Point2D.Double(p.x,p.y));return copy;}
    static boolean pointsEqual(List<Point2D.Double> a,List<Point2D.Double> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i).distance(b.get(i))>1e-9)return false;return true;}
    void recordEdit(Photo photo,Region region,List<Point2D.Double> before,boolean whole){Session s=owner(photo);if(s==null||pointsEqual(before,region.points))return;s.edits.addLast(new RoiUndo(photo,region,pointsCopy(before),pointsCopy(region.points),whole));while(s.edits.size()>100)s.edits.removeFirst();}
    void clearEdits(Photo photo,Region region){Session s=owner(photo);if(s!=null)s.edits.removeIf(m->m.photo()==photo&&(region==null||m.region()==region));}
    void undoRoiEdit(){if(busy||canvas.dragRegion!=null)return;Session s=sessions.get(activeSession);if(s.edits.isEmpty()){setStatus("No ROI movement or vertex edit to undo in this project.");return;}RoiUndo move=s.edits.removeLast();if(!project.photos.contains(move.photo())||!move.photo().regions.contains(move.region())||!pointsEqual(move.region().points,move.after())){setStatus("This edit cannot be undone because its ROI has changed or was removed.");return;}
        move.region().points=pointsCopy(move.before());move.photo().confirmed=false;photos.setSelectedValue(move.photo(),true);rois.setSelectedValue(move.region(),true);String action=move.whole()?"ROI movement undone":"ROI vertex edit undone";changed(action+" "+move.region().id);setStatus(action+": "+move.region().name+". Ctrl+Z undoes earlier ROI edits.");
    }
    JSpinner threshold=new JSpinner(new SpinnerNumberModel(5.0,0.001,200.0,0.5));
    JCheckBox whiteReference=new JCheckBox("Use pure white as reference");
    JLabel referenceHint=new JLabel("<html><small>Draw one Reference skin ROI per image.<br>Its name defaults to reference.</small></html>");
    JCheckBox locked=new JCheckBox("Lock parameters for all modes"),showMask=new JCheckBox("Show segmentation",true),allowScale=new JCheckBox("Allow uniform scaling");
    JLabel status=new JLabel("Import images, then draw reference skin and measurement ROIs."),info=new JLabel("No image loaded"),zoomInfo=new JLabel("100%"),paramHint=new JLabel();
    JComboBox<Photo> source=new JComboBox<>();Canvas canvas=new Canvas();
    DefaultTableModel tableModel=new DefaultTableModel(new String[]{"Visit","Mode","Measurement ROI","Relative color intensity","Affected area (%)","Valid pixels","Affected pixels","Quality notes"},0){public boolean isCellEditable(int r,int c){return false;}};
    JTable table=new JTable(tableModel);ArrayList<JButton> actionButtons=new ArrayList<>();
    SwingWorker<ArrayList<Engine.Result>,String> worker;

    App(){
        super("ColorMetric Studio · "+VERSION);setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);setMinimumSize(new Dimension(940,620));Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();setSize(Math.min(1500,screen.width-30),Math.min(980,screen.height-30));setLocationRelativeTo(null);
        lastImportDirectory=directoryHistory.recalled(DirectoryHistory.IMPORT);lastOutputDirectory=directoryHistory.recalled(DirectoryHistory.OUTPUT);
        JPanel root=new JPanel(new BorderLayout(0,0));root.setBackground(BG);setContentPane(root);
        JPanel top=new JPanel(new BorderLayout());top.setBackground(NAVY);top.setBorder(new EmptyBorder(16,22,16,22));
        JLabel title=new JLabel("ColorMetric Studio");title.setForeground(Color.WHITE);title.setFont(title.getFont().deriveFont(Font.BOLD,23f));top.add(title,BorderLayout.WEST);
        JLabel version=new JLabel("Offline  ·  ImageJ "+ij.IJ.getVersion()+"  ·  v"+VERSION);version.setForeground(new Color(178,220,222));top.add(version,BorderLayout.EAST);
        JPanel north=new JPanel(new BorderLayout());north.add(top,BorderLayout.NORTH);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT,8,8));tools.setBackground(Color.WHITE);
        tools.add(button("New project",()->addSession(new Project(),null)));tools.add(button("Import images",()->importPhotos()));tools.add(button("Open project",()->open()));tools.add(button("Save project",()->save(false)));
        tools.add(button("Analyze current",()->analyze(false)));tools.add(button("Analyze all",()->analyze(true)));tools.add(primary("Export results",()->export()));tools.add(button("User guide",()->help()));
        JPanel navigation=new JPanel(new BorderLayout());navigation.add(tools,BorderLayout.NORTH);projectTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);projectTabs.setPreferredSize(new Dimension(900,36));navigation.add(projectTabs,BorderLayout.SOUTH);north.add(navigation,BorderLayout.SOUTH);root.add(north,BorderLayout.NORTH);
        JPanel left=leftPanel(),right=rightPanel();JPanel center=new JPanel(new BorderLayout());center.setBackground(BG);
        JPanel ctools=new JPanel(new FlowLayout(FlowLayout.LEFT,7,7));ctools.setBackground(BG);
        ctools.add(button("Draw polygon",()->{canvas.tool=0;canvas.pending.clear();canvas.repaint();setStatus("Click vertices; double-click or Enter to close. Ctrl+Z or Backspace undoes a point; Esc cancels.");}));
        ctools.add(button("Edit / move",()->{canvas.tool=1;canvas.pending.clear();setStatus("Drag near a boundary point to edit that point; drag in the interior to move the whole ROI. Crosshair = point edit; move cursor = whole ROI. Ctrl+Z undoes either edit.");}));
        ctools.add(button("Close polygon",()->canvas.finish()));ctools.add(button("Fit image",()->canvas.fit()));ctools.add(zoomInfo);ctools.add(showMask);
        center.add(ctools,BorderLayout.NORTH);center.add(canvas,BorderLayout.CENTER);
        JPanel imageRow=new JPanel(new BorderLayout(10,0));imageRow.setBorder(new EmptyBorder(10,12,10,12));imageRow.setBackground(BG);left.setPreferredSize(new Dimension(265,600));right.setPreferredSize(new Dimension(310,right.getPreferredSize().height));
        JScrollPane rightScroll=new JScrollPane(right);rightScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);rightScroll.getVerticalScrollBar().setUnitIncrement(24);rightScroll.setPreferredSize(new Dimension(332,600));
        imageRow.add(left,BorderLayout.WEST);imageRow.add(center,BorderLayout.CENTER);imageRow.add(rightScroll,BorderLayout.EAST);
        table.setRowHeight(29);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={100,85,150,140,125,115,115,370};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JScrollPane resultPane=new JScrollPane(table);resultPane.setColumnHeaderView(table.getTableHeader());resultPane.setBorder(BorderFactory.createTitledBorder("Results · Affected area is a percentage, not mm²"));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,imageRow,resultPane);split.setResizeWeight(.78);split.setDividerLocation(Math.max(320,getHeight()-310));split.setBorder(null);root.add(split,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout());bottom.setBackground(Color.WHITE);bottom.setBorder(new EmptyBorder(8,16,8,16));bottom.add(status,BorderLayout.CENTER);JLabel unit=new JLabel("Image-derived signals · Research prototype");unit.setForeground(MUTED);bottom.add(unit,BorderLayout.EAST);root.add(bottom,BorderLayout.SOUTH);
        setJMenuBar(menu());
        photos.addListSelectionListener(e->{if(!syncing&&!e.getValueIsAdjusting())syncPhoto();});rois.addListSelectionListener(e->{if(!syncing&&!e.getValueIsAdjusting())syncRegion();});
        showMask.addActionListener(e->canvas.repaint());
        whiteReference.addActionListener(e->{if(!syncing)guard(()->setWhiteReference(whiteReference.isSelected()));});
        role.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean selected,boolean focus){Component c=super.getListCellRendererComponent(l,v,i,selected,focus);if(v==Role.REFERENCE&&current()!=null&&current().pureWhiteReference)c.setEnabled(false);return c;}});
        role.addActionListener(e->{if(syncing)return;if(current()!=null&&current().pureWhiteReference&&role.getSelectedItem()==Role.REFERENCE){role.setSelectedItem(Role.TARGET);setStatus("Reference skin is disabled while pure-white reference is selected.");}updateReferenceControls();});
        locked.addActionListener(e->{if(!syncing){project.locked=locked.isSelected();changed("Parameter lock="+project.locked);updateSettingsControls();}});
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&table.getSelectedRow()>=0&&table.getSelectedRow()<results.size()){Engine.Result r=results.get(table.getSelectedRow());photos.setSelectedValue(r.photo,true);rois.setSelectedValue(r.region,true);canvas.repaint();}});
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){guard(()->{if(discardAll())dispose();});}});
        projectTabs.addChangeListener(e->{if(!switchingTabs&&projectTabs.getSelectedIndex()>=0){if(busy){switchingTabs=true;projectTabs.setSelectedIndex(activeSession);switchingTabs=false;}else activateSession(projectTabs.getSelectedIndex());}});
        addSession(project,null);
    }
    static void style(){
        try{UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());}catch(Exception ignored){}
        // A logical font uses installed fallback fonts for user filenames and ROI names.
        // Segoe UI alone cannot display Chinese characters on Windows.
        Font f=new Font(Font.DIALOG,Font.PLAIN,13);for(Object key:Collections.list(UIManager.getDefaults().keys()))if(UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)UIManager.put(key,new javax.swing.plaf.FontUIResource(f));
        UIManager.put("Panel.background",BG);UIManager.put("List.selectionBackground",new Color(209,235,236));UIManager.put("List.selectionForeground",NAVY);UIManager.put("Table.selectionBackground",new Color(209,235,236));
    }
    interface Action {void run() throws Exception;}
    JButton button(String text,Action a){JButton b=new JButton(text);b.setFocusPainted(false);b.setMargin(new Insets(7,11,7,11));b.addActionListener(e->guard(a));actionButtons.add(b);return b;}
    JButton primary(String t,Action a){JButton b=button(t,a);b.setBackground(TEAL);b.setForeground(Color.WHITE);b.setOpaque(true);return b;}
    void guard(Action a){if(busy){setStatus("Processing. Please wait.");return;}try{a.run();}catch(Exception e){error(e);}}
    void error(Exception e){setStatus("Not completed: "+e.getMessage());JOptionPane.showMessageDialog(this,e.getMessage(),"Please check",JOptionPane.WARNING_MESSAGE);}
    JMenuBar menu(){JMenuBar mb=new JMenuBar();JMenu f=new JMenu("File");item(f,"New project",()->addSession(new Project(),null));item(f,"Close current project",()->closeSession(activeSession));item(f,"Save project as",()->save(true));item(f,"Export current ROIs (ImageJ ZIP)",()->{Photo p=requirePhoto();Path path=chooseSave("ImageJ-ROIs", "zip");if(path!=null){ProjectIO.exportRois(p,path);setStatus("ImageJ-compatible ROIs exported: "+path);}});item(f,"Open synthetic demo",()->addSession(Tests.demo(),null));item(f,"Exit",()->{if(discardAll())dispose();});mb.add(f);JMenu h=new JMenu("Help");item(h,"Measurement method and user guide",()->help());item(h,"Version and components",()->JOptionPane.showMessageDialog(this,"ColorMetric Studio "+VERSION+"\nEmbedded ImageJ "+ij.IJ.getVersion()+"\nAlgorithm: "+ALGORITHM+"\nLab conversion assumes sRGB / D65; no physical color calibration.\nComponent notices: installation folder / app / THIRD-PARTY.txt.","About",JOptionPane.INFORMATION_MESSAGE));mb.add(h);return mb;}
    void item(JMenu m,String name,Action a){JMenuItem i=new JMenuItem(name);i.addActionListener(e->guard(a));m.add(i);}
    JPanel box(String title){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBackground(Color.WHITE);p.setBorder(new CompoundBorder(new TitledBorder(title),new EmptyBorder(6,8,8,8)));return p;}
    void field(JPanel p,String label,JComponent c){JLabel l=new JLabel(label);l.setAlignmentX(Component.LEFT_ALIGNMENT);l.setMaximumSize(new Dimension(400,24));p.add(l);p.add(Box.createVerticalStrut(4));c.setAlignmentX(Component.LEFT_ALIGNMENT);c.setMaximumSize(new Dimension(400,32));p.add(c);p.add(Box.createVerticalStrut(9));}
    JPanel leftPanel(){
        JPanel p=new JPanel(new BorderLayout(0,8));p.setBackground(BG);JPanel meta=box("Project");field(meta,"Subject ID (use an anonymous ID)",subject);meta.add(button("Apply subject ID",()->{if(subject.getText().isBlank())throw new IllegalArgumentException("Enter a subject ID.");project.subject=subject.getText().trim();changed("Subject ID updated");}));p.add(meta,BorderLayout.NORTH);
        JPanel lists=new JPanel(new GridLayout(2,1,0,8));photos.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int index,boolean selected,boolean focus){JLabel label=(JLabel)super.getListCellRendererComponent(l,v,index,selected,focus);if(v instanceof Photo ph){label.setText("<html><b>"+ProjectIO.esc(ph.visit)+" · "+ph.mode+"</b> "+"<br><small>"+ProjectIO.esc(ph.name)+"</small></html>");label.setBorder(new EmptyBorder(8,7,8,7));}return label;}});
        photos.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);photos.setDragEnabled(true);photos.setDropMode(DropMode.INSERT);photos.setTransferHandler(new ImageOrderTransferHandler());photos.setToolTipText("Drag an image to reorder it within this project.");
        JScrollPane ps=new JScrollPane(photos);ps.setBorder(new TitledBorder("Images"));lists.add(ps);JScrollPane rs=new JScrollPane(rois);rs.setBorder(new TitledBorder("ROIs in current image"));lists.add(rs);p.add(lists,BorderLayout.CENTER);
        JPanel lower=new JPanel(new GridLayout(2,1,0,5));lower.add(button("Delete selected ROI",()->deleteRegion()));lower.add(button("Remove current image",()->{Photo ph=requirePhoto();if(yes("Remove this image from the project? The source file is preserved.")){clearEdits(ph,null);project.photos.remove(ph);changed("Image removed "+ph.id);refreshPhotos();}}));p.add(lower,BorderLayout.SOUTH);return p;
    }
    JPanel rightPanel(){
        JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBackground(BG);
        JPanel ph=box("Image details");field(ph,"Visit",visit);field(ph,"Image mode",mode);field(ph,"Notes (e.g. treatment side, pose)",note);
        ph.add(button("Apply image details",()->{Photo x=requirePhoto();if(visit.getText().isBlank())throw new IllegalArgumentException("Visit cannot be empty.");x.visit=visit.getText().trim();x.mode=(Mode)mode.getSelectedItem();x.note=note.getText();x.confirmed=false;changed("Image details updated "+x.id);syncPhoto();photos.repaint();}));ph.add(Box.createVerticalStrut(7));ph.add(info);p.add(ph);p.add(Box.createVerticalStrut(8));
        JPanel r=box("ROI definition");field(r,"New / selected ROI name",roiName);field(r,"ROI role",role);r.add(whiteReference);r.add(Box.createVerticalStrut(5));r.add(referenceHint);r.add(button("Apply ROI details",()->applyRegionDetails()));p.add(r);p.add(Box.createVerticalStrut(8));
        JPanel s=box("Parameters for current mode");field(s,"Color metric",metric);field(s,"Threshold (relative Lab units)",threshold);s.add(paramHint);s.add(Box.createVerticalStrut(6));s.add(button("Apply parameters",()->{Photo x=requirePhoto();if(project.locked)throw new IllegalArgumentException("Parameters are locked. Unlock them before editing.");threshold.commitEdit();Settings st=project.settings.get(x.mode);st.metric=(Metric)metric.getSelectedItem();st.threshold=((Number)threshold.getValue()).doubleValue();changed("Parameters "+x.mode+" = "+st.metric+", threshold="+st.threshold);syncSettings();}));s.add(Box.createVerticalStrut(6));s.add(locked);JLabel hint=new JLabel("<html><small>Defaults are exploratory. Validate, then lock.<br>Use one rule per mode across all visits.</small></html>");hint.setForeground(MUTED);s.add(hint);p.add(s);p.add(Box.createVerticalStrut(8));
        JPanel a=box("ROI transfer / alignment");field(a,"Source image with ROIs",source);
        a.add(button("Copy exact coordinates to current",()->copyToCurrent(false)));a.add(Box.createVerticalStrut(5));a.add(button("Copy source ROIs to other images...",()->copyMany()));a.add(Box.createVerticalStrut(5));
        a.add(button("Mark two landmarks on current image",()->{requirePhoto().landmarks.clear();canvas.pending.clear();canvas.tool=2;changed("Landmarks reset "+current().id);canvas.repaint();setStatus("Click two anatomical landmarks. Use the same locations and order in both images.");}));a.add(Box.createVerticalStrut(5));a.add(allowScale);a.add(button("Transfer using two landmarks",()->copyToCurrent(true)));
        a.add(new JLabel("<html><small>Without scaling: translation and rotation only.<br>Verify anatomical correspondence after transfer.</small></html>"));p.add(a);p.add(Box.createVerticalGlue());return p;
    }
    Photo current(){return photos.getSelectedValue();}Photo requirePhoto(){Photo p=current();if(p==null)throw new IllegalArgumentException("Import and select an image first.");return p;}Region selectedRegion(){return rois.getSelectedValue();}
    void captureSession(){
        if(activeSession<0)return;Session s=sessions.get(activeSession);s.project=project;s.path=projectPath;s.dirty=dirty;s.results=results;s.photo=current();s.region=selectedRegion();s.source=(Photo)source.getSelectedItem();
        s.pending=new ArrayList<>(canvas.pending);s.zoom=canvas.zoom;s.ox=canvas.ox;s.oy=canvas.oy;s.tool=canvas.tool;
        s.subject=subject.getText();s.visit=visit.getText();s.name=roiName.getText();s.note=note.getText();s.mode=(Mode)mode.getSelectedItem();s.role=(Role)role.getSelectedItem();s.metric=(Metric)metric.getSelectedItem();s.threshold=threshold.getValue();s.status=status.getText();s.showMask=showMask.isSelected();s.allowScale=allowScale.isSelected();
    }
    void updateTabTitles(){
        captureSession();for(int i=0;i<sessions.size();i++){Session s=sessions.get(i);String label=(s.dirty?"* ":"")+(s.path==null?s.project.subject:s.path.getFileName().toString());projectTabs.setTitleAt(i,label);s.tabLabel.setText(label);projectTabs.setToolTipTextAt(i,s.path==null?"Unsaved project":s.path.toString());}
        setTitle((dirty?"* ":"")+"ColorMetric Studio · "+project.subject+" · "+VERSION);
    }
    void addSession(Project p,Path path){
        if(busy)throw new IllegalStateException("Wait for analysis before opening another project.");captureSession();sessions.add(new Session(p,path));switchingTabs=true;projectTabs.addTab(p.subject,new JPanel());int index=sessions.size()-1;
        JButton close=new JButton("×");close.setToolTipText("Close project");close.setMargin(new Insets(0,4,0,4));close.addActionListener(e->guard(()->{int n=projectTabs.indexOfComponent(projectTabsComponent(close));if(n>=0)closeSession(n);}));
        JPanel header=new JPanel(new BorderLayout(5,0));header.setOpaque(false);JLabel label=new JLabel(p.subject);sessions.get(index).tabLabel=label;header.add(label,BorderLayout.CENTER);header.add(close,BorderLayout.EAST);projectTabs.setTabComponentAt(index,header);
        projectTabs.setSelectedIndex(index);switchingTabs=false;activateSession(index);
    }
    Component projectTabsComponent(JButton close){int i=projectTabs.indexOfTabComponent(close.getParent());return i<0?null:projectTabs.getComponentAt(i);}
    void activateSession(int index){
        if(index==activeSession)return;if(busy)throw new IllegalStateException("Wait for analysis before switching projects.");captureSession();Session s=sessions.get(index);activeSession=index;
        project=s.project;projectPath=s.path;dirty=s.dirty;results=s.results;tableModel.setRowCount(0);syncing=true;photoModel.clear();roiModel.clear();canvas.image=null;syncing=false;refreshPhotos();
        if(s.photo!=null&&project.photos.contains(s.photo))photos.setSelectedValue(s.photo,true);if(s.region!=null&&current()!=null&&current().regions.contains(s.region))rois.setSelectedValue(s.region,true);
        canvas.pending=new ArrayList<>(s.pending);canvas.tool=s.tool;canvas.dragVertex=-1;canvas.moveRegion=false;canvas.dragRegion=null;canvas.pan=false;
        if(s.subject!=null){syncing=true;subject.setText(s.subject);visit.setText(s.visit);roiName.setText(s.name);note.setText(s.note);mode.setSelectedItem(s.mode);role.setSelectedItem(s.role);metric.setSelectedItem(s.metric);threshold.setValue(s.threshold);showMask.setSelected(s.showMask);allowScale.setSelected(s.allowScale);source.setSelectedItem(s.source);syncing=false;canvas.zoom=s.zoom;canvas.ox=s.ox;canvas.oy=s.oy;zoomInfo.setText(String.format(Locale.ROOT,"%.0f%%",s.zoom*100));setStatus(s.status);}
        else {roiName.setText("Measurement ROI");role.setSelectedItem(Role.TARGET);if(current()==null){visit.setText("Baseline");mode.setSelectedItem(Mode.PL);Settings defaults=project.settings.get(Mode.PL);metric.setSelectedItem(defaults.metric);threshold.setValue(defaults.threshold);paramHint.setText("Applied threshold: "+defaults.threshold);}note.setText(current()==null?"":current().note);showMask.setSelected(true);allowScale.setSelected(false);setStatus("Draw one reference skin ROI per image; all measurement ROIs share it automatically.");}
        switchingTabs=true;projectTabs.setSelectedIndex(index);switchingTabs=false;updateReferenceControls();refreshResults();canvas.maskImage=null;canvas.repaint();updateTabTitles();
    }
    void removeSession(int index){
        // The caller has already resolved unsaved changes for this tab.
        if(index!=activeSession)throw new IllegalArgumentException("Select the project before closing it.");switchingTabs=true;sessions.remove(index);projectTabs.removeTabAt(index);activeSession=-1;switchingTabs=false;
        if(sessions.isEmpty())addSession(new Project(),null);else activateSession(Math.min(index,sessions.size()-1));
    }
    void closeSession(int index) throws Exception {activateSession(index);if(discard())removeSession(index);}
    boolean discardAll() throws Exception {int original=activeSession;for(int i=0;i<sessions.size();i++){activateSession(i);if(!discard()){activateSession(original);return false;}captureSession();}return true;}
    void ensureSingleReference(Photo p,Region selected,Role nextRole){if(nextRole==Role.REFERENCE&&p.pureWhiteReference)throw new IllegalArgumentException("Reference skin ROIs are disabled for this image while pure-white reference is selected.");if(nextRole==Role.REFERENCE&&p.regions.stream().anyMatch(r->r!=selected&&r.role==Role.REFERENCE))throw new IllegalArgumentException("This image already has a Reference skin ROI. Edit the existing reference or delete it before creating another.");}
    void applyRegionDetails(){Region z=selectedRegion();if(z==null)throw new IllegalArgumentException("Select an ROI first.");if(roiName.getText().isBlank())throw new IllegalArgumentException("ROI name cannot be empty.");Photo p=requirePhoto();if(p.pureWhiteReference&&z.role==Role.REFERENCE)throw new IllegalArgumentException("This Reference ROI is inactive. Disable pure-white reference before editing it.");Role next=(Role)role.getSelectedItem();ensureSingleReference(p,z,next);z.name=next==Role.REFERENCE?"reference":roiName.getText().trim();z.role=next;z.referenceId="";p.confirmed=false;changed("ROI updated "+z.id);syncPhoto();rois.setSelectedValue(z,true);}
    void changed(String event){dirty=true;project.log(event);results.clear();refreshResults();canvas.maskImage=null;canvas.repaint();updateTabTitles();}
    void setStatus(String text){status.setText(text);}
    boolean yes(String text){return JOptionPane.showConfirmDialog(this,text,"Confirm",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
    boolean discard() throws Exception {if(!dirty)return true;int q=JOptionPane.showConfirmDialog(this,"This project has unsaved changes. Save before continuing?","Project",JOptionPane.YES_NO_CANCEL_OPTION);if(q==JOptionPane.CANCEL_OPTION||q==JOptionPane.CLOSED_OPTION)return false;if(q==JOptionPane.YES_OPTION){save(false);return !dirty;}return true;}
    void refreshSourceChoices(Photo preferred){source.removeAllItems();for(Photo p:project.photos)source.addItem(p);if(preferred!=null&&project.photos.contains(preferred))source.setSelectedItem(preferred);else {Photo fallback=current();if(fallback==null||fallback.regions.isEmpty())fallback=project.photos.stream().filter(p->!p.regions.isEmpty()).findFirst().orElse(fallback);source.setSelectedItem(fallback);}}
    void refreshPhotos(){syncing=true;Photo selected=current(),oldSource=(Photo)source.getSelectedItem();photoModel.clear();for(Photo p:project.photos)photoModel.addElement(p);if(selected!=null&&project.photos.contains(selected))photos.setSelectedValue(selected,true);else if(!photoModel.isEmpty())photos.setSelectedIndex(0);refreshSourceChoices(oldSource);subject.setText(project.subject);locked.setSelected(project.locked);syncing=false;syncPhoto();}
    record ImageDrag(App app,Session session,Photo photo) {}
    static final DataFlavor IMAGE_ORDER_FLAVOR=new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType+";class="+ImageDrag.class.getName(),"ColorMetric image order");
    final class ImageOrderTransferHandler extends TransferHandler {
        protected Transferable createTransferable(JComponent c){if(busy||c!=photos||current()==null)return null;ImageDrag payload=new ImageDrag(App.this,sessions.get(activeSession),current());return new Transferable(){public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{IMAGE_ORDER_FLAVOR};}public boolean isDataFlavorSupported(DataFlavor f){return IMAGE_ORDER_FLAVOR.equals(f);}public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {if(!isDataFlavorSupported(f))throw new UnsupportedFlavorException(f);return payload;}};}
        public int getSourceActions(JComponent c){return busy?NONE:MOVE;}
        public boolean canImport(TransferSupport support){if(busy||support.getComponent()!=photos||!support.isDrop()||!support.isDataFlavorSupported(IMAGE_ORDER_FLAVOR))return false;try{ImageDrag d=(ImageDrag)support.getTransferable().getTransferData(IMAGE_ORDER_FLAVOR);return acceptsImageDrag(d)&&((JList.DropLocation)support.getDropLocation()).getIndex()>=0&&((JList.DropLocation)support.getDropLocation()).getIndex()<=project.photos.size();}catch(Exception ex){return false;}}
        public boolean importData(TransferSupport support){if(!canImport(support))return false;try{ImageDrag d=(ImageDrag)support.getTransferable().getTransferData(IMAGE_ORDER_FLAVOR);return moveImage(d,((JList.DropLocation)support.getDropLocation()).getIndex());}catch(Exception ex){error(ex);return false;}}
    }
    boolean acceptsImageDrag(ImageDrag d){return !busy&&d!=null&&d.app()==this&&activeSession>=0&&d.session()==sessions.get(activeSession)&&project.photos.contains(d.photo());}
    boolean moveImage(ImageDrag drag,int insertion){if(!acceptsImageDrag(drag)||insertion<0||insertion>project.photos.size())return false;int from=project.photos.indexOf(drag.photo()),to=insertion>from?insertion-1:insertion;if(from==to)return true;
        Photo selected=current(),oldSource=(Photo)source.getSelectedItem();project.photos.remove(from);project.photos.add(to,drag.photo());syncing=true;try{photoModel.clear();for(Photo p:project.photos)photoModel.addElement(p);photos.setSelectedValue(selected,true);refreshSourceChoices(oldSource);}finally{syncing=false;}
        dirty=true;project.log("Image order changed "+drag.photo().id+" from "+(from+1)+" to "+(to+1));updateTabTitles();photos.ensureIndexIsVisible(project.photos.indexOf(drag.photo()));setStatus("Image order updated. Save project to retain this order.");return true;
    }
    void syncPhoto(){
        syncing=true;Region old=selectedRegion();BufferedImage previous=canvas.image;roiModel.clear();Photo p=current();
        if(p!=null){visit.setText(p.visit);mode.setSelectedItem(p.mode);note.setText(p.note);info.setText(p.width+" × "+p.height+" pixels");
            for(Region r:p.regions)roiModel.addElement(r);try{canvas.image=p.image();}catch(Exception ex){canvas.image=null;error(ex);}
        }else {canvas.image=null;info.setText("No image loaded");}
        syncing=false;canvas.pending.clear();canvas.maskImage=null;canvas.dragRegion=null;canvas.dragVertex=-1;canvas.moveRegion=false;canvas.pan=false;if(previous!=canvas.image)canvas.fit();syncSettings();if(old!=null&&p!=null&&p.regions.contains(old))rois.setSelectedValue(old,true);else if(!roiModel.isEmpty())rois.setSelectedIndex(0);updateReferenceControls();canvas.repaint();
    }
    void syncRegion(){Region r=selectedRegion();boolean before=syncing;syncing=true;if(r!=null){roiName.setText(r.name);role.setSelectedItem(r.role);}syncing=before;updateReferenceControls();canvas.maskImage=null;canvas.repaint();}
    void updateReferenceControls(){
        Photo p=current();boolean white=p!=null&&p.pureWhiteReference;
        whiteReference.setSelected(white);whiteReference.setEnabled(p!=null&&!busy);
        boolean inactive=white&&selectedRegion()!=null&&selectedRegion().role==Role.REFERENCE;
        if(white&&role.getSelectedItem()==Role.REFERENCE){boolean before=syncing;syncing=true;role.setSelectedItem(Role.TARGET);roiName.setText("Measurement ROI");syncing=before;}
        role.setEnabled(!busy);
        boolean reference=role.getSelectedItem()==Role.REFERENCE;
        roiName.setEditable(!reference);
        if(reference&&!inactive)roiName.setText("reference");
        referenceHint.setText(white?"<html><small>White: sRGB #FFFFFF. No Reference ROI needed.<br>Existing Reference ROIs are inactive.</small></html>":"<html><small>Draw one Reference skin ROI per image.<br>Its name defaults to reference.</small></html>");
    }
    void setWhiteReference(boolean white){
        if(busy)throw new IllegalStateException("Wait for analysis before changing the reference.");
        Photo p=requirePhoto();if(p.pureWhiteReference==white){updateReferenceControls();return;}
        p.pureWhiteReference=white;p.confirmed=false;canvas.pending.clear();
        if(white&&role.getSelectedItem()==Role.REFERENCE){boolean before=syncing;syncing=true;rois.clearSelection();role.setSelectedItem(Role.TARGET);roiName.setText("Measurement ROI");syncing=before;}
        changed("Reference type for "+p.id+" = "+(white?"PURE_WHITE sRGB #FFFFFF":"SKIN_ROI"));updateReferenceControls();
        setStatus(white?"Pure-white reference selected for this image. Reference skin drawing is disabled; reanalyze results.":"Skin reference selected for this image. Draw one Reference skin ROI named reference; reanalyze results.");
    }
    void syncSettings(){syncing=true;Photo p=current();if(p!=null){Settings s=project.settings.get(p.mode);metric.setSelectedItem(s.metric);threshold.setValue(s.threshold);paramHint.setText("Applied threshold: "+s.threshold);}locked.setSelected(project.locked);syncing=false;updateSettingsControls();}
    void updateSettingsControls(){metric.setEnabled(!project.locked&&!busy);threshold.setEnabled(!project.locked&&!busy);updateReferenceControls();}
    void refreshResults(){results.sort(ProjectIO.resultOrder());tableModel.setRowCount(0);for(var r:results)tableModel.addRow(new Object[]{r.photo.visit,r.photo.mode,r.region.name,Double.isNaN(r.severity)?"N/A":String.format(Locale.ROOT,"%.4f",r.severity),String.format(Locale.ROOT,"%.4f",r.areaPercent),r.pixels,r.abnormal,r.warnings});}
    JFileChooser importChooser(){
        JFileChooser f=new FolderFileChooser(lastImportDirectory!=null&&lastImportDirectory.isDirectory()?lastImportDirectory:null,folder->rememberFolder(true,folder));
        f.setMultiSelectionEnabled(true);f.setFileFilter(new FileNameExtensionFilter("Images JPG / PNG / TIFF","jpg","jpeg","png","tif","tiff"));return f;
    }
    void rememberFolder(boolean importing,File folder){if(folder==null||!folder.isDirectory())return;if(importing)lastImportDirectory=folder;else lastOutputDirectory=folder;if(!directoryHistory.remember(importing?DirectoryHistory.IMPORT:DirectoryHistory.OUTPUT,folder))setStatus("Folder selected. Its history could not be saved for the next application restart.");}
    void rememberImportDirectory(JFileChooser f){rememberFolder(true,f.getCurrentDirectory());}
    void importPhotos() throws Exception {
        JFileChooser f=importChooser();int choice=f.showOpenDialog(this);rememberImportDirectory(f);if(choice!=JFileChooser.APPROVE_OPTION)return;
        JTextField v=new JTextField(current()==null?"Baseline":current().visit);JPanel p=new JPanel(new GridLayout(0,1,4,4));p.add(new JLabel("Visit for this batch (editable per image)"));p.add(v);p.add(new JLabel("Mode is inferred from filenames. Verify each image."));if(JOptionPane.showConfirmDialog(this,p,"Import images",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        if(v.getText().isBlank())throw new IllegalArgumentException("Visit cannot be empty.");ArrayList<Photo> added=new ArrayList<>();
        for(File file:f.getSelectedFiles()){if(project.photos.size()+added.size()>=300)throw new IOException("Maximum 300 images per project.");Photo ph=Photo.read(file.toPath(),Mode.guess(file.getName()),v.getText().trim());added.add(ph);}
        project.photos.addAll(added);changed("Imported "+added.size()+" images");refreshPhotos();if(!added.isEmpty())photos.setSelectedValue(added.get(0),true);setStatus("Imported. Verify modes; draw reference skin, then measurement ROIs.");
    }
    JFileChooser outputChooser(String label,String ext){JFileChooser f=new FolderFileChooser(lastOutputDirectory!=null&&lastOutputDirectory.isDirectory()?lastOutputDirectory:null,folder->rememberFolder(false,folder));f.setDialogType(JFileChooser.SAVE_DIALOG);f.setFileFilter(new FileNameExtensionFilter(label,ext));f.setSelectedFile(new File(f.getCurrentDirectory(),"ColorMetric-"+label+"."+ext));return f;}
    void rememberOutputDirectory(JFileChooser f,boolean approved){File folder=f.getCurrentDirectory();if(approved&&f.getSelectedFile()!=null)folder=f.getSelectedFile().getAbsoluteFile().getParentFile();rememberFolder(false,folder);}
    Path chooseSave(String label,String ext){JFileChooser f=outputChooser(label,ext);int choice=f.showSaveDialog(this);rememberOutputDirectory(f,choice==JFileChooser.APPROVE_OPTION);if(choice!=JFileChooser.APPROVE_OPTION)return null;Path p=f.getSelectedFile().toPath();if(!p.toString().toLowerCase(Locale.ROOT).endsWith("."+ext))p=Path.of(p+"."+ext);if(Files.exists(p)&&!yes("Overwrite existing file?\n"+p))return null;return p;}
    int openPathIndex(Path path) throws IOException {captureSession();Path key=Files.exists(path)?path.toRealPath():path.toAbsolutePath().normalize();for(int i=0;i<sessions.size();i++){Path p=sessions.get(i).path;if(p!=null&&(Files.exists(p)?p.toRealPath():p.toAbsolutePath().normalize()).equals(key))return i;}return -1;}
    void saveTo(Path path) throws Exception {int existing=openPathIndex(path);if(existing>=0&&existing!=activeSession)throw new IOException("This file is already open in another project tab. Choose a different filename.");project.log("Save project");ProjectIO.save(project,path);projectPath=path.toAbsolutePath().normalize();dirty=false;updateTabTitles();setStatus("Project saved with original images, ROIs and parameters: "+path);}
    void save(boolean as) throws Exception {if(subject.getText().isBlank())throw new IllegalArgumentException("Subject ID cannot be empty.");project.subject=subject.getText().trim();Path p=projectPath;if(as||p==null)p=chooseSave("Project","pips");if(p!=null)saveTo(p);}
    void openPath(Path path) throws Exception {int existing=openPathIndex(path);if(existing>=0){activateSession(existing);return;}Project next=ProjectIO.load(path);addSession(next,path.toAbsolutePath().normalize());setStatus("Project opened. Re-run analysis to regenerate results. Older projects with multiple references need one reference per image.");}
    void open() throws Exception {JFileChooser f=new FolderFileChooser(lastOutputDirectory,folder->rememberFolder(false,folder));f.setMultiSelectionEnabled(true);f.setFileFilter(new FileNameExtensionFilter("Analysis project","pips"));int choice=f.showOpenDialog(this);rememberOutputDirectory(f,choice==JFileChooser.APPROVE_OPTION);if(choice!=JFileChooser.APPROVE_OPTION)return;for(File file:f.getSelectedFiles())openPath(file.toPath());}
    void deleteRegion(){Photo p=requirePhoto();Region r=selectedRegion();if(r==null)return;if(yes("Delete ROI \""+r.name+"\"?")){clearEdits(p,r);p.regions.remove(r);p.confirmed=false;changed("ROI deleted "+r.id);syncPhoto();}}
    void replaceRois(Photo target,Photo src,double[] transform,String mapping){
        clearEdits(target,null);
        target.regions=Model.mapped(src,transform);if(target.pureWhiteReference)target.regions.removeIf(r->r.role==Role.REFERENCE);target.confirmed=false;target.alignment="Source "+src.id+" | "+mapping;
        project.log("Transferred to "+target.id+" | "+target.alignment);
    }
    void copyToCurrent(boolean align){
        Photo target=requirePhoto(),src=(Photo)source.getSelectedItem();if(src==null||src==target)throw new IllegalArgumentException("Select a different source image with ROIs.");if(src.regions.isEmpty())throw new IllegalArgumentException("The source image has no ROIs.");
        if(!target.regions.isEmpty()&&!yes("Replace all ROIs in the current image?"))return;
        double[] t=align?Model.transform(src.landmarks,target.landmarks,allowScale.isSelected()):new double[]{1,0,0,0,1,0};
        String mapping=align?String.format(Locale.ROOT,"Landmark mapping c=%.8f s=%.8f tx=%.4f ty=%.4f scale=%.8f second-point residual=%.4f px",t[0],t[1],t[2],t[3],t[4],t[5]):"Exact pixel coordinates copied; no registration";
        replaceRois(target,src,t,mapping);changed("ROI transfer completed");syncPhoto();setStatus("ROIs copied. Check positions and boundaries, then check image mode. Out-of-bounds ROIs cannot be analyzed.");
    }
    Session owner(Photo photo){for(Session s:sessions)if(s.project.photos.contains(photo))return s;return null;}
    String sessionLabel(Session s){return "Project "+(sessions.indexOf(s)+1)+" · "+(s.path==null?s.project.subject:s.path.getFileName().toString());}
    record ImageChoice(Session session,Photo photo,String projectLabel){public String toString(){return projectLabel+" / "+photo;}}
    final class BatchCopyPanel extends JPanel {
        final JComboBox<Session> sourceProject=new JComboBox<>(sessions.toArray(Session[]::new));final JComboBox<Photo> sourceImage=new JComboBox<>();
        final JList<Region> regions=new JList<>(new DefaultListModel<>());final JList<ImageChoice> images=new JList<>(new DefaultListModel<>());
        BatchCopyPanel(Photo preferred){
            super(new BorderLayout(0,10));regions.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);images.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);regions.setVisibleRowCount(10);images.setVisibleRowCount(10);
            sourceProject.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){JLabel label=(JLabel)super.getListCellRendererComponent(l,value,index,selected,focus);if(value instanceof Session s)label.setText(sessionLabel(s));return label;}});
            JPanel pickers=new JPanel(new GridLayout(0,1,4,4));pickers.add(new JLabel("Source project (any open project)"));pickers.add(sourceProject);pickers.add(new JLabel("Source image with ROIs"));pickers.add(sourceImage);pickers.add(new JLabel("<html>Choose a source, then Ctrl / Shift selects ROIs and destination images across open projects.<br>Coordinates and shape stay exact. Other ROIs stay; a copied reference replaces the destination reference.</html>"));add(pickers,BorderLayout.NORTH);
            JPanel lists=new JPanel(new GridLayout(1,2,12,0));JScrollPane rs=new JScrollPane(regions),ps=new JScrollPane(images);rs.setBorder(new TitledBorder("Source ROIs to copy"));ps.setBorder(new TitledBorder("Destination images (all open projects)"));lists.add(rs);lists.add(ps);add(lists,BorderLayout.CENTER);setPreferredSize(new Dimension(960,460));
            Session initial=owner(preferred);sourceProject.setSelectedItem(initial==null?sessions.get(activeSession):initial);fillSourceImages(preferred);sourceProject.addActionListener(e->fillSourceImages(null));sourceImage.addActionListener(e->refreshSelections());
        }
        void fillSourceImages(Photo preferred){sourceImage.removeAllItems();Session s=(Session)sourceProject.getSelectedItem();if(s!=null){for(Photo p:s.project.photos)sourceImage.addItem(p);Photo chosen=preferred!=null&&s.project.photos.contains(preferred)?preferred:s.project.photos.stream().filter(p->!p.regions.isEmpty()).findFirst().orElse(s.project.photos.isEmpty()?null:s.project.photos.get(0));sourceImage.setSelectedItem(chosen);}refreshSelections();}
        void refreshSelections(){Photo src=(Photo)sourceImage.getSelectedItem();Set<Photo> selected=new HashSet<>(images.getSelectedValuesList().stream().map(ImageChoice::photo).toList());DefaultListModel<Region> rm=(DefaultListModel<Region>)regions.getModel();DefaultListModel<ImageChoice> im=(DefaultListModel<ImageChoice>)images.getModel();rm.clear();im.clear();if(src!=null)for(Region r:src.regions)rm.addElement(r);if(!rm.isEmpty())regions.setSelectionInterval(0,rm.size()-1);
            for(Session s:sessions)for(Photo p:s.project.photos)if(p!=src){int index=im.size();im.addElement(new ImageChoice(s,p,sessionLabel(s)));if(selected.contains(p))images.addSelectionInterval(index,index);}if(images.isSelectionEmpty()&&current()!=src)for(int i=0;i<im.size();i++)if(im.get(i).photo()==current())images.setSelectedIndex(i);
        }
        List<Photo> targets(){return images.getSelectedValuesList().stream().map(ImageChoice::photo).toList();}
    }
    void copySelectedRois(Photo src,List<Region> selected,List<Photo> targets){
        if(busy)throw new IllegalStateException("Wait for analysis before copying ROIs.");
        if(selected.isEmpty())throw new IllegalArgumentException("Select at least one source ROI to copy.");if(targets.isEmpty())throw new IllegalArgumentException("Select at least one destination image.");
        Session sourceSession=owner(src);if(sourceSession==null||selected.stream().anyMatch(r->!src.regions.contains(r))||targets.stream().anyMatch(p->p==src||owner(p)==null))throw new IllegalArgumentException("Choose source ROIs and other images from open projects.");
        if(selected.stream().filter(r->r.role==Role.REFERENCE).count()>1)throw new IllegalArgumentException("Select no more than one Reference skin ROI to copy.");
        captureSession();Set<Photo> unique=new LinkedHashSet<>(targets);Set<Session> affected=new LinkedHashSet<>();boolean hasReference=selected.stream().anyMatch(r->r.role==Role.REFERENCE);String ids=String.join(",",selected.stream().map(r->r.id).toList());
        for(Photo target:unique){Session destination=owner(target);affected.add(destination);
            ArrayList<Region> next=new ArrayList<>(target.regions);if(hasReference&&!target.pureWhiteReference)next.removeIf(r->r.role==Role.REFERENCE);
            for(Region r:selected){if(target.pureWhiteReference&&r.role==Role.REFERENCE)continue;next.removeIf(old->old.id.equals(r.id));Region copy=r.copy();if(copy.role==Role.REFERENCE)copy.name="reference";next.add(copy);}
            target.regions=next;target.confirmed=false;target.alignment="Source project "+sessionLabel(sourceSession)+" | Source image "+src.id+" | Selected ROI IDs "+ids+" | Exact coordinates copied; no registration; other ROIs retained";
            clearEdits(target,null);
            destination.project.log("Transferred selected ROIs to "+target.id+" | "+target.alignment);
        }
        for(Session s:affected){s.dirty=true;s.results.clear();s.project.log("Batch copied "+selected.size()+" selected ROIs from "+src.id);if(s.project==project){dirty=true;results=s.results;refreshResults();canvas.maskImage=null;if(unique.contains(current()))syncPhoto();canvas.repaint();}}
        updateTabTitles();photos.repaint();setStatus("Copied "+selected.size()+" ROIs to "+unique.size()+" images in "+affected.size()+" projects. Check positions in every destination; reanalyze changed projects.");
    }
    void copyMany(){Photo src=current();if(src==null||src.regions.isEmpty())src=(Photo)source.getSelectedItem();if(src==null||src.regions.isEmpty())src=sessions.stream().flatMap(s->s.project.photos.stream()).filter(p->!p.regions.isEmpty()).findFirst().orElseThrow(()->new IllegalArgumentException("Draw ROIs in a source image first."));
        if(sessions.stream().mapToInt(s->s.project.photos.size()).sum()<2)throw new IllegalArgumentException("Import another image or open another project to copy ROIs to.");BatchCopyPanel panel=new BatchCopyPanel(src);
        if(JOptionPane.showConfirmDialog(this,panel,"Batch copy selected ROIs",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        copySelectedRois((Photo)panel.sourceImage.getSelectedItem(),panel.regions.getSelectedValuesList(),panel.targets());
    }
    void analyze(boolean all){
        List<Photo> list=all?new ArrayList<>(project.photos):List.of(requirePhoto());if(list.isEmpty())throw new IllegalArgumentException("Import images first.");
        for(Photo p:list){if(p.regions.stream().noneMatch(r->r.role==Role.TARGET))throw new IllegalArgumentException(p.visit+" / "+p.mode+" has no measurement ROI.");}
        busy=true;projectTabs.setEnabled(false);for(JButton b:actionButtons)b.setEnabled(false);photos.setEnabled(false);rois.setEnabled(false);locked.setEnabled(false);showMask.setEnabled(false);updateSettingsControls();setStatus("Starting analysis...");
        worker=new SwingWorker<>(){
            protected ArrayList<Engine.Result> doInBackground() throws Exception {ArrayList<Engine.Result> out=new ArrayList<>();int i=0;for(Photo p:list){publish("Analyzing "+(++i)+" / "+list.size()+"："+p.visit+" · "+p.mode);out.addAll(Engine.analyze(p,project.settings.get(p.mode)));}return out;}
            protected void process(List<String> chunks){setStatus(chunks.get(chunks.size()-1));}
            protected void done(){busy=false;projectTabs.setEnabled(true);for(JButton b:actionButtons)b.setEnabled(true);photos.setEnabled(true);rois.setEnabled(true);locked.setEnabled(true);showMask.setEnabled(true);updateSettingsControls();
                try{ArrayList<Engine.Result> next=get();if(all)results=next;else {Photo p=list.get(0);results.removeIf(r->r.photo==p);results.addAll(next);}refreshResults();canvas.maskImage=null;canvas.repaint();project.log("Analysis completed for "+list.size()+" images; fixed mode parameters");dirty=true;updateTabTitles();setStatus("Completed: "+next.size()+" ROI results. Intensity uses affected pixels only; inspect segmentation before export.");}
                catch(Exception ex){results.clear();refreshResults();canvas.maskImage=null;error(new Exception(ex.getCause()==null?ex.getMessage():ex.getCause().getMessage()));}
            }
        };worker.execute();
    }
    List<ProjectIO.ExportProject> exportProjects(boolean all){
        captureSession();ArrayList<ProjectIO.ExportProject> out=new ArrayList<>();for(int i=0;i<sessions.size();i++)if(all||i==activeSession){Session s=sessions.get(i);String label=s.path==null?s.project.subject:s.path.getFileName().toString();out.add(new ProjectIO.ExportProject(label,s.path==null?"Unsaved project":s.path.toString(),s.project,new ArrayList<>(s.results)));}return out;
    }
    void export() throws Exception {
        Object[] options={"Current project (Excel)","All open projects (Excel)","Current project (ZIP package)","Cancel"};
        int choice=JOptionPane.showOptionDialog(this,"Export cached results shown in the Results tables.\nProjects with no results are listed in the Projects worksheet.\nAnalyze after opening a saved project to generate its results.","Export results",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,options,options[0]);
        if(choice<0||choice==3)return;if(choice==2){Path p=chooseSave("Results-package","zip");if(p!=null){ProjectIO.export(project,results,p);setStatus("Results package exported: "+p);}return;}
        List<ProjectIO.ExportProject> items=exportProjects(choice==1);if(items.stream().allMatch(x->x.results().isEmpty()))throw new IOException("No analysis results to export. Analyze a project first.");
        Path p=chooseSave(choice==1?"All-project-results":"Results","xlsx");if(p==null)return;ProjectIO.exportExcel(items,p);
        long empty=items.stream().filter(x->x.results().isEmpty()).count();setStatus("Excel exported: "+items.stream().mapToInt(x->x.results().size()).sum()+" results from "+items.size()+" projects; "+empty+" projects without results listed in Projects. "+p);
    }
    void help(){
        String html="<html><body style='font-family:Segoe UI;font-size:12px;color:#173b4d;padding:14px'><h2>ColorMetric Studio 0.1.0</h2><p>Quantitative color analysis across imaging modes. Define ROIs on any visible skin area; analysis is not restricted to the eyes.</p>"
            +"<p><b>1. Import</b>: Use one project per subject. Open or create multiple projects and switch with the top tabs. Each tab keeps its own images, ROIs, results and parameters. Import original PL, Redness, Brown and Inflammation exports by visit. Inflammation is recognized from device red-area filenames; verify visit and mode. All processing is local.</p>"
            +"<p><b>2. Draw</b>: Choose Reference skin, click vertices, and double-click or press Enter to close. Draw exactly one reference per image; its name defaults to reference. Alternatively select Use pure white as reference for this image; no reference drawing is allowed while selected. Existing reference polygons are retained but inactive. All Measurement ROIs share the selected reference, with no linking. Use Exclusion ROIs for hair, moles or glare according to a predefined rule. Ctrl+Z removes the latest unfinished vertex.</p>"
            +"<p><b>3. Transfer</b>: Copy source ROIs to other images lets you choose any source project and image, then select source ROIs and destination images across all open projects with Ctrl / Shift. Changed destination projects are marked unsaved and need reanalysis; untouched projects retain their results. Matching ROI IDs are updated; other ROIs stay. A copied reference replaces the destination reference. Exact copying preserves coordinates and shape but does not register anatomy. Two-landmark transfer translates and rotates; optional scaling is recorded. Mark the same anatomical locations in the same order.</p>"
            +"<p><b>4. Review</b>: Check boundaries and image mode after every transfer or edit. No verification checkbox is required. In Edit / move, drag inside any ROI to select and move the whole polygon without changing shape or size. A nearby boundary point takes priority from either side of the outline. Crosshair cursor means point editing; the move cursor means whole-ROI movement. After a whole-ROI move or vertex edit, Ctrl+Z in the canvas restores its previous coordinates. Repeated presses undo earlier edits in this project. In Draw polygon, Ctrl+Z still removes unfinished vertices. Scroll to zoom; right-drag to pan.</p>"
            +"<p><b>5. Analyze</b>: PL and Brown default to reference mean L* minus each pixel L*; Redness and Inflammation default to each pixel a* minus reference mean a*. Each mode has independent parameters. Pixels at or above the fixed threshold are affected. Relative color intensity is the mean difference of affected pixels only, or N/A when none are detected. Affected area is affected pixels divided by all valid measurement ROI pixels, multiplied by 100. Exclusions apply to target and reference.</p>"
            +"<p><b>6. Save / export</b>: Save and export dialogs share the last browsed output folder across project tabs and application restarts, including cancelled dialogs. Import-folder history is stored separately. Type a path into Folder address and press Enter or Go; Ctrl+L focuses the address. A .pips project includes original image bytes, checksums, ROIs, landmarks, parameters and history. Drag images in the left Images list to reorder them and save the project to keep this order. Results are sorted separately by region, then Baseline first, then numbers in Visit labels; filenames supply a number only when the Visit has none. Equal numbers use mode order. Export results offers current-project Excel, all-open-project Excel, or a current-project ZIP package. Excel includes Results and Projects worksheets and distinct project numbers; projects without cached results are listed. Re-run analysis after opening a saved project. The optional ZIP contains Excel, CSV, analyzed overlays, ImageJ-compatible ROIs, ROI-only original-color previews and a parameter manifest. Both overlays and imagej-rois previews retain the imported filename, including Chinese characters and the original JPG/PNG/TIFF extension. Duplicate overlay names use separate image-ID subfolders. imagej-rois/&lt;image-id&gt;/&lt;source-filename&gt; shows all ROI boundaries and names without segmentation tint. JPEG previews are re-encoded at maximum quality; source project bytes and measurements do not change.</p>"
            +"<p><b>Interpretation</b>: These are image-derived signals, not melanin or hemoglobin concentrations, pigment depth or a clinical inflammation diagnosis. Default thresholds are PL 5, Redness 3, Brown 5 and Inflammation 3: exploratory starting values, not clinically validated cutoffs. Lab conversion assumes sRGB / D65. Affected-pixel intensity depends on threshold selection: it can rise as mildly affected pixels leave the mask, even when area decreases. Interpret both outputs together. The exported whole-ROI mean is auxiliary only. Legacy whole-ROI primary values must not be mixed with affected-pixel-only intensity; reanalyze legacy projects.</p>"
            +"<p><b>Research use</b>: Validate repeatability, segmentation and thresholds on independent data, then lock parameters. Do not tune thresholds per image to obtain desired treatment results. Without physical scale calibration, report percent, not mm². Large differences in pose or eyelid closure may prevent reliable comparison.</p>"
            +"<p>Open the synthetic demo from File. It contains no real patient photographs.</p></body></html>";
        JEditorPane pane=new JEditorPane("text/html",html);pane.setEditable(false);pane.setCaretPosition(0);JScrollPane sp=new JScrollPane(pane);sp.setPreferredSize(new Dimension(780,620));JOptionPane.showMessageDialog(this,sp,"User guide and measurement definitions",JOptionPane.PLAIN_MESSAGE);
    }

    final class Canvas extends JPanel {
        BufferedImage image,maskImage;double zoom=1,ox=0,oy=0;int tool=0,dragVertex=-1;
        ArrayList<Point2D.Double> pending=new ArrayList<>(),dragPoints=new ArrayList<>();Point lastScreen;Point2D.Double lastImage,dragStart;boolean pan=false,moveRegion=false,dragChanged=false;Region dragRegion;Photo dragPhoto;
        Canvas(){setBackground(new Color(225,234,238));setFocusable(true);MouseAdapter mouse=new MouseAdapter(){
            public void mousePressed(MouseEvent e){if(busy||image==null)return;requestFocusInWindow();lastScreen=e.getPoint();lastImage=toImage(e.getPoint());pan=SwingUtilities.isRightMouseButton(e)||SwingUtilities.isMiddleMouseButton(e);
                dragRegion=null;dragVertex=-1;moveRegion=false;dragChanged=false;if(pan||!SwingUtilities.isLeftMouseButton(e))return;
                if(tool==1){VertexHit hit=vertexAt(lastImage);Region r;
                    if(hit!=null){r=hit.region();dragVertex=hit.index();}else {r=interiorRegion(lastImage);moveRegion=r!=null;}
                    if(r!=null){rois.setSelectedValue(r,true);dragRegion=r;dragPhoto=current();dragStart=lastImage;dragPoints=new ArrayList<>();for(Point2D.Double point:r.points)dragPoints.add(new Point2D.Double(point.x,point.y));}
                }
            }
            public void mouseDragged(MouseEvent e){if(busy||image==null)return;if(pan){ox+=e.getX()-lastScreen.x;oy+=e.getY()-lastScreen.y;lastScreen=e.getPoint();repaint();return;}if(tool==1&&dragRegion!=null&&current()==dragPhoto){Point2D.Double pt=toImage(e.getPoint());if(pt.distance(dragStart)<.000001&&!dragChanged)return;
                    if(moveRegion){double dx=pt.x-dragStart.x,dy=pt.y-dragStart.y;for(int i=0;i<dragPoints.size();i++){Point2D.Double original=dragPoints.get(i);dragRegion.points.get(i).setLocation(original.x+dx,original.y+dy);}}
                    else if(dragVertex>=0){Point2D.Double original=dragPoints.get(dragVertex);dragRegion.points.get(dragVertex).setLocation(original.x+pt.x-dragStart.x,original.y+pt.y-dragStart.y);}
                    dragPhoto.confirmed=false;if(!dragChanged){dragChanged=true;dragPhoto.alignment+=moveRegion?" | manually translated ROI "+dragRegion.id:" | manually edited ROI "+dragRegion.id;changed((moveRegion?"ROI translated without shape change ":"ROI vertex edited ")+dragRegion.id);}repaint();}
            }
            public void mouseReleased(MouseEvent e){if(busy)return;if(dragChanged){recordEdit(dragPhoto,dragRegion,dragPoints,moveRegion);setStatus(moveRegion?"ROI moved. Ctrl+Z undoes this movement.":"ROI vertex edited. Ctrl+Z undoes this point adjustment.");}dragRegion=null;dragVertex=-1;moveRegion=false;dragChanged=false;pan=false;}
            public void mouseMoved(MouseEvent e){int cursor=Cursor.DEFAULT_CURSOR;if(!busy&&image!=null&&tool==1){Point2D.Double point=toImage(e.getPoint());if(vertexAt(point)!=null)cursor=Cursor.CROSSHAIR_CURSOR;else if(interiorRegion(point)!=null)cursor=Cursor.MOVE_CURSOR;}setCursor(Cursor.getPredefinedCursor(cursor));}
            public void mouseClicked(MouseEvent e){if(busy||image==null||!SwingUtilities.isLeftMouseButton(e))return;Point2D.Double p=toImage(e.getPoint());if(p.x<0||p.y<0||p.x>image.getWidth()||p.y>image.getHeight())return;
                if(tool==0){if(e.getClickCount()==2){guard(()->finish());return;}if(pending.isEmpty()||pending.get(pending.size()-1).distance(p)>0.5)pending.add(p);repaint();}
                else if(tool==2){Photo ph=current();if(ph.landmarks.size()>=2)ph.landmarks.clear();ph.landmarks.add(p);changed("Landmark "+ph.id+" #"+ph.landmarks.size());repaint();if(ph.landmarks.size()==2){tool=1;setStatus("Two landmarks recorded. Select a source image and transfer ROIs.");}}
            }
            public void mouseWheelMoved(MouseWheelEvent e){if(image==null||busy||dragRegion!=null)return;Point2D.Double pt=toImage(e.getPoint());zoom=Math.max(.025,Math.min(12,zoom*Math.pow(1.15,-e.getPreciseWheelRotation())));ox=e.getX()-pt.x*zoom;oy=e.getY()-pt.y*zoom;zoomInfo.setText(String.format(Locale.ROOT,"%.0f%%",zoom*100));repaint();}
        };addMouseListener(mouse);addMouseMotionListener(mouse);addMouseWheelListener(mouse);
            bind("ENTER",()->guard(()->finish()));bind("ESCAPE",()->{pending.clear();repaint();});bind("BACK_SPACE",()->undoPendingPoint());bind("ctrl Z",()->{if(tool==0)undoPendingPoint();else if(tool==1)undoRoiEdit();});
            addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){if(image!=null)fit();}});
        }
        boolean inside(Region r,Point2D.Double point){if(r.points.size()<3)return false;Path2D.Double polygon=new Path2D.Double();polygon.moveTo(r.points.get(0).x,r.points.get(0).y);for(int i=1;i<r.points.size();i++)polygon.lineTo(r.points.get(i).x,r.points.get(i).y);polygon.closePath();return polygon.contains(point);}
        record VertexHit(Region region,int index) {}
        double vertexRadius(Region r){double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;for(Point2D.Double p:r.points){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}return Math.min(9,Math.max(0,Math.min(maxX-minX,maxY-minY)*zoom*.3));}
        VertexHit vertexAt(Point2D.Double point){Photo photo=current();if(photo==null)return null;ArrayList<Region> candidates=new ArrayList<>();Region selected=selectedRegion();if(selected!=null)candidates.add(selected);for(int i=photo.regions.size()-1;i>=0;i--)if(photo.regions.get(i)!=selected)candidates.add(photo.regions.get(i));VertexHit hit=null;double nearest=Double.POSITIVE_INFINITY;for(Region r:candidates){double radius=vertexRadius(r);for(int i=0;i<r.points.size();i++){double distance=r.points.get(i).distance(point)*zoom;if(distance<=radius&&distance<nearest){nearest=distance;hit=new VertexHit(r,i);}}}return hit;}
        Region interiorRegion(Point2D.Double point){Region selected=selectedRegion();if(selected!=null&&inside(selected,point))return selected;Photo photo=current();if(photo!=null)for(int i=photo.regions.size()-1;i>=0;i--){Region r=photo.regions.get(i);if(inside(r,point))return r;}return null;}
        void bind(String key,Runnable run){getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key),key);getActionMap().put(key,new AbstractAction(){public void actionPerformed(ActionEvent e){if(!busy)run.run();}});}
        void undoPendingPoint(){if(tool==0&&!pending.isEmpty()){pending.remove(pending.size()-1);repaint();setStatus("Last polygon vertex removed. Continue drawing, or press Ctrl+Z again.");}}
        Point2D.Double toImage(Point p){return new Point2D.Double((p.x-ox)/zoom,(p.y-oy)/zoom);}
        void fit(){if(image!=null&&getWidth()>0&&getHeight()>0){zoom=Math.min((getWidth()-24.0)/image.getWidth(),(getHeight()-24.0)/image.getHeight());ox=(getWidth()-image.getWidth()*zoom)/2;oy=(getHeight()-image.getHeight()*zoom)/2;zoomInfo.setText(String.format(Locale.ROOT,"%.0f%%",zoom*100));}repaint();}
        void finish(){if(pending.size()<3)throw new IllegalArgumentException("At least three vertices are needed to close a polygon.");Photo p=requirePhoto();String name=roiName.getText().trim();if(name.isBlank())throw new IllegalArgumentException("Enter an ROI name.");Role nextRole=(Role)role.getSelectedItem();if(nextRole==Role.REFERENCE)name="reference";Region r=new Region(name,nextRole);ensureSingleReference(p,null,r.role);r.points.addAll(pending);Engine.validate(r,p.width,p.height);p.regions.add(r);p.confirmed=false;pending.clear();changed("ROI created "+r.id+" "+r.role);syncPhoto();rois.setSelectedValue(r,true);setStatus("ROI created. All measurement ROIs share the image's reference skin. Verify positions before analysis.");}
        protected void paintComponent(Graphics gg){super.paintComponent(gg);Graphics2D g=(Graphics2D)gg.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            if(image==null){g.setColor(NAVY);g.setFont(getFont().deriveFont(Font.BOLD,24f));g.drawString("Start with an image",35,100);g.setFont(getFont().deriveFont(15f));g.setColor(MUTED);String[] lines={"1  Import PL, Redness, Brown or Inflammation images","2  Draw reference skin and measurement ROIs","3  Transfer ROIs and verify correspondence","4  Measure relative color and affected area","","Or open the synthetic demo from the File menu."};for(int i=0;i<lines.length;i++)g.drawString(lines[i],35,145+i*32);g.dispose();return;}
            AffineTransform at=new AffineTransform();at.translate(ox,oy);at.scale(zoom,zoom);g.drawImage(image,at,null);
            if(showMask.isSelected()){
                if(maskImage==null){Photo ph=current();List<Engine.Result> zs=results.stream().filter(r->r.photo==ph&&(selectedRegion()==null||r.region==selectedRegion())).toList();if(!zs.isEmpty()){maskImage=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);for(var z:zs)for(int i=z.abnormalMask.nextSetBit(0);i>=0;i=z.abnormalMask.nextSetBit(i+1))maskImage.setRGB(i%image.getWidth(),i/image.getWidth(),0x70FF3040);}}
                if(maskImage!=null)g.drawImage(maskImage,at,null);
            }
            Photo ph=current();if(ph!=null){for(Region r:ph.regions){Color c=r.role==Role.TARGET?new Color(0,215,230):r.role==Role.REFERENCE?new Color(50,220,120):new Color(255,174,40);drawPolygon(g,r.points,true,c,r==selectedRegion()?3:1.6f);
                    Point2D.Double p=r.points.get(0);int x=(int)(ox+p.x*zoom),y=(int)(oy+p.y*zoom)-7;g.setFont(getFont().deriveFont(Font.BOLD,12f));String label=r.name;int maxWidth=Math.max(55,Math.min(145,(int)(r.nativeRoi().getBounds().width*zoom)));while(label.length()>4&&g.getFontMetrics().stringWidth(label)>maxWidth)label=label.substring(0,label.length()-2)+"…";int textW=g.getFontMetrics().stringWidth(label);g.setColor(new Color(10,35,44,210));g.fillRoundRect(x-3,y-14,textW+8,20,5,5);g.setColor(c);g.drawString(label,x,y);
                    if(tool==1&&r==selectedRegion())for(Point2D.Double pt:r.points){g.setColor(Color.WHITE);g.fillOval((int)(ox+pt.x*zoom)-4,(int)(oy+pt.y*zoom)-4,8,8);g.setColor(TEAL);g.drawOval((int)(ox+pt.x*zoom)-4,(int)(oy+pt.y*zoom)-4,8,8);}}
                for(int i=0;i<ph.landmarks.size();i++){var pt=ph.landmarks.get(i);int x=(int)(ox+pt.x*zoom),y=(int)(oy+pt.y*zoom);g.setColor(Color.YELLOW);g.drawLine(x-8,y,x+8,y);g.drawLine(x,y-8,x,y+8);g.drawString(Integer.toString(i+1),x+10,y-5);}}
            drawPolygon(g,pending,false,Color.YELLOW,2);g.dispose();
        }
        void drawPolygon(Graphics2D g,List<Point2D.Double> pts,boolean close,Color color,float stroke){if(pts.isEmpty())return;Path2D path=new Path2D.Double();path.moveTo(ox+pts.get(0).x*zoom,oy+pts.get(0).y*zoom);for(int i=1;i<pts.size();i++)path.lineTo(ox+pts.get(i).x*zoom,oy+pts.get(i).y*zoom);if(close)path.closePath();g.setColor(color);g.setStroke(new BasicStroke(stroke));g.draw(path);for(Point2D.Double p:pts)if(!close)g.fillOval((int)(ox+p.x*zoom)-3,(int)(oy+p.y*zoom)-3,6,6);}
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        if(args.length>0&&args[0].equals("--self-test")){Tests.run(Path.of(args.length>1?args[1]:"test-output"));return;}
        if(args.length>0&&args[0].equals("--preview")){Tests.preview(Path.of(args[1]));return;}
        if(args.length>0&&args[0].equals("--smoke-images")){Tests.smoke(Path.of(args[1]),Path.of(args[2]));return;}
        SwingUtilities.invokeLater(()->{style();App app=new App();if(args.length>0){try{for(String path:args)app.openPath(Path.of(path));}catch(Exception e){app.error(e);}}app.setVisible(true);});
    }
}
