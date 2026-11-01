import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Piyingxi: minimal WorkAudioBook-style shadowing tool: mp3 + waveform + silence split + SRT. */
public class Piyingxi extends JFrame {

    static class Seg {
        long s, e; String text = "";
        Seg(long s, long e) { this.s = s; this.e = e; }
    }

    short[] pcm = new short[0];
    int rate = 44100;
    final List<Seg> segs = new ArrayList<>();
    Seg sel;
    File srt;
    boolean updating;

    static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(Piyingxi.class);
    static final SimpleAttributeSet CENTER = new SimpleAttributeSet();
    static { StyleConstants.setAlignment(CENTER, StyleConstants.ALIGN_CENTER); }
    static boolean curDark;

    static Color boxBg() { return curDark ? new Color(30, 31, 34) : Color.WHITE; }

    CardLayout subCards; JPanel subPanel, hiddenPanel;
    JToggleButton drawTog;
    JSlider lenSlider; JLabel lenLbl;
    JCheckBoxMenuItem srcLine, srcSpec;

    void applyTextStyle() {
        text.setBackground(boxBg());
        if (hiddenPanel != null) hiddenPanel.setBackground(boxBg());
        int top = Math.round(text.getFont().getSize() * 1.3f); // roughly one line of space above the text
        text.setBorder(BorderFactory.createEmptyBorder(top, 12, 8, 12));
    }

