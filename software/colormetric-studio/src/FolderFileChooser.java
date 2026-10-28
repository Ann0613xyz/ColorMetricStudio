package peri;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.*;
import java.util.function.Consumer;

/** Standard file chooser with a separate, visible folder address field. */
final class FolderFileChooser extends JFileChooser {
    final JTextField folderPath=new JTextField(38);
    final JButton go=new JButton("Go");
    final JLabel folderStatus=new JLabel(" ");
    FolderFileChooser(File initial,Consumer<File> changed){
        super(initial);
        folderPath.setName("folderPath");go.setName("goToFolder");folderStatus.setName("folderPathStatus");
        folderPath.setToolTipText("Type or paste a folder path, then press Enter. Ctrl+L selects this field.");
        JLabel label=new JLabel("Folder:");label.setLabelFor(folderPath);label.setDisplayedMnemonic('d');
        JPanel address=new JPanel(new BorderLayout(7,0));address.add(label,BorderLayout.WEST);address.add(folderPath,BorderLayout.CENTER);address.add(go,BorderLayout.EAST);
        JPanel bar=new JPanel(new BorderLayout(0,2));bar.setBorder(new EmptyBorder(3,0,6,0));bar.add(address,BorderLayout.NORTH);
        folderStatus.setForeground(new Color(175,40,40));folderStatus.setFont(folderStatus.getFont().deriveFont(11f));bar.add(folderStatus,BorderLayout.SOUTH);
        // Preserve the normal Look in / toolbar controls installed by MetalFileChooserUI.
        BorderLayout layout=(BorderLayout)getLayout();Component original=layout.getLayoutComponent(BorderLayout.NORTH);
        if(original!=null)remove(original);
        JPanel header=new JPanel(new BorderLayout(0,4));header.add(bar,BorderLayout.NORTH);if(original!=null)header.add(original,BorderLayout.CENTER);add(header,BorderLayout.NORTH);
        updateAddress();
        addPropertyChangeListener(DIRECTORY_CHANGED_PROPERTY,e->{updateAddress();if(changed!=null&&getCurrentDirectory()!=null)changed.accept(getCurrentDirectory());});
        folderPath.addActionListener(e->navigate());go.addActionListener(e->navigate());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ctrl L"),"folderAddress");
        getActionMap().put("folderAddress",new AbstractAction(){public void actionPerformed(ActionEvent e){folderPath.requestFocusInWindow();folderPath.selectAll();}});
        setPreferredSize(new Dimension(980,660));
    }
    void updateAddress(){File d=getCurrentDirectory();if(d!=null){folderPath.setText(d.getAbsolutePath());folderPath.setCaretPosition(0);}folderStatus.setText(" ");}
    boolean navigate(){
        String text=folderPath.getText().trim();
        if(text.length()>1&&((text.startsWith("\"")&&text.endsWith("\""))||(text.startsWith("'")&&text.endsWith("'"))))text=text.substring(1,text.length()-1);
        if(text.isBlank())return invalid("Enter an existing folder path.");
        try{
            if(text.equals("~")||text.startsWith("~/")||text.startsWith("~\\"))text=System.getProperty("user.home")+text.substring(1);
            Path p=Path.of(text);if(!p.isAbsolute())p=getCurrentDirectory().toPath().resolve(p);p=p.toAbsolutePath().normalize();
            if(!Files.isDirectory(p))return invalid("Folder not found. Enter an existing folder path.");
            File folder=p.toFile();String filename=null;
            if(getDialogType()==SAVE_DIALOG){
                String typed=getUI() instanceof javax.swing.plaf.basic.BasicFileChooserUI ui?ui.getFileName():null;
                if(typed!=null&&!typed.isBlank()){
                    typed=typed.trim();if(typed.length()>1&&typed.startsWith("\"")&&typed.endsWith("\""))typed=typed.substring(1,typed.length()-1);
                    filename=new File(typed).getName();
                }else if(getSelectedFile()!=null)filename=getSelectedFile().getName();
            }
            setSelectedFiles(new File[0]);setSelectedFile(null);setCurrentDirectory(folder);
            if(!getCurrentDirectory().getAbsoluteFile().toPath().normalize().equals(p))return invalid("This folder could not be opened.");
            if(filename!=null)setSelectedFile(new File(folder,filename));updateAddress();rescanCurrentDirectory();return true;
        }catch(InvalidPathException|SecurityException e){return invalid("Invalid or inaccessible folder path.");}
    }
    boolean invalid(String message){folderStatus.setText(message);folderPath.requestFocusInWindow();folderPath.selectAll();return false;}
}