    static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null; }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().toLowerCase();
        } catch (Exception e) { return null; }
    }

    static boolean systemDark() {
        String gt = System.getenv("GTK_THEME");
        if (gt != null && gt.toLowerCase().contains("dark")) return true;
        String cs = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (cs != null) {
            if (cs.contains("prefer-dark")) return true;
            if (cs.contains("prefer-light")) return false;
        }
        String[][] more = {
            {"gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"},
            {"kreadconfig6", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme"},
            {"kreadconfig5", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme"}};
        for (String[] c : more) { String o = run(c); if (o != null && o.contains("dark")) return true; }
        return false;
    }

    static boolean wantDark() {
        String m = PREFS.get("theme", "system");
        return m.equals("dark") || (m.equals("system") && systemDark());
    }

    static void boldFonts() {
        for (Object k : new ArrayList<>(UIManager.getDefaults().keySet())) {
            if (k instanceof String && ((String) k).toLowerCase().endsWith("font")) {
                Font f = UIManager.getFont(k);
                if (f != null && !f.isBold()) UIManager.put(k, new FontUIResource(f.deriveFont(Font.BOLD)));
            }
        }
    }

    static void installLaf() {
        curDark = wantDark();
        if (curDark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        boldFonts();
        UIManager.put("TextComponent.disabledBackground", new javax.swing.plaf.ColorUIResource(boxBg()));
        UIManager.put("TextComponent.inactiveBackground", new javax.swing.plaf.ColorUIResource(boxBg()));
    }

    void refreshTheme(boolean force) {
        if (!force && !PREFS.get("theme", "system").equals("system")) return;
        if (!force && wantDark() == curDark) return;
        installLaf();
        FlatLaf.updateUI();
        wave.applyTheme();
        applyTextStyle();
        refreshTabs();
    }

    final Wave wave = new Wave();
    final Pitch pitch = new Pitch();
    final JComponent selOverlay = new SelOverlay(); // draws the dragged range once, across both panels
    volatile float[] f0 = new float[0];
    volatile int pitchGen;
    final JTextPane text = new JTextPane();
    final JScrollPane sp;
    final JLabel status = new JLabel(" Open an mp3 file to begin.");
    final JToggleButton loop = new JToggleButton("\u221E");
    final javax.swing.Timer saveTimer = new javax.swing.Timer(800, e -> saveSrt());
    final javax.swing.Timer playTimer = new javax.swing.Timer(30, e -> tick());

    AtomicBoolean curStop; Thread playThread; volatile SourceDataLine line; volatile long playBase;
    long playMs = -1;

    Piyingxi() {
        super("Piyingxi");
        saveTimer.setRepeats(false);
        JPanel stack = new JPanel(null) { @Override public boolean isOptimizedDrawingEnabled() { return false; } }; // children overlap (the overlay)
        stack.setLayout(new LayoutManager() { // pitch panel and waveform get equal heights (waveform fills all if pitch is hidden)
            public void addLayoutComponent(String n, Component c) { }
            public void removeLayoutComponent(Component c) { }
            public Dimension preferredLayoutSize(Container p) { return new Dimension(wave.getPreferredSize().width, 220); }
            public Dimension minimumLayoutSize(Container p) { return new Dimension(1, 120); }
            public void layoutContainer(Container p) {
                int w = p.getWidth(), h = p.getHeight();
                selOverlay.setBounds(0, 0, w, h);
                if (pitch.isVisible()) { int ph = h / 2; pitch.setBounds(0, 0, w, ph); wave.setBounds(0, ph, w, h - ph); }
                else wave.setBounds(0, 0, w, h);
            }
        });
        stack.add(pitch);
        stack.add(wave);
        stack.add(selOverlay, 0); // index 0 = drawn on top
        sp = new JScrollPane(stack, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        sp.setPreferredSize(new Dimension(1100, 380));
        StyleConstants.setAlignment(text.getStyledDocument().getStyle(StyleContext.DEFAULT_STYLE), StyleConstants.ALIGN_CENTER);
        text.setParagraphAttributes(CENTER, false);
        text.setFont(new Font(PREFS.get("fontFamily", "SansSerif"), PREFS.getBoolean("fontBold", true) ? Font.BOLD : Font.PLAIN, PREFS.getInt("fontSize", 24)));
        applyTextStyle();
        text.setEnabled(false);
        // Tab = play/stop (not a tab character, not focus traversal)
        text.setFocusTraversalKeysEnabled(false);
        text.getInputMap().put(KeyStroke.getKeyStroke("TAB"), "play");
        text.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "leave");
        text.getActionMap().put("leave", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { wave.requestFocusInWindow(); }
        });
        text.getActionMap().put("play", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { playFromCursor(); }
        });
        // Left/Right move the caret as usual, but at the very start/end of the text they jump to the prev/next segment
        Action back = text.getActionMap().get("caret-backward"), fwd = text.getActionMap().get("caret-forward");
        text.getActionMap().put("caret-backward", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (text.getSelectionStart() == 0 && text.getSelectionEnd() == 0) step(-1); else back.actionPerformed(e);
            }
        });
        text.getActionMap().put("caret-forward", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int len = text.getDocument().getLength();
                if (text.getSelectionStart() == len && text.getSelectionEnd() == len) step(1); else fwd.actionPerformed(e);
            }
        });
        text.getDocument().addDocumentListener(new DocumentListener() {
            void ch() { if (updating || sel == null) return; sel.text = text.getText(); saveTimer.restart(); wave.repaint(); }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        JPanel playbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        recBtn = symBtn(REC, "Record (R): record the microphone for the length of the dragged range or selected segment", this::recordSegment);
        playbar.add(recBtn);
        playbar.add(symBtn("\u23EA\uFE0E", "Previous segment (Left arrow)", () -> step(-1)));
        playBtn = symBtn(PLAY, "Play / pause (Space)", this::togglePlay);
        playbar.add(playBtn);
        playbar.add(symBtn("\u23E9\uFE0E", "Next segment (Right arrow)", () -> step(1)));
        loop.setFont(new Font(Font.DIALOG, Font.PLAIN, 22));
        loop.setToolTipText("Loop the selected segment");
        loop.setFocusable(false);
        loop.setPreferredSize(new Dimension(56, 38));
        playbar.add(loop);
        playbar.add(symBtn("\u2702\uFE0E", "Split (S): split the selected segment at the click position, or make the dragged range its own segment", this::splitAction));
        playbar.add(symBtn("\u21B6", "Undo the last change (Ctrl+Z)", this::undo));
        JPanel viewbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        viewbar.add(symBtn("-", "Zoom out (Ctrl+-)", () -> zoom(1.5)));
        viewbar.add(symBtn("+", "Zoom in (Ctrl++)", () -> zoom(1 / 1.5)));
        viewbar.add(textBtn("All", "Zoom all the way out (A)", this::zoomAll));
        viewbar.add(textBtn("Selection", "Zoom in on the selection (same as double-clicking it)", this::zoomSelection));
        JPanel leftbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        leftbar.add(btn("Auto-split", this::autoSplit));
        JCheckBox autoRecTog = new JCheckBox("Auto-record", PREFS.getBoolean("autoRecord", false));
        autoRecTog.setToolTipText("After a segment finishes playing, start recording automatically");
        autoRecTog.setFocusable(false);
        autoRecTog.addActionListener(e -> PREFS.putBoolean("autoRecord", autoRecTog.isSelected()));
        leftbar.add(autoRecTog);
        JCheckBox hideTog = new JCheckBox("Hide subtitles", PREFS.getBoolean("hideSubs", false));
        hideTog.setToolTipText("Hide the subtitles (the box keeps its size)");
        hideTog.setFocusable(false);
        hideTog.addActionListener(e -> {
            PREFS.putBoolean("hideSubs", hideTog.isSelected());
            subCards.show(subPanel, hideTog.isSelected() ? "hidden" : "text");
            if (hideTog.isSelected()) wave.requestFocusInWindow();
        });
        leftbar.add(hideTog);
        int sideW = Math.max(leftbar.getPreferredSize().width, viewbar.getPreferredSize().width); // equal sides keep the playback buttons centred
        leftbar.setPreferredSize(new Dimension(sideW, 42));
        viewbar.setPreferredSize(new Dimension(sideW, 42));
        JPanel transport = new JPanel(new BorderLayout());
        transport.add(leftbar, BorderLayout.WEST);
        transport.add(playbar, BorderLayout.CENTER);
        transport.add(viewbar, BorderLayout.EAST);
        JPanel upper = new JPanel(new BorderLayout());
        upper.add(sp, BorderLayout.CENTER);
        docs.add(curDoc);
        tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); // slim strip: one tab per document (a file or a new recording)
        tabBar.add(makeTab(curDoc));
        upper.add(tabBar, BorderLayout.NORTH);
        refreshTabs();
        upper.add(transport, BorderLayout.SOUTH);
        subCards = new CardLayout();
        subPanel = new JPanel(subCards);
        subPanel.add(new JScrollPane(text), "text");
        hiddenPanel = new JPanel(new BorderLayout());
        JLabel hiddenLbl = new JLabel("subtitles hidden", SwingConstants.CENTER);
        hiddenLbl.setFont(new Font(Font.DIALOG, Font.ITALIC, 12));
        hiddenLbl.setForeground(Color.GRAY);
        hiddenPanel.add(hiddenLbl);
        hiddenPanel.setBackground(boxBg());
        subPanel.add(hiddenPanel, "hidden");
        subCards.show(subPanel, PREFS.getBoolean("hideSubs", false) ? "hidden" : "text");
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, subPanel);
        split.setResizeWeight(0.65);

        JPanel tools = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
        tools.add(new JLabel("Mic:"));
        micBox.setFocusable(false);
        micBox.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxx");
        fillMicBox();
        micBox.addActionListener(e -> {
            if (micUpdating || micBox.getSelectedIndex() < 0) return;
            PREFS.put("micName", micBox.getSelectedIndex() == 0 ? "" : (String) micBox.getSelectedItem());
            wave.requestFocusInWindow();
        });
        micBox.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) { fillMicBox(); }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            public void popupMenuCanceled(PopupMenuEvent e) { }
        });
        tools.add(micBox);
        tools.add(new JLabel("Pitch:"));
        pitchLbl = new JLabel();
        pitchSlider = new RangeSlider(0, 500, PREFS.getInt("viewLo", 50), PREFS.getInt("viewHi", 300));
        pitchSlider.setPreferredSize(new Dimension(150, 28));
        pitchSlider.setToolTipText("Pitch display range (Hz). With a segment selected it applies to that segment only, otherwise to the whole audio.");
        pitchLbl.setText(pitchSlider.lo + "\u2013" + pitchSlider.hi + " Hz");
        pitchSlider.onChange = () -> {
            pitchLbl.setText(pitchSlider.lo + "\u2013" + pitchSlider.hi + " Hz");
            if (syncing) return;
            if (sel != null) { Reg r = regForSel(); r.lo = pitchSlider.lo; r.hi = pitchSlider.hi; }
            else { PREFS.putInt("viewLo", pitchSlider.lo); PREFS.putInt("viewHi", pitchSlider.hi); }
            pitch.repaint();
        };
        pitchSlider.onRelease = () -> { saveRegs(); wave.requestFocusInWindow(); };
        pitchLbl.setPreferredSize(new Dimension(80, 20));
        tools.add(pitchSlider);
        tools.add(pitchLbl);
        tools.add(new JLabel("Octave:"));
        octSlider = new JSlider(-1, 1, 0);
        octSlider.setPreferredSize(new Dimension(70, 28));
        octSlider.setFocusable(false);
        octSlider.setPaintTicks(true); octSlider.setMajorTickSpacing(1); octSlider.setSnapToTicks(true);
        octSlider.setToolTipText("Octave correction: left = one octave lower, right = one octave higher. Applies to a dragged range if there is one, else the selected segment, else the whole audio.");
        octLbl = new JLabel("0");
        octLbl.setPreferredSize(new Dimension(26, 20));
        octSlider.addChangeListener(e -> {
            int v = octSlider.getValue();
            octLbl.setText(v > 0 ? "+" + v : String.valueOf(v));
            if (syncing) return;
            boolean blue = PREFS.getBoolean("showBlue", true), orange = PREFS.getBoolean("showOrange", false);
            if (blue || !orange) {
                if (range != null) regFor(range.s, range.e).oct = v;      // a dragged range: only that range
                else if (sel != null) regFor(sel.s, sel.e).oct = v;        // else the selected segment
                else globalOct = v;                                        // else the whole audio
            }
            if (orange) { // the shadow contour(s): the dragged range / selected segment, else the whole recording(s)
                Seg t = range != null ? range : sel;
                for (Rec r : recs) {
                    if (t == null) r.globalOct = v;
                    else if (overlaps(r, t)) regForRec(r, t.s, t.e).oct = v;
                }
            }
            pitch.repaint();
            if (!octSlider.getValueIsAdjusting()) { saveRegs(); wave.requestFocusInWindow(); }
        });
        tools.add(octSlider);
        tools.add(octLbl);
        tools.add(new JLabel("Shadow length:"));
        lenSlider = new JSlider(50, 200, 100);
        lenSlider.setPreferredSize(new Dimension(130, 28));
        lenSlider.setFocusable(false);
        lenSlider.setToolTipText("Stretch or squeeze your recording's contour and spectrogram in time (50% to 200%), to match a slower or faster delivery. Double-click to reset.");
        lenLbl = new JLabel("100%");
        lenLbl.setPreferredSize(new Dimension(50, 20));
        lenSlider.addChangeListener(e -> {
            int v = lenSlider.getValue();
            lenLbl.setText(v + "%");
            if (!syncing) { for (Rec r : recs) r.scale = v / 100.0; pitch.repaint(); }
            if (!lenSlider.getValueIsAdjusting()) wave.requestFocusInWindow();
        });
        lenSlider.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) lenSlider.setValue(100); }
        });
        tools.add(lenSlider);
        tools.add(lenLbl);
        JToggleButton moveTog = new JToggleButton("Move shadow");
        moveTog.setToolTipText("Click your recording (the shadow) in the pitch panel and drag it left, right, up or down, or use the arrow keys (Shift = bigger steps). Left/right lines it up in time; up/down allows for a different voice pitch.");
        moveTog.setFocusable(false);
        moveTog.addActionListener(e -> {
            moveMode = moveTog.isSelected();
            if (moveMode && drawMode) { drawTog.setSelected(false); drawMode = false; }
            pitch.setCursor(moveMode ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor());
            status.setText(!moveMode ? " Move shadow off."
                    : recs.isEmpty() ? " No shadow yet: record one first."
                    : " Move shadow: drag the shadow in the pitch panel, or use the arrow keys (Shift = bigger steps).");
            wave.requestFocusInWindow();
        });
        tools.add(moveTog);
        drawTog = new JToggleButton("Draw");
        drawTog.setToolTipText("Draw the pitch contour by hand over the spectrogram. Drag to draw, Shift+drag to erase, Ctrl+drag to bring back the automatic line. Your drawing replaces the automatic contour and is saved with the file.");
        drawTog.setFocusable(false);
        drawTog.addActionListener(e -> {
            drawMode = drawTog.isSelected();
            if (drawMode && moveMode) { moveTog.setSelected(false); moveMode = false; }
            pitch.setCursor(drawMode ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
            if (drawMode && !PREFS.getBoolean("showBlue", true) && !PREFS.getBoolean("showOrange", false)) { // something must be visible to draw on
                PREFS.putBoolean("showBlue", true); srcLine.setSelected(true);
            }
            status.setText(drawMode ? " Draw mode: drag in the pitch panel to draw the contour (Shift+drag erases, Ctrl+drag restores the automatic line)." : " Draw mode off.");
            pitch.repaint();
        });
        tools.add(drawTog);
        JButton delBtn = btn("Delete", this::deleteContour);
        delBtn.setToolTipText("Delete the visible pitch contour inside the selected area (Ctrl+Z undoes it)");
        tools.add(delBtn);
        add(tools, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        bind("alt RIGHT", () -> step(1));
        bind("alt LEFT", () -> step(-1));
        bind("ctrl R", this::recordSegment);
        for (String k : new String[]{"ctrl EQUALS", "ctrl PLUS", "ctrl shift EQUALS", "ctrl ADD"}) bind(k, () -> zoom(1 / 1.5));
        for (String k : new String[]{"ctrl MINUS", "ctrl SUBTRACT"}) bind(k, () -> zoom(1.5));

        JMenuBar mb = new JMenuBar();
        JMenu settings = new JMenu("Settings");
        JMenuItem fontItem = new JMenuItem("Subtitle font...");
        fontItem.addActionListener(e -> fontDialog());
        settings.add(fontItem);
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup grp = new ButtonGroup();
        for (String[] t : new String[][]{{"system", "Follow system"}, {"light", "Light"}, {"dark", "Dark"}}) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(t[1], PREFS.get("theme", "system").equals(t[0]));
            it.addActionListener(e -> { PREFS.put("theme", t[0]); refreshTheme(true); });
            grp.add(it); themeMenu.add(it);
        }
        settings.add(themeMenu);
        JCheckBoxMenuItem showPitch = new JCheckBoxMenuItem("Show pitch contour", PREFS.getBoolean("showPitch", true));
        showPitch.addActionListener(e -> { PREFS.putBoolean("showPitch", showPitch.isSelected()); pitch.setVisible(showPitch.isSelected()); });
        settings.add(showPitch);
        JMenu smoothMenu = new JMenu("Spectrogram smoothing");
        ButtonGroup sg = new ButtonGroup();
        double curSigma = PREFS.getDouble("specSigma", 2.5);
        for (Object[] o : new Object[][]{{"Off", 0.0}, {"Gaussian 2.5", 2.5}, {"Gaussian 3.5", 3.5}}) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem((String) o[0], Math.abs(curSigma - (Double) o[1]) < 0.01);
            it.addActionListener(e -> { PREFS.putDouble("specSigma", (Double) o[1]); pitch.repaint(); });
            sg.add(it); smoothMenu.add(it);
        }
        settings.add(smoothMenu);
        JMenuItem silenceItem = new JMenuItem("Silence detection...");
        silenceItem.addActionListener(e -> silenceDialog());
        settings.add(silenceItem);
        pitch.setVisible(showPitch.isSelected());
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New");
        newItem.setToolTipText("A blank screen for a new recording (press the record button to start)");
        newItem.setAccelerator(KeyStroke.getKeyStroke("ctrl N"));
        newItem.addActionListener(e -> newDocument());
        trackBtn = newItem;
        JMenuItem openItem = new JMenuItem("Open...");
        openItem.setAccelerator(KeyStroke.getKeyStroke("ctrl O"));
        openItem.addActionListener(e -> openDialog());
        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.setAccelerator(KeyStroke.getKeyStroke("ctrl S"));
        saveItem.addActionListener(e -> saveAll());
        JMenuItem closeItem = new JMenuItem("Close tab");
        closeItem.setAccelerator(KeyStroke.getKeyStroke("ctrl W"));
        closeItem.addActionListener(e -> closeTab());
        fileMenu.add(newItem); fileMenu.add(openItem); fileMenu.add(saveItem); fileMenu.add(closeItem);
        JMenu sourceMenu = new JMenu("Source"); // the original audio
        srcSpec = new JCheckBoxMenuItem("Spectrogram", PREFS.getBoolean("showSpec", false));
        srcSpec.addActionListener(e -> {
            PREFS.putBoolean("showSpec", srcSpec.isSelected());
            if (srcSpec.isSelected() && spec == null) computeSpectrogram();
            pitch.repaint();
        });
        srcLine = new JCheckBoxMenuItem("Pitch contour", PREFS.getBoolean("showBlue", true));
        srcLine.addActionListener(e -> { PREFS.putBoolean("showBlue", srcLine.isSelected()); syncSliders(); pitch.repaint(); });
        sourceMenu.add(srcSpec); sourceMenu.add(srcLine);
        JMenu shadowMenu = new JMenu("Shadow"); // your recording
        JCheckBoxMenuItem shSpec = new JCheckBoxMenuItem("Spectrogram", PREFS.getBoolean("showShadowSpec", true));
        shSpec.addActionListener(e -> { PREFS.putBoolean("showShadowSpec", shSpec.isSelected()); pitch.repaint(); });
        JCheckBoxMenuItem shLine = new JCheckBoxMenuItem("Pitch contour", PREFS.getBoolean("showOrange", false));
        shLine.addActionListener(e -> { PREFS.putBoolean("showOrange", shLine.isSelected()); syncSliders(); pitch.repaint(); });
        shadowMenu.add(shSpec); shadowMenu.add(shLine);
        mb.add(fileMenu);
        mb.add(sourceMenu);
        mb.add(shadowMenu);
        mb.add(settings);
        setJMenuBar(mb);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { if (recStop != null) recStop.set(true); if (!confirmAllClosing()) return; stopPlay(); saveSrt(); System.exit(0); }
            public void windowActivated(WindowEvent e) { refreshTheme(false); }
        });
        pack();
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        if (getWidth() > scr.width - 40) { setSize(scr.width - 40, getHeight()); validate(); } // the top bar wraps onto a second row
        setLocationRelativeTo(null);
    }

    static final String PLAY = "\u23EF\uFE0E", PAUSE = PLAY, REC = "\u23FA\uFE0E", STOP = "\u23F9\uFE0E", DOT = "\u25CF\uFE0E";
    JButton playBtn;

    JLabel glyphLabel(String t) {
        JLabel l = new JLabel(t); // same font as the other labels...
        Font base = l.getFont();
        if (base.canDisplayUpTo(t) >= 0) l.setFont(new Font(Font.DIALOG, base.getStyle(), base.getSize())); // ...unless it lacks the arrow symbols
        return l;
    }

    JButton textBtn(String text, String tip, Runnable r) {
        JButton b = btn(text, r);
        b.setToolTipText(tip);
        b.setPreferredSize(new Dimension(b.getPreferredSize().width + 8, 38));
        return b;
    }

    JButton symBtn(String sym, String tip, Runnable r) {
        JButton b = btn(sym, r);
        b.setFont(new Font(Font.DIALOG, Font.PLAIN, 22)); // logical font: falls back to whichever installed font has the symbol
        b.setToolTipText(tip);
        b.setPreferredSize(new Dimension(56, 38));
        return b;
    }

    JButton btn(String t, Runnable r) {
        JButton b = new JButton(t); b.setFocusable(false); b.addActionListener(e -> r.run()); return b;
    }

    void bind(String key, Runnable r) {
        JRootPane rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        rp.getActionMap().put(key, new AbstractAction() { public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    long totalMs() { return pcm.length * 1000L / rate; }

    // ---------------------------------------------------------------- settings
    void silenceDialog() {
        JSpinner thr = new JSpinner(new SpinnerNumberModel(PREFS.getInt("silenceDb", -40), -90, -5, 1));
        JSpinner min = new JSpinner(new SpinnerNumberModel(PREFS.getInt("minSilenceMs", 300), 50, 5000, 50));
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Silence threshold (dB):")); p.add(thr);
        p.add(new JLabel("Minimum silence (ms):")); p.add(min);
        JPanel box = new JPanel(new BorderLayout(0, 8));
        box.add(p, BorderLayout.CENTER);
        box.add(new JLabel("<html>Used by <b>Auto-split</b>. Raise the threshold (e.g. -30) if background noise<br>"
                + "stops pauses being detected; lower it (e.g. -50) for quiet speech.<br>"
                + "A longer minimum silence gives longer segments.</html>"), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, box, "Silence detection", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            PREFS.putInt("silenceDb", ((Number) thr.getValue()).intValue());
            PREFS.putInt("minSilenceMs", ((Number) min.getValue()).intValue());
        }
        wave.requestFocusInWindow();
    }

    void fontDialog() {
        Font cur = text.getFont();
        String[] all = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> cjk = new ArrayList<>();
        for (String n : all) if (new Font(n, Font.PLAIN, 12).canDisplay('\u597d')) cjk.add(n);

        JCheckBox only = new JCheckBox("Only show fonts that support Chinese", true);
        JComboBox<String> fam = new JComboBox<>();
        JSpinner size = new JSpinner(new SpinnerNumberModel(cur.getSize(), 8, 120, 1));
        JCheckBox boldBox = new JCheckBox("Bold", cur.isBold());
        JLabel prev = new JLabel("\u4f60\u597d\uff0c\u4e16\u754c  Hello 123  n\u01d0 h\u01ceo", SwingConstants.CENTER);
        Runnable upd = () -> {
            if (fam.getSelectedItem() != null)
                prev.setFont(new Font((String) fam.getSelectedItem(), boldBox.isSelected() ? Font.BOLD : Font.PLAIN, ((Number) size.getValue()).intValue()));
        };
        Runnable fill = () -> {
            Object keep = fam.getSelectedItem() != null ? fam.getSelectedItem() : cur.getFamily();
            fam.removeAllItems();
            for (String n : only.isSelected() ? cjk : Arrays.asList(all)) fam.addItem(n);
            fam.setSelectedItem(keep);
            upd.run();
        };
        fam.addActionListener(e -> upd.run());
        size.addChangeListener(e -> upd.run());
        boldBox.addActionListener(e -> upd.run());
        only.addActionListener(e -> fill.run());
        fill.run();

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Font:")); form.add(fam);
        form.add(new JLabel("Size:")); form.add(size);
        form.add(new JLabel("Style:")); form.add(boldBox);
        JPanel box = new JPanel(new BorderLayout(6, 8));
        box.add(form, BorderLayout.NORTH);
        box.add(only, BorderLayout.CENTER);
        JPanel pv = new JPanel(new BorderLayout());
        pv.setBorder(BorderFactory.createTitledBorder("Preview"));
        pv.setPreferredSize(new Dimension(520, 110));
        pv.add(prev);
        box.add(pv, BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(this, box, "Subtitle font", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && fam.getSelectedItem() != null) {
            String f = (String) fam.getSelectedItem();
            int sz = ((Number) size.getValue()).intValue();
            text.setFont(new Font(f, boldBox.isSelected() ? Font.BOLD : Font.PLAIN, sz));
            PREFS.putBoolean("fontBold", boldBox.isSelected());
            applyTextStyle();
            PREFS.put("fontFamily", f);
            PREFS.putInt("fontSize", sz);
        }
        wave.requestFocusInWindow();
    }

    // ---------------------------------------------------------------- per-region pitch view corrections
    // Saved next to the audio as <name>.piyingxi so the corrections come back next session.
    static final int UNSET = Integer.MIN_VALUE;
    static class Reg {
        long s, e; int lo = UNSET, hi = UNSET, oct = UNSET;
        Reg(long s, long e) { this.s = s; this.e = e; }
    }
    final List<Reg> regs = new ArrayList<>();
    int globalOct;   // octave shift of the original's (blue) contour where no region overrides it
    File regFile;
    boolean syncing;
    RangeSlider pitchSlider; JSlider octSlider; JLabel pitchLbl, octLbl;

    /** The most specific (shortest) region containing ms that has the property set (0 lo, 1 hi, 2 octave); later wins ties. */
    Reg pick(long ms, int prop) {
        Reg best = null;
        for (Reg r : regs) {
            int v = prop == 0 ? r.lo : prop == 1 ? r.hi : r.oct;
            if (v == UNSET || ms < r.s || ms >= r.e) continue;
            if (best == null || r.e - r.s <= best.e - best.s) best = r;
        }
        return best;
    }
    int viewLoAt(long ms) { Reg r = pick(ms, 0); return r != null ? r.lo : PREFS.getInt("viewLo", 50); }
    int viewHiAt(long ms) { Reg r = pick(ms, 1); return r != null ? r.hi : PREFS.getInt("viewHi", 300); }
    int octAt(long ms) { Reg r = pick(ms, 2); return r != null ? r.oct : globalOct; }

    Reg regFor(long s, long e) {
        for (Reg r : regs) if (r.s == s && r.e == e) return r;
        Reg r = new Reg(s, e);
        regs.add(r);
        return r;
    }
    Reg regForSel() { return regFor(sel.s, sel.e); }

    void saveRegs() {
        if (regFile == null) return;
        StringBuilder sb = new StringBuilder("G," + globalOct + "\n");
        int n = 0, runs = 0;
        float[] dr = drawnSrc;
        for (int i = 0; i < dr.length; ) {
            if (dr[i] == 0) { i++; continue; }
            int j = i;
            StringBuilder vals = new StringBuilder();
            while (j < dr.length && dr[j] != 0) { if (j > i) vals.append(' '); vals.append(Math.round(dr[j] * 10) / 10.0); j++; }
            sb.append("D,").append(i).append(',').append(j - i).append(',').append(vals).append('\n');
            runs++; i = j;
        }
        for (Reg r : regs) if (r.lo != UNSET || r.hi != UNSET || r.oct != UNSET) {
            sb.append("R,").append(r.s).append(',').append(r.e).append(',').append(r.lo).append(',').append(r.hi).append(',').append(r.oct).append('\n');
            n++;
        }
        if (n == 0 && runs == 0 && globalOct == 0 && !regFile.exists()) return;
        try { Files.write(regFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ex) { status.setText(" Could not save pitch corrections: " + ex.getMessage()); }
    }

    void loadRegs() {
        regs.clear(); globalOct = 0;
        drawnSrc = new float[(int) (pcm.length * 1000L / rate / 10)];
        if (regFile == null || !regFile.exists()) return;
        try {
            for (String l : Files.readAllLines(regFile.toPath(), StandardCharsets.UTF_8)) {
                String[] p = l.trim().split(",");
                if (p[0].equals("G") && p.length >= 2) globalOct = Integer.parseInt(p[1]);
                else if (p[0].equals("D") && p.length >= 4) {
                    int start = Integer.parseInt(p[1]);
                    String[] vs = p[3].trim().split(" ");
                    for (int k = 0; k < vs.length && start + k < drawnSrc.length; k++) drawnSrc[start + k] = Float.parseFloat(vs[k]);
                }
                else if (p[0].equals("R") && p.length >= 6) {
                    Reg r = new Reg(Long.parseLong(p[1]), Long.parseLong(p[2]));
                    r.lo = Integer.parseInt(p[3]); r.hi = Integer.parseInt(p[4]); r.oct = Integer.parseInt(p[5]);
                    regs.add(r);
                }
            }
        } catch (Exception ignored) { }
    }

    /** Make the two sliders show the values in force for the current selection (or the whole audio if nothing is selected). */
    void syncLenSlider() {
        if (lenSlider == null) return;
        syncing = true;
        int v = lastRec != null ? (int) Math.round(lastRec.scale * 100) : 100;
        lenSlider.setValue(Math.max(50, Math.min(200, v)));
        lenLbl.setText(lenSlider.getValue() + "%");
        syncing = false;
    }

    /** Move-shadow keys: left/right shift it in time, up/down in pitch. */
    void nudgeShadow(int dMs, int dHz) {
        recShift = Math.max(-3000, Math.min(3000, recShift + dMs));
        recHz = Math.max(-400, Math.min(400, recHz + dHz));
        PREFS.putInt("shadowShift", recShift);
        PREFS.putInt("shadowHz5", recHz);
        pitch.repaint();
    }

    AbstractAction nudgeAction(int dMs, int dHz) {
        return new AbstractAction() { public void actionPerformed(ActionEvent e) { if (moveMode) nudgeShadow(dMs, dHz); } };
    }

    void syncSliders() {
        if (pitchSlider == null) return;
        syncing = true;
        long mid = sel != null ? (sel.s + sel.e) / 2 : -1;
        int lo = sel != null ? viewLoAt(mid) : PREFS.getInt("viewLo", 50);
        int hi = sel != null ? viewHiAt(mid) : PREFS.getInt("viewHi", 300);
        pitchSlider.lo = lo; pitchSlider.hi = hi; pitchSlider.repaint();
        pitchLbl.setText(lo + "\u2013" + hi + " Hz");
        boolean orangeOnly = PREFS.getBoolean("showOrange", false) && !PREFS.getBoolean("showBlue", true);
        int oc;
        if (orangeOnly) {
            Seg t = range != null ? range : sel;
            Rec r = t != null ? recUnder(t) : lastRec;
            oc = r == null ? 0 : (t != null ? recOctAt(r, (t.s + t.e) / 2) : r.globalOct);
        } else oc = range != null ? octAt((range.s + range.e) / 2) : sel != null ? octAt(mid) : globalOct;
        octSlider.setValue(Math.max(-1, Math.min(1, oc)));
        octLbl.setText(oc > 0 ? "+" + oc : String.valueOf(oc));
        syncing = false;
    }

    // ---------------------------------------------------------------- pitch (F0)
    static class Spec {
        final byte[] d; final int cols, bins; final double binHz;
        double maxDb; // loudest level this spectrogram was scaled against
        Spec(byte[] d, int cols, int bins, double binHz) { this.d = d; this.cols = cols; this.bins = bins; this.binHz = binHz; }
    }
    volatile Spec spec;
    volatile int specGen;

    void computeSpectrogram() {
        final int gen = ++specGen;
        final short[] src = pcm; final int sr = rate;
        spec = null; pitch.repaint();
        if (src.length == 0 || !PREFS.getBoolean("showSpec", false)) return;
        if (src.length / sr > 1800) { status.setText(" Spectrogram skipped: audio is longer than 30 minutes."); return; }
        new Thread(() -> {
            Spec r = spectrogram(src, sr, gen);
            if (r != null) SwingUtilities.invokeLater(() -> { if (gen == specGen) { spec = r; pitch.repaint(); } });
        }).start();
    }

    /** Narrow-band spectrum of the low frequencies only (0-520 Hz, the range of the pitch view):
     *  50 ms Hann window, one column per 10 ms, 45 dB range. The bright ridge follows F0. */
    Spec spectrogram(short[] src, int sr, int gen) { return spectrogram(src, sr, gen, Double.NaN); }

    /** fixedMaxDb: scale against this loudness instead of the loudest part of src (NaN = find it). */
    Spec spectrogram(short[] src, int sr, int gen, double fixedMaxDb) {
        int fac = Math.max(1, (int) Math.round(sr / 4000.0));
        int n = src.length / fac;
        double sr2 = (double) sr / fac;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) { double sum = 0; for (int k = 0; k < fac; k++) sum += src[i * fac + k]; x[i] = sum / fac; }
        double hp = Math.exp(-2 * Math.PI * 30 / sr2); // remove DC and very low rumble
        for (int ps = 0; ps < 2; ps++) {
            double px = 0, py = 0;
            for (int i = 0; i < n; i++) { double xi = x[i], yi = hp * (py + xi - px); px = xi; py = yi; x[i] = yi; }
        }
        int win = (int) Math.round(sr2 * 0.05), hop = (int) Math.round(sr2 * 0.01);
        int nfft = 1; while (nfft < win * 2) nfft <<= 1;
        int cols = (int) (src.length * 1000L / sr / 10);
        double binHz = sr2 / nfft;
        int bins = (int) Math.min(nfft / 2, Math.floor(520 / binHz)) + 1;
        double[] w = new double[win];
        for (int i = 0; i < win; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (win - 1));
        double[] re = new double[nfft], im = new double[nfft];
        byte[] out = new byte[cols * bins];
        double maxDb = -1e9;
        for (int pass = 0; pass < 2; pass++) { // pass 0 finds the loudest level (sampled), pass 1 fills the image
            if (pass == 0 && !Double.isNaN(fixedMaxDb)) { maxDb = fixedMaxDb; continue; }
            for (int c = 0; c < cols; c += pass == 0 ? 8 : 1) {
                if ((c & 255) == 0 && gen != specGen) return null;
                int start = (int) Math.round(c * sr2 / 100.0 - (fac - 1) / (2.0 * fac)) - win / 2; // column c is centred on exactly c x 10 ms
                Arrays.fill(re, 0); Arrays.fill(im, 0);
                for (int i = 0; i < win; i++) { int q = start + i; if (q >= 0 && q < n) re[i] = x[q] * w[i]; }
                fft(re, im);
                for (int b = 0; b < bins; b++) {
                    double db = 10 * Math.log10(re[b] * re[b] + im[b] * im[b] + 1e-6);
                    if (pass == 0) maxDb = Math.max(maxDb, db);
                    else out[c * bins + b] = (byte) Math.round(Math.max(0, Math.min(1, (db - (maxDb - 45)) / 45)) * 255);
                }
            }
        }
        Spec res = new Spec(out, cols, bins, binHz);
        res.maxDb = maxDb;
        return res;
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    double ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }

    void computePitch() {
        final int gen = ++pitchGen;
        final short[] src = pcm; final int sr = rate;
        final double floor = ANALYSIS_LO, ceil = ANALYSIS_HI;
        f0 = new float[0]; pitch.repaint();
        if (src.length == 0) return;
        new Thread(() -> {
            float[] out = pitchTrack(src, sr, floor, ceil, gen);
            if (out != null) SwingUtilities.invokeLater(() -> { if (gen == pitchGen) { f0 = smooth(out); pitch.repaint(); } });
        }).start();
    }

    /** Octave-error repair, then per voiced run a 5-point median and a weighted average (all in the log-frequency domain). */
    static float[] smooth(float[] f) {
        final double LN2 = Math.log(2);
        List<Double> all = new ArrayList<>();
        for (float v : f) if (v > 0) all.add(Math.log(v) / LN2);
        if (all.isEmpty()) return new float[f.length];
        double[] allv = new double[all.size()];
        for (int q = 0; q < allv.length; q++) allv[q] = all.get(q);
        double gmed = median(allv, 0, allv.length - 1);
        float[] out = new float[f.length];
        int i = 0;
        while (i < f.length) {
            if (f[i] <= 0) { i++; continue; }
            int j = i;
            while (j < f.length && f[j] > 0) j++;
            int n = j - i;
            if (n >= 3) {
                double[] v = new double[n];
                for (int k = 0; k < n; k++) v[k] = Math.log(f[i + k]) / LN2;
                // 1) a whole run about an octave away from the speaker's typical pitch is almost certainly an octave error
                double gd = median(v, 0, n - 1) - gmed;
                if (Math.abs(gd) > 0.6) { long sh = Math.round(gd); for (int k = 0; k < n; k++) v[k] -= sh; }
                // 2) single frames that jump an octave relative to their neighbourhood
                double[] u = v.clone();
                for (int k = 0; k < n; k++) {
                    double dev = v[k] - median(v, Math.max(0, k - 10), Math.min(n - 1, k + 10));
                    if (Math.abs(dev) > 0.6) { long sh = Math.round(dev); if (Math.abs(dev - sh) < 0.35) u[k] = v[k] - sh; }
                }
                // 3) median of 5, then a weighted average
                double[] m = new double[n];
                for (int k = 0; k < n; k++) m[k] = median(u, Math.max(0, k - 2), Math.min(n - 1, k + 2));
                for (int k = 0; k < n; k++) {
                    double sum = 0, wt = 0;
                    for (int d = -2; d <= 2; d++) {
                        int q = k + d;
                        if (q < 0 || q >= n) continue;
                        double w = 3 - Math.abs(d);
                        sum += w * m[q]; wt += w;
                    }
                    out[i + k] = (float) Math.pow(2, sum / wt);
                }
            }
            i = j;
        }
        return out;
    }

    static double median(double[] a, int lo, int hi) {
        double[] w = Arrays.copyOfRange(a, lo, hi + 1);
        Arrays.sort(w);
        return w[w.length / 2];
    }

    /** Normalised-autocorrelation pitch candidates + Viterbi smoothing (Praat-style). One value per 10 ms; 0 = unvoiced. */
    float[] pitchTrack(short[] src, int sr, double floor, double ceil, int gen) {
        int fac = Math.max(1, (int) Math.round(sr / 10000.0));
        int n = src.length / fac;
        double sr2 = (double) sr / fac, peak = 1;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0; for (int k = 0; k < fac; k++) s += src[i * fac + k];
            x[i] = s / fac; peak = Math.max(peak, Math.abs(x[i]));
        }
        // high-pass (~60 Hz, applied twice): removes DC, rumble and mains hum that confuse the autocorrelation
        double hp = Math.exp(-2 * Math.PI * 60 / sr2);
        for (int pass = 0; pass < 2; pass++) {
            double px = 0, py = 0;
            for (int i = 0; i < n; i++) { double xi = x[i], yi = hp * (py + xi - px); px = xi; py = yi; x[i] = yi; }
        }
        peak = 1;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(x[i]));
        int hop = (int) Math.round(sr2 * 0.01), N = (int) Math.round(sr2 * 0.04);
        int minLag = Math.max(2, (int) Math.floor(sr2 / ceil)), maxLag = (int) Math.ceil(sr2 / floor);
        int frames = (int) (src.length * 1000L / sr / 10);
        double[][] cf = new double[frames][], cs = new double[frames][];
        double[] seg = new double[N + maxLag + 2], e = new double[maxLag + 2], r = new double[maxLag + 2];
        for (int i = 0; i < frames; i++) {
            if ((i & 255) == 0 && gen != pitchGen) return null;
            cf[i] = new double[0]; cs[i] = new double[0];
            int start = (int) Math.round(i * sr2 / 100.0 - (fac - 1) / (2.0 * fac)) - N / 2 - maxLag / 4; // frame i at exactly i x 10 ms
            if (start < 0 || start + seg.length > n) continue;
            double mean = 0, lp = 0;
            for (int j = 0; j < seg.length; j++) mean += x[start + j];
            mean /= seg.length;
            for (int j = 0; j < seg.length; j++) { seg[j] = x[start + j] - mean; lp = Math.max(lp, Math.abs(seg[j])); }
            if (lp < 0.03 * peak) continue; // silence
            double e0 = 0; for (int j = 0; j < N; j++) e0 += seg[j] * seg[j];
            if (e0 <= 0) continue;
            double ee = e0; e[0] = e0;
            for (int lag = 0; lag <= maxLag; lag++) { ee += seg[lag + N] * seg[lag + N] - seg[lag] * seg[lag]; e[lag + 1] = ee; }
            for (int lag = minLag - 1; lag <= maxLag + 1; lag++) {
                double c = 0; for (int j = 0; j < N; j++) c += seg[j] * seg[j + lag];
                r[lag] = c / Math.sqrt(e0 * e[lag] + 1e-9);
            }
            List<double[]> cands = new ArrayList<>();
            for (int l = minLag; l <= maxLag; l++) {
                if (r[l] > 0.4 && r[l] >= r[l - 1] && r[l] > r[l + 1]) {
                    double den = r[l - 1] - 2 * r[l] + r[l + 1];
                    double d = den < 0 ? 0.5 * (r[l - 1] - r[l + 1]) / den : 0;
                    cands.add(new double[]{sr2 / (l + d), Math.min(1, r[l])});
                }
            }
            cands.sort((a, b) -> Double.compare(b[1], a[1]));
            int m = Math.min(6, cands.size());
            cf[i] = new double[m]; cs[i] = new double[m];
            for (int k = 0; k < m; k++) { cf[i][k] = cands.get(k)[0]; cs[i][k] = cands.get(k)[1]; }
        }
        final double VT = 0.45, OJ = 0.6, VUV = 0.14, LN2 = Math.log(2);
        double[][] sc = new double[frames][]; int[][] bk = new int[frames][];
        for (int i = 0; i < frames; i++) {
            int m = cf[i].length;
            sc[i] = new double[m + 1]; bk[i] = new int[m + 1];
            for (int j = 0; j <= m; j++) {
                double local = j < m ? cs[i][j] + 0.05 * Math.log(cf[i][j] / floor) / LN2 : VT;
                if (i == 0) { sc[i][j] = local; continue; }
                int pm = cf[i - 1].length; double best = -1e18; int bj = 0;
                for (int k = 0; k <= pm; k++) {
                    double tr;
                    if (j < m && k < pm) tr = OJ * Math.abs(Math.log(cf[i][j] / cf[i - 1][k]) / LN2);
                    else if (j == m && k == pm) tr = 0;
                    else tr = VUV;
                    double v = sc[i - 1][k] - tr;
                    if (v > best) { best = v; bj = k; }
                }
                sc[i][j] = best + local; bk[i][j] = bj;
            }
        }
        float[] out = new float[frames];
        if (frames == 0) return out;
        int j = 0;
        for (int k = 1; k < sc[frames - 1].length; k++) if (sc[frames - 1][k] > sc[frames - 1][j]) j = k;
        for (int i = frames - 1; i >= 0; i--) { out[i] = j < cf[i].length ? (float) cf[i][j] : 0; j = bk[i][j]; }
        return out;
    }

    // ---------------------------------------------------------------- loading
    void openDialog() {
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setFileFilter(new FileNameExtensionFilter("Audio (mp3, wav)", "mp3", "wav"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) open(fc.getSelectedFile());
    }

    void open(File f) {
        if (recording) { status.setText(" Finish recording first."); return; }
        if (!confirmDiscardUnsaved()) return;
        stopPlay(); saveSrt();
        final long resume = f.getAbsolutePath().equals(PREFS.get("lastFile", "")) ? PREFS.getLong("lastSegMs", -1) : -1;
        PREFS.put("lastFile", f.getAbsolutePath());
        if (resume < 0) PREFS.putLong("lastSegMs", -1);
        if (f.getAbsoluteFile().getParent() != null) PREFS.put("lastDir", f.getAbsoluteFile().getParent());
        status.setText(" Decoding " + f.getName() + " ...");
        final Doc target = curDoc;
        new Thread(() -> {
            try {
                decode(f);
                SwingUtilities.invokeLater(() -> {
                    if (curDoc != target) switchTo(target); // you changed tab while it was loading
                    unsaved = false; blankNew = false; liveNew = false;
                    String n = f.getName();
                    setTabName(curDoc, n);
                    int dot = n.lastIndexOf('.');
                    srt = new File(f.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".srt");
                    regFile = new File(f.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".piyingxi");
                    loadRegs();
                    segs.clear(); undoStack.clear(); select(null);
                    if (srt.exists()) loadSrt();
                    wave.spp = defaultSpp(); // 600 px per second (less for very long files)
                    wave.updateSize();
                    computePitch();
                    computeSpectrogram();
                    recs.clear(); lastRec = null;
                    sp.getHorizontalScrollBar().setValue(0);
                    setTitle("Piyingxi - " + n);
                    status.setText(" " + n + "  |  " + segs.size() + " subtitles loaded from " + srt.getName());
                    if (resume >= 0) SwingUtilities.invokeLater(() -> { for (Seg g : segs) if (g.s == resume) { select(g); break; } });
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Could not read file: " + ex));
            }
        }).start();
    }

    void decodeWav(File f) throws Exception {
        try (AudioInputStream in0 = AudioSystem.getAudioInputStream(f)) {
            AudioFormat base = in0.getFormat();
            int ch = base.getChannels();
            AudioFormat want = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16, ch, ch * 2, base.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(want, in0)) {
                byte[] all = in.readAllBytes();
                short[] out = new short[all.length / (2 * ch)];
                for (int i = 0; i < out.length; i++) {
                    int sum = 0;
                    for (int c = 0; c < ch; c++) { int k = (i * ch + c) * 2; sum += (short) ((all[k + 1] << 8) | (all[k] & 0xff)); }
                    out[i] = (short) (sum / ch);
                }
                rate = (int) base.getSampleRate();
                pcm = out;
            }
        }
    }

    void decode(File f) throws Exception {
        if (f.getName().toLowerCase().endsWith(".wav")) { decodeWav(f); return; }
        short[] out = new short[1 << 20]; int n = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            Bitstream bs = new Bitstream(in);
            Decoder dec = new Decoder();
            Header h;
            int sr = 44100;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                short[] b = sb.getBuffer(); int len = sb.getBufferLength(), ch = sb.getChannelCount();
                sr = sb.getSampleFrequency();
                for (int i = 0; i + ch <= len; i += ch) {
                    int s = 0; for (int c = 0; c < ch; c++) s += b[i + c];
                    if (n == out.length) out = Arrays.copyOf(out, n * 2);
                    out[n++] = (short) (s / ch);
                }
                bs.closeFrame();
            }
            rate = sr;
        }
        pcm = Arrays.copyOf(out, n);
    }

    // ---------------------------------------------------------------- SRT
    static String fmt(long ms) {
        return String.format("%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    static long parseTime(String t) {
        String[] p = t.trim().split("[:,.]");
        return ((Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 60 + Long.parseLong(p[2])) * 1000 + Long.parseLong(p[3]);
    }

    void saveSrt() {
        if (srt == null) return;
        saveTimer.stop();
        List<Seg> ok = new ArrayList<>();
        for (Seg s : segs) if (!s.text.isBlank()) ok.add(s);
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Seg s : ok) {
            sb.append(i++).append("\r\n").append(fmt(s.s)).append(" --> ").append(fmt(s.e)).append("\r\n")
              .append(s.text.trim().replaceAll("\\R{2,}", "\n").replace("\n", "\r\n")).append("\r\n\r\n");
        }
        try {
            Files.write(srt.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            status.setText(" Saved " + ok.size() + " subtitles to " + srt.getName());
        } catch (IOException ex) { status.setText(" Save failed: " + ex.getMessage()); }
    }

    void loadSrt() {
        try {
            List<String> lines = Files.readAllLines(srt.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).replace("\uFEFF", "");
                if (!l.contains("-->")) continue;
                String[] t = l.split("-->");
                Seg s = new Seg(parseTime(t[0]), parseTime(t[1]));
                StringBuilder tx = new StringBuilder();
                while (++i < lines.size() && !lines.get(i).trim().isEmpty()) {
                    if (tx.length() > 0) tx.append("\n");
                    tx.append(lines.get(i));
                }
                s.text = tx.toString();
                segs.add(s);
            }
            sortSegs();
        } catch (Exception ex) { status.setText(" Could not parse SRT: " + ex.getMessage()); }
    }

    // ---------------------------------------------------------------- segments
    void sortSegs() { segs.sort(Comparator.comparingLong(s -> s.s)); }

    void select(Seg s) {
        sel = s;
        range = null; cursorMs = -1;
        if (s != null) PREFS.putLong("lastSegMs", s.s);
        updating = true;
        text.setText(s == null ? "" : s.text);
        text.getStyledDocument().setParagraphAttributes(0, text.getDocument().getLength() + 1, CENTER, false);
        text.setEnabled(s != null);
        updating = false;
        boolean typing = text.isFocusOwner(); // keep the caret only if the user is already typing
        if (s != null) {
            Rectangle r = new Rectangle((int) wave.msToX(s.s) - 40, 0, (int) (wave.msToX(s.e) - wave.msToX(s.s)) + 80, 1);
            wave.scrollRectToVisible(r);
        }
        if (s == null || !typing) wave.requestFocusInWindow();
        wave.repaint(); pitch.repaint(); selOverlay.repaint();
        syncSliders();
    }

    void step(int d) {
        if (segs.isEmpty()) return;
        int i = sel == null ? (d > 0 ? -1 : segs.size()) : segs.indexOf(sel);
        i = Math.max(0, Math.min(segs.size() - 1, i + d));
        select(segs.get(i));
        zoomToSeg(sel, false); // fill the window with the new segment
    }

    void deleteSel() {
        if (sel == null) return;
        pushUndo(); segs.remove(sel); select(null); saveSrt();
    }

    /** Delete button (and the Delete key in draw mode): remove the visible contour line(s) inside the selected range / segment. */
    void deleteContour() {
        Seg t = range != null ? range : sel;
        if (t == null) { status.setText(" Select an area first (drag a range or click a segment), then press Delete."); return; }
        boolean showSrc = PREFS.getBoolean("showBlue", true), showShadow = PREFS.getBoolean("showOrange", false);
        if (!showSrc && !showShadow) { status.setText(" Turn on a pitch contour (Source or Shadow menu) first."); return; }
        int cleared = 0;
        if (showSrc) {
            int n = (int) (pcm.length * 1000L / rate / 10);
            if (n > 0) {
                float[] arr = drawnSrc;
                if (arr.length != n) { arr = new float[n]; drawnSrc = arr; }
                pushUndoItem(new DrawUndo(null, arr.clone()));
                int a = (int) Math.max(0, Math.ceil(t.s / 10.0)), b = (int) Math.min(n - 1, Math.floor(t.e / 10.0));
                for (int k = a; k <= b; k++) arr[k] = -1; // -1 = a gap that overrides the automatic contour too
                cleared++;
            }
        }
        if (showShadow) for (Rec r : recs) {
            int n = r.f0.length;
            if (n == 0 || !overlaps(r, t)) continue;
            float[] arr = r.drawn;
            if (arr.length != n) { arr = new float[n]; r.drawn = arr; }
            pushUndoItem(new DrawUndo(r, arr.clone()));
            long base = recFrom(r);
            int a = (int) Math.max(0, Math.ceil((t.s - base) / (10.0 * r.scale))), b = (int) Math.min(n - 1, Math.floor((t.e - base) / (10.0 * r.scale)));
            for (int k = a; k <= b; k++) arr[k] = -1;
            cleared++;
        }
        if (cleared > 0) { if (showSrc) saveRegs(); status.setText(" Deleted the contour in the selection (Ctrl+Z undoes it)."); }
        pitch.repaint();
    }

    void autoSplit() {
        if (pcm.length == 0) return;
        double thr = PREFS.getInt("silenceDb", -40);
        int minSilWin = PREFS.getInt("minSilenceMs", 300) / 10;
        int win = rate / 100, n = pcm.length / win;
        boolean[] snd = new boolean[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = i * win; j < (i + 1) * win; j++) sum += (double) pcm[j] * pcm[j];
            double db = 20 * Math.log10(Math.sqrt(sum / win) / 32768.0 + 1e-9);
            snd[i] = db > thr;
        }
        pushUndo();
        segs.removeIf(s -> s.text.isBlank()); // keep what you've already transcribed
        List<Seg> found = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (!snd[i]) { i++; continue; }
            int st = i, last = i, j = i;
            while (j < n) {
                if (snd[j]) last = j; else if (j - last >= minSilWin) break;
                j++;
            }
            long s = Math.max(0, st * 10L - 50), e = Math.min(totalMs(), (last + 1) * 10L + 50);
            if (e - s >= 150) found.add(new Seg(s, e));
            i = j;
        }
        int added = 0;
        for (Seg f : found) {
            boolean overlap = false;
            for (Seg o : segs) if (f.s < o.e && f.e > o.s) { overlap = true; break; }
            if (!overlap) { segs.add(f); added++; }
        }
        sortSegs(); select(null);
        status.setText(" Auto-split added " + added + " segments (" + segs.size() + " total).");
    }

    // ---------------------------------------------------------------- playback
    boolean isPlaying() { return playThread != null && playThread.isAlive(); }

    Seg range;            // temporary sub-selection (drag inside a segment), used for playing part of a segment
    long cursorMs = -1;   // last click / pause position inside the selected segment
    static final long MIN_PIECE = 20, MIN_SEL = 20; // shortest piece / selection, in ms

    /** S: turn the dragged range into its own segment, or split the selected segment at the cursor. */
    void splitAction() {
        if (pcm.length == 0) return;
        if (range != null) { carve(range.s, range.e); return; }
        if (sel == null) { status.setText(" Click inside a segment (or drag a range), then press S to split."); return; }
        long at = isPlaying() && playingOriginal && playMs >= 0 ? playMs : cursorMs;
        if (at <= sel.s + MIN_PIECE || at >= sel.e - MIN_PIECE) {
            status.setText(" Click inside the segment where you want to split (not at an edge), then press S.");
            return;
        }
        pushUndo();
        Seg right = new Seg(at, sel.e);
        sel.e = at;
        segs.add(right); sortSegs();
        select(right); saveSrt();
        status.setText(" Split the segment at " + fmt(at) + ".");
    }

    /** Make [rs, re) a segment of its own, trimming, splitting or merging whatever it overlaps. */
    void carve(long rs, long re) {
        stopPlay();
        pushUndo();
        StringBuilder merged = new StringBuilder();
        List<Seg> add = new ArrayList<>();
        for (Iterator<Seg> it = segs.iterator(); it.hasNext(); ) {
            Seg o = it.next();
            if (o.s >= re || o.e <= rs) continue;
            boolean left = rs - o.s >= MIN_PIECE, right = o.e - re >= MIN_PIECE;
            long oe = o.e;
            if (left && right) { o.e = rs; add.add(new Seg(re, oe)); }
            else if (left) o.e = rs;
            else if (right) o.s = re;
            else {
                if (!o.text.isBlank()) { if (merged.length() > 0) merged.append(' '); merged.append(o.text.trim()); }
                it.remove();
            }
        }
        Seg n = new Seg(rs, re);
        n.text = merged.toString();
        segs.addAll(add); segs.add(n); sortSegs();
        select(n); saveSrt();
        status.setText(String.format(" New segment %.2f s.", (re - rs) / 1000.0));
    }

    // ---- undo (structural edits: split, new/removed segments, edge drags, auto-split)
    static class Snap {
        final Seg seg; final long s, e; final String text;
        Snap(Seg g) { seg = g; s = g.s; e = g.e; text = g.text; }
    }
    /** One shadow recording, tied to the part of the audio it shadows. Only the newest one per segment is kept. */
    static class Rec {
        short[] pcm; long s, e;          // the segment / range it shadows
        long startMs, lenMs;             // start, aligned to the original's timeline; length
        double scale = 1.0;              // time stretch (the "Shadow length" slider)
        volatile float[] f0 = new float[0];
        volatile float[] drawn = new float[0]; // hand-drawn values (>0 Hz, -1 = gap, 0 = automatic)
        volatile Spec spec;
        final List<Reg> regs = new ArrayList<>(); // octave corrections for parts of this recording
        int globalOct;
    }
    final List<Rec> recs = new ArrayList<>();
    Rec lastRec;
    // Live view for File > New: the recording is shown, analysed and drawn while it grows (like Audacity)
    volatile boolean liveNew, liveDocReady;
    boolean unsaved, blankNew; // unsaved: the audio on screen is a recording with no file yet; blankNew: File > New, waiting for the first recording
    short[] liveBuf = new short[0]; int liveLen;                                  // recording thread only
    float[] liveRaw = new float[0]; double liveMaxDb = -1e9; Spec liveSpecObj;    // recording thread only
    volatile Spec liveSpecPub; volatile short[] livePcmPub; volatile float[] liveF0Pub; // latest results, picked up by the screen
    byte[] liveSpecBuf = new byte[0]; int liveSpecBins; long lastPcmPub;           // recording thread only
    final java.util.concurrent.atomic.AtomicBoolean livePosting = new java.util.concurrent.atomic.AtomicBoolean(false);

    long recFrom(Rec r) { return r.startMs + recShift; }
    long recTo(Rec r) { return recFrom(r) + (long) (r.lenMs * r.scale); }
    boolean overlaps(Rec r, Seg t) { return t.s < recTo(r) && t.e > recFrom(r); }
    Rec recAt(long ms) {
        for (int i = recs.size() - 1; i >= 0; i--) { Rec r = recs.get(i); if (ms >= recFrom(r) && ms < recTo(r)) return r; }
        return null;
    }
    Rec recUnder(Seg t) { // the recording overlapping t the most
        Rec best = null; long bo = 0;
        for (Rec r : recs) { long o = Math.min(t.e, recTo(r)) - Math.max(t.s, recFrom(r)); if (o > bo) { bo = o; best = r; } }
        return best;
    }
    boolean sameSeg(Rec o, Seg seg) {
        long ov = Math.min(o.e, seg.e) - Math.max(o.s, seg.s);
        return ov > 0.5 * Math.min(o.e - o.s, seg.e - seg.s);
    }
    int recOctAt(Rec r, long ms) {
        Reg best = null;
        for (Reg g : r.regs) if (g.oct != UNSET && ms >= g.s && ms < g.e && (best == null || g.e - g.s <= best.e - best.s)) best = g;
        return best != null ? best.oct : r.globalOct;
    }
    Reg regForRec(Rec r, long s, long e) {
        for (Reg g : r.regs) if (g.s == s && g.e == e) return g;
        Reg g = new Reg(s, e);
        r.regs.add(g);
        return g;
    }

    /** One undone step of hand-drawing: the drawn array as it was before the stroke / deletion (rec == null: the source contour). */
    static class DrawUndo {
        final Rec rec; final float[] before;
        DrawUndo(Rec rec, float[] before) { this.rec = rec; this.before = before; }
    }
    final java.util.ArrayDeque<Object> undoStack = new java.util.ArrayDeque<>(); // List<Snap> (segment edits) or DrawUndo, newest first
    void pushUndoItem(Object o) { undoStack.push(o); while (undoStack.size() > 50) undoStack.removeLast(); }

    List<Snap> snapshot() { List<Snap> l = new ArrayList<>(); for (Seg g : segs) l.add(new Snap(g)); return l; }
    void pushUndo(List<Snap> snap) { undoStack.push(snap); while (undoStack.size() > 50) undoStack.removeLast(); }
    void pushUndo() { pushUndo(snapshot()); }

    void undo() {
        stopPlay();
        Object top = null;
        while (!undoStack.isEmpty()) {
            Object o = undoStack.pop();
            if (o instanceof DrawUndo du && du.rec != null && !recs.contains(du.rec)) continue; // that recording has since been replaced
            top = o;
            break;
        }
        if (top == null) { status.setText(" Nothing to undo."); return; }
        if (top instanceof DrawUndo du) {
            if (du.rec != null) du.rec.drawn = du.before;
            else { drawnSrc = du.before; saveRegs(); }
            pitch.repaint();
            status.setText(" Undid the last drawing change.");
            return;
        }
        @SuppressWarnings("unchecked") List<Snap> snap = (List<Snap>) top;
        List<Seg> current = new ArrayList<>(segs);
        Seg keep = sel;
        segs.clear();
        for (Snap n : snap) {
            n.seg.s = n.s; n.seg.e = n.e;
            if (!current.contains(n.seg)) n.seg.text = n.text; // segments that were removed get their old text back
            segs.add(n.seg);                                   // surviving ones keep whatever you've typed since
        }
        sortSegs();
        select(segs.contains(keep) ? keep : null);
        saveSrt();
        status.setText(" Undid the last change.");
    }

    /** Tab: play from the dotted cursor to the end of the segment (or behave like play/pause if there is no cursor). */
    void playFromCursor() {
        if (recording || pcm.length == 0) return;
        if (range == null && sel != null && cursorMs > sel.s && cursorMs < sel.e) {
            final Seg part = new Seg(cursorMs, sel.e);
            resumeMs = -1;
            boolean looping = loop.isSelected();
            boolean auto = PREFS.getBoolean("autoRecord", false) && !looping;
            play(part.s, part.e, looping, auto ? () -> { if (!recording) startRecording(1, part); } : null);
        } else togglePlay();
    }

    long resumeMs = -1; Seg resumeSeg; volatile boolean playingOriginal;

    void togglePlay() {
        if (recording || pcm.length == 0) return;
        if (isPlaying()) { // pause: remember where we were
            if (playingOriginal && playMs >= 0) { resumeMs = playMs; cursorMs = playMs; resumeSeg = range != null ? range : sel; } else resumeMs = -1;
            stopPlay();
            return;
        }
        boolean looping = loop.isSelected();
        final Seg t = range != null ? range : sel;
        if (t != null) {
            long start = (!looping && resumeSeg == t && resumeMs >= t.s && resumeMs < t.e - 100) ? resumeMs : t.s;
            resumeMs = -1;
            boolean auto = PREFS.getBoolean("autoRecord", false) && !looping;
            play(start, t.e, looping, auto ? () -> { if (!recording) startRecording(1, t); } : null);
        } else {
            long start = (resumeSeg == null && resumeMs >= 0 && resumeMs < totalMs() - 100) ? resumeMs : 0;
            resumeMs = -1;
            play(start, totalMs(), false, null);
        }
    }

    void stopPlay() {
        if (curStop != null) curStop.set(true);
        SourceDataLine l = line;
        if (l != null) l.flush();
        Thread t = playThread;
        if (t != null) try { t.join(500); } catch (InterruptedException ignored) { }
        playTimer.stop(); playMs = -1; wave.repaint();
        if (playBtn != null) playBtn.setText(PLAY);
    }

    volatile int playRate = 44100;
    volatile long playOffsetMs;

    void play(long fromMs, long toMs, boolean looping, Runnable onDone) {
        startPlay(pcm, rate, (int) (fromMs * rate / 1000), (int) Math.min(pcm.length, toMs * rate / 1000), looping, 0, onDone);
    }

    /** Plays data[a..b) at sample rate sr; offsetMs places the playhead on the original's timeline. */
    void startPlay(short[] data, int sr, int a, int b, boolean looping, long offsetMs, Runnable onDone) {
        stopPlay();
        final AtomicBoolean stop = new AtomicBoolean(false);
        curStop = stop; playRate = sr; playOffsetMs = offsetMs; playingOriginal = (data == pcm);
        playThread = new Thread(() -> {
            try {
                AudioFormat f = new AudioFormat(sr, 16, 1, true, false);
                byte[] buf = new byte[4096];
                do {
                    SourceDataLine l = AudioSystem.getSourceDataLine(f);
                    l.open(f);
                    playBase = a; line = l;
                    l.start();
                    for (int i = a; i < b && !stop.get(); ) {
                        int n = Math.min(2048, b - i);
                        for (int j = 0; j < n; j++) { short v = data[i + j]; buf[2 * j] = (byte) v; buf[2 * j + 1] = (byte) (v >> 8); }
                        l.write(buf, 0, n * 2);
                        i += n;
                    }
                    if (!stop.get()) l.drain();
                    line = null;
                    l.close();
                } while (looping && !stop.get());
                if (!stop.get() && onDone != null) SwingUtilities.invokeLater(onDone); // finished naturally
            } catch (Exception ex) { ex.printStackTrace(); }
            line = null;
        });
        playThread.start();
        playTimer.start();
        playBtn.setText(PAUSE);
    }

    void tick() {
        SourceDataLine l = line;
        if (l != null) {
            playMs = playOffsetMs + (playBase + l.getLongFramePosition()) * 1000L / playRate;
        } else if (!isPlaying()) { playTimer.stop(); playMs = -1; playBtn.setText(PLAY); resumeMs = -1; }
        wave.repaint();
    }

    double prevSpp = -1;

    void zoomSelection() {
        Seg t = range != null ? range : sel;
        if (t == null) { status.setText(" Select a segment (or drag a range) first."); return; }
        zoomToSeg(t);
    }

    /** Mouse wheel: scrolls left/right; Ctrl + wheel zooms. */
    void handleWheel(MouseWheelEvent e) {
        if (e.isControlDown()) zoom(e.getWheelRotation() > 0 ? 1.25 : 1 / 1.25);
        else sp.getHorizontalScrollBar().setValue(sp.getHorizontalScrollBar().getValue() + e.getWheelRotation() * 60);
    }

    /** A: zoom out so the whole file fits the window. */
    void zoomAll() {
        if (pcm.length == 0) return;
        JViewport vp = sp.getViewport();
        wave.spp = Math.max(2, pcm.length / (double) Math.max(100, vp.getWidth()));
        prevSpp = -1;
        wave.updateSize();
        SwingUtilities.invokeLater(() -> vp.setViewPosition(new Point(0, 0)));
    }

    /** Zoom so the segment fills the visible width; double-click again to return to the previous zoom. */
    void zoomToSeg(Seg s) { zoomToSeg(s, true); }

    void zoomToSeg(Seg s, boolean toggle) {
        JViewport vp = sp.getViewport();
        int vw = Math.max(100, vp.getWidth());
        double fit = Math.max(1, Math.min(rate * 2.0, (s.e - s.s) / 1000.0 * rate / (vw * 0.96)));
        boolean already = Math.abs(wave.spp - fit) < 0.5;
        double target;
        if (toggle && already && prevSpp > 0) { target = prevSpp; prevSpp = -1; }
        else { if (!already) prevSpp = (toggle || prevSpp < 0) ? wave.spp : prevSpp; target = fit; }
        wave.spp = target;
        wave.updateSize();
        final long mid = (s.s + s.e) / 2;
        SwingUtilities.invokeLater(() ->
                vp.setViewPosition(new Point((int) Math.max(0, wave.msToX(mid) - vp.getWidth() / 2.0), 0)));
    }

    void zoom(double f) {
        if (pcm.length == 0) return;
        JViewport vp = sp.getViewport();
        long centre = wave.xToMs(vp.getViewPosition().x + vp.getWidth() / 2.0);
        wave.spp = Math.max(2, Math.min(rate, wave.spp * f));
        wave.updateSize();
        SwingUtilities.invokeLater(() -> {
            int x = (int) Math.max(0, wave.msToX(centre) - vp.getWidth() / 2.0);
            vp.setViewPosition(new Point(x, 0));
        });
    }

    // ---------------------------------------------------------------- recording
    static final int ANALYSIS_LO = 50, ANALYSIS_HI = 500; // F0 search range; the top-bar slider only changes what is displayed
    static final double SHADOW_TRANSPARENCY = 0.4; // how see-through the shadow spectrogram is over the source one
    static final long REC_EXTRA_MS = 1000; // extra recording time after the segment length, for slower reading
    static final int[] MIC_RATES = {44100, 48000, 32000, 22050, 16000, 8000};
    JButton recBtn;
    JMenuItem trackBtn; // File > New (becomes "Stop & save" while recording a new track)
    final JComboBox<String> micBox = new JComboBox<>();
    boolean micUpdating;
    volatile boolean recording;
    volatile int recMode; // 1 = segment (shadowing), 2 = free-form new track
    volatile AtomicBoolean recStop;
    volatile long recEndMs; // end of the part of the original that the recording being played shadows (the playhead hides after it)
    volatile int recHz = Math.max(-100, Math.min(100, PREFS.getInt("shadowHz5", 0))); // vertical shift of the recording's display in Hz: the whole thing moves, nothing is stretched
    volatile boolean drawMode, moveMode;
    volatile float[] drawnSrc = new float[0]; // hand-drawn contour: >0 Hz, -1 = drawn gap, 0 = use the automatic value
    volatile int recShift = PREFS.getInt("shadowShift", 0); // manual nudge (ms) of the recording's display; kept between recordings and sessions

    static File lastDir() {
        File d = new File(PREFS.get("lastDir", System.getProperty("user.home")));
        return d.isDirectory() ? d : new File(System.getProperty("user.home"));
    }

    static List<String> micNames() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info i : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(i);
                for (int sr : MIC_RATES)
                    if (m.isLineSupported(new DataLine.Info(TargetDataLine.class, new AudioFormat(sr, 16, 1, true, false)))) {
                        if (!out.contains(i.getName())) out.add(i.getName());
                        break;
                    }
            } catch (Exception ignored) { }
        }
        return out;
    }

    void fillMicBox() {
        micUpdating = true;
        String saved = PREFS.get("micName", "");
        micBox.removeAllItems();
        micBox.addItem("System default");
        for (String n : micNames()) micBox.addItem(n);
        micBox.setSelectedIndex(0);
        if (!saved.isEmpty()) micBox.setSelectedItem(saved);
        micUpdating = false;
    }

    static TargetDataLine openMic(AudioFormat[] fmtOut) throws Exception {
        String name = PREFS.get("micName", "");
        Mixer m = null;
        if (!name.isEmpty()) for (Mixer.Info i : AudioSystem.getMixerInfo()) if (i.getName().equals(name)) { m = AudioSystem.getMixer(i); break; }
        for (int sr : MIC_RATES) {
            AudioFormat f = new AudioFormat(sr, 16, 1, true, false);
            DataLine.Info di = new DataLine.Info(TargetDataLine.class, f);
            if (m != null ? m.isLineSupported(di) : AudioSystem.isLineSupported(di)) {
                TargetDataLine l = (TargetDataLine) (m != null ? m.getLine(di) : AudioSystem.getLine(di));
                try { l.open(f, (int) (sr * f.getFrameSize() * 0.1)); } // a short (100 ms) buffer: less delay before audio reaches us
                catch (Exception ex) { l.open(f); }
                fmtOut[0] = f;
                return l;
            }
        }
        throw new IllegalStateException("No usable microphone found");
    }

    void updateRecUi() {
        recBtn.setText(recording && recMode == 2 ? STOP : REC); // a red stop square while recording a new track
        recBtn.setForeground(recording ? new Color(235, 40, 40)
                : new javax.swing.plaf.ColorUIResource(UIManager.getColor("Button.foreground"))); // red only while recording
        recBtn.setToolTipText(recording && recMode == 2 ? "Stop recording (it stays on screen; File > Save keeps it)"
                : blankNew && pcm.length == 0 ? "Start recording the new track"
                : "Record (R): record the microphone for the length of the dragged range or selected segment");
        trackBtn.setText("New");
        recBtn.setEnabled(true); // doubles as the stop button
        trackBtn.setEnabled(!recording);
        micBox.setEnabled(!recording);
    }

    /** Shadowing: record the microphone for exactly the length of the selected segment, then play it back. */
    void recordSegment() {
        if (recording) { recStop.set(true); return; } // cancels a shadowing recording; stops a new recording (it stays on screen, unsaved)
        if (blankNew && pcm.length == 0) { startRecording(2, null); return; } // File > New: this starts the recording
        Seg t = range != null ? range : sel; // a dragged range if there is one, otherwise the selected segment
        if (pcm.length == 0 || t == null) {
            status.setText(" Nothing selected: click a segment, or drag a range on the waveform or pitch panel, then press record.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        startRecording(1, t);
    }

    static final double DEFAULT_PPS = 600; // default zoom: pixels per second

    double defaultSpp() { return Math.max(rate / DEFAULT_PPS, pcm.length / 30000.0); } // very long files are capped at ~30,000 px wide

    // ---------------------------------------------------------------- tabs: one document per tab
    /** Everything that belongs to one open document. The live fields of this class always hold the current tab's. */
    static class Doc {
        short[] pcm = new short[0]; int rate = 44100;
        final List<Seg> segs = new ArrayList<>(); Seg sel;
        File srt, regFile;
        final List<Reg> regs = new ArrayList<>(); int globalOct;
        final java.util.ArrayDeque<Object> undo = new java.util.ArrayDeque<>();
        float[] f0 = new float[0]; Spec spec; float[] drawnSrc = new float[0];
        final List<Rec> recs = new ArrayList<>(); Rec lastRec; long recEndMs;
        boolean unsaved, blankNew, liveNew;
        boolean showSpec, showLine = true; // this tab's Source > Spectrogram / Pitch contour toggles
        double spp = 441; int scroll;
        String title = "Piyingxi", tabName = "No file";
        JPanel tabComp; JLabel tabLabel;
    }
    final List<Doc> docs = new ArrayList<>();
    Doc curDoc = new Doc();
    JPanel tabBar;

    void capture(Doc d) { // live state -> the tab we are leaving
        d.pcm = pcm; d.rate = rate;
        d.segs.clear(); d.segs.addAll(segs); d.sel = sel;
        d.srt = srt; d.regFile = regFile;
        d.regs.clear(); d.regs.addAll(regs); d.globalOct = globalOct;
        d.undo.clear(); d.undo.addAll(undoStack);
        d.f0 = f0; d.spec = spec; d.drawnSrc = drawnSrc;
        d.recs.clear(); d.recs.addAll(recs); d.lastRec = lastRec; d.recEndMs = recEndMs;
        d.unsaved = unsaved; d.blankNew = blankNew; d.liveNew = liveNew;
        d.showSpec = PREFS.getBoolean("showSpec", false); d.showLine = PREFS.getBoolean("showBlue", true);
        d.spp = wave.spp; d.scroll = sp.getHorizontalScrollBar().getValue();
        d.title = getTitle();
    }

    void restore(Doc d) { // the tab we are entering -> live state
        pitchGen++; specGen++; // drop any analysis still running for the other tab
        pcm = d.pcm; rate = d.rate;
        segs.clear(); segs.addAll(d.segs);
        regs.clear(); regs.addAll(d.regs); globalOct = d.globalOct;
        undoStack.clear(); undoStack.addAll(d.undo);
        recs.clear(); recs.addAll(d.recs); lastRec = d.lastRec; recEndMs = d.recEndMs;
        srt = d.srt; regFile = d.regFile; f0 = d.f0; spec = d.spec; drawnSrc = d.drawnSrc;
        unsaved = d.unsaved; blankNew = d.blankNew; liveNew = d.liveNew;
        PREFS.putBoolean("showSpec", d.showSpec); PREFS.putBoolean("showBlue", d.showLine); // the menu follows the tab
        if (srcSpec != null) srcSpec.setSelected(d.showSpec);
        if (srcLine != null) srcLine.setSelected(d.showLine);
        resumeMs = -1; resumeSeg = null; cursorMs = -1;
        wave.spp = d.spp;
        wave.updateSize();
        select(d.sel);
        syncLenSlider();
        setTitle(d.title);
        final int scroll = d.scroll;
        SwingUtilities.invokeLater(() -> sp.getHorizontalScrollBar().setValue(scroll));
        if (pcm.length > 0 && f0.length == 0) computePitch();                                       // analysis may have been cancelled
        if (pcm.length > 0 && spec == null && PREFS.getBoolean("showSpec", false)) computeSpectrogram();
        updateRecUi();
        wave.repaint(); pitch.repaint();
    }

    void switchTo(Doc d) {
        if (d == curDoc) return;
        stopPlay(); saveSrt(); // subtitles of the tab we are leaving
        capture(curDoc);
        curDoc = d;
        restore(d);
        refreshTabs();
    }

    JPanel makeTab(Doc d) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        JLabel l = new JLabel(d.tabName);
        JButton x = new JButton("\u00D7");
        x.putClientProperty("JButton.buttonType", "borderless");
        x.setContentAreaFilled(false); x.setBorderPainted(false); x.setFocusable(false);
        x.setMargin(new Insets(0, 3, 0, 3));
        x.setToolTipText("Close tab");
        x.addActionListener(e -> closeDoc(d));
        MouseAdapter pick = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (recording) { status.setText(" Stop recording before switching tabs."); return; }
                switchTo(d);
            }
        };
        p.addMouseListener(pick); l.addMouseListener(pick);
        p.add(l); p.add(x);
        d.tabComp = p; d.tabLabel = l;
        return p;
    }

    void refreshTabs() { // highlight the current tab
        for (Doc d : docs) {
            boolean on = d == curDoc;
            d.tabComp.setOpaque(true);
            d.tabComp.setBackground(on ? boxBg() : UIManager.getColor("Panel.background"));
            d.tabComp.setBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, on ? wave.inkSel : d.tabComp.getBackground()));
            d.tabLabel.setForeground(on ? UIManager.getColor("Label.foreground") : Color.GRAY);
        }
        tabBar.revalidate(); tabBar.repaint();
    }

    void setTabName(Doc d, String name) {
        d.tabName = name;
        if (d.tabLabel != null) d.tabLabel.setText(name);
        tabBar.revalidate(); tabBar.repaint();
    }

    /** Closes a tab straight away: no questions, and a new recording that was never saved is simply dropped. */
    void closeDoc(Doc d) {
        if (recording) { status.setText(" Stop recording before closing a tab."); return; }
        int i = docs.indexOf(d);
        if (i < 0) return;
        boolean wasCurrent = d == curDoc;
        if (wasCurrent) { stopPlay(); saveSrt(); } // a file's subtitles are autosaved anyway
        docs.remove(i);
        tabBar.remove(d.tabComp);
        if (docs.isEmpty()) { Doc e = new Doc(); docs.add(e); tabBar.add(makeTab(e)); }
        if (wasCurrent) {
            Doc next = docs.get(Math.max(0, Math.min(i, docs.size() - 1)));
            curDoc = next;
            restore(next);
        }
        refreshTabs();
    }

    void closeTab() { closeDoc(curDoc); }

    boolean confirmAllClosing() {
        for (Doc d : new ArrayList<>(docs)) {
            boolean un = d == curDoc ? (unsaved && pcm.length > 0) : (d.unsaved && d.pcm.length > 0);
            if (!un) continue;
            switchTo(d);
            if (!confirmDiscardUnsaved()) return false;
        }
        return true;
    }

    /** File > New: a new tab with a blank "new recording" screen. Recording starts when you press the record button. */
    void newDocument() {
        if (recording) return;
        Doc d = new Doc();
        d.tabName = "New recording"; d.title = "Piyingxi - new recording";
        d.rate = rate > 0 ? rate : 44100;
        d.spp = d.rate / DEFAULT_PPS;
        d.unsaved = true; d.blankNew = true; d.liveNew = true;
        d.showSpec = true; d.showLine = true; // a new recording starts with the live contour and spectrogram showing; both can be switched off
        docs.add(d);
        tabBar.add(makeTab(d));
        switchTo(d);
        status.setText(" New recording: press the red " + REC + " button to start recording.");
    }

    void startRecording(int mode, Seg seg) {
        stopPlay();
        final long maxMs = mode == 1 ? seg.e - seg.s + REC_EXTRA_MS : Long.MAX_VALUE;
        final AtomicBoolean stop = new AtomicBoolean(false);
        recStop = stop; recMode = mode; recording = true;
        updateRecUi();
        status.setText(mode == 1
                ? String.format(" \u25CF Recording %.1f s - speak now (R to cancel)", maxMs / 1000.0)
                : " \u25CF Recording - press the red \u23F9 button to stop (nothing is saved until File > Save)");
        new Thread(() -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            AudioFormat[] fo = new AudioFormat[1];
            String err = null; boolean complete = false;
            try {
                TargetDataLine l = openMic(fo);
                long target = mode == 1 ? (long) (maxMs * fo[0].getFrameRate() / 1000) * 2 : Long.MAX_VALUE;
                if (mode == 2) { // File > New: show the recording as it grows
                    liveBuf = new short[0]; liveLen = 0; liveRaw = new float[0]; liveMaxDb = -1e9; liveSpecObj = null;
                    liveSpecBuf = new byte[0]; liveSpecBins = 0; lastPcmPub = 0;
                    final int sr0 = (int) fo[0].getSampleRate();
                    SwingUtilities.invokeLater(() -> beginLiveDoc(sr0));
                }
                l.start();
                byte[] buf = new byte[1024]; // small reads = quick screen updates
                long lastLive = 0, lastFast = 0;
                while (!stop.get() && bos.size() < target) {
                    int n = l.read(buf, 0, (int) Math.min(buf.length, target - bos.size()));
                    if (n > 0) bos.write(buf, 0, n);
                    if (n > 0 && mode == 2) appendLive(buf, n);
                    long now = System.currentTimeMillis();
                    int sr1 = (int) fo[0].getSampleRate();
                    if (mode == 2) {
                        if (now - lastFast > 40) { lastFast = now; liveFast(sr1); }                                          // spectrogram + waveform: ~25 times a second
                        if (now - lastLive > 150 + (long) (liveLen / (double) sr1) * 3) { lastLive = now; liveSlow(sr1); }  // pitch contour: a few times a second
                    }
                }
                l.stop(); l.close();
                complete = mode == 2 || bos.size() >= target;
            } catch (Exception ex) { err = ex.getMessage() != null ? ex.getMessage() : ex.toString(); }
            final byte[] bytes = bos.toByteArray(); final AudioFormat fmt = fo[0];
            final boolean ok = complete; final String error = err;
            SwingUtilities.invokeLater(() -> recordingDone(mode, seg, bytes, fmt, ok, error));
        }).start();
    }

    /** Start of speech (ms from `from`): first 10 ms window that is clearly above the noise floor, twice in a row. */
    static long onsetMs(short[] x, int sr, int from, int to) {
        int win = Math.max(1, sr / 100), n = (to - from) / win;
        if (from < 0 || n < 3) return 0;
        double[] r = new double[n];
        double peak = 0;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = from + i * win; j < from + (i + 1) * win; j++) sum += (double) x[j] * x[j];
            r[i] = Math.sqrt(sum / win);
            peak = Math.max(peak, r[i]);
        }
        double[] sorted = r.clone();
        Arrays.sort(sorted);
        double thr = Math.max(0.12 * peak, 2.5 * sorted[n / 5]);
        for (int i = 0; i < n - 1; i++) if (r[i] > thr && r[i + 1] > thr) return i * 10L;
        return 0;
    }

    void appendLive(byte[] b, int n) {
        int add = n / 2;
        if (liveLen + add > liveBuf.length) liveBuf = Arrays.copyOf(liveBuf, Math.max(liveBuf.length * 2, liveLen + add + 44100));
        for (int i = 0; i < add; i++) liveBuf[liveLen + i] = (short) ((b[2 * i + 1] << 8) | (b[2 * i] & 0xff));
        liveLen += add;
    }

    /** EDT: the new recording starts to fill the (blank) screen. */
    void beginLiveDoc(int sr) {
        pitchGen++; specGen++;
        liveSpecPub = null; livePcmPub = null; liveF0Pub = null; livePosting.set(false);
        pcm = new short[0]; rate = sr; f0 = new float[0]; spec = null;
        wave.spp = sr / DEFAULT_PPS;
        wave.updateSize();
        setTitle("Piyingxi - recording...");
        liveNew = true; liveDocReady = true;
        wave.repaint(); pitch.repaint();
    }

    /** At most one screen update is queued at a time, so a slow screen never builds up a backlog. */
    void postLive(int sr) {
        if (!livePosting.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                if (!liveDocReady) return;
                short[] p = livePcmPub;
                if (p != null && p.length != pcm.length) {
                    pcm = p; rate = sr;
                    wave.updateSize();
                    JViewport vp = sp.getViewport();
                    SwingUtilities.invokeLater(() -> vp.setViewPosition(new Point(Math.max(0, wave.getPreferredSize().width - vp.getWidth() + 30), 0))); // follow the end
                }
                Spec sp0 = liveSpecPub; if (sp0 != null) spec = sp0;
                float[] ff = liveF0Pub; if (ff != null) f0 = ff;
                wave.repaint(); pitch.repaint();
            } finally { livePosting.set(false); }
        });
    }

    /** Recording thread, ~25 times a second: extend the spectrogram from the newest 0.4 s and refresh the waveform. */
    void liveFast(int sr) {
        if (!liveDocReady || liveLen < sr / 20) return;
        final int total = liveLen;
        final int frames = (int) (total * 100L / sr); // 10 ms columns recorded so far
        if (frames < 6) return;
        int tailFrame = Math.max(0, frames - 40);
        int a = (int) Math.round(tailFrame * sr / 100.0);
        if (a >= total) return;
        short[] tail = Arrays.copyOfRange(liveBuf, a, total);
        Spec t0 = spectrogram(tail, sr, specGen, Double.NaN); // how loud the tail is
        if (t0 == null) return;
        liveMaxDb = Math.max(liveMaxDb, t0.maxDb);             // one running loudness scale for the whole recording
        Spec t1 = spectrogram(tail, sr, specGen, liveMaxDb);
        if (t1 == null) return;
        int bins = t1.bins;
        if (liveSpecBins != bins || liveSpecBuf.length < (frames + 1) * bins) { // grow the shared picture buffer
            byte[] nb = new byte[Math.max(liveSpecBuf.length * 2, (frames + 600) * bins)];
            if (liveSpecBins == bins) System.arraycopy(liveSpecBuf, 0, nb, 0, liveSpecBuf.length);
            liveSpecBuf = nb; liveSpecBins = bins;
        }
        int skip = tailFrame == 0 ? 0 : 4; // the first columns of the tail lack their left-hand context
        for (int j = skip; j < t1.cols && tailFrame + j < frames; j++)
            System.arraycopy(t1.d, j * bins, liveSpecBuf, (tailFrame + j) * bins, bins);
        liveSpecPub = new Spec(liveSpecBuf, frames, bins, t1.binHz); // shares the buffer: nothing big is copied
        long now = System.currentTimeMillis();
        if (livePcmPub == null || now - lastPcmPub > 80 + (long) (total / (double) sr) * 3) { // the waveform needs a full copy, so not every time
            livePcmPub = Arrays.copyOf(liveBuf, total);
            lastPcmPub = now;
        }
        postLive(sr);
    }

    /** Recording thread, a few times a second: re-analyse the last 1.5 s for the pitch contour. */
    void liveSlow(int sr) {
        if (!liveDocReady || liveLen < sr / 20) return;
        final int total = liveLen;
        final int frames = (int) (total * 100L / sr);
        if (frames < 8) return;
        int tailFrame = Math.max(0, frames - 150);
        int a = (int) Math.round(tailFrame * sr / 100.0);
        if (a >= total) return;
        short[] tail = Arrays.copyOfRange(liveBuf, a, total);
        float[] part = pitchTrack(tail, sr, ANALYSIS_LO, ANALYSIS_HI, pitchGen);
        if (part == null) return;
        if (liveRaw.length < frames) liveRaw = Arrays.copyOf(liveRaw, frames + 200);
        for (int i = 0; i < part.length && tailFrame + i < liveRaw.length; i++) liveRaw[tailFrame + i] = part[i];
        liveF0Pub = smooth(Arrays.copyOf(liveRaw, frames));
        postLive(sr);
    }

    /** A New recording that failed or was cancelled: back to the blank new-recording screen. */
    void resetBlankNew() {
        pcm = new short[0]; f0 = new float[0]; spec = null; blankNew = true;
        wave.updateSize(); wave.repaint(); pitch.repaint();
        setTitle("Piyingxi - new recording");
        updateRecUi();
    }

    static short[] toShorts(byte[] b) {
        short[] out = new short[b.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((b[2 * i + 1] << 8) | (b[2 * i] & 0xff));
        return out;
    }

    void recordingDone(int mode, Seg seg, byte[] bytes, AudioFormat fmt, boolean ok, String error) {
        boolean hadLive = liveDocReady; liveDocReady = false; // the live updates end; what is on screen stays
        recording = false; recMode = 0; updateRecUi();
        if (error != null) {
            status.setText(" Recording failed: " + error);
            JOptionPane.showMessageDialog(this, "Could not record: " + error + "\n\nCheck the microphone selection and that your system exposes it to Java (ALSA).");
            if (hadLive) resetBlankNew();
            return;
        }
        if (!ok || bytes.length < 400) { status.setText(" Recording cancelled."); if (hadLive) resetBlankNew(); return; }
        if (mode == 2) { finishNewRecording(bytes, fmt); return; }
        final short[] data = toShorts(bytes);
        final int sr = (int) fmt.getSampleRate();
        // line up where speech starts in the recording with where it starts in the original segment
        long origOn = onsetMs(pcm, rate, (int) (seg.s * rate / 1000), (int) Math.min(pcm.length, seg.e * rate / 1000));
        long recOn = onsetMs(data, sr, 0, data.length);
        long base = seg.s + origOn - recOn;
        final Rec nr = new Rec();
        nr.pcm = data; nr.s = seg.s; nr.e = seg.e; nr.startMs = base; nr.lenMs = data.length * 1000L / sr;
        recs.clear(); // only the latest recording is kept (and drawn)
        recs.add(nr); lastRec = nr; recEndMs = seg.e;
        syncLenSlider(); // a new recording starts at 100% length
        syncSliders();
        pitch.repaint();
        startPlay(data, sr, 0, data.length, false, base, null);
        status.setText(" Playing back your recording...");
        final int gen = pitchGen;
        final double fl = ANALYSIS_LO, ce = ANALYSIS_HI;
        new Thread(() -> {
            float[] o = pitchTrack(data, sr, fl, ce, gen);
            if (o != null) SwingUtilities.invokeLater(() -> { nr.f0 = smooth(o); pitch.repaint(); });
            Spec rs = spectrogram(data, sr, specGen);
            if (rs != null) SwingUtilities.invokeLater(() -> { nr.spec = rs; pitch.repaint(); });
        }).start();
    }

    /** Stop pressed: the recording stays in the viewer to be examined. It is only written to a file by File > Save. */
    void finishNewRecording(byte[] bytes, AudioFormat fmt) {
        pcm = toShorts(bytes);
        rate = (int) fmt.getSampleRate();
        blankNew = false; unsaved = true; // contour + spectrogram stay visible (liveNew) until another file is opened
        wave.updateSize();
        computePitch();
        computeSpectrogram();
        setTitle("Piyingxi - new recording (not saved)");
        status.setText(" Recording stopped. Use File > Save to keep it as an MP3 file.");
        updateRecUi();
        wave.repaint(); pitch.repaint();
    }

    /** File > Save: subtitles and corrections as always; a new recording first asks where to put its WAV file. */
    void saveAll() {
        if (unsaved && pcm.length > 0 && !saveRecordingAs()) return;
        saveSrt();
        saveRegs();
    }

    /** File > Save for a new recording: an MP3 made by ffmpeg's libmp3lame (a name ending in .wav still gives a WAV). */
    boolean saveRecordingAs() {
        if (pcm.length == 0) return true;
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setFileFilter(new FileNameExtensionFilter("MP3 audio", "mp3"));
        fc.setSelectedFile(new File(lastDir(), "recording-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".mp3"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File f = fc.getSelectedFile();
        boolean wavOut = f.getName().toLowerCase().endsWith(".wav");
        if (!wavOut && !f.getName().toLowerCase().endsWith(".mp3")) f = new File(f.getParentFile(), f.getName() + ".mp3");
        if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " exists. Overwrite?", "Overwrite", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
            return saveRecordingAs(); // ask again
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        String err = wavOut ? writeWav(f) : encodeMp3(f);
        setCursor(Cursor.getDefaultCursor());
        if (err != null && !wavOut) { // no ffmpeg / no libmp3lame: offer to keep the recording as a WAV instead
            String nm = f.getName();
            File wav = new File(f.getParentFile(), nm.substring(0, nm.lastIndexOf('.')) + ".wav");
            if (JOptionPane.showConfirmDialog(this, "Could not make the MP3: " + err + "\n\nSave it as a WAV file instead?",
                    "MP3 export failed", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                err = writeWav(wav);
                if (err == null) f = wav;
            }
        }
        if (err != null) { status.setText(" Could not save: " + err); return false; }
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        File dir = f.getAbsoluteFile().getParentFile();
        srt = new File(dir, base + ".srt");           // from now on this is an ordinary file with its subtitles and corrections beside it
        regFile = new File(dir, base + ".piyingxi");
        PREFS.put("lastFile", f.getAbsolutePath());
        PREFS.put("lastDir", dir.getAbsolutePath());
        unsaved = false;
        setTitle("Piyingxi - " + n);
        setTabName(curDoc, n);
        status.setText(" Saved " + n);
        return true;
    }

    /** Pipes the mono 16-bit recording into ffmpeg (libmp3lame, VBR quality 2). Returns null on success, else a message. */
    String encodeMp3(File out) {
        try {
            byte[] bytes = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) { bytes[2 * i] = (byte) pcm[i]; bytes[2 * i + 1] = (byte) (pcm[i] >> 8); }
            Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", String.valueOf(rate), "-ac", "1",
                    "-i", "pipe:0", "-c:a", "libmp3lame", "-q:a", "2", out.getAbsolutePath()).redirectErrorStream(true).start();
            final StringBuilder log = new StringBuilder();
            Thread reader = new Thread(() -> { // keep ffmpeg's messages flowing so it can never block
                try (InputStream in = p.getInputStream()) { log.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            });
            reader.start();
            try (OutputStream os = p.getOutputStream()) { os.write(bytes); }
            int code = p.waitFor();
            reader.join(3000);
            return code == 0 ? null : "ffmpeg stopped with code " + code + (log.length() > 0 ? ": " + log.toString().trim() : "");
        } catch (IOException ex) {
            return "ffmpeg could not be run (" + ex.getMessage() + "). Is ffmpeg installed and on your PATH, with libmp3lame?";
        } catch (InterruptedException ex) {
            return "interrupted";
        }
    }

    String writeWav(File f) {
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) { bytes[2 * i] = (byte) pcm[i]; bytes[2 * i + 1] = (byte) (pcm[i] >> 8); }
        AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(bytes), fmt, pcm.length)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, f);
        } catch (IOException ex) { return ex.getMessage(); }
        return null;
    }

    /** True if it is fine to replace the audio on screen (a new, unsaved recording is saved or deliberately discarded first). */
    boolean confirmDiscardUnsaved() {
        if (!unsaved || pcm.length == 0) return true;
        int r = JOptionPane.showConfirmDialog(this, "The new recording has not been saved. Save it now?", "Unsaved recording", JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
        if (r == JOptionPane.YES_OPTION) return saveRecordingAs();
        return true; // discard
    }

    // ---------------------------------------------------------------- waveform view
    class Wave extends JPanel {
        double spp = 441;
        List<Snap> edgeSnap;
        int mode = 0; // 1 = new region, 2 = drag start edge, 3 = drag end edge
        long anchor; Seg drag;

        Wave() {
            applyTheme();
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            getInputMap().put(KeyStroke.getKeyStroke("TAB"), "playCursor");
            getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "play");
            getInputMap().put(KeyStroke.getKeyStroke("ctrl Z"), "undo");
            getActionMap().put("undo", new AbstractAction() { public void actionPerformed(ActionEvent e) { undo(); } });
            getActionMap().put("playCursor", new AbstractAction() { public void actionPerformed(ActionEvent e) { playFromCursor(); } });
            getInputMap().put(KeyStroke.getKeyStroke("LEFT"), "prev");
            getInputMap().put(KeyStroke.getKeyStroke("RIGHT"), "next");
            getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "edit");
            getInputMap().put(KeyStroke.getKeyStroke("R"), "rec");
            getActionMap().put("rec", new AbstractAction() { public void actionPerformed(ActionEvent e) { recordSegment(); } });
            getInputMap().put(KeyStroke.getKeyStroke("S"), "split");
            getActionMap().put("split", new AbstractAction() { public void actionPerformed(ActionEvent e) { splitAction(); } });
            getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "del");
            getActionMap().put("del", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (drawMode) deleteContour(); else deleteSel(); } });
            getInputMap().put(KeyStroke.getKeyStroke("A"), "zoomAll");
            getActionMap().put("zoomAll", new AbstractAction() { public void actionPerformed(ActionEvent e) { zoomAll(); } });
            getActionMap().put("edit", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { if (sel != null && !PREFS.getBoolean("hideSubs", false)) text.requestFocusInWindow(); }
            });
            getActionMap().put("play", new AbstractAction() { public void actionPerformed(ActionEvent e) { togglePlay(); } });
            getActionMap().put("prev", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (moveMode) nudgeShadow(-10, 0); else step(-1); } });
            getActionMap().put("next", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (moveMode) nudgeShadow(10, 0); else step(1); } });
            String[][] nk = {{"UP", "0,2"}, {"DOWN", "0,-2"}, {"shift LEFT", "-50,0"}, {"shift RIGHT", "50,0"}, {"shift UP", "0,10"}, {"shift DOWN", "0,-10"}};
            for (String[] k : nk) { // only act while Move shadow is on
                String[] v = k[1].split(",");
                getInputMap().put(KeyStroke.getKeyStroke(k[0]), "nudge " + k[0]);
                getActionMap().put("nudge " + k[0], nudgeAction(Integer.parseInt(v[0]), Integer.parseInt(v[1])));
            }
            MouseAdapter m = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (pcm.length == 0) return;
                    int x = e.getX(); long ms = clamp(xToMs(x));
                    mode = 0;
                    range = null;
                    if (findEdge(x)) {
                        if (edgeSeg != sel) select(edgeSeg);
                        mode = edgeWhich == 1 ? 2 : 3; drag = edgeSeg; edgeSnap = snapshot();
                        return;
                    }
                    for (Seg s : segs) if (ms >= s.s && ms <= s.e) {
                        select(s);
                        if (e.getClickCount() == 2) { zoomToSeg(s); return; }
                        mode = 4; anchor = ms; cursorMs = ms; // click = cursor, drag = temporary range
                        repaint();
                        return;
                    }
                    select(null);
                    mode = 4; anchor = ms; // dragging on blank waveform gives a temporary range, not a segment (S makes it one)
                }
                public void mouseDragged(MouseEvent e) {
                    if (mode == 0) return;
                    long ms = clamp(xToMs(e.getX()));
                    if (mode == 1) { drag.s = Math.min(anchor, ms); drag.e = Math.max(anchor, ms); }
                    else if (mode == 2) drag.s = Math.min(ms, drag.e - 20);
                    else if (mode == 3) drag.e = Math.max(ms, drag.s + 20);
                    else if (mode == 4) {
                        if (range == null && Math.abs(e.getX() - msToX(anchor)) < 2) return; // still just a click
                        if (range == null) range = new Seg(anchor, anchor);
                        range.s = Math.min(anchor, ms); range.e = Math.max(anchor, ms);
                    }
                    scrollRectToVisible(new Rectangle(e.getX() - 20, 0, 40, 1));
                    repaint(); selOverlay.repaint();
                }
                public void mouseReleased(MouseEvent e) {
                    if (mode == 1 && drag != null && drag.e - drag.s >= 100) {
                        pushUndo(); segs.add(drag); sortSegs(); select(drag);
                    } else if (mode == 2 || mode == 3) {
                        if (edgeSnap != null) {
                            boolean changed = false;
                            for (Snap n : edgeSnap) if (n.seg == drag && (n.s != drag.s || n.e != drag.e)) changed = true;
                            if (changed) pushUndo(edgeSnap);
                        }
                        saveSrt();
                    } else if (mode == 4 && range != null) {
                        if (range.e - range.s < MIN_SEL) range = null;
                        else status.setText(String.format(" Range %.2f s selected: Space plays it, the octave slider adjusts only this range, S makes it a segment.", (range.e - range.s) / 1000.0));
                        syncSliders();
                    }
                    mode = 0; drag = null; repaint(); selOverlay.repaint();
                }
                public void mouseMoved(MouseEvent e) {
                    if (mode != 0) return;
                    Seg ps = hoverSeg; int pw = hoverWhich;
                    if (findEdge(e.getX())) {
                        hoverSeg = edgeSeg; hoverWhich = edgeWhich;
                        setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                    } else {
                        hoverSeg = null; hoverWhich = 0;
                        setCursor(Cursor.getDefaultCursor());
                    }
                    if (ps != hoverSeg || pw != hoverWhich) repaint();
                }
                public void mouseExited(MouseEvent e) {
                    if (mode != 0) return;
                    hoverSeg = null; hoverWhich = 0;
                    setCursor(Cursor.getDefaultCursor());
                    repaint();
                }
                public void mouseWheelMoved(MouseWheelEvent e) {
                    handleWheel(e);
                }
            };
            handler = m;
            addMouseListener(m); addMouseMotionListener(m); addMouseWheelListener(m);
        }

        MouseAdapter handler;
        Seg hoverSeg, edgeSeg; int hoverWhich, edgeWhich;

        /** Finds the segment edge (any segment; the selected one wins ties) within a few pixels of x. */
        boolean findEdge(int x) {
            edgeSeg = null; edgeWhich = 0;
            double best = 6;
            List<Seg> order = new ArrayList<>();
            if (sel != null) order.add(sel);
            order.addAll(segs);
            for (Seg g : order) {
                double d1 = Math.abs(x - msToX(g.s)), d2 = Math.abs(x - msToX(g.e));
                if (d1 < best) { best = d1; edgeSeg = g; edgeWhich = 1; }
                if (d2 < best) { best = d2; edgeSeg = g; edgeWhich = 2; }
            }
            return edgeSeg != null;
        }

        Color ink = Color.GRAY, inkSel = Color.BLUE;
        void applyTheme() {
            setBackground(boxBg());
            ink = curDark ? new Color(150, 150, 155) : new Color(95, 95, 100);
            inkSel = curDark ? new Color(90, 170, 255) : new Color(0, 100, 255);
            repaint();
        }

        long clamp(long ms) { return Math.max(0, Math.min(totalMs(), ms)); }
        double msToX(long ms) { return ms * (double) rate / 1000.0 / spp; }
        long xToMs(double x) { return (long) (x * spp * 1000.0 / rate); }

        void updateSize() {
            setPreferredSize(new Dimension((int) Math.max(1, pcm.length / spp), 100));
            revalidate(); repaint(); pitch.repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            Rectangle c = g.getClipBounds();
            int h = getHeight(), mid = h / 2;
            for (Seg s : segs) paintSeg(g, s, s == sel && range == null);
            if (mode == 1 && drag != null) paintSeg(g, drag, true);
            if (range == null && sel != null && cursorMs > sel.s && cursorMs < sel.e) { // split position
                Stroke old = g.getStroke();
                g.setColor(curDark ? new Color(210, 210, 210, 170) : new Color(60, 60, 60, 170));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
                int cx = (int) msToX(cursorMs);
                g.drawLine(cx, 0, cx, h);
                g.setStroke(old);
            }
            Seg hl = (mode == 1 && drag != null) ? drag : (range != null ? range : sel); // the waveform inside this region is drawn in blue
            int hx1 = hl == null ? -1 : (int) msToX(hl.s), hx2 = hl == null ? -2 : (int) msToX(hl.e);
            for (int x = c.x; x <= c.x + c.width; x++) {
                g.setColor(x >= hx1 && x <= hx2 ? inkSel : ink);
                int from = (int) (x * spp), to = (int) Math.min(pcm.length, (x + 1) * spp);
                if (from >= pcm.length) break;
                int step = Math.max(1, (to - from) / 64), mn = 0, mx = 0;
                for (int i = from; i < to; i += step) { mn = Math.min(mn, pcm[i]); mx = Math.max(mx, pcm[i]); }
                g.drawLine(x, mid - mx * mid / 32768, x, mid - mn * mid / 32768);
            }
            if (hoverSeg != null) { // drag handle on the hovered edge
                int ex = (int) msToX(hoverWhich == 1 ? hoverSeg.s : hoverSeg.e), my = h / 2;
                g.setColor(inkSel);
                g.fillRoundRect(ex - 5, my - 20, 10, 40, 8, 8);
                g.setColor(new Color(255, 255, 255, 210));
                g.drawLine(ex - 1, my - 9, ex - 1, my + 9);
                g.drawLine(ex + 1, my - 9, ex + 1, my + 9);
            }
            if (playMs >= 0 && (playingOriginal || playMs <= recEndMs)) {
                g.setColor(Color.RED);
                int px = (int) msToX(playMs);
                g.drawLine(px, 0, px, h);
            }
        }

        void paintSeg(Graphics2D g, Seg s, boolean selected) {
            int x1 = (int) msToX(s.s), x2 = (int) msToX(s.e), h = getHeight();
            if (!s.text.isBlank()) { g.setColor(new Color(0, 160, 60)); g.fillRect(x1, h - 4, Math.max(1, x2 - x1), 4); } // subtitle marker, at the bottom
            g.setColor(selected ? inkSel : (curDark ? new Color(115, 115, 122) : new Color(135, 135, 142)));
            g.fillRect(x1, 0, Math.max(2, x2 - x1 + 1), 2);
            g.fillRect(x1, 0, 2, h);
            g.fillRect(x2 - 1, 0, 2, h);
        }
    }

    /** FlowLayout that wraps onto extra rows when the window is narrower than the bar. */
    static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
        @Override public Dimension preferredLayoutSize(Container t) { return layoutSize(t, true); }
        @Override public Dimension minimumLayoutSize(Container t) { return layoutSize(t, false); }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                Container c = target;
                while (c.getSize().width == 0 && c.getParent() != null) c = c.getParent();
                int targetWidth = c.getSize().width == 0 ? Integer.MAX_VALUE : c.getSize().width;
                int hgap = getHgap(), vgap = getVgap();
                Insets in = target.getInsets();
                int maxWidth = targetWidth - (in.left + in.right + hgap * 2);
                Dimension dim = new Dimension(0, 0);
                int rowW = 0, rowH = 0;
                for (Component m : target.getComponents()) {
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowW + d.width > maxWidth && rowW > 0) { addRow(dim, rowW, rowH); rowW = 0; rowH = 0; }
                    if (rowW != 0) rowW += hgap;
                    rowW += d.width;
                    rowH = Math.max(rowH, d.height);
                }
                addRow(dim, rowW, rowH);
                dim.width += in.left + in.right + hgap * 2;
                dim.height += in.top + in.bottom + vgap * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowW, int rowH) {
            dim.width = Math.max(dim.width, rowW);
            if (dim.height > 0) dim.height += getVgap();
            dim.height += rowH;
        }
    }

    /** Two-thumb slider (used for the pitch display range, in Hz). */
    static class RangeSlider extends JComponent {
        final int min, max;
        int lo, hi, active;
        Runnable onChange, onRelease;

        RangeSlider(int min, int max, int lo, int hi) {
            this.min = min; this.max = max; this.lo = lo; this.hi = hi;
            setPreferredSize(new Dimension(180, 28));
            setFocusable(false);
            MouseAdapter m = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    int x = e.getX();
                    active = Math.abs(x - xOf(RangeSlider.this.lo)) <= Math.abs(x - xOf(RangeSlider.this.hi)) ? 1 : 2;
                    update(x);
                }
                public void mouseDragged(MouseEvent e) { update(e.getX()); }
                public void mouseReleased(MouseEvent e) { active = 0; if (onRelease != null) onRelease.run(); }
            };
            addMouseListener(m); addMouseMotionListener(m);
        }

        int xOf(int v) { return 10 + (int) Math.round((getWidth() - 20) * (v - min) / (double) (max - min)); }

        void update(int x) {
            int v = min + (int) Math.round((x - 10) * (double) (max - min) / Math.max(1, getWidth() - 20));
            v = Math.max(min, Math.min(max, Math.round(v / 10f) * 10));
            if (active == 1) lo = Math.max(min, Math.min(v, hi - 50));
            else if (active == 2) hi = Math.min(max, Math.max(v, lo + 50));
            repaint();
            if (onChange != null) onChange.run();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = getHeight() / 2, x1 = xOf(lo), x2 = xOf(hi);
            Color accent = curDark ? new Color(90, 170, 255) : new Color(0, 100, 255);
            g.setColor(curDark ? new Color(80, 82, 88) : new Color(200, 200, 206));
            g.fillRoundRect(10, cy - 2, getWidth() - 20, 4, 4, 4);
            g.setColor(accent);
            g.fillRect(x1, cy - 2, x2 - x1, 4);
            for (int x : new int[]{x1, x2}) {
                g.setColor(Color.WHITE); g.fillOval(x - 7, cy - 7, 14, 14);
                g.setColor(accent); g.drawOval(x - 7, cy - 7, 14, 14);
            }
        }
    }

    /** F0 contour shown above the waveform, sharing its horizontal scale and scrolling. */
    class Pitch extends JPanel {
        Pitch() {
            setPreferredSize(new Dimension(1, 120));
            MouseAdapter pm = new MouseAdapter() { // selecting works here as on the waveform; draw mode draws; move mode drags the shadow
                public void mousePressed(MouseEvent e) {
                    if (moveMode) { wave.requestFocusInWindow(); moveFrom(e, true); }
                    else if (drawMode) { wave.requestFocusInWindow(); drawFrom(e, true); }
                    else wave.handler.mousePressed(toWave(e));
                }
                public void mouseDragged(MouseEvent e) {
                    if (moveMode) moveFrom(e, false); else if (drawMode) drawFrom(e, false); else wave.handler.mouseDragged(toWave(e));
                }
                public void mouseReleased(MouseEvent e) {
                    if (moveMode) moveEnd(); else if (drawMode) drawEnd(); else wave.handler.mouseReleased(toWave(e));
                }
                public void mouseMoved(MouseEvent e) { if (!drawMode && !moveMode) wave.handler.mouseMoved(toWave(e)); }
                public void mouseExited(MouseEvent e) { if (!drawMode && !moveMode) wave.handler.mouseExited(toWave(e)); }
            };
            addMouseListener(pm); addMouseMotionListener(pm);
            addMouseWheelListener(e -> handleWheel(e));
        }

        MouseEvent toWave(MouseEvent e) {
            return new MouseEvent(wave, e.getID(), e.getWhen(), e.getModifiersEx(), e.getX(),
                    Math.max(0, Math.min(wave.getHeight() - 1, e.getY())), e.getClickCount(), e.isPopupTrigger(), e.getButton());
        }

        long lastFrame = -1; float lastVal;
        float[] strokeBefore; boolean strokeRec;
        double mvX0, mvY0, mvShift0, mvHz0; boolean moving;

        /** Move shadow: press on the shadow and drag. Left/right = time (ms), up/down = pitch (Hz). */
        void moveFrom(MouseEvent e, boolean start) {
            if (start) {
                long ms = (long) (e.getX() * wave.spp * 1000.0 / rate);
                moving = recAt(ms) != null; // you have to grab the shadow itself
                if (!moving) { status.setText(" Click on the shadow (your recording) to move it."); return; }
                mvX0 = e.getX(); mvY0 = e.getY(); mvShift0 = recShift; mvHz0 = recHz;
                return;
            }
            if (!moving) return;
            double dxMs = (e.getX() - mvX0) * wave.spp * 1000.0 / rate;
            long m0 = (long) (mvX0 * wave.spp * 1000.0 / rate);
            double hzPerPx = (viewHiAt(m0) - viewLoAt(m0)) / (double) Math.max(1, getHeight() - 32);
            recShift = (int) Math.max(-3000, Math.min(3000, Math.round(mvShift0 + dxMs)));
            recHz = (int) Math.max(-400, Math.min(400, Math.round(mvHz0 - (e.getY() - mvY0) * hzPerPx))); // dragging up raises the shadow
            repaint();
        }

        void moveEnd() {
            if (!moving) return;
            moving = false;
            PREFS.putInt("shadowShift", recShift);
            PREFS.putInt("shadowHz5", recHz);
            status.setText(" Shadow moved: " + recShift + " ms, " + (recHz > 0 ? "+" : "") + recHz + " Hz.");
        }

        /** Draw target: the source contour, or the shadow one if only that is showing. */
        boolean tgtRec() { return PREFS.getBoolean("showOrange", false) && !PREFS.getBoolean("showBlue", true); }

        Rec strokeRecObj, lastRecObj;

        void drawFrom(MouseEvent e, boolean start) {
            boolean rec = tgtRec();
            double ms = e.getX() * wave.spp * 1000.0 / rate;
            Rec rt = null;
            if (rec) { rt = recAt((long) ms); if (rt == null) return; } // draw on the recording under the mouse
            long baseMs = rec ? recFrom(rt) : 0;
            int n = rec ? rt.f0.length : (int) (pcm.length * 1000L / rate / 10);
            if (n == 0) return;
            float[] arr = rec ? rt.drawn : drawnSrc;
            if (arr.length != n) { arr = new float[n]; if (rec) rt.drawn = arr; else drawnSrc = arr; }
            if (start) { strokeBefore = arr.clone(); strokeRecObj = rt; } // so the whole stroke can be undone
            long frame = Math.round((ms - baseMs) / (rec ? 10.0 * rt.scale : 10.0));
            if (frame < 0 || frame >= n) return;
            float val;
            if (e.isShiftDown()) val = -1;            // erase: a drawn gap
            else if (e.isControlDown()) val = 0;      // bring back the automatic value
            else {
                int h = getHeight(), pad = 16, lo = viewLoAt((long) ms), hi = viewHiAt((long) ms);
                double t = Math.max(0, Math.min(1, (h - pad - e.getY()) / (double) Math.max(1, h - 2 * pad)));
                double shown = lo + t * (hi - lo); // stored in true Hz, whatever the view corrections are
                val = rec ? (float) ((shown - recHz) / Math.pow(2, recOctAt(rt, (long) ms))) : (float) (shown / Math.pow(2, octAt((long) ms)));
            }
            boolean cont = !start && lastFrame >= 0 && lastRecObj == rt;
            long from = cont ? lastFrame : frame;
            float v0 = cont ? lastVal : val;
            long a = Math.min(from, frame), b = Math.max(from, frame);
            for (long k = Math.max(0, a); k <= Math.min(n - 1, b); k++)
                arr[(int) k] = (frame == from) ? val
                        : (val > 0 && v0 > 0) ? (float) (v0 + (val - v0) * ((double) (k - from) / (frame - from))) : val;
            lastFrame = frame; lastVal = val; lastRecObj = rt;
            repaint();
        }

        void drawEnd() {
            lastFrame = -1;
            if (strokeBefore != null) {
                float[] cur = strokeRecObj != null ? strokeRecObj.drawn : drawnSrc;
                if (!Arrays.equals(strokeBefore, cur)) pushUndoItem(new DrawUndo(strokeRecObj, strokeBefore));
                strokeBefore = null;
            }
            if (!tgtRec()) saveRegs(); // hand-drawn source contours are saved with the file; shadow ones last for this session
        }

        float val(float[] f, float[] ov, int i) { // hand-drawn value wins over the automatic one
            float d = (ov != null && i < ov.length) ? ov[i] : 0;
            if (d > 0) return d;
            if (d < 0) return 0;
            return i < f.length ? f[i] : 0;
        }

        /** Where a contour value is shown: octave correction, plus (shadow only) the vertical shift. */
        double disp(float f, Rec r, long ms) { return r != null ? f * Math.pow(2, recOctAt(r, ms)) + recHz : f * Math.pow(2, octAt(ms)); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle c = g.getClipBounds(), vis = getVisibleRect();
            int h = getHeight(), pad = 16;
            double fl = PREFS.getInt("viewLo", 50), ce = PREFS.getInt("viewHi", 300);
            g.setColor(boxBg());
            g.fillRect(c.x, c.y, c.width, c.height);
            Spec so = PREFS.getBoolean("showSpec", false) ? spec : null; // original audio: off by default
            boolean anyShadowSpec = false;
            for (Rec r : recs) if (r.spec != null) anyShadowSpec = true;
            boolean showRecs = PREFS.getBoolean("showShadowSpec", true) && anyShadowSpec; // every recording that has been made stays visible
            halo = so != null || showRecs;
            if (halo) drawSpec(g, so, showRecs, c, h, pad);
            float[] f = f0;
            if (f.length == 0 && pcm.length > 0) {
                g.setColor(Color.GRAY);
                g.drawString("Calculating pitch...", vis.x + 70, h / 2);
            }
            if (PREFS.getBoolean("showBlue", true)) {
                g.setColor(curDark ? new Color(90, 200, 255) : new Color(0, 70, 200));
                drawContour(g, f, drawnSrc, 0, c, h, pad, null);
            }
            if (PREFS.getBoolean("showOrange", false)) {
                g.setColor(new Color(255, 140, 0));
                for (Rec r : recs) drawContour(g, r.f0, r.drawn, r.startMs + recShift, c, h, pad, r);
            }
        }

        boolean halo; // outline the contours when they sit on top of the spectrogram
        java.awt.image.BufferedImage specImg; Object specKey; // last spectrogram picture and what it was made from

        int mix(int p, int q, double t) {
            int r = (int) Math.round(((p >> 16) & 255) * (1 - t) + ((q >> 16) & 255) * t);
            int gg = (int) Math.round(((p >> 8) & 255) * (1 - t) + ((q >> 8) & 255) * t);
            int b = (int) Math.round((p & 255) * (1 - t) + (q & 255) * t);
            return (r << 16) | (gg << 8) | b;
        }

        /** Shadow (your recording) in the normal grey ramp underneath; source (original audio) on top in the same blue as
         *  the source contour, 40% transparent where the shadow shows through it. */
        void drawSpec(Graphics2D g, Spec orig, boolean showRecs, Rectangle c, int h, int pad) {
            int w = c.width;
            if (w <= 0 || h <= 0) return;
            int vs = 17; // fingerprint of the per-region pitch ranges
            for (Reg rg : regs) vs = vs * 31 + Arrays.hashCode(new long[]{rg.s, rg.e, rg.lo, rg.hi});
            vs = vs * 31 + PREFS.getInt("viewLo", 50) * 1000 + PREFS.getInt("viewHi", 300);
            List<Object> key = new ArrayList<Object>(Arrays.asList(new Object[]{c.x, c.width, h, wave.spp, curDark,
                    PREFS.getDouble("specSigma", 2.5), liveDocReady, recHz, recShift, System.identityHashCode(orig), showRecs, vs}));
            if (showRecs) for (Rec rc : recs) { key.add(System.identityHashCode(rc.spec)); key.add(rc.startMs); key.add(rc.scale); }
            if (key.equals(specKey) && specImg != null) { g.drawImage(specImg, c.x, 0, null); return; }
            double sigma = liveDocReady ? 0 : PREFS.getDouble("specSigma", 2.5); // no blur while recording: it is the slow part
            int r = sigma > 0 ? (int) Math.ceil(3 * sigma) : 0;
            int bg = boxBg().getRGB() & 0xffffff;
            int blue = curDark ? 0x5AC8FF : 0x0046C8; // same blue as the source contour
            double shiftHz = recHz; // vertical shift of the recording
            int[] px = new int[w * h];
            Arrays.fill(px, bg);
            boolean[] under = new boolean[w]; // columns where the shadow spectrogram exists
            if (showRecs) {
                int[] lut = new int[256];
                for (int v = 0; v < 256; v++) {
                    int gray = curDark ? 30 + (int) (v * 0.65) : 255 - (int) (v * 0.80); // louder = brighter (dark) / darker (light)
                    lut[v] = (gray << 16) | (gray << 8) | gray;
                }
                for (Rec rc : recs) {
                    if (rc.spec == null) continue;
                    boolean[] in = new boolean[w];
                    float[] I = spectLayer(rc.spec, rc.startMs + recShift, c, h, pad, sigma, r, in, shiftHz, 10.0 * rc.scale);
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++) {
                            if (!in[x]) continue;
                            under[x] = true;
                            int i = y * w + x, v = Math.max(0, Math.min(255, Math.round(I[i])));
                            px[i] = mix(px[i], lut[v], Math.min(1, v / 255.0 * 3));
                        }
                }
            }
            if (orig != null) {
                int[] lut = new int[256];
                for (int v = 0; v < 256; v++) lut[v] = mix(bg, blue, v / 255.0);
                float[] I = spectLayer(orig, 0, c, h, pad, sigma, r, null, 0, 10.0);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x, v = Math.max(0, Math.min(255, Math.round(I[i])));
                        double a = under[x] ? (1 - SHADOW_TRANSPARENCY) * Math.min(1, v / 255.0 * 3) : 1.0;
                        px[i] = mix(px[i], lut[v], a);
                    }
            }
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            specImg = img; specKey = key;
            g.drawImage(img, c.x, 0, null);
        }

        /** Interpolated (and optionally Gaussian-blurred) intensity 0..255 of one spectrogram over the clip. The vertical
         *  scale at each column is the pitch range in force there, so the ridge lines up with the contour. */
        float[] spectLayer(Spec q, long offsetMs, Rectangle c, int h, int pad, double sigma, int r, boolean[] inRange, double shiftHz, double stepMs) {
            int w = c.width, W = w + 2 * r;
            Map<Long, int[]> rbs = new HashMap<>();
            Map<Long, double[]> rfs = new HashMap<>();
            float[] a = new float[W * h];
            for (int xx = 0; xx < W; xx++) {
                double ms = (c.x - r + xx + 0.5) * wave.spp * 1000.0 / rate; // pixel centre, like the waveform column
                double cf = (ms - offsetMs) / stepMs;
                boolean in = cf >= 0 && cf <= q.cols - 1;
                int xc = xx - r;
                if (inRange != null && xc >= 0 && xc < w) inRange[xc] = in;
                if (!in) continue;
                int lo = viewLoAt((long) ms), hi = viewHiAt((long) ms);
                long key = lo * 100000L + hi;
                int[] rb = rbs.get(key);
                double[] rf = rfs.get(key);
                if (rb == null) {
                    rb = new int[h]; rf = new double[h];
                    for (int y = 0; y < h; y++) {
                        double t = (h - pad - y) / (double) Math.max(1, h - 2 * pad);
                        double bf = (lo + t * (hi - lo) - shiftHz) / q.binHz; // a shift moves the whole image; the scale stays linear
                        if (bf < 0 || bf > q.bins - 1) rb[y] = -1;
                        else { rb[y] = (int) Math.floor(bf); rf[y] = bf - rb[y]; }
                    }
                    rbs.put(key, rb); rfs.put(key, rf);
                }
                int c0 = (int) Math.floor(cf), c1 = Math.min(c0 + 1, q.cols - 1);
                double fx = cf - c0;
                for (int y = 0; y < h; y++) {
                    int b0 = rb[y];
                    if (b0 < 0) continue;
                    int b1 = Math.min(b0 + 1, q.bins - 1);
                    double fy = rf[y];
                    double v00 = q.d[c0 * q.bins + b0] & 0xff, v01 = q.d[c0 * q.bins + b1] & 0xff;
                    double v10 = q.d[c1 * q.bins + b0] & 0xff, v11 = q.d[c1 * q.bins + b1] & 0xff;
                    a[y * W + xx] = (float) ((v00 * (1 - fy) + v01 * fy) * (1 - fx) + (v10 * (1 - fy) + v11 * fy) * fx);
                }
            }
            float[] out = new float[w * h];
            if (r > 0) { // separable Gaussian blur
                float[] k = new float[2 * r + 1];
                float ks = 0;
                for (int i = -r; i <= r; i++) { k[i + r] = (float) Math.exp(-0.5 * i * i / (sigma * sigma)); ks += k[i + r]; }
                for (int i = 0; i < k.length; i++) k[i] /= ks;
                float[] hb = new float[w * h];
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        float sum = 0; int base = y * W + x;
                        for (int i = 0; i < k.length; i++) sum += k[i] * a[base + i];
                        hb[y * w + x] = sum;
                    }
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        float sum = 0;
                        for (int i = -r; i <= r; i++) sum += k[i + r] * hb[Math.max(0, Math.min(h - 1, y + i)) * w + x];
                        out[y * w + x] = sum;
                    }
            } else {
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out[y * w + x] = a[y * W + x];
            }
            return out;
        }

        /** Draws each voiced run as one smooth Catmull-Rom curve (no dots). */
        void drawContour(Graphics2D g, float[] f, float[] ov, long baseMs, Rectangle c, int h, int pad, Rec rec) {
            int len = Math.max(f.length, ov == null ? 0 : ov.length);
            if (len == 0) return;
            double step = rec != null ? 10.0 * rec.scale : 10.0; // ms per frame (stretched for the shadow)
            int i0 = Math.max(0, (int) ((wave.xToMs(c.x) - baseMs) / step) - 3);
            int i1 = Math.min(len - 1, (int) ((wave.xToMs(c.x + c.width) - baseMs) / step) + 3);
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int i = i0;
            while (i <= i1) {
                long ms = baseMs + Math.round(i * step);
                int lo = viewLoAt(ms), hi = viewHiAt(ms);
                float fi = val(f, ov, i);
                double v = disp(fi, rec, ms); // octave correction (and vertical shift for the shadow line)
                if (fi <= 0 || v < lo || v > hi) { i++; continue; } // outside the displayed range = gap
                int j = i;
                while (j + 1 <= i1) { // a run ends at a gap or where the display range changes
                    long m2 = baseMs + Math.round((j + 1) * step);
                    float fj = val(f, ov, j + 1);
                    double v2 = disp(fj, rec, m2);
                    if (fj <= 0 || v2 < lo || v2 > hi || viewLoAt(m2) != lo || viewHiAt(m2) != hi) break;
                    j++;
                }
                int n = j - i + 1;
                double[] xs = new double[n], ys = new double[n];
                for (int k = 0; k < n; k++) {
                    long m = baseMs + Math.round((i + k) * step);
                    xs[k] = wave.msToX(m);
                    ys[k] = yOf(disp(val(f, ov, i + k), rec, m), lo, hi, h, pad);
                }
                Path2D.Double path = new Path2D.Double();
                path.moveTo(xs[0], ys[0]);
                if (n == 1) path.lineTo(xs[0] + 0.1, ys[0]);
                for (int k = 0; k < n - 1; k++) {
                    int p0 = Math.max(0, k - 1), p3 = Math.min(n - 1, k + 2);
                    path.curveTo(xs[k] + (xs[k + 1] - xs[p0]) / 6, ys[k] + (ys[k + 1] - ys[p0]) / 6,
                                 xs[k + 1] - (xs[p3] - xs[k]) / 6, ys[k + 1] - (ys[p3] - ys[k]) / 6,
                                 xs[k + 1], ys[k + 1]);
                }
                if (halo) {
                    Color col = g.getColor();
                    g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(curDark ? new Color(0, 0, 0, 150) : new Color(255, 255, 255, 170));
                    g.draw(path);
                    g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(col);
                }
                g.draw(path);
                i = j + 1;
            }
        }

        int yOf(double hz, double fl, double ce, int h, int pad) {
            double t = Math.max(0, Math.min(1, (hz - fl) / (ce - fl)));
            return h - pad - (int) (t * (h - 2 * pad));
        }
    }

    /** The dragged range's box, drawn once over both the pitch panel and the waveform so the two can never disagree. */
    class SelOverlay extends JComponent {
        @Override public boolean contains(int x, int y) { return false; } // never steals mouse events
        @Override protected void paintComponent(Graphics g0) {
            Seg r = range;
            if (r == null) return;
            Graphics2D g = (Graphics2D) g0;
            int x1 = (int) wave.msToX(r.s), x2 = (int) wave.msToX(r.e), h = getHeight();
            g.setColor(wave.inkSel);
            g.fillRect(x1, 0, Math.max(2, x2 - x1 + 1), 2);
            g.fillRect(x1, 0, 2, h);
            g.fillRect(x2 - 1, 0, 2, h);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            installLaf();
            Piyingxi app = new Piyingxi();
            app.setVisible(true);
            if (args.length > 0) app.open(new File(args[0]));
            else {
                File last = new File(PREFS.get("lastFile", ""));
                if (last.isFile()) app.open(last);
            }
        });
    }
}
