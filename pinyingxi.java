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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Piyingxi: minimal WorkAudioBook-style shadowing tool: mp3 + waveform + silence split + SRT. */
public class Piyingxi extends JFrame {

    static class Seg {
        long s, e; String text = "";
        String[] ach; long[] ams; // characters placed under their syllables by "Align characters", and their centre times (ms)
        Seg(long s, long e) { this.s = s; this.e = e; }
    }

    short[] pcm = new short[0];
    int rate = 44100;
    final List<Seg> segs = new ArrayList<>();
    final List<Long> sylMarks = new ArrayList<>();             // syllable boundaries (ms), set with S
    final Map<Long, Integer> sylTones = new HashMap<>();       // tone of the syllable that starts at this time (ms): 1-4, 5 = neutral
    final Map<Long, long[]> pendingAlign = new HashMap<>();    // alignments read from the .piyingxi file, applied once the subtitles are loaded
    Seg sel;
    File srt;
    boolean updating;

    static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(Piyingxi.class);
    static final SimpleAttributeSet CENTER = new SimpleAttributeSet();
    static { StyleConstants.setAlignment(CENTER, StyleConstants.ALIGN_CENTER); }
    static boolean curDark;

    /** Contour colours, chosen under Settings > Contour colours. A chosen colour is used on both themes; unset = the theme's default. */
    static Color userColour(String key, Color dark, Color light) {
        String v = PREFS.get(key, "");
        if (!v.isEmpty()) try { return new Color(Integer.parseInt(v)); } catch (NumberFormatException ignored) { }
        return curDark ? dark : light;
    }
    static Color sourceColour() { return userColour("col.source", new Color(90, 200, 255), new Color(0, 70, 200)); }
    static Color shadowColour() { return userColour("col.shadow", new Color(255, 140, 0), new Color(255, 140, 0)); }
    static Color modelColour()  { return userColour("col.model", new Color(190, 190, 195), new Color(105, 105, 112)); }
    static Color colourFor(String key) {
        return key.equals("col.source") ? sourceColour() : key.equals("col.shadow") ? shadowColour() : modelColour();
    }

    JPanel coloursPanel() {
        JPanel dlg = new JPanel(new BorderLayout());
        String[][] rows = {
            {"col.source", "Source: the original audio's pitch contour"},
            {"col.shadow", "Shadow: your recordings' pitch contours"},
            {"col.model", "Model: the synthetic tone contour and its tone numbers"}};
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6); gc.anchor = GridBagConstraints.WEST;
        for (int r = 0; r < rows.length; r++) {
            final String key = rows[r][0], title = rows[r][1];
            JPanel sw = new JPanel() {
                @Override protected void paintComponent(Graphics g) { g.setColor(colourFor(key)); g.fillRect(0, 0, getWidth(), getHeight()); }
            };
            sw.setPreferredSize(new Dimension(44, 22));
            sw.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            Runnable apply = () -> { sw.repaint(); pitch.repaint(); charBar.repaint(); };
            JButton choose = btn("Choose...", () -> {
                Color c = JColorChooser.showDialog(dlg, "Choose colour: " + title, colourFor(key));
                if (c != null) { PREFS.put(key, String.valueOf(c.getRGB() & 0xFFFFFF)); apply.run(); }
            });
            JButton def = btn("Default", () -> { PREFS.remove(key); apply.run(); });
            gc.gridy = r;
            gc.gridx = 0; grid.add(new JLabel(rows[r][1]), gc);
            gc.gridx = 1; grid.add(sw, gc);
            gc.gridx = 2; grid.add(choose, gc);
            gc.gridx = 3; grid.add(def, gc);
        }
        JPanel flow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        flow.add(grid);
        dlg.add(flow, BorderLayout.NORTH);
        return dlg;
    }

    static Color boxBg() { return curDark ? new Color(30, 31, 34) : Color.WHITE; }

    CardLayout subCards; JPanel subPanel, hiddenPanel;
    JToggleButton drawTog;
    JSlider lenSlider; JLabel lenLbl;
    JCheckBoxMenuItem srcLine, srcSpec;

    void applyTextStyle() {
        text.setBackground(boxBg());
        if (hiddenPanel != null) hiddenPanel.setBackground(boxBg());
        text.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        centreSubtitle();
    }

    JScrollPane subScroll;

    /** Centres the subtitle vertically in its window by sharing the spare height above and below it (horizontally it is a centred paragraph). */
    void centreSubtitle() {
        if (subScroll == null) return;
        int vh = subScroll.getViewport().getHeight();
        if (vh <= 0 || text.getWidth() <= 0) return;
        try {
            java.awt.geom.Rectangle2D r = text.modelToView2D(text.getDocument().getLength());
            if (r == null) return;
            Insets in = text.getInsets();
            double contentH = r.getMaxY() - in.top;
            int top = (int) Math.max(0, Math.floor((vh - contentH) / 2));
            if (in.top != top || in.bottom != 0) text.setBorder(BorderFactory.createEmptyBorder(top, 12, 0, 12));
        } catch (javax.swing.text.BadLocationException ignored) { }
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
    final CharBar charBar = new CharBar();
    JPanel stackPanel;
    boolean waveOnTop = PREFS.getBoolean("waveOnTop", true); // bar order: waveform, tone bar, aligned characters (or tone bar first)
    JCheckBox swapItem, pitchChk;

    // ---------------------------------------------------------------- shadowing session timer
    JButton sessionBtn;
    javax.swing.Timer sessionTimer;
    long sessionEndMs;
    boolean sessionRunning;

    static int sessionMinutes() { return Math.max(1, Math.min(180, PREFS.getInt("sessionMin", 20))); }

    /** Start session / (while running) stop it early. */
    void toggleSession() {
        if (sessionRunning) { endSession(false); return; }
        sessionRunning = true;
        sessionEndMs = System.currentTimeMillis() + sessionMinutes() * 60_000L;
        if (sessionTimer == null) sessionTimer = new javax.swing.Timer(250, e -> tickSession());
        sessionTimer.start();
        sessionBtn.setToolTipText("Time left in this shadowing session. Click to stop it early.");
        status.setText(" Shadowing session started: " + sessionMinutes() + " minutes. Click the timer to stop it early.");
        tickSession();
    }

    void tickSession() {
        long left = sessionEndMs - System.currentTimeMillis();
        if (left <= 0) { endSession(true); return; }
        long s = (left + 999) / 1000;
        String t = String.format("%d:%02d", s / 60, s % 60);
        if (!t.equals(sessionBtn.getText())) sessionBtn.setText(t);
    }

    void endSession(boolean finished) {
        sessionRunning = false;
        if (sessionTimer != null) sessionTimer.stop();
        sessionBtn.setText("Start session");
        sessionBtn.setToolTipText("Start a timed shadowing session (set its length under Settings > Shadowing session). A light bell rings when time is up.");
        if (finished) { playBell(); status.setText(" Time is up - well done! Your " + sessionMinutes() + "-minute shadowing session is finished."); }
        else status.setText(" Shadowing session stopped.");
    }

    /** One light bell: a few decaying, slightly inharmonic partials, played quietly on the default output. */
    void playBell() {
        Thread t = new Thread(() -> {
            try {
                float sr = 44100f;
                int n = (int) (sr * 2.4);
                byte[] buf = new byte[n * 2];
                double f0 = 1318.5; // about E6
                double[] mult = {1.0, 2.76, 5.4}, amp = {1.0, 0.42, 0.16}, dec = {2.0, 3.4, 6.0};
                for (int i = 0; i < n; i++) {
                    double tt = i / sr, v = 0;
                    for (int k = 0; k < 3; k++) v += amp[k] * Math.exp(-dec[k] * tt) * Math.sin(2 * Math.PI * f0 * mult[k] * tt);
                    double attack = Math.min(1, tt / 0.004); // no click at the start
                    short sv = (short) Math.max(-32767, Math.min(32767, v * attack * 0.2 * 32767)); // kept quiet: a light ding
                    buf[2 * i] = (byte) (sv & 0xff);
                    buf[2 * i + 1] = (byte) ((sv >> 8) & 0xff);
                }
                AudioFormat af = new AudioFormat(sr, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(af)) {
                    line.open(af);
                    line.start();
                    line.write(buf, 0, buf.length);
                    line.drain();
                }
            } catch (Exception ex) { Toolkit.getDefaultToolkit().beep(); } // no audio output: fall back to the system beep
        }, "bell");
        t.setDaemon(true);
        t.start();
    }

    JPanel sessionPanel() {
        JPanel p = column();
        JSpinner mins = new JSpinner(new SpinnerNumberModel(sessionMinutes(), 1, 180, 1));
        mins.addChangeListener(e -> PREFS.putInt("sessionMin", ((Number) mins.getValue()).intValue()));
        p.add(row(new JLabel("Default session length:"), mins, new JLabel("minutes")));
        p.add(note("The <b>Start session</b> button in the toolbar (under the settings gear) counts down this long and then rings a light bell. "
                + "While it runs the button shows the time left; click it to stop early. A new length applies to the next session you start."));
        p.add(row(btn("Play the bell", this::playBell)));
        p.add(Box.createVerticalGlue());
        return p;
    }
    JDialog settingsDlg;
    final JComponent selOverlay = new SelOverlay(); // draws the dragged range once, across both panels
    volatile float[] f0 = new float[0];
    volatile int pitchGen;
    final JTextPane text = new JTextPane();
    final JScrollPane sp;
    final JLabel status = new JLabel(" Open an mp3 file (File > Open), or press the record button to start a new recording.");
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
            public Dimension preferredLayoutSize(Container p) { return new Dimension(wave.getPreferredSize().width, 220 + (charBar.isVisible() ? barHeight() : 0)); }
            public Dimension minimumLayoutSize(Container p) { return new Dimension(1, 120); }
            public void layoutContainer(Container p) {
                int w = p.getWidth(), h = p.getHeight();
                selOverlay.setBounds(0, 0, w, h);
                int cb = charBar.isVisible() ? Math.min(barHeight(), h / 3) : 0, avail = h - cb;
                if (pitch.isVisible()) {
                    JComponent top = waveOnTop ? wave : pitch, bottom = waveOnTop ? pitch : wave;
                    int th = avail / 2;
                    top.setBounds(0, 0, w, th); bottom.setBounds(0, th, w, avail - th);
                } else wave.setBounds(0, 0, w, avail);
                charBar.setBounds(0, avail, w, cb);
            }
        });
        stackPanel = stack;
        stack.add(pitch);
        stack.add(wave);
        stack.add(charBar);
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
            void ch() { if (updating || sel == null) return; sel.text = text.getText(); saveTimer.restart(); updateCharBar(); wave.repaint(); SwingUtilities.invokeLater(Piyingxi.this::centreSubtitle); }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        JPanel playbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        recBtn = iconBtn(GlyphIcon.REC, "Record (R): record the microphone for the length of the dragged range or selected segment", this::recordSegment);
        playbar.add(recBtn);
        playbar.add(iconBtn(GlyphIcon.PREV, "Previous segment (Left arrow)", () -> step(-1)));
        playBtn = iconBtn(GlyphIcon.PLAY, "Play / pause (Space)", this::togglePlay);
        playbar.add(playBtn);
        playbar.add(iconBtn(GlyphIcon.FF, "Go to the next segment and play it straight away", this::nextAndPlay));
        playbar.add(iconBtn(GlyphIcon.NEXT, "Next segment (Right arrow)", () -> step(1)));
        loop.setText("");
        loop.setIcon(new GlyphIcon(GlyphIcon.LOOP));
        loop.setToolTipText("Loop the selected segment");
        loop.setFocusable(false);
        loop.setPreferredSize(new Dimension(56, 38));
        playbar.add(loop);
        splitBtn = iconBtn(GlyphIcon.SPLIT, "", this::cutAction); // scissors, or a boundary marker while Syllable boundaries is ticked (see updateCutButton)
        playbar.add(splitBtn);
        playbar.add(iconBtn(GlyphIcon.UNDO, "Undo the last change (Ctrl+Z)", this::undo));
        JPanel viewbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        viewbar.add(symBtn("-", "Zoom out (Ctrl+-)", () -> zoom(1.5)));
        viewbar.add(symBtn("+", "Zoom in (Ctrl++)", () -> zoom(1 / 1.5)));
        viewbar.add(textBtn("All", "Zoom all the way out (A)", this::zoomAll));
        viewbar.add(textBtn("Selection", "Zoom in on the selection (same as double-clicking it)", this::zoomSelection));
        JPanel leftbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        leftbar.add(btn("Auto-split", this::autoSplit));
        JButton alignBtn = btn("Align characters", this::alignCharacters);
        alignBtn.setToolTipText("Put each character of the subtitle (punctuation ignored) centred under its syllable segment. Mark syllable boundaries on the waveform first (click, then S); the number of syllable segments in the selection must equal the number of characters.");
        leftbar.add(alignBtn);
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
        tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); // slim strip: one tab per document (a file or a new recording); hidden while nothing is open
        upper.add(tabBar, BorderLayout.NORTH);
        refreshTabs();
        upper.add(transport, BorderLayout.SOUTH);
        subCards = new CardLayout();
        subPanel = new JPanel(subCards);
        subScroll = new JScrollPane(text);
        subPanel.add(subScroll, "text");
        ComponentAdapter recentre = new ComponentAdapter() { public void componentResized(ComponentEvent e) { SwingUtilities.invokeLater(Piyingxi.this::centreSubtitle); } };
        subScroll.getViewport().addComponentListener(recentre);
        text.addComponentListener(recentre);
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
        sylTog = new JCheckBox("Syllable boundaries", showSyl);
        sylTog.setToolTipText("Show or hide the syllable boundary lines");
        sylTog.setFocusable(false);
        sylTog.addActionListener(e -> setShowSyl(sylTog.isSelected()));
        tools.add(sylTog);
        modelBtn = new JToggleButton("qTA", modelOn);
        modelBtn.setToolTipText("The qTA tone model: pressed = on. Switching it on shows the tone bar, characters to click and the dashed model contour, and regenerates the "
                + "contour from your syllable tones (re-attaching tones that lost their syllable). Press again to hide it when you are done. "
                + "Click a syllable's character in the bottom bar to pick its tone; the contour is scaled to the latest recording's pitch range, to compare against.");
        modelBtn.setFocusable(false);
        modelBtn.addActionListener(e -> { if (modelBtn.isSelected()) refreshQta(); else setModelOn(false); });
        tools.add(modelBtn);
        sandhiTog = new JCheckBox("Sandhi", sandhiOn);
        sandhiTog.setToolTipText("Apply the tone system's sandhi rules (e.g. 3+3 -> 3*+3, \u4e00 and \u4e0d, neutral-tone pitch). Ticked: the tone bar shows each syllable's final tone. Unticked: every tone is shown and modelled as written");
        sandhiTog.setFocusable(false);
        sandhiTog.addActionListener(e -> setSandhi(sandhiTog.isSelected()));
        sandhiTog.setVisible(modelOn);
        tools.add(sandhiTog);
        JPanel toolRow = new JPanel(new BorderLayout());
        toolRow.add(tools, BorderLayout.CENTER);
        sessionBtn = btn("Start session", this::toggleSession);
        sessionBtn.setToolTipText("Start a timed shadowing session (set its length under Settings > Shadowing session). A light bell rings when time is up.");
        sessionBtn.setPreferredSize(new Dimension(sessionBtn.getPreferredSize().width + 16, sessionBtn.getPreferredSize().height));
        JPanel sessionBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 4)); // the right-hand end of the bar, under the settings gear
        sessionBox.add(sessionBtn);
        toolRow.add(sessionBox, BorderLayout.EAST);
        add(toolRow, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);


        JMenuBar mb = new JMenuBar();
        pitch.setVisible(PREFS.getBoolean("showPitch", true));
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
        mb.add(Box.createHorizontalGlue()); // pushes the gear to the far right
        JButton gear = new JButton("\u2699\uFE0E");
        gear.setFont(gear.getFont().deriveFont(Font.PLAIN, 16f));
        gear.setMargin(new Insets(0, 5, 0, 5));
        gear.setToolTipText("Settings");
        gear.setFocusable(false);
        gear.putClientProperty("JButton.buttonType", "toolBarButton"); // flat until hovered (FlatLaf)
        gear.setMaximumSize(gear.getPreferredSize());
        gear.addActionListener(e -> openSettings());
        mb.add(gear);
        setJMenuBar(mb);
        registerActs();
        acts.get("new").item = newItem; acts.get("open").item = openItem; acts.get("save").item = saveItem; acts.get("close").item = closeItem;
        applyKeys();

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

    /** Simple drawn button graphics: they take the button's colour, so they suit any theme and look the same on every computer. */
    static class GlyphIcon implements Icon {
        static final int PREV = 1, NEXT = 2, PLAY = 3, FF = 4, LOOP = 5, REC = 6, STOP = 7, SPLIT = 8, MARK = 9, UNDO = 10, GEAR = 11;
        final int kind;
        GlyphIcon(int kind) { this.kind = kind; }
        public int getIconWidth() { return 28; }
        public int getIconHeight() { return 26; }

        static void arrowHead(Graphics2D g, double ex, double ey, double dx, double dy) {
            double len = Math.hypot(dx, dy); dx /= len; dy /= len;
            double px = -dy, py = dx;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(ex + dx * 6, ey + dy * 6);
            p.lineTo(ex + px * 5, ey + py * 5);
            p.lineTo(ex - px * 5, ey - py * 5);
            p.closePath();
            g.fill(p);
        }

        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            boolean on = c.isEnabled();
            Color col = on ? c.getForeground() : new Color(150, 150, 150);
            if (kind == REC || kind == STOP) col = on ? new Color(225, 45, 45) : new Color(150, 150, 150);
            g.setColor(col);
            g.translate(x, y);
            BasicStroke thick = new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            switch (kind) {
                case PREV: // back arrow
                    g.setStroke(thick);
                    g.drawLine(24, 13, 6, 13);
                    { Path2D.Double h = new Path2D.Double(); h.moveTo(13, 5); h.lineTo(5, 13); h.lineTo(13, 21); g.draw(h); }
                    break;
                case NEXT: // forward arrow
                    g.setStroke(thick);
                    g.drawLine(4, 13, 22, 13);
                    { Path2D.Double h = new Path2D.Double(); h.moveTo(15, 5); h.lineTo(23, 13); h.lineTo(15, 21); g.draw(h); }
                    break;
                case PLAY:
                    g.fillPolygon(new int[]{8, 8, 23}, new int[]{3, 23, 13}, 3);
                    break;
                case FF: // fast forward: two triangles
                    g.fillPolygon(new int[]{2, 2, 14}, new int[]{4, 22, 13}, 3);
                    g.fillPolygon(new int[]{14, 14, 26}, new int[]{4, 22, 13}, 3);
                    break;
                case LOOP: { // a circling arrow
                    g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Arc2D.Double(4, 5, 20, 16, 40, 290, java.awt.geom.Arc2D.OPEN));
                    arrowHead(g, 14 + 10 * Math.cos(Math.toRadians(330)), 13 - 8 * Math.sin(Math.toRadians(330)), 0.587, -0.81);
                    break;
                }
                case REC:
                    g.fillOval(5, 4, 18, 18);
                    break;
                case STOP:
                    g.fillRect(6, 5, 16, 16);
                    break;
                case SPLIT: // scissors
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(8, 9, 25, 20);
                    g.drawLine(8, 17, 25, 6);
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(1, 3, 7, 7);
                    g.drawOval(1, 16, 7, 7);
                    break;
                case MARK: // a boundary: a dotted line with a marker on top
                    g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
                    g.drawLine(14, 10, 14, 25);
                    g.fillPolygon(new int[]{7, 21, 14}, new int[]{1, 1, 10}, 3);
                    break;
                case GEAR: { // a cog: ring, eight teeth, hub
                    g.setStroke(new BasicStroke(3.2f));
                    g.drawOval(7, 6, 14, 14);
                    g.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        g.drawLine((int) Math.round(14 + 8.2 * Math.cos(a)), (int) Math.round(13 + 8.2 * Math.sin(a)),
                                (int) Math.round(14 + 11.8 * Math.cos(a)), (int) Math.round(13 + 11.8 * Math.sin(a)));
                    }
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(11, 10, 6, 6);
                    break;
                }
                case UNDO: { // a curved arrow turning back
                    g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Arc2D.Double(6, 6, 16, 16, -20, 200, java.awt.geom.Arc2D.OPEN));
                    arrowHead(g, 6, 14, 0, 1);
                    break;
                }
                default:
            }
            g.dispose();
        }
    }
    static final GlyphIcon IC_REC = new GlyphIcon(GlyphIcon.REC), IC_STOP = new GlyphIcon(GlyphIcon.STOP),
            IC_SPLIT = new GlyphIcon(GlyphIcon.SPLIT), IC_MARK = new GlyphIcon(GlyphIcon.MARK);

    JButton iconBtn(int kind, String tip, Runnable r) {
        JButton b = btn("", r);
        b.setIcon(new GlyphIcon(kind));
        b.setToolTipText(tip);
        b.setPreferredSize(new Dimension(56, 38));
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
    // ---------------------------------------------------------------- the Settings window
    static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        return p;
    }

    static JPanel row(Component... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component c : cs) r.add(c);
        return r;
    }

    static JLabel note(String html) {
        JLabel l = new JLabel("<html><div style='width:520px'>" + html + "</div></html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
        l.setForeground(Color.GRAY);
        return l;
    }

    JRadioButton radio(ButtonGroup grp, String text, boolean on, Runnable act) {
        JRadioButton r = new JRadioButton(text, on);
        r.setFocusable(false);
        r.addActionListener(e -> act.run());
        grp.add(r);
        return r;
    }

    JCheckBox check(String text, boolean on, String tip, java.util.function.Consumer<Boolean> act) {
        JCheckBox c = new JCheckBox(text, on);
        c.setFocusable(false);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (tip != null) c.setToolTipText(tip);
        c.addActionListener(e -> act.accept(c.isSelected()));
        return c;
    }

    JPanel displayPanel() {
        JPanel p = column();
        p.add(fontPanel());
        ButtonGroup tg = new ButtonGroup();
        String th = PREFS.get("theme", "system");
        JPanel themeRow = row(new JLabel("Theme:"));
        for (String[] t : new String[][]{{"system", "Follow system"}, {"light", "Light"}, {"dark", "Dark"}})
            themeRow.add(radio(tg, t[1], th.equals(t[0]), () -> { PREFS.put("theme", t[0]); refreshTheme(true); }));
        p.add(themeRow);
        pitchChk = check("Show the tone contour area (pitch panel and character bar)", PREFS.getBoolean("showPitch", true),
                "Also on a key: see Keyboard shortcuts (T by default).", this::setShowPitch);
        p.add(pitchChk);
        ButtonGroup sg = new ButtonGroup();
        double curSigma = PREFS.getDouble("specSigma", 2.5);
        JPanel smoothRow = row(new JLabel("Spectrogram smoothing:"));
        for (Object[] o : new Object[][]{{"Off", 0.0}, {"Gaussian 2.5", 2.5}, {"Gaussian 3.5", 3.5}})
            smoothRow.add(radio(sg, (String) o[0], Math.abs(curSigma - (Double) o[1]) < 0.01, () -> { PREFS.putDouble("specSigma", (Double) o[1]); pitch.repaint(); }));
        p.add(smoothRow);
        swapItem = check("Waveform bar above tone bar", waveOnTop, "Untick to put the tone (pitch) bar on top and the waveform below it.", this::setWaveOnTop);
        p.add(swapItem);
        p.add(note("The bar with the tone symbols and characters is always at the bottom."));
        p.add(Box.createVerticalGlue());
        return p;
    }

    JPanel recordingPanel() {
        JPanel p = column();
        p.add(row(new JLabel("Microphone:"), micBox));
        p.add(row(btn("Silence detection...", this::silenceDialog)));
        p.add(check("Show spectrogram while recording", liveView,
                "While a new recording is being made, draw its spectrogram and pitch contour as it grows (uses more CPU). When off, only the waveform grows; the analysis appears when you stop.",
                on -> { liveView = on; PREFS.putBoolean("liveView", on); }));
        p.add(note("When this is off, only the waveform grows while you record; the spectrogram and pitch appear when you stop. It takes effect from the next recording."));
        p.add(Box.createVerticalGlue());
        return p;
    }

    JPanel toneModelPanel() {
        JPanel p = column();
        modelChk = check("Show the qTA tone model (tone bar, characters to click, model contour)", modelOn,
                "The toolbar's qTA button does the same: pressed = on, press again to hide.", this::setModelOn);
        p.add(modelChk);
        p.add(row(btn("Regenerate model contour", this::refreshQta)));
        ButtonGroup lg = new ButtonGroup();
        JPanel labelRow = row(new JLabel("Tone bar shows:"));
        labelRow.add(radio(lg, "Tone symbols (\u02c9 \u02ca \u02c7 \u02cb \u02d9)", symMode(), () -> { PREFS.put("toneShow", "sym"); charBar.repaint(); }));
        labelRow.add(radio(lg, "Tone numbers", !symMode(), () -> { PREFS.put("toneShow", "num"); charBar.repaint(); }));
        p.add(labelRow);
        ButtonGroup mg = new ButtonGroup();
        JPanel scopeRow = row(new JLabel("Show the model contour for:"));
        scopeRow.add(radio(mg, "The selected segment only", scopeSel(), () -> { PREFS.put("modelScope", "sel"); pitch.repaint(); }));
        scopeRow.add(radio(mg, "All segments", !scopeSel(), () -> { PREFS.put("modelScope", "all"); pitch.repaint(); }));
        p.add(scopeRow);
        p.add(note("Each segment is modelled on its own: the target approximation starts afresh at the segment's start and stops at its end, so nothing is carried across the gap between phrases. "
                + "Split a segment where there is a pause, so the two parts are modelled separately."));
        JSpinner decl = new JSpinner(new SpinnerNumberModel(declSt(), 0.0, 6.0, 0.1));
        decl.setEditor(new JSpinner.NumberEditor(decl, "0.0"));
        decl.addChangeListener(e -> { PREFS.putDouble("declSt", ((Number) decl.getValue()).doubleValue()); pitch.repaint(); });
        p.add(row(new JLabel("Sentence declination:"), decl, new JLabel("semitones per second (0 = none)")));
        p.add(note("Pitch drifts downward across the segment by this amount, pivoting on its midpoint, so the first half sits a little above the recording's pitch range centre and the second half below it. "
                + "It is added to the targets themselves, so the model's transitions follow the drift. A rate of about 1 is a rough typical value; adjust it to your speakers."));
        p.add(note("The tone system, sandhi rules, and which characters have fixed tones are on the <b>Tone systems</b> tab. The qTA button in the toolbar is a switch for the model (pressed = shown); the Sandhi box appears next to it while it is on. Choose the tone system on the <b>Tone systems</b> tab."));
        p.add(Box.createVerticalGlue());
        return p;
    }

    void openSettings() {
        if (settingsDlg == null) {
            settingsDlg = new JDialog(this, "Settings", false);
            JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT); // the sections are stacked down the left
            tabs.putClientProperty("JTabbedPane.tabWidthMode", "equal");   // FlatLaf: same width for every section
            tabs.putClientProperty("JTabbedPane.tabAreaAlignment", "leading");
            tabs.addTab("Appearance", displayPanel());
            tabs.addTab("Recording", recordingPanel());
            tabs.addTab("Shadowing session", sessionPanel());
            tabs.addTab("Tone model", toneModelPanel());
            tabs.addTab("Tone systems", toneSystemsPanel());
            tabs.addTab("Colours", coloursPanel());
            tabs.addTab("Keyboard shortcuts", shortcutsPanel());
            settingsDlg.add(tabs);
            settingsDlg.setSize(940, 860);
            settingsDlg.setLocationRelativeTo(this);
            settingsDlg.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent e) { settingsDlg = null; toneDlgRefresh = null; swapItem = null; modelChk = null; pitchChk = null; wave.requestFocusInWindow(); } });
            settingsDlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        }
        settingsDlg.setVisible(true);
        settingsDlg.toFront();
    }

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

    /** The subtitle font controls, shown directly in the Appearance tab. Changes apply as you make them. */
    JPanel fontPanel() {
        Font cur = text.getFont();
        String[] all = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> cjk = new ArrayList<>();
        for (String n : all) if (new Font(n, Font.PLAIN, 12).canDisplay('\u597d')) cjk.add(n);

        JCheckBox only = new JCheckBox("Only show fonts that support Chinese", true);
        only.setFocusable(false);
        JComboBox<String> fam = new JComboBox<>();
        fam.setMaximumRowCount(16);
        fam.setPreferredSize(new Dimension(260, 26));
        JSpinner size = new JSpinner(new SpinnerNumberModel(cur.getSize(), 8, 120, 1));
        JCheckBox boldBox = new JCheckBox("Bold", cur.isBold());
        boldBox.setFocusable(false);
        JLabel prev = new JLabel("\u4f60\u597d\uff0c\u4e16\u754c  Hello 123  n\u01d0 h\u01ceo", SwingConstants.CENTER);
        prev.setFont(cur);
        final boolean[] busy = {false};
        Runnable apply = () -> {
            if (busy[0] || fam.getSelectedItem() == null) return;
            String f = (String) fam.getSelectedItem();
            int sz = ((Number) size.getValue()).intValue();
            Font nf = new Font(f, boldBox.isSelected() ? Font.BOLD : Font.PLAIN, sz);
            prev.setFont(nf);
            text.setFont(nf);
            PREFS.putBoolean("fontBold", boldBox.isSelected());
            PREFS.put("fontFamily", f);
            PREFS.putInt("fontSize", sz);
            applyTextStyle();
            updateCharBar(); // the characters bar follows the subtitle font size
            if (stackPanel != null) { stackPanel.revalidate(); stackPanel.repaint(); }
        };
        Runnable fill = () -> {
            busy[0] = true;
            Object keep = fam.getSelectedItem() != null ? fam.getSelectedItem() : cur.getFamily();
            fam.removeAllItems();
            for (String n : only.isSelected() ? cjk : Arrays.asList(all)) fam.addItem(n);
            fam.setSelectedItem(keep);
            busy[0] = false;
        };
        fam.addActionListener(e -> apply.run());
        size.addChangeListener(e -> apply.run());
        boldBox.addActionListener(e -> apply.run());
        only.addActionListener(e -> fill.run());
        fill.run();

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Subtitle font"));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(row(new JLabel("Font:"), fam));
        p.add(row(new JLabel("Size:"), size, boldBox));
        p.add(row(only));
        JPanel pv = new JPanel(new BorderLayout());
        pv.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        pv.setAlignmentX(Component.LEFT_ALIGNMENT);
        pv.setPreferredSize(new Dimension(560, 70));
        pv.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        pv.add(prev);
        p.add(pv);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 20));
        return p;
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
        for (long m : sylMarks) sb.append("M,").append(m).append('\n');
        for (Map.Entry<Long, Integer> t : sylTones.entrySet()) sb.append("T,").append(t.getKey()).append(',').append(t.getValue()).append('\n');
        if (!sylTones.isEmpty()) sb.append("S,").append(activeSys.name).append('\n');
        int aligned = 0;
        for (Seg g : segs) if (alignValid(g)) {
            StringBuilder ms = new StringBuilder();
            for (int k = 0; k < g.ams.length; k++) { if (k > 0) ms.append(' '); ms.append(g.ams[k]); }
            sb.append("A,").append(g.s).append(',').append(ms).append('\n');
            aligned++;
        }
        if (n == 0 && runs == 0 && globalOct == 0 && sylMarks.isEmpty() && sylTones.isEmpty() && aligned == 0 && !regFile.exists()) return;
        try { Files.write(regFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ex) { status.setText(" Could not save pitch corrections: " + ex.getMessage()); }
    }

    void loadRegs() {
        regs.clear(); globalOct = 0; sylMarks.clear(); sylTones.clear(); pendingAlign.clear(); pendingToneSys = null;
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
                else if (p[0].equals("M") && p.length >= 2) sylMarks.add(Long.parseLong(p[1]));
                else if (p[0].equals("T") && p.length >= 3) sylTones.put(Long.parseLong(p[1]), Integer.parseInt(p[2]));
                else if (p[0].equals("S") && p.length >= 2) pendingToneSys = String.join(",", Arrays.copyOfRange(p, 1, p.length));
                else if (p[0].equals("A") && p.length >= 3) {
                    String[] vs = p[2].trim().split(" ");
                    long[] a = new long[vs.length];
                    for (int k = 0; k < vs.length; k++) a[k] = Long.parseLong(vs[k]);
                    pendingAlign.put(Long.parseLong(p[1]), a);
                }
                else if (p[0].equals("R") && p.length >= 6) {
                    Reg r = new Reg(Long.parseLong(p[1]), Long.parseLong(p[2]));
                    r.lo = Integer.parseInt(p[3]); r.hi = Integer.parseInt(p[4]); r.oct = Integer.parseInt(p[5]);
                    regs.add(r);
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(sylMarks);
    }

    /** After the subtitles are loaded: give each segment back the alignment saved for it (if its text still has the same number of characters). */
    void applyPendingAlign() {
        for (Seg g : segs) {
            long[] a = pendingAlign.get(g.s);
            if (a == null) continue;
            List<String> ch = subtitleChars(g.text);
            if (ch.size() == a.length) { g.ach = ch.toArray(new String[0]); g.ams = a; }
        }
        pendingAlign.clear();
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
    /** Up / Down: the octave slider moved by one step, which applies to the dragged range if there is one, else the selected segment, else the whole audio. */
    void octaveStep(int d) {
        if (pcm.length == 0) return;
        int cur = octSlider.getValue(), v = Math.max(octSlider.getMinimum(), Math.min(octSlider.getMaximum(), cur + d));
        if (v == cur) { status.setText(" Octave is already at " + (v > 0 ? "+" + v : String.valueOf(v)) + " - the furthest it goes."); return; }
        octSlider.setValue(v);
        status.setText(" Octave " + (v > 0 ? "+" + v : String.valueOf(v)) + " for " + (range != null ? "the dragged selection" : sel != null ? "the selected segment" : "the whole audio") + ".");
    }

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

    volatile boolean loading; // a file is being decoded: no tab changes meanwhile

    static File srtFor(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return new File(f.getAbsoluteFile().getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".srt");
    }

    /** Opens an audio file in a NEW tab (the first file simply fills the empty window); a file that is already open is just switched to. */
    void open(File f) {
        if (recording) { status.setText(" Finish recording first."); return; }
        if (loading) { status.setText(" Wait for the other file to finish loading."); return; }
        stopPlay(); saveSrt();
        File want = srtFor(f);
        for (Doc d : docs) { // already open: go to its tab
            File s = d == curDoc ? srt : d.srt;
            if (s != null && s.getAbsoluteFile().equals(want)) { switchTo(d); status.setText(" " + f.getName() + " is already open."); return; }
        }
        final Doc prev = curDoc;
        final boolean keepPrev = docs.contains(prev);
        if (keepPrev) { // like File > New: the file that is open stays in its tab, and this one gets a tab of its own
            Doc fresh = new Doc();
            fresh.showSpec = PREFS.getBoolean("showSpec", false); fresh.showLine = PREFS.getBoolean("showBlue", true); // keep the Source menu as it is
            switchTo(fresh);
        }
        final long resume = f.getAbsolutePath().equals(PREFS.get("lastFile", "")) ? PREFS.getLong("lastSegMs", -1) : -1;
        PREFS.put("lastFile", f.getAbsolutePath());
        if (resume < 0) PREFS.putLong("lastSegMs", -1);
        if (f.getAbsoluteFile().getParent() != null) PREFS.put("lastDir", f.getAbsoluteFile().getParent());
        status.setText(" Decoding " + f.getName() + " ...");
        final Doc target = curDoc;
        loading = true;
        new Thread(() -> {
            try {
                decode(f);
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    if (curDoc != target) switchTo(target);
                    ensureTab(curDoc);
                    unsaved = false; blankNew = false; liveNew = false;
                    String n = f.getName();
                    setTabName(curDoc, n);
                    int dot = n.lastIndexOf('.');
                    srt = new File(f.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".srt");
                    regFile = new File(f.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".piyingxi");
                    loadRegs();
                    segs.clear(); undoStack.clear(); select(null);
                    if (srt.exists()) loadSrt();
                    applyPendingAlign();
                    applyPendingToneSystem();
                    updateCharBar();
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
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    if (keepPrev && docs.contains(prev) && curDoc == target) switchTo(prev); // back to where you were
                    JOptionPane.showMessageDialog(this, "Could not read file: " + ex);
                });
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
        SwingUtilities.invokeLater(this::centreSubtitle);
        boolean typing = text.isFocusOwner(); // keep the caret only if the user is already typing
        if (s != null) {
            Rectangle r = new Rectangle((int) wave.msToX(s.s) - 40, 0, (int) (wave.msToX(s.e) - wave.msToX(s.s)) + 80, 1);
            wave.scrollRectToVisible(r);
        }
        if (s == null || !typing) wave.requestFocusInWindow();
        wave.repaint(); pitch.repaint(); selOverlay.repaint();
        updateCharBar();
        pitch.repaint();
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

    /** Shift+S: turn the dragged range into its own segment, or split the selected segment at the cursor. */
    void splitAction() {
        if (pcm.length == 0) return;
        if (range != null) { carve(range.s, range.e); return; }
        if (sel == null) { status.setText(" Click inside a segment (or drag a range), then press " + keyName("split") + " to split."); return; }
        long at = isPlaying() && playingOriginal && playMs >= 0 ? playMs : cursorMs;
        if (at <= sel.s + MIN_PIECE || at >= sel.e - MIN_PIECE) {
            status.setText(" Click inside the segment where you want to split (not at an edge), then press " + keyName("split") + ".");
            return;
        }
        pushUndo();
        Seg right = new Seg(at, sel.e);
        sel.e = at;
        segs.add(right); sortSegs();
        select(right); saveSrt();
        status.setText(" Split the segment at " + fmt(at) + ".");
    }

    // ---------------------------------------------------------------- syllable marks + character alignment
    /** The characters of a subtitle that count as syllables: letters, digits and Chinese characters; punctuation and spaces are ignored.
     *  Combining tone marks stay with the letter before them. */
    static List<String> subtitleChars(String t) {
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            int ty = Character.getType(cp);
            if ((ty == Character.NON_SPACING_MARK || ty == Character.COMBINING_SPACING_MARK) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) out.add(new String(Character.toChars(cp)));
        }
        return out;
    }

    /** After a syllable boundary moves: keep a segment's aligned characters centred under their syllables. */
    void recentreAlign(Seg s) {
        if (s.ach == null || s.ams == null || !alignValid(s)) return;
        List<Long> b = syllableBounds(s);
        if (b.size() - 1 != s.ach.length) return;
        for (int i = 0; i < s.ams.length; i++) s.ams[i] = (b.get(i) + b.get(i + 1)) / 2;
    }

    /** The first syllable boundary after `from` inside t, or t's end if there is none. */
    long nextBoundary(long from, Seg t) {
        long best = t.e;
        for (long m : sylMarks) if (m > from && m < best) best = m;
        return best;
    }

    /** True if the segment has an alignment that still matches its subtitle text. */
    boolean alignValid(Seg g) {
        return g.ach != null && g.ams != null && g.ach.length == g.ams.length && Arrays.asList(g.ach).equals(subtitleChars(g.text));
    }

    /** Start, the syllable boundaries strictly inside t, and end. The syllable segments are the gaps between neighbours. */
    List<Long> syllableBounds(Seg t) {
        List<Long> b = new ArrayList<>();
        b.add(t.s);
        for (long m : sylMarks) if (m > t.s && m < t.e) b.add(m);
        b.add(t.e);
        return b;
    }

    /** S: mark a syllable boundary at the cursor (or, while playing, at the playhead). S again on an existing mark removes it. */
    void markSyllable() {
        if (pcm.length == 0) return;
        if (sel == null) { status.setText(" Select a segment and click on its waveform where a syllable boundary goes, then press " + keyName("syl") + "."); return; }
        long at = range != null ? range.e                                       // a dragged selection: the boundary goes at its end
                : isPlaying() && playingOriginal && playMs >= 0 ? playMs : cursorMs;
        if (at <= sel.s + MIN_PIECE || at >= sel.e - MIN_PIECE) {
            status.setText(" Click inside the selected segment where the syllable boundary goes (or drag a selection that ends there), not at an edge, then press " + keyName("syl") + ".");
            return;
        }
        long tol = Math.max(MIN_PIECE, wave.xToMs(3));
        Long near = null;
        for (Long m : sylMarks) if (Math.abs(m - at) <= tol) { near = m; break; }
        pushUndoItem(markSnap());
        if (!showSyl) setShowSyl(true); // marking something you can't see would be confusing
        if (near != null) sylMarks.remove(near); else { sylMarks.add(at); Collections.sort(sylMarks); }
        int n = syllableBounds(sel).size() - 1;
        status.setText((near != null ? " Removed the syllable boundary." : " Syllable boundary at " + fmt(at) + ".")
                + " The selected segment now has " + n + " syllable segment" + (n == 1 ? "" : "s") + ".");
        saveRegs();
        wave.repaint(); selOverlay.repaint();
    }

    /** Align characters: when the selection has as many syllable segments as the subtitle has characters, centre each character under its syllable. */
    void alignCharacters() {
        if (sel == null) { status.setText(" Select a segment (or drag a range inside it) first."); return; }
        Seg t = range != null ? range : sel;
        List<String> ch = subtitleChars(sel.text);
        if (ch.isEmpty()) { status.setText(" The selected segment has no subtitle text to align."); Toolkit.getDefaultToolkit().beep(); return; }
        List<Long> b = syllableBounds(t);
        int n = b.size() - 1;
        if (n != ch.size()) {
            status.setText(" Can't align: the selection has " + n + " syllable segment" + (n == 1 ? "" : "s") + " but the subtitle has "
                    + ch.size() + " character" + (ch.size() == 1 ? "" : "s") + " (punctuation ignored). Mark boundaries with " + keyName("syl") + ".");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        pushUndo();
        sel.ach = ch.toArray(new String[0]);
        sel.ams = new long[n];
        for (int i = 0; i < n; i++) sel.ams[i] = (b.get(i) + b.get(i + 1)) / 2;
        saveRegs();
        status.setText(" Aligned " + n + " character" + (n == 1 ? "" : "s") + " under their syllables.");
        updateCharBar();
        wave.repaint();
    }

    /** The cut button: marks a syllable boundary at the cursor while Syllable boundaries is ticked, otherwise splits a segment. */
    void cutAction() { if (showSyl) markSyllable(); else splitAction(); }

    void updateCutButton() {
        if (splitBtn == null) return;
        splitBtn.setIcon(showSyl ? IC_MARK : IC_SPLIT);
        splitBtn.setToolTipText(showSyl
                ? "Mark a syllable boundary at the cursor (" + keyName("syl") + "). Untick Syllable boundaries and this button splits segments instead (" + keyName("split") + ")."
                : "Split (" + keyName("split") + "): split the selected segment at the click position, or make the dragged range its own segment. Tick Syllable boundaries and this button marks syllable boundaries instead (" + keyName("syl") + ").");
    }

    void setShowSyl(boolean on) {
        showSyl = on;
        PREFS.putBoolean("showSylMarks", on);
        if (sylTog != null) sylTog.setSelected(on);
        wave.repaint(); pitch.repaint(); selOverlay.repaint();
        updateCutButton();
    }

    /** Shows the characters bar when some segment has a valid alignment, hides it otherwise. */
    void updateCharBar() {
        boolean want = barHeight() > 0 && pitch.isVisible(); // hidden along with the tone contour area
        if (want != charBar.isVisible()) charBar.setVisible(want);
        charBar.setCursor(Cursor.getPredefinedCursor(toneUi() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        if (stackPanel != null) { stackPanel.revalidate(); stackPanel.repaint(); }
    }

    void setWaveOnTop(boolean on) {
        waveOnTop = on;
        PREFS.putBoolean("waveOnTop", on);
        if (swapItem != null) swapItem.setSelected(on);
        if (stackPanel != null) { stackPanel.revalidate(); stackPanel.repaint(); }
    }

    int alignFontPx() { return Math.min(text.getFont().getSize(), 26); }

    /** After a file loads: switch to the tone system its tone numbers were chosen with, if you have it. */
    void applyPendingToneSystem() {
        String n = pendingToneSys;
        pendingToneSys = null;
        if (n == null || sylTones.isEmpty()) return;
        for (ToneSys s : toneSystems) if (s.name.equals(n)) { setActiveSys(s); return; }
        status.setText(" This file's tones were set with the tone system \"" + n + "\", which isn't in your library (Settings > Tone systems and presets > Import). Showing them with \"" + activeSys.name + "\" for now.");
    }

    /** Shows or hides the tone contour area (the pitch panel); the waveform then fills the height. Bound to a key (T by default). */
    void setShowPitch(boolean on) {
        PREFS.putBoolean("showPitch", on);
        pitch.setVisible(on);
        if (pitchChk != null) pitchChk.setSelected(on);
        updateCharBar(); // the character / tone bar goes and comes back with it
        if (stackPanel != null) { stackPanel.revalidate(); stackPanel.repaint(); }
        status.setText(on ? " Tone contour area and character bar shown." : " Tone contour area and character bar hidden (" + keyName("pitchToggle") + " shows them again).");
    }

    void setSandhi(boolean on) {
        sandhiOn = on;
        PREFS.putBoolean("sandhiOn", on);
        if (sandhiTog != null) sandhiTog.setSelected(on);
        pitch.repaint(); charBar.repaint();
    }

    /** Gives tones that lost their syllable (a divider was removed or moved, or a segment's start edge was dragged) to the syllable they now fall in.
     *  Returns how many were moved. Tones are never discarded. */
    int repairTones() {
        Set<Long> starts = new HashSet<>();
        for (Seg s : segs) { List<Long> b = syllableBounds(s); for (int i = 0; i + 1 < b.size(); i++) starts.add(b.get(i)); }
        List<Long> orphans = new ArrayList<>();
        for (Long k : sylTones.keySet()) if (!starts.contains(k)) orphans.add(k);
        Collections.sort(orphans);
        int moved = 0;
        for (long k : orphans) {
            long target = -1;
            for (Seg s : segs) {
                if (k < s.s || k >= s.e) continue; // inside a segment: the syllable it now falls in
                List<Long> b = syllableBounds(s);
                for (int i = 0; i + 1 < b.size(); i++) if (k >= b.get(i) && k < b.get(i + 1)) { target = b.get(i); break; }
                break;
            }
            if (target < 0) { // just outside a segment (its start edge was dragged): that segment's first syllable
                long best = 400;
                for (Seg s : segs) if (Math.abs(s.s - k) <= best) { best = Math.abs(s.s - k); target = s.s; }
            }
            if (target >= 0 && !sylTones.containsKey(target)) { sylTones.put(target, sylTones.remove(k)); moved++; }
        }
        return moved;
    }

    /** The qTA button: switches the model on, regenerates its contour from the current syllables and tones, re-attaches orphaned tones, and says what is still missing. */
    void refreshQta() {
        boolean wasOn = modelOn;
        if (!wasOn) setModelOn(true);
        MarkUndo snap = markSnap();
        int moved = repairTones();
        if (moved > 0) { pushUndoItem(snap); saveRegs(); }
        int syl = 0, gaps = 0;
        List<Seg> todo = scopeSel() ? (sel == null ? new ArrayList<Seg>() : Arrays.asList(sel)) : segs;
        for (Seg s : todo) {
            List<Long> b = syllableBounds(s);
            Surf[] sf = surfaces(b, sylTones, activeSys, sandhiOn, sylChars(s, b, true).ch);
            for (Surf x : sf) { syl++; if (x == null) gaps++; }
        }
        updateCharBar();
        wave.repaint(); pitch.repaint(); charBar.repaint(); selOverlay.repaint();
        String where = scopeSel() ? "the selected segment" : "all segments";
        String msg = (wasOn ? " qTA model regenerated" : " qTA model on") + " for " + where + ".";
        if (todo.isEmpty()) msg += " Select a segment to see its model.";
        else msg += " " + (syl - gaps) + " of " + syl + " syllables modelled" + (gaps > 0 ? "; " + gaps + " without a tone (click their characters in the bottom bar to give them one)." : ".");
        if (moved > 0) msg += moved == 1 ? " Re-attached 1 tone that had lost its syllable (Ctrl+Z undoes it)." : " Re-attached " + moved + " tones that had lost their syllables (Ctrl+Z undoes it).";
        status.setText(msg);
    }

    void setModelOn(boolean on) {
        modelOn = on;
        PREFS.putBoolean("modelOn", on);
        if (modelBtn != null) modelBtn.setSelected(on); // pressed while the model is on
        if (modelChk != null) modelChk.setSelected(on);
        if (sandhiTog != null) sandhiTog.setVisible(on);
        updateCharBar();
        pitch.repaint(); charBar.repaint();
        if (on && segs.isEmpty()) status.setText(" qTA on: mark syllables, then click a syllable's character in the bottom bar to give it a tone.");
    }

    void setTone(long key, Integer tone) {
        pushUndoItem(markSnap());
        if (tone == null) sylTones.remove(key); else sylTones.put(key, tone);
        saveRegs();
        pitch.repaint(); charBar.repaint();
    }

    void showToneMenu(Component inv, int x, int y, long key) {
        JPopupMenu pm = new JPopupMenu();
        Integer cur = sylTones.get(key);
        for (int i = 0; i < activeSys.tones.size(); i++) {
            final int tone = i + 1;
            ToneDef d = activeSys.tones.get(i);
            String sy = effSym(activeSys, d);
            int mk = toneMarkKind(sy);
            JCheckBoxMenuItem mi = new JCheckBoxMenuItem(mk > 0 ? d.label : d.label + (sy.isEmpty() ? "  " + d.chao : " " + sy), cur != null && cur == tone);
            if (mk > 0) { mi.setIcon(new ToneIcon(mk)); mi.setHorizontalTextPosition(SwingConstants.LEADING); mi.setIconTextGap(8); }
            mi.addActionListener(e -> setTone(key, tone));
            pm.add(mi);
        }
        pm.show(inv, x, y);
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    static String cell(Object o) { return str(o).replace("|", " "); }

    Runnable toneDlgRefresh; // keeps the Settings tab in step when the tone system is changed from the toolbar

    JPanel toneSystemsPanel() {
        JPanel dlg = new JPanel(new BorderLayout());
        final boolean[] busy = {false};
        JComboBox<String> box = new JComboBox<>();
        javax.swing.table.DefaultTableModel tm = new javax.swing.table.DefaultTableModel(
                new Object[]{"Tone", "Symbol", "Chao letters (1-5)", "Strength \u03bb (1/s)", "Description"}, 0) {
            public Class<?> getColumnClass(int c) { return c == 3 ? Double.class : String.class; }
            public boolean isCellEditable(int r, int c) { return !activeSys.builtin; }
        };
        javax.swing.table.DefaultTableModel rm = new javax.swing.table.DefaultTableModel(
                new Object[]{"Tone", "Character", "After (previous tone)", "Before (next tone)", "Becomes", "Mark", "Note"}, 0) {
            public boolean isCellEditable(int r, int c) { return !activeSys.builtin; }
        };
        javax.swing.table.DefaultTableModel cm = new javax.swing.table.DefaultTableModel(new Object[]{"Character", "Always has tone"}, 0) {
            public boolean isCellEditable(int r, int c) { return !activeSys.builtin; }
        };
        JTable table = new JTable(tm), rtable = new JTable(rm), ctable = new JTable(cm);
        table.setRowHeight(26); rtable.setRowHeight(26); ctable.setRowHeight(26);
        int[] tw = {50, 60, 120, 110, 260};
        for (int i = 0; i < tw.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(tw[i]);
        int[] rw = {50, 80, 120, 120, 70, 50, 260};
        for (int i = 0; i < rw.length; i++) rtable.getColumnModel().getColumn(i).setPreferredWidth(rw[i]);
        List<JButton> editBtns = new ArrayList<>();
        Runnable fill = () -> {
            busy[0] = true;
            tm.setRowCount(0);
            for (ToneDef d : activeSys.tones) tm.addRow(new Object[]{d.label, d.sym, d.chao, d.lam, d.name});
            rm.setRowCount(0);
            for (SandhiRule r : activeSys.rules) rm.addRow(new Object[]{r.tone, r.chars, r.after, r.before, r.becomes, r.mark, r.note});
            cm.setRowCount(0);
            for (Map.Entry<String, String> e : activeSys.charTones.entrySet()) cm.addRow(new Object[]{e.getKey(), e.getValue()});
            for (JButton eb : editBtns) eb.setEnabled(!activeSys.builtin);
            busy[0] = false;
        };
        Runnable refreshBox = () -> {
            busy[0] = true;
            box.removeAllItems();
            for (ToneSys s : toneSystems) box.addItem(s.name + (s.builtin ? "   (built in, read-only)" : ""));
            box.setSelectedIndex(toneSystems.indexOf(activeSys));
            busy[0] = false;
        };
        toneDlgRefresh = () -> { refreshBox.run(); fill.run(); };
        Runnable changed = () -> { saveToneLib(); pitch.repaint(); charBar.repaint(); };
        tm.addTableModelListener(e -> { // edits apply straight away
            if (busy[0] || activeSys.builtin) return;
            int r = e.getFirstRow();
            if (r < 0 || r >= activeSys.tones.size()) return;
            ToneDef d = activeSys.tones.get(r);
            String chao = str(tm.getValueAt(r, 2)).replaceAll("[^1-5]", "");
            if (chao.isEmpty()) { Toolkit.getDefaultToolkit().beep(); chao = d.chao; } // Chao letters need at least one digit 1-5
            double lam = d.lam;
            try { lam = ((Number) tm.getValueAt(r, 3)).doubleValue(); } catch (Exception ignored) { }
            d.label = cell(tm.getValueAt(r, 0)).isEmpty() ? String.valueOf(r + 1) : cell(tm.getValueAt(r, 0));
            d.sym = cell(tm.getValueAt(r, 1));
            d.chao = chao;
            d.lam = Math.max(3, Math.min(150, lam));
            d.name = cell(tm.getValueAt(r, 4));
            busy[0] = true;
            tm.setValueAt(d.label, r, 0); tm.setValueAt(d.sym, r, 1); tm.setValueAt(d.chao, r, 2); tm.setValueAt(d.lam, r, 3); tm.setValueAt(d.name, r, 4);
            busy[0] = false;
            changed.run();
        });
        rm.addTableModelListener(e -> {
            if (busy[0] || activeSys.builtin) return;
            int r = e.getFirstRow();
            if (r < 0 || r >= activeSys.rules.size()) return;
            SandhiRule x = activeSys.rules.get(r);
            String bec = str(rm.getValueAt(r, 4));
            bec = bec.startsWith("=") ? "=" + bec.substring(1).trim() : bec.replaceAll("[^1-5]", "");
            if (bec.isEmpty() || bec.equals("=")) { Toolkit.getDefaultToolkit().beep(); bec = x.becomes; } // "=2" (use tone 2) or Chao letters
            x.tone = cell(rm.getValueAt(r, 0)); x.chars = cell(rm.getValueAt(r, 1)); x.after = cell(rm.getValueAt(r, 2)); x.before = cell(rm.getValueAt(r, 3));
            x.becomes = bec; x.mark = cell(rm.getValueAt(r, 5)); x.note = cell(rm.getValueAt(r, 6));
            busy[0] = true;
            rm.setValueAt(x.becomes, r, 4);
            busy[0] = false;
            changed.run();
        });
        cm.addTableModelListener(e -> {
            if (busy[0] || activeSys.builtin) return;
            activeSys.charTones.clear();
            for (int r = 0; r < cm.getRowCount(); r++) {
                String ch = cell(cm.getValueAt(r, 0)), t = cell(cm.getValueAt(r, 1));
                if (!ch.isEmpty() && !t.isEmpty()) activeSys.charTones.put(ch, t);
            }
            changed.run();
        });
        box.addActionListener(e -> {
            if (busy[0]) return;
            int i = box.getSelectedIndex();
            if (i >= 0) { setActiveSys(toneSystems.get(i)); }
        });

        JButton newB = btn("New...", () -> {
            String n = JOptionPane.showInputDialog(dlg, "Name of the new tone system (e.g. Cantonese):");
            if (n == null || n.isBlank()) return;
            ToneSys s = new ToneSys(uniqueName(n.replaceAll("[\\[\\],\\r\\n]", " ").trim(), toneSystems), false);
            s.tones.add(new ToneDef("1", "33", 40, ""));
            toneSystems.add(s); saveToneLib(); setActiveSys(s);
        });
        JButton dupB = btn("Duplicate", () -> {
            ToneSys s = activeSys.copy(uniqueName(activeSys.name + " copy", toneSystems));
            toneSystems.add(s); saveToneLib(); setActiveSys(s);
        });
        JButton delB = btn("Delete", () -> {
            if (activeSys.builtin) return;
            if (JOptionPane.showConfirmDialog(dlg, "Delete the tone system \"" + activeSys.name + "\"?", "Delete", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            toneSystems.remove(activeSys); saveToneLib(); setActiveSys(toneSystems.get(0));
        });
        JButton impB = btn("Import...", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Import tone systems");
            if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            try {
                List<ToneSys> got = parseToneSystems(new String(java.nio.file.Files.readAllBytes(fc.getSelectedFile().toPath()), java.nio.charset.StandardCharsets.UTF_8));
                if (got.isEmpty()) { JOptionPane.showMessageDialog(dlg, "No tone systems found in that file.\nExpected a [name] line, then lines like:  1 | 55 | 40 | High level"); return; }
                for (ToneSys s : got) { s.name = uniqueName(s.name.replaceAll("[\\[\\],]", " ").trim(), toneSystems); toneSystems.add(s); }
                saveToneLib(); setActiveSys(got.get(0));
            } catch (Exception ex) { JOptionPane.showMessageDialog(dlg, "Couldn't read that file: " + ex.getMessage()); }
        });
        JButton expB = btn("Export...", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Export this tone system");
            fc.setSelectedFile(new File(activeSys.name.replaceAll("[^\\w.-]+", "_") + "-tones.txt"));
            if (fc.showSaveDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (f.exists() && JOptionPane.showConfirmDialog(dlg, f.getName() + " exists. Replace it?", "Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try { java.nio.file.Files.write(f.toPath(), formatToneSystems(List.of(activeSys)).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Exception ex) { JOptionPane.showMessageDialog(dlg, "Couldn't write that file: " + ex.getMessage()); }
        });
        JButton addB = btn("Add tone", () -> {
            if (activeSys.builtin) return;
            activeSys.tones.add(new ToneDef(String.valueOf(activeSys.tones.size() + 1), "33", 40, ""));
            changed.run(); fill.run();
        });
        JButton remB = btn("Remove last tone", () -> {
            if (activeSys.builtin || activeSys.tones.size() <= 1) return;
            activeSys.tones.remove(activeSys.tones.size() - 1);
            changed.run(); fill.run();
        });
        JButton addRB = btn("Add rule", () -> {
            if (activeSys.builtin) return;
            String first = activeSys.tones.get(0).label;
            activeSys.rules.add(new SandhiRule(first, "", "", "", "=" + first, "", ""));
            changed.run(); fill.run();
            rtable.setRowSelectionInterval(activeSys.rules.size() - 1, activeSys.rules.size() - 1);
        });
        JButton remRB = btn("Remove rule", () -> {
            int r = rtable.getSelectedRow();
            if (activeSys.builtin || r < 0 || r >= activeSys.rules.size()) return;
            activeSys.rules.remove(r);
            changed.run(); fill.run();
        });
        JButton upB = btn("Rule up", () -> {
            int r = rtable.getSelectedRow();
            if (activeSys.builtin || r < 1 || r >= activeSys.rules.size()) return;
            Collections.swap(activeSys.rules, r, r - 1);
            changed.run(); fill.run(); rtable.setRowSelectionInterval(r - 1, r - 1);
        });
        JButton downB = btn("Rule down", () -> {
            int r = rtable.getSelectedRow();
            if (activeSys.builtin || r < 0 || r >= activeSys.rules.size() - 1) return;
            Collections.swap(activeSys.rules, r, r + 1);
            changed.run(); fill.run(); rtable.setRowSelectionInterval(r + 1, r + 1);
        });
        JButton addCB = btn("Add character", () -> {
            if (activeSys.builtin) return;
            String ch = JOptionPane.showInputDialog(dlg, "Character that always has a fixed tone (e.g. \u4e00):");
            if (ch == null || ch.isBlank()) return;
            activeSys.charTones.put(ch.trim().replaceAll("[|\\s]", ""), activeSys.tones.get(0).label);
            changed.run(); fill.run();
        });
        JButton remCB = btn("Remove character", () -> {
            int r = ctable.getSelectedRow();
            if (activeSys.builtin || r < 0 || r >= cm.getRowCount()) return;
            activeSys.charTones.remove(cell(cm.getValueAt(r, 0)));
            changed.run(); fill.run();
        });
        editBtns.add(delB); editBtns.add(addB); editBtns.add(remB); editBtns.add(addRB); editBtns.add(remRB); editBtns.add(upB); editBtns.add(downB);
        editBtns.add(addCB); editBtns.add(remCB);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("Tone system:")); top.add(box); top.add(newB); top.add(dupB); top.add(delB); top.add(impB); top.add(expB);
        JLabel hint = new JLabel("<html><div style='width:640px'><b>Tones.</b> Chao tone letters: digits 1-5 for pitch level (5 = highest), e.g. 55 high level, 35 rising, 214 dipping, 51 falling; "
                + "three or more digits are spread in equal time steps across the syllable. Levels 1-5 are spread over the student's pitch range. "
                + "Strength \u03bb is how fast the pitch chases the target (about 10-60). The symbol is what the tone bar shows in symbol mode. Tone numbers are row positions.<br>"
                + "<b>Sandhi rules.</b> A rule applies to a syllable with the given <i>Tone</i> and/or <i>Character</i> (blank = any; the character is the one attributed to the syllable in the subtitle), "
                + "when the previous syllable's tone (<i>After</i>, as already changed) and/or the next syllable's tone (<i>Before</i>, as written) is in the list. "
                + "<i>Becomes</i> is <b>=label</b> (use that tone exactly) or Chao letters such as 21. <i>Mark</i> is shown after the written tone in the tone bar instead of the final tone (e.g. * gives 3*). "
                + "Rules are tried top to bottom, first match wins, left to right within a segment.<br>"
                + "<b>Characters with a fixed tone</b> get that tone automatically, so nothing needs to be chosen for them.<br>"
                + "The built-in Mandarin system is read-only: use Duplicate to make your own version. Your systems are kept in " + TONE_FILE.getName()
                + " in your home folder; Export / Import share them as plain text.</div></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));
        JScrollPane hintScroll = new JScrollPane(hint, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        hintScroll.setBorder(null);
        hintScroll.setPreferredSize(new Dimension(700, 150));
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH); north.add(hintScroll, BorderLayout.CENTER);
        JScrollPane s1 = new JScrollPane(table), s2 = new JScrollPane(rtable), s3 = new JScrollPane(ctable);
        s1.setBorder(BorderFactory.createTitledBorder("Tones"));
        s2.setBorder(BorderFactory.createTitledBorder("Sandhi rules"));
        s3.setBorder(BorderFactory.createTitledBorder("Characters with a fixed tone"));
        s1.setPreferredSize(new Dimension(700, 190)); s2.setPreferredSize(new Dimension(700, 210)); s3.setPreferredSize(new Dimension(700, 90));
        JSplitPane inner = new JSplitPane(JSplitPane.VERTICAL_SPLIT, s2, s3);
        inner.setResizeWeight(0.7);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, s1, inner);
        split.setResizeWeight(0.35);
        JPanel bp = new JPanel(new WrapLayout(FlowLayout.CENTER, 8, 6));
        bp.add(addB); bp.add(remB); bp.add(addRB); bp.add(remRB); bp.add(upB); bp.add(downB); bp.add(addCB); bp.add(remCB);
        dlg.add(north, BorderLayout.NORTH); dlg.add(split, BorderLayout.CENTER); dlg.add(bp, BorderLayout.SOUTH);
        refreshBox.run(); fill.run();
        return dlg;
    }

    /** Chao level 1-5 as a fraction of the pitch range: 1 sits near the bottom (0.05), 5 near the top (0.95). */
    static double levelToFrac(int level) { return 0.05 + 0.9 * (level - 1) / 4.0; }

    /** What a syllable is actually pronounced as after sandhi: its Chao letters and strength, the tone it counts as for its neighbours,
     *  the tone it was written with, and a rule's display mark. */
    static class Surf { String id, under, chao, mark = ""; double lam; boolean changed; }

    static ToneDef byLabel(ToneSys sys, String label) {
        for (ToneDef d : sys.tones) if (d.label.equals(label)) return d;
        return null;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("[\\s,]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    static Surf makeSurf(ToneDef d) {
        Surf s = new Surf();
        s.id = d.label; s.under = d.label; s.chao = d.chao; s.lam = d.lam;
        return s;
    }

    /**
     * The tone each syllable of ONE segment is pronounced with. With sandhi on, a character with a fixed tone (\u4e00, \u4e0d) gets it without one being
     * chosen, then the rules are applied left to right: a rule's "after" is matched against the previous syllable as already changed, its
     * "before" against the next syllable as written; a syllable with no tone is not a neighbour. Nothing looks beyond the segment.
     * `chars` gives each syllable's character (or null). Entries are null for syllables without a tone.
     */
    static Surf[] surfaces(List<Long> b, Map<Long, Integer> tones, ToneSys sys, boolean sandhi, String[] chars) {
        int n = b.size() - 1;
        Surf[] out = new Surf[n];
        for (int i = 0; i < n; i++) {
            Integer t = tones.get(b.get(i));
            if (t != null && t >= 1 && t <= sys.tones.size()) out[i] = makeSurf(sys.tones.get(t - 1));
            else if (sandhi && chars != null && chars[i] != null && sys.charTones.containsKey(chars[i])) {
                ToneDef d = byLabel(sys, sys.charTones.get(chars[i]));
                if (d != null) out[i] = makeSurf(d);
            }
        }
        if (!sandhi) return out;
        for (int i = 0; i < n; i++) {
            Surf s = out[i];
            if (s == null) continue;
            String ch = chars != null ? chars[i] : null;
            String prev = i > 0 && out[i - 1] != null ? out[i - 1].id : null;
            String next = i + 1 < n && out[i + 1] != null ? out[i + 1].id : null; // the next syllable is still as written
            for (SandhiRule r : sys.rules) {
                if (!r.tone.isBlank() && !r.tone.equals(s.under)) continue;
                if (!r.chars.isBlank() && (ch == null || !tokens(r.chars).contains(ch))) continue;
                if (!r.after.isBlank() && (prev == null || !tokens(r.after).contains(prev))) continue;
                if (!r.before.isBlank() && (next == null || !tokens(r.before).contains(next))) continue;
                String bec = r.becomes.trim();
                if (bec.startsWith("=")) {
                    ToneDef td = byLabel(sys, bec.substring(1).trim());
                    if (td == null) continue;
                    s.chao = td.chao; s.lam = td.lam; s.id = td.label;
                } else {
                    String c = bec.replaceAll("[^1-5]", "");
                    if (c.isEmpty()) continue;
                    s.chao = c; // Chao letters only: still counts as the same tone for its neighbours
                }
                s.mark = r.mark.trim();
                s.changed = true;
                break;
            }
        }
        return out;
    }

    /**
     * qTA synthesis (Prom-on, Xu and Thipakorn 2009) of ONE segment: the chain starts afresh at the segment's start and stops at its end, so
     * nothing carries across a gap between segments. A tone's Chao letters give a piecewise-linear target across the syllable (one level = static;
     * "35" = one rising piece; "214" = a fall then a rise, in equal time steps). Over each piece the pitch approaches the linear target m*t + b as a
     * critically damped third-order system, x(t) = m*t + b + (c1 + c2*t + c3*t^2) * exp(-lambda*t), starting from the position, velocity and
     * acceleration the previous piece ended with. Heights are fractions of the pitch range [loSt, hiSt] (semitones re 100 Hz). Sentence declination
     * (declSt semitones per second, pivoting on the segment's midpoint) is added to the targets themselves.
     * A syllable without a tone breaks the chain. Returns {ms[], hz[]} on a 10 ms grid, NaN where there is no model.
     */
    static double[][] qtaChain(List<Long> b, Map<Long, Integer> tones, ToneSys sys, boolean sandhi, String[] chars,
                               long segS, long segE, double loSt, double hiSt, double declSt) {
        long k0 = (segS + 9) / 10 * 10;
        int cnt = (int) Math.max(0, (segE - k0) / 10 + 1);
        double[] ms = new double[cnt], hz = new double[cnt];
        for (int k = 0; k < cnt; k++) { ms[k] = k0 + 10.0 * k; hz[k] = Double.NaN; }
        boolean have = false;
        double x = 0, v = 0, a = 0;
        double dr = declSt / Math.max(1e-6, hiSt - loSt), tMid = (segS + segE) / 2.0; // declination in range units per second
        int k = 0, n = b.size() - 1;
        Surf[] sf = surfaces(b, tones, sys, sandhi, chars);
        for (int i = 0; i < n; i++) {
            double s0 = b.get(i), s1 = b.get(i + 1);
            boolean lastSyl = i == n - 1;
            int[] lv = sf[i] == null ? new int[0] : levelsOf(sf[i].chao);
            if (lv.length == 0 || s1 <= s0) { // no tone: nothing is drawn and the next syllable starts afresh
                have = false;
                while (k < cnt && (lastSyl ? ms[k] <= s1 : ms[k] < s1)) k++;
                continue;
            }
            double lam = sf[i].lam;
            int pieces = Math.max(1, lv.length - 1);
            for (int p = 0; p < pieces; p++) {
                double t0 = s0 + (s1 - s0) * p / pieces, t1 = s0 + (s1 - s0) * (p + 1) / pieces;
                boolean lastPiece = lastSyl && p == pieces - 1;
                double r0 = levelToFrac(lv[p]), r1 = levelToFrac(lv.length == 1 ? lv[0] : lv[p + 1]);
                double d = (t1 - t0) / 1000.0;
                double bb = r0 - dr * (t0 - tMid) / 1000.0, m = (r1 - r0) / d - dr;
                double x0 = have ? x : bb, v0 = have ? v : 0, a0 = have ? a : 0;
                double c1 = x0 - bb, c2 = v0 - m + lam * c1, c3 = (a0 + 2 * lam * c2 - lam * lam * c1) / 2;
                while (k < cnt && (lastPiece ? ms[k] <= t1 : ms[k] < t1)) {
                    double t = Math.max(0, (ms[k] - t0) / 1000.0);
                    double r = bb + m * t + (c1 + c2 * t + c3 * t * t) * Math.exp(-lam * t);
                    hz[k] = 100 * Math.pow(2, (loSt + r * (hiSt - loSt)) / 12);
                    k++;
                }
                double e = Math.exp(-lam * d), poly = c1 + c2 * d + c3 * d * d;
                x = bb + m * d + poly * e;
                v = m + (c2 + 2 * c3 * d - lam * poly) * e;
                a = (2 * c3 - 2 * lam * (c2 + 2 * c3 * d) + lam * lam * poly) * e;
                have = true;
            }
        }
        return new double[][]{ms, hz};
    }

    boolean anyAligned() {
        for (Seg s : segs) if (s.ach != null && alignValid(s)) return true;
        return false;
    }
    int charRowH() { return (int) (alignFontPx() * 1.3) + 10; }
    static final int TONE_ROW = 30;
    boolean toneUi() { return modelOn && !segs.isEmpty(); } // the bottom bar carries the tone row and clickable characters

    /** Height of the bottom bar: a tone row above a characters row when the model is on, else just the characters row if something is aligned. */
    int barHeight() { return toneUi() ? TONE_ROW + charRowH() : anyAligned() ? charRowH() : 0; }

    // ---------------------------------------------------------------- customisable keyboard shortcuts
    static class Act {
        final String id, label, def; final String[] alts; final boolean global; final Runnable run;
        String key; JMenuItem item;
        Act(String id, String label, String def, String[] alts, boolean global, Runnable run) {
            this.id = id; this.label = label; this.def = def; this.alts = alts; this.global = global; this.run = run; key = def;
        }
    }
    final Map<String, Act> acts = new LinkedHashMap<>();
    final List<KeyStroke> boundWave = new ArrayList<>(), boundRoot = new ArrayList<>();
    JButton splitBtn;
    boolean showSyl = PREFS.getBoolean("showSylMarks", true); // syllable boundaries visible
    JCheckBox sylTog, modelChk;
    JToggleButton modelBtn;
    boolean modelOn = PREFS.getBoolean("modelOn", false);      // tone selector + synthetic (qTA) model contour
    String pendingToneSys;                                      // tone system named in the .piyingxi file, applied once it is loaded
    List<ToneSys> toneSystems = loadToneLib();
    ToneSys activeSys = pickActiveSys();
    boolean sandhiOn = PREFS.getBoolean("sandhiOn", true); // apply the tone system's sandhi rules in the model
    JCheckBox sandhiTog;

    /** One tone of a tone system: a label, an optional tone symbol, its Chao tone letters (digits 1-5, 5 = highest) and how fast the pitch chases the target. */
    static class ToneDef {
        String label, chao, name, sym; double lam;
        ToneDef(String label, String chao, double lam, String name, String sym) {
            this.label = label; this.chao = chao; this.lam = lam; this.name = name; this.sym = sym;
        }
        ToneDef(String label, String chao, double lam, String name) { this(label, chao, lam, name, ""); }
        int[] levels() { return levelsOf(chao); }
    }

    /** The digits 1-5 of a string of Chao tone letters, as levels. */
    static int[] levelsOf(String chao) {
        int[] t = new int[chao.length()]; int n = 0;
        for (char ch : chao.toCharArray()) if (ch >= '1' && ch <= '5') t[n++] = ch - '0';
        return Arrays.copyOf(t, n);
    }

    /**
     * A tone-sandhi rule. It applies to a syllable whose written tone is `tone` (blank = any) and whose character is one of `chars` (blank = any),
     * when the syllable before it has one of the tones in `after` (as already changed by the rules) and/or the syllable after it has one of the
     * tones in `before` (as written); blank = no condition. `becomes` is "=label" (use that tone exactly) or Chao letters such as "21".
     * `mark` is text the tone bar shows after the written tone instead of the final tone (e.g. "*" gives "3*").
     */
    static class SandhiRule {
        String tone, chars, after, before, becomes, mark, note;
        SandhiRule(String tone, String chars, String after, String before, String becomes, String mark, String note) {
            this.tone = tone; this.chars = chars; this.after = after; this.before = before; this.becomes = becomes; this.mark = mark; this.note = note;
        }
    }

    /** A named tone system (a topolect's tone inventory). Tone numbers are row positions, starting at 1. */
    static class ToneSys {
        String name; final boolean builtin; final List<ToneDef> tones = new ArrayList<>();
        final List<SandhiRule> rules = new ArrayList<>();
        final Map<String, String> charTones = new LinkedHashMap<>(); // characters with a fixed tone (e.g. \u4e00 -> 1): no tone needs choosing for them
        ToneSys(String name, boolean builtin) { this.name = name; this.builtin = builtin; }
        ToneSys copy(String newName) {
            ToneSys s = new ToneSys(newName, false);
            for (ToneDef d : tones) s.tones.add(new ToneDef(d.label, d.chao, d.lam, d.name, d.sym));
            for (SandhiRule r : rules) s.rules.add(new SandhiRule(r.tone, r.chars, r.after, r.before, r.becomes, r.mark, r.note));
            s.charTones.putAll(charTones);
            return s;
        }
    }

    /** Standard Mandarin in Chao tone letters (55, 35, 214, 51, and a mid, weak neutral tone) with its sandhi rules. */
    static ToneSys mandarin() {
        ToneSys s = new ToneSys("Mandarin", true);
        s.tones.add(new ToneDef("1", "55", 40, "High level", "\u02c9"));
        s.tones.add(new ToneDef("2", "35", 40, "Rising", "\u02ca"));
        s.tones.add(new ToneDef("3", "214", 40, "Dipping", "\u02c7"));
        s.tones.add(new ToneDef("4", "51", 40, "Falling", "\u02cb"));
        s.tones.add(new ToneDef("N", "3", 18, "Neutral (mid, weak)", "\u02d9"));
        // \u4e00 and \u4e0d are recognised by the character attributed to the syllable, and take their citation tone without one being chosen
        s.charTones.put("\u4e00", "1");
        s.charTones.put("\u4e0d", "4");
        // third tone: 3 + 3 -> 2 + 3 (shown as 3*); otherwise the half-third (21) before any other tone
        s.rules.add(new SandhiRule("3", "", "", "3", "=2", "*", "Third-tone sandhi: 3 + 3 -> 2 + 3 (shown as 3*)"));
        s.rules.add(new SandhiRule("3", "", "", "1 2 4 N", "21", "", "Half-third before any other tone"));
        s.rules.add(new SandhiRule("", "\u4e00", "", "4", "=2", "", "\u4e00 before a 4th tone: y\u00ed"));
        s.rules.add(new SandhiRule("", "\u4e00", "", "1 2 3", "=4", "", "\u4e00 before a 1st, 2nd or 3rd tone: y\u00ec"));
        s.rules.add(new SandhiRule("", "\u4e0d", "", "4", "=2", "", "\u4e0d before a 4th tone: b\u00fa"));
        // neutral tone: its pitch depends on the tone before it (Chao 1968)
        s.rules.add(new SandhiRule("N", "", "1", "", "2", "", "Neutral after a 1st tone: low"));
        s.rules.add(new SandhiRule("N", "", "2", "", "3", "", "Neutral after a 2nd tone: mid"));
        s.rules.add(new SandhiRule("N", "", "3", "", "4", "", "Neutral after a 3rd tone: high"));
        s.rules.add(new SandhiRule("N", "", "4", "", "1", "", "Neutral after a 4th tone: low"));
        return s;
    }

    static final File TONE_FILE = new File(System.getProperty("user.home"), ".piyingxi-tone-systems.txt");

    static String uniqueName(String base, List<ToneSys> lib) {
        String n = base.isBlank() ? "Untitled" : base.trim();
        String cand = n;
        for (int i = 2; ; i++) {
            boolean clash = false;
            for (ToneSys s : lib) if (s.name.equalsIgnoreCase(cand)) { clash = true; break; }
            if (!clash) return cand;
            cand = n + " (" + i + ")";
        }
    }

    /**
     * Text format: "[name]" starts a system; then lines "label | Chao letters | strength | description | symbol" for tones,
     * "char: character | tone label" for characters with a fixed tone, and
     * "rule: tone | characters | after | before | becomes | mark | note" for sandhi rules. "#" starts a comment.
     * (An older rule layout without the characters and mark columns is still read.)
     */
    static List<ToneSys> parseToneSystems(String text) {
        List<ToneSys> out = new ArrayList<>();
        ToneSys cur = null;
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("[") && l.endsWith("]")) { cur = new ToneSys(l.substring(1, l.length() - 1).trim(), false); out.add(cur); continue; }
            if (cur == null) continue;
            if (l.toLowerCase().startsWith("char:")) {
                String[] q = l.substring(5).split("\\|", -1);
                if (q.length >= 2 && !q[0].trim().isEmpty() && !q[1].trim().isEmpty()) cur.charTones.put(q[0].trim(), q[1].trim());
                continue;
            }
            if (l.toLowerCase().startsWith("rule:")) {
                String[] q = l.substring(5).split("\\|", -1);
                for (int i = 0; i < q.length; i++) q[i] = q[i].trim();
                String tone, chars, after, before, bec, mark, note;
                if (q.length >= 6) { tone = q[0]; chars = q[1]; after = q[2]; before = q[3]; bec = q[4]; mark = q[5]; note = q.length > 6 ? q[6] : ""; }
                else if (q.length >= 4) { tone = q[0]; chars = ""; after = q[1]; before = q[2]; bec = q[3]; mark = ""; note = q.length > 4 ? q[4] : ""; }
                else continue;
                bec = bec.startsWith("=") ? "=" + bec.substring(1).trim() : bec.replaceAll("[^1-5]", "");
                if (bec.isEmpty() || bec.equals("=") || (tone.isEmpty() && chars.isEmpty())) continue;
                cur.rules.add(new SandhiRule(tone, chars, after, before, bec, mark, note));
                continue;
            }
            String[] p = l.split("\\|", -1);
            if (p.length < 2) continue;
            ToneDef d = new ToneDef(p[0].trim(), p[1].replaceAll("[^1-5]", ""), 40, p.length > 3 ? p[3].trim() : "", p.length > 4 ? p[4].trim() : "");
            try { if (p.length > 2 && !p[2].trim().isEmpty()) d.lam = Math.max(3, Math.min(150, Double.parseDouble(p[2].trim()))); }
            catch (NumberFormatException ignored) { }
            if (d.chao.isEmpty()) continue;
            if (d.label.isEmpty()) d.label = String.valueOf(cur.tones.size() + 1);
            cur.tones.add(d);
        }
        out.removeIf(s -> s.tones.isEmpty());
        return out;
    }

    static String formatToneSystems(List<ToneSys> list) {
        StringBuilder sb = new StringBuilder("# Piyingxi tone systems.\n# One [name] line per system, then:\n"
                + "#   label | Chao tone letters (digits 1-5, 1 = lowest, 5 = highest) | strength (3-150, usually 10-60) | description | symbol\n"
                + "#   char: character | tone label        (a character that always has this tone, e.g. \u4e00 -> 1)\n"
                + "#   rule: tone | characters | after | before | becomes | mark | note\n"
                + "#     tone or characters picks the syllable (blank = any); after / before are tone labels around it (blank = anywhere);\n"
                + "#     becomes is =label (use that tone) or Chao letters; mark is shown after the written tone (e.g. *). First match wins.\n");
        for (ToneSys s : list) {
            sb.append("\n[").append(s.name).append("]\n");
            for (ToneDef d : s.tones) {
                String lam = d.lam == Math.rint(d.lam) ? String.valueOf((long) d.lam) : String.valueOf(d.lam);
                sb.append(d.label).append(" | ").append(d.chao).append(" | ").append(lam).append(" | ").append(d.name).append(" | ").append(d.sym).append('\n');
            }
            for (Map.Entry<String, String> e : s.charTones.entrySet()) sb.append("char: ").append(e.getKey()).append(" | ").append(e.getValue()).append('\n');
            for (SandhiRule r : s.rules)
                sb.append("rule: ").append(r.tone).append(" | ").append(r.chars).append(" | ").append(r.after).append(" | ").append(r.before).append(" | ")
                        .append(r.becomes).append(" | ").append(r.mark).append(" | ").append(r.note).append('\n');
        }
        return sb.toString();
    }

    static List<ToneSys> loadToneLib() {
        List<ToneSys> lib = new ArrayList<>();
        lib.add(mandarin());
        try {
            if (TONE_FILE.exists())
                for (ToneSys s : parseToneSystems(new String(java.nio.file.Files.readAllBytes(TONE_FILE.toPath()), java.nio.charset.StandardCharsets.UTF_8))) {
                    s.name = uniqueName(s.name.replaceAll("[\\[\\],]", " ").trim(), lib);
                    lib.add(s);
                }
        } catch (Exception ignored) { }
        return lib;
    }

    void saveToneLib() {
        try {
            List<ToneSys> mine = new ArrayList<>();
            for (ToneSys s : toneSystems) if (!s.builtin) mine.add(s);
            java.nio.file.Files.write(TONE_FILE.toPath(), formatToneSystems(mine).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) { status.setText(" Couldn't save your tone systems to " + TONE_FILE + ": " + ex.getMessage()); }
    }

    ToneSys pickActiveSys() {
        String n = PREFS.get("toneActive", "Mandarin");
        for (ToneSys s : toneSystems) if (s.name.equals(n)) return s;
        return toneSystems.get(0);
    }

    void setActiveSys(ToneSys s) {
        activeSys = s;
        PREFS.put("toneActive", s.name);
        if (toneDlgRefresh != null) toneDlgRefresh.run();
        pitch.repaint(); charBar.repaint();
    }

    static final int MARK_W = 15, MARK_H = 12;

    /** 1-5 for the Mandarin tone marks \u02c9 \u02ca \u02c7 \u02cb \u02d9 at the start of a string, else 0. Accent glyphs are tiny and vary from font to font, so these are drawn as shapes. */
    static int toneMarkKind(String t) {
        if (t == null || t.isEmpty()) return 0;
        switch (t.charAt(0)) { case '\u02c9': return 1; case '\u02ca': return 2; case '\u02c7': return 3; case '\u02cb': return 4; case '\u02d9': return 5; case '\u02c6': return 6; default: return 0; }
    }

    /** Draws tone mark `k` in a MARK_W x MARK_H box whose top-left corner is (x, y), in the graphics' current colour. */
    static void paintToneMark(Graphics2D g, int k, int x, int y) {
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke old = g.getStroke();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (k) {
            case 1: g.drawLine(x + 1, y + MARK_H / 2, x + MARK_W - 1, y + MARK_H / 2); break;                                  // level
            case 2: g.drawLine(x + 1, y + MARK_H - 1, x + MARK_W - 1, y + 1); break;                                          // rising
            case 3: { Path2D.Double p = new Path2D.Double(); p.moveTo(x + 1, y + 1); p.lineTo(x + MARK_W / 2.0, y + MARK_H - 1); p.lineTo(x + MARK_W - 1, y + 1); g.draw(p); break; } // dipping
            case 4: g.drawLine(x + 1, y + 1, x + MARK_W - 1, y + MARK_H - 1); break;                                          // falling
            case 6: { Path2D.Double p = new Path2D.Double(); p.moveTo(x + 1, y + MARK_H - 1); p.lineTo(x + MARK_W / 2.0, y + 1); p.lineTo(x + MARK_W - 1, y + MARK_H - 1); g.draw(p); break; } // peaking
            default: g.fillOval(x + MARK_W / 2 - 2, y + MARK_H / 2 - 2, 5, 5);                                                // neutral
        }
        g.setStroke(old);
        if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    static class ToneIcon implements Icon {
        final int k;
        ToneIcon(int k) { this.k = k; }
        public int getIconWidth() { return MARK_W + 6; }
        public int getIconHeight() { return MARK_H + 4; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(c.getForeground());
            paintToneMark(g2, k, x + 3, y + 2);
            g2.dispose();
        }
    }

    /** A tone mark worked out from Chao letters: level, rising, falling, dipping or peaking; a single digit is the neutral dot. "" if none fits. */
    static String deriveSym(String chao) {
        int[] lv = levelsOf(chao);
        if (lv.length == 0) return "";
        if (lv.length == 1) return "\u02d9";
        int first = lv[0], last = lv[lv.length - 1], min = Integer.MAX_VALUE, max = 0;
        for (int i = 1; i < lv.length - 1; i++) { min = Math.min(min, lv[i]); max = Math.max(max, lv[i]); }
        boolean up = true, down = true;
        for (int i = 1; i < lv.length; i++) { if (lv[i] < lv[i - 1]) up = false; if (lv[i] > lv[i - 1]) down = false; }
        if (up && down) return "\u02c9";
        if (up) return "\u02ca";
        if (down) return "\u02cb";
        if (min < first && min < last) return "\u02c7";
        if (max > first && max > last) return "\u02c6";
        return "";
    }

    /** The symbol a tone is shown with: its own if it has one, else one derived from its Chao letters, but only where that does not make two tones of the
     *  system look the same (several level tones, say), in which case it is "" and the tone's number is shown. */
    static String effSym(ToneSys sys, ToneDef d) {
        if (!d.sym.isEmpty()) return d.sym;
        String mine = deriveSym(d.chao);
        if (mine.isEmpty()) return "";
        for (ToneDef o : sys.tones) {
            if (o == d) continue;
            String theirs = o.sym.isEmpty() ? deriveSym(o.chao) : o.sym;
            if (theirs.equals(mine) && !o.chao.equals(d.chao)) return ""; // identical Chao letters may share a symbol
        }
        return mine;
    }

    static boolean symMode() { return PREFS.get("toneShow", "sym").equals("sym"); }   // tone bar shows symbols (default) or numbers
    static boolean scopeSel() { return PREFS.get("modelScope", "sel").equals("sel"); } // model for the selected segment only (default) or all
    static double declSt() { return PREFS.getDouble("declSt", 1.0); }                  // sentence declination, semitones per second

    /** What the tone row shows for one syllable: its final tone (after sandhi) as a symbol or number. A rule's mark (e.g. "*") goes after the written tone instead. */
    String toneText(Surf s) {
        if (s == null) return "";
        ToneDef d = byLabel(activeSys, s.mark.isEmpty() ? s.id : s.under);
        String sy = d == null ? "" : effSym(activeSys, d);
        String base = d == null ? (s.mark.isEmpty() ? s.id : s.under) : (symMode() && !sy.isEmpty() ? sy : d.label);
        return base + s.mark;
    }

    /** The character of each syllable of a segment, and where to centre it (ms): from the alignment if there is one, else (if asked) from the subtitle when it has one character per syllable. */
    static class SylCh {
        final String[] ch; final double[] ms;
        SylCh(int n) { ch = new String[n]; ms = new double[n]; }
    }

    SylCh sylChars(Seg s, List<Long> b, boolean fallback) {
        int n = b.size() - 1;
        SylCh r = new SylCh(n);
        for (int i = 0; i < n; i++) r.ms[i] = (b.get(i) + b.get(i + 1)) / 2.0;
        if (s.ach != null && alignValid(s)) {
            for (int j = 0; j < s.ach.length; j++)
                for (int i = 0; i < n; i++)
                    if (s.ams[j] >= b.get(i) && s.ams[j] < b.get(i + 1)) { if (r.ch[i] == null) { r.ch[i] = s.ach[j]; r.ms[i] = s.ams[j]; } break; }
        }
        if (fallback) {
            List<String> t = subtitleChars(s.text);
            if (t.size() == n) for (int i = 0; i < n; i++) if (r.ch[i] == null) r.ch[i] = t.get(i);
        }
        return r;
    }
    static final String[] NO_ALTS = {};
    static final String[] RESERVED = {"shift LEFT", "shift RIGHT", "shift UP", "shift DOWN", "ESCAPE"}; // Move shadow keys and Esc stay fixed

    void act(String id, String label, String def, String[] alts, boolean global, Runnable run) {
        Act a = new Act(id, label, def, alts, global, run);
        String saved = PREFS.get("key." + id, null);
        if (saved != null) a.key = saved;
        acts.put(id, a);
    }

    void registerActs() {
        act("play", "Play / pause", "SPACE", NO_ALTS, false, this::togglePlay);
        act("playCursor", "Play from the cursor to the next syllable boundary (or segment end)", "TAB", NO_ALTS, false, this::playFromCursor);
        act("prev", "Previous segment", "LEFT", new String[]{"alt LEFT"}, false, () -> { if (moveMode) nudgeShadow(-10, 0); else step(-1); });
        act("next", "Next segment", "RIGHT", new String[]{"alt RIGHT"}, false, () -> { if (moveMode) nudgeShadow(10, 0); else step(1); });
        act("edit", "Edit the subtitle of the selected segment", "ENTER", NO_ALTS, false,
                () -> { if (sel != null && !PREFS.getBoolean("hideSubs", false)) text.requestFocusInWindow(); });
        act("rec", "Record / stop recording", "R", new String[]{"ctrl R"}, false, this::recordSegment);
        act("syl", "Mark a syllable boundary at the cursor", "S", NO_ALTS, false, this::markSyllable);
        act("split", "Split the segment at the cursor / make the dragged range a segment", "shift S", NO_ALTS, false, this::splitAction);
        act("nextPlay", "Go to the next segment and play it", "", NO_ALTS, false, this::nextAndPlay);
        act("align", "Align characters under their syllables", "", NO_ALTS, false, this::alignCharacters);
        act("swapBars", "Swap the waveform bar and the tone bar", "", NO_ALTS, false, () -> setWaveOnTop(!waveOnTop));
        act("qtaRefresh", "Regenerate the qTA model contour (switches the model on)", "", NO_ALTS, false, this::refreshQta);
        act("modelToggle", "Show / hide the model tone contour and tone selector", "", NO_ALTS, false, () -> setModelOn(!modelOn));
        act("sandhiToggle", "Apply / ignore the tone system's sandhi rules", "", NO_ALTS, false, () -> setSandhi(!sandhiOn));
        act("sylToggle", "Show / hide syllable boundaries", "", NO_ALTS, false, () -> setShowSyl(!showSyl));
        act("del", "Delete the segment (the contour in Draw mode)", "DELETE", NO_ALTS, false, () -> { if (drawMode) deleteContour(); else deleteSel(); });
        act("octUp", "Octave up (dragged selection; else the selected segment or whole audio)", "UP", NO_ALTS, false, () -> { if (moveMode) nudgeShadow(0, 2); else octaveStep(1); });
        act("octDown", "Octave down (dragged selection; else the selected segment or whole audio)", "DOWN", NO_ALTS, false, () -> { if (moveMode) nudgeShadow(0, -2); else octaveStep(-1); });
        act("zoomAll", "Zoom all the way out", "A", NO_ALTS, false, this::zoomAll);
        act("pitchToggle", "Show / hide the tone contour area and character bar", "T", NO_ALTS, false, () -> setShowPitch(!pitch.isVisible()));
        act("zoomSel", "Zoom in on the selection", "", NO_ALTS, false, this::zoomSelection);
        act("undo", "Undo", "ctrl Z", NO_ALTS, false, this::undo);
        act("zoomIn", "Zoom in", "ctrl EQUALS", new String[]{"ctrl PLUS", "ctrl shift EQUALS", "ctrl ADD"}, true, () -> zoom(1 / 1.5));
        act("zoomOut", "Zoom out", "ctrl MINUS", new String[]{"ctrl SUBTRACT"}, true, () -> zoom(1.5));
        act("new", "File menu: New", "ctrl N", NO_ALTS, true, this::newDocument);
        act("open", "File menu: Open", "ctrl O", NO_ALTS, true, this::openDialog);
        act("save", "File menu: Save", "ctrl S", NO_ALTS, true, this::saveAll);
        act("close", "File menu: Close tab", "ctrl W", NO_ALTS, true, this::closeTab);
    }

    static KeyStroke stroke(String s) { return s == null || s.isEmpty() ? null : KeyStroke.getKeyStroke(s); }

    static String keyLabel(String s) {
        KeyStroke k = stroke(s);
        if (k == null) return s == null || s.isEmpty() ? "(none)" : s;
        String m = InputEvent.getModifiersExText(k.getModifiers());
        String key = KeyEvent.getKeyText(k.getKeyCode());
        return m.isEmpty() ? key : m + "+" + key;
    }

    String keyName(String id) { Act a = acts.get(id); return a == null ? "" : keyLabel(a.key); }

    void bindKey(InputMap im, List<KeyStroke> track, KeyStroke k, String name) { im.put(k, name); track.add(k); }

    /** (Re)builds every customisable binding from the acts' current keys. */
    void applyKeys() {
        InputMap wi = wave.getInputMap(JComponent.WHEN_FOCUSED), ri = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap wa = wave.getActionMap(), ra = getRootPane().getActionMap();
        for (KeyStroke k : boundWave) wi.remove(k);
        for (KeyStroke k : boundRoot) ri.remove(k);
        boundWave.clear(); boundRoot.clear();
        Set<KeyStroke> primary = new HashSet<>();
        for (Act a : acts.values()) { KeyStroke k = stroke(a.key); if (k != null) primary.add(k); }
        for (Act a : acts.values()) {
            KeyStroke k = stroke(a.key);
            if (a.item != null) { a.item.setAccelerator(k); continue; } // menu entries carry their own accelerator
            final String name = "act." + a.id;
            Action ac = new AbstractAction() { public void actionPerformed(ActionEvent e) { a.run.run(); } };
            wa.put(name, ac); ra.put(name, ac);
            if (k != null) bindKey(a.global ? ri : wi, a.global ? boundRoot : boundWave, k, name);
            for (String alt : a.alts) { // fixed extra keys, unless you gave that key to something else
                KeyStroke ak = KeyStroke.getKeyStroke(alt);
                if (ak != null && !primary.contains(ak)) bindKey(ri, boundRoot, ak, name);
            }
        }
        String[][] nk = {{"shift LEFT", "-50,0"}, {"shift RIGHT", "50,0"}, {"shift UP", "0,10"}, {"shift DOWN", "0,-10"}};
        for (String[] k : nk) { // Move shadow: only act while it is on
            String[] v = k[1].split(",");
            wi.put(KeyStroke.getKeyStroke(k[0]), "nudge " + k[0]);
            wa.put("nudge " + k[0], nudgeAction(Integer.parseInt(v[0]), Integer.parseInt(v[1])));
        }
        updateCutButton();
    }

    /** Asks for a new key for one action. Returns after applying it (or doing nothing if cancelled). */
    void changeKey(Act a, Window owner) {
        final String[] res = {null};
        JDialog d = new JDialog(owner, "Set shortcut", Dialog.ModalityType.APPLICATION_MODAL);
        JLabel l1 = new JLabel("Press the new shortcut for", SwingConstants.CENTER);
        JLabel l2 = new JLabel(a.label, SwingConstants.CENTER);
        JPanel c = new JPanel(new GridLayout(0, 1, 0, 6));
        c.setBorder(BorderFactory.createEmptyBorder(18, 28, 8, 28));
        c.add(l1); c.add(l2);
        c.setFocusable(true);
        c.setFocusTraversalKeysEnabled(false); // so Tab can be captured too
        c.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META
                        || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_CAPS_LOCK || code == KeyEvent.VK_UNDEFINED) return;
                e.consume();
                if (code == KeyEvent.VK_ESCAPE) { d.dispose(); return; }
                res[0] = KeyStroke.getKeyStroke(code, e.getModifiersEx()).toString();
                d.dispose();
            }
        });
        JButton clear = btn("No shortcut", () -> { res[0] = ""; d.dispose(); });
        JButton cancel = btn("Cancel", d::dispose);
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(clear); bp.add(cancel);
        d.add(c, BorderLayout.CENTER); d.add(bp, BorderLayout.SOUTH);
        d.addWindowListener(new WindowAdapter() { public void windowOpened(WindowEvent e) { c.requestFocusInWindow(); } });
        d.pack(); d.setLocationRelativeTo(owner);
        d.setVisible(true);
        if (res[0] == null) return;
        String nk = res[0];
        if (!nk.isEmpty()) {
            KeyStroke k = KeyStroke.getKeyStroke(nk);
            for (String r : RESERVED) if (KeyStroke.getKeyStroke(r).equals(k)) {
                JOptionPane.showMessageDialog(owner, keyLabel(nk) + " is reserved (Move shadow keys and Esc cannot be changed). Pick another key.");
                return;
            }
            for (Act o : acts.values()) {
                if (o == a || !k.equals(stroke(o.key))) continue;
                if (JOptionPane.showConfirmDialog(owner, keyLabel(nk) + " is already used for \"" + o.label + "\".\nUse it for \"" + a.label + "\" instead?",
                        "Shortcut in use", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                o.key = ""; PREFS.put("key." + o.id, "");
            }
        }
        a.key = nk;
        PREFS.put("key." + a.id, nk);
        applyKeys();
    }

    JPanel shortcutsPanel() {
        List<Act> list = new ArrayList<>(acts.values());
        JPanel dlg = new JPanel(new BorderLayout());
        javax.swing.table.AbstractTableModel tm = new javax.swing.table.AbstractTableModel() {
            public int getRowCount() { return list.size(); }
            public int getColumnCount() { return 2; }
            public String getColumnName(int c) { return c == 0 ? "Action" : "Shortcut"; }
            public Object getValueAt(int r, int c) {
                Act a = list.get(r);
                return c == 0 ? a.label : keyLabel(a.key) + (a.key.equals(a.def) ? "" : "   (changed)");
            }
        };
        JTable table = new JTable(tm);
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(380);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getTableHeader().setReorderingAllowed(false);
        Runnable change = () -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            changeKey(list.get(r), SwingUtilities.getWindowAncestor(dlg));
            tm.fireTableRowsUpdated(0, list.size() - 1);
        };
        table.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) change.run(); } });
        JButton chg = btn("Change...", change);
        JButton reset = btn("Reset selected", () -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            Act a = list.get(r);
            a.key = a.def; PREFS.remove("key." + a.id);
            applyKeys(); tm.fireTableRowsUpdated(0, list.size() - 1);
        });
        JButton resetAll = btn("Reset all", () -> {
            for (Act a : list) { a.key = a.def; PREFS.remove("key." + a.id); }
            applyKeys(); tm.fireTableRowsUpdated(0, list.size() - 1);
        });
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(chg); bp.add(reset); bp.add(resetAll);
        JLabel hint = new JLabel("<html>Double-click a row (or press Change...) and then press the new key combination.<br>"
                + "Arrow keys with Move shadow, and Esc, are fixed.</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(600, 440));
        dlg.add(hint, BorderLayout.NORTH);
        dlg.add(scroll, BorderLayout.CENTER);
        dlg.add(bp, BorderLayout.SOUTH);
        return dlg;
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
        final Seg seg; final long s, e; final String text; final String[] ach; final long[] ams;
        Snap(Seg g) { seg = g; s = g.s; e = g.e; text = g.text; ach = g.ach; ams = g.ams; }
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
    volatile boolean liveView = PREFS.getBoolean("liveView", false); // Settings: show the spectrogram and pitch while recording (off by default)
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
    /** One undone step of syllable marking: the list of boundaries as it was before. */
    static class MarkUndo {
        final List<Long> before; final Map<Long, Integer> tonesBefore;
        Seg alignSeg; long[] alignAms; // when a boundary is dragged, the aligned characters of its segment follow it, and undo puts them back
        MarkUndo(List<Long> b, Map<Long, Integer> t) { before = b; tonesBefore = t; }
    }
    MarkUndo markSnap() { return new MarkUndo(new ArrayList<>(sylMarks), new HashMap<>(sylTones)); }
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
        if (top instanceof MarkUndo mu) {
            sylMarks.clear(); sylMarks.addAll(mu.before);
            sylTones.clear(); sylTones.putAll(mu.tonesBefore);
            if (mu.alignSeg != null) mu.alignSeg.ams = mu.alignAms;
            wave.repaint(); selOverlay.repaint(); saveRegs();
            status.setText(" Undid the last syllable mark / tone change.");
            updateCharBar(); pitch.repaint(); charBar.repaint();
            return;
        }
        @SuppressWarnings("unchecked") List<Snap> snap = (List<Snap>) top;
        List<Seg> current = new ArrayList<>(segs);
        Seg keep = sel;
        segs.clear();
        for (Snap n : snap) {
            n.seg.s = n.s; n.seg.e = n.e; n.seg.ach = n.ach; n.seg.ams = n.ams;
            if (!current.contains(n.seg)) n.seg.text = n.text; // segments that were removed get their old text back
            segs.add(n.seg);                                   // surviving ones keep whatever you've typed since
        }
        sortSegs();
        select(segs.contains(keep) ? keep : null);
        saveSrt(); saveRegs();
        status.setText(" Undid the last change.");
    }

    /** Tab: play from the dotted cursor to the end of the segment (or behave like play/pause if there is no cursor). */
    void playFromCursor() {
        if (recording || pcm.length == 0) return;
        if (range == null && sel != null && cursorMs > sel.s && cursorMs < sel.e) {
            final Seg owner = sel;
            final Seg part = new Seg(cursorMs, nextBoundary(cursorMs, sel)); // to the next syllable boundary, or the segment end
            final boolean toBoundary = part.e < sel.e;
            resumeMs = -1;
            boolean looping = loop.isSelected();
            boolean auto = PREFS.getBoolean("autoRecord", false) && !looping;
            play(part.s, part.e, looping, () -> {
                if (toBoundary && sel == owner && range == null) { cursorMs = part.e; wave.repaint(); } // so the next Tab plays the next syllable
                if (auto && !recording) startRecording(1, part);
            });
        } else togglePlay();
    }

    long resumeMs = -1; Seg resumeSeg; volatile boolean playingOriginal;

    /** The fast-forward button: go to the next segment and play it from its start. */
    void nextAndPlay() {
        if (recording || pcm.length == 0 || segs.isEmpty()) return;
        int i = sel == null ? -1 : segs.indexOf(sel);
        if (i >= segs.size() - 1) { status.setText(" That is the last segment."); return; }
        stopPlay(); resumeMs = -1; resumeSeg = null;
        step(1);
        togglePlay();
    }

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
    }

    void tick() {
        SourceDataLine l = line;
        if (l != null) {
            playMs = playOffsetMs + (playBase + l.getLongFramePosition()) * 1000L / playRate;
        } else if (!isPlaying()) { playTimer.stop(); playMs = -1; resumeMs = -1; }
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
    static final Color REC_RED = new Color(225, 40, 40);
    boolean recPulse = true;                       // the REC dot pulses
    final javax.swing.Timer recBlink = new javax.swing.Timer(500, e -> { recPulse = !recPulse; wave.repaint(); });
    int savedScrollMode = -1;
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
        if (recording) { recPulse = true; if (!recBlink.isRunning()) recBlink.start(); }
        else recBlink.stop();
        if (sp != null) { // the badge sits in the visible corner, so scroll by repainting rather than by copying pixels while recording
            JViewport vp = sp.getViewport();
            if (recording && savedScrollMode < 0) { savedScrollMode = vp.getScrollMode(); vp.setScrollMode(JViewport.SIMPLE_SCROLL_MODE); }
            else if (!recording && savedScrollMode >= 0) { vp.setScrollMode(savedScrollMode); savedScrollMode = -1; }
        }
        wave.repaint(); selOverlay.repaint();
        recBtn.setIcon(recording && recMode == 2 ? IC_STOP : IC_REC); // a red stop square while recording a new track
        recBtn.setToolTipText(recording && recMode == 2 ? "Stop recording (it stays on screen; File > Save keeps it)"
                : pcm.length == 0 ? "Start recording a new track"
                : "Record (R): record the microphone for the length of the dragged range or selected segment");
        trackBtn.setText("New");
        recBtn.setEnabled(true); // doubles as the stop button
        trackBtn.setEnabled(!recording);
        micBox.setEnabled(!recording);
    }

    /** Shadowing: record the microphone for exactly the length of the selected segment, then play it back. */
    void recordSegment() {
        if (recording) { recStop.set(true); return; } // cancels a shadowing recording; stops a new recording (it stays on screen, unsaved)
        if (pcm.length == 0 && !blankNew) newDocument();                       // no file open: open a blank new-recording tab first
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
        final List<Long> marks = new ArrayList<>();
        final Map<Long, Integer> tones = new HashMap<>();
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
        d.marks.clear(); d.marks.addAll(sylMarks);
        d.tones.clear(); d.tones.putAll(sylTones);
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
        sylMarks.clear(); sylMarks.addAll(d.marks);
        sylTones.clear(); sylTones.putAll(d.tones);
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
                if (loading) { status.setText(" Wait for the file to finish loading."); return; }
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
        tabBar.setVisible(!docs.isEmpty());
        tabBar.revalidate(); tabBar.repaint();
    }

    /** A document gets its tab only once it has something in it (a file or a new recording). */
    void ensureTab(Doc d) {
        if (docs.contains(d)) return;
        docs.add(d); tabBar.add(makeTab(d));
        refreshTabs();
    }

    void setTabName(Doc d, String name) {
        d.tabName = name;
        if (d.tabLabel != null) d.tabLabel.setText(name);
        tabBar.revalidate(); tabBar.repaint();
    }

    /** Closes a tab straight away: no questions, and a new recording that was never saved is simply dropped. */
    void closeDoc(Doc d) {
        if (recording) { status.setText(" Stop recording before closing a tab."); return; }
        if (loading) { status.setText(" Wait for the file to finish loading."); return; }
        int i = docs.indexOf(d);
        if (i < 0) return;
        boolean wasCurrent = d == curDoc;
        if (wasCurrent) { stopPlay(); saveSrt(); } // a file's subtitles are autosaved anyway
        docs.remove(i);
        tabBar.remove(d.tabComp);
        if (docs.isEmpty()) { // nothing left open: a blank screen with no tab
            curDoc = new Doc();
            restore(curDoc);
            status.setText(" Open an mp3 file (File > Open), or press the record button to start a new recording.");
        } else if (wasCurrent) {
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
        if (recording || loading) return;
        Doc d = new Doc();
        d.tabName = "New recording"; d.title = "Piyingxi - new recording";
        d.rate = rate > 0 ? rate : 44100;
        d.spp = d.rate / DEFAULT_PPS;
        d.unsaved = true; d.blankNew = true; d.liveNew = true;
        d.showSpec = true; d.showLine = false; // a new recording starts with the source spectrogram showing and the pitch contour hidden; both can be changed in the Source menu
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
        final boolean live = liveView; // this recording's choice
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
                        if (live && now - lastFast > 40) { lastFast = now; liveFast(sr1); } else if (!live && now - lastFast > 100) { lastFast = now; liveWave(sr1); }                                          // spectrogram + waveform: ~25 times a second
                        if (live && now - lastLive > 150 + (long) (liveLen / (double) sr1) * 3) { lastLive = now; liveSlow(sr1); }  // pitch contour: a few times a second
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

    /** Recording thread, live spectrogram switched off: only the waveform grows on screen (no analysis). */
    void liveWave(int sr) {
        if (!liveDocReady || liveLen < sr / 20) return;
        final int total = liveLen;
        long now = System.currentTimeMillis();
        if (livePcmPub == null || now - lastPcmPub > 80 + (long) (total / (double) sr) * 3) {
            livePcmPub = Arrays.copyOf(liveBuf, total);
            lastPcmPub = now;
        }
        postLive(sr);
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
                        else status.setText(String.format(" Range %.2f s selected: Space plays it, the octave slider adjusts only this range, " + keyName("split") + " makes it a segment.", (range.e - range.s) / 1000.0));
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
            if (recording) drawRecIndicator(g);
            if (charBar.isVisible()) charBar.repaint(c.x, 0, Math.max(1, c.width), charBar.getHeight()); // the character bar follows the waveform's repaints
        }

        /** While recording: a red frame round the waveform when there is no segment box to turn red (a new track), and a REC badge in the visible top-right corner. */
        void drawRecIndicator(Graphics2D g) {
            Rectangle vr = getVisibleRect();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (recMode == 2) {
                Stroke old = g.getStroke();
                g.setColor(REC_RED);
                g.setStroke(new BasicStroke(3f));
                g.drawRect(vr.x + 1, vr.y + 1, vr.width - 3, vr.height - 3);
                g.setStroke(old);
            }
            g.setFont(new Font(Font.DIALOG, Font.BOLD, 15));
            FontMetrics fm = g.getFontMetrics();
            String t = "REC";
            int w = 10 + 14 + 8 + fm.stringWidth(t) + 12, h = 28, x = vr.x + vr.width - w - 14, y = vr.y + 12;
            g.setColor(new Color(0, 0, 0, 165));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(recPulse ? REC_RED : new Color(225, 40, 40, 110)); // a pulsing red dot
            g.fillOval(x + 10, y + 7, 14, 14);
            g.setColor(Color.WHITE);
            g.drawString(t, x + 10 + 14 + 8, y + (h + fm.getAscent() - fm.getDescent()) / 2);
        }

        void paintSeg(Graphics2D g, Seg s, boolean selected) {
            int x1 = (int) msToX(s.s), x2 = (int) msToX(s.e), h = getHeight();
            int w = Math.max(2, x2 - x1 + 1);
            g.setColor(selected ? (recording && recMode == 1 ? REC_RED : inkSel) : (curDark ? new Color(115, 115, 122) : new Color(135, 135, 142))); // red while recording into it
            g.fillRect(x1, h - 2, w, 2);       // bottom line
            g.fillRect(x1, 0, 2, h);
            g.fillRect(x2 - 1, 0, 2, h);
            if (!s.text.isBlank() && !(selected && recording && recMode == 1)) g.setColor(new Color(0, 160, 60)); // the top line turns green once the segment has a subtitle (red box while recording)
            g.fillRect(x1, 0, w, 2);
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
                g.setColor(sourceColour());
                drawContour(g, f, drawnSrc, 0, c, h, pad, null);
            }
            if (PREFS.getBoolean("showOrange", false)) {
                g.setColor(shadowColour());
                for (Rec r : recs) drawContour(g, r.f0, r.drawn, r.startMs + recShift, c, h, pad, r);
            }
            if (modelOn) drawModel(g, c, h, pad);
        }

        /** The pitch range the model is scaled to, as {low, high} semitones re 100 Hz: the 5th to 95th percentile of the latest recording
         *  as it is displayed (so it follows the shadow's vertical shift), else of the original. Null if there is no pitch yet. */
        double[] modelRange() {
            Rec r = recs.isEmpty() ? null : recs.get(recs.size() - 1);
            double[] rg = r != null ? percentileRange(r.f0, r.drawn, r.startMs + recShift, 10.0 * r.scale, r) : null;
            return rg != null ? rg : percentileRange(f0, drawnSrc, 0, 10.0, null);
        }

        double[] percentileRange(float[] f, float[] ov, long baseMs, double step, Rec rec) {
            int[] hist = new int[800]; // 0.1 st bins from -30 to +50 st
            int tot = 0, len = Math.max(f.length, ov == null ? 0 : ov.length);
            for (int i = 0; i < len; i++) {
                float fv = val(f, ov, i);
                if (fv <= 0) continue;
                double hz = disp(fv, rec, baseMs + Math.round(i * step));
                if (hz <= 0) continue;
                int bin = (int) Math.floor((12 * Math.log(hz / 100) / Math.log(2) + 30) * 10);
                if (bin < 0 || bin >= hist.length) continue;
                hist[bin]++; tot++;
            }
            if (tot < 15) return null;
            double lo = 0, hi = 0;
            int acc = 0; boolean gotLo = false;
            for (int i = 0; i < hist.length; i++) {
                acc += hist[i];
                if (!gotLo && acc >= tot * 0.05) { lo = i / 10.0 - 30; gotLo = true; }
                if (acc >= tot * 0.95) { hi = i / 10.0 - 30 + 0.1; break; }
            }
            if (hi - lo < 6) { double mid = (hi + lo) / 2; lo = mid - 3; hi = mid + 3; } // a very narrow recording still gets a usable range
            return new double[]{lo, hi};
        }

        /** The synthetic contour: dashed, over the recorded ones, on the original audio's time axis. Each segment is its own chain, and by default
         *  only the selected segment is modelled, so nothing is ever carried across the gap between phrases. */
        void drawModel(Graphics2D g, Rectangle c, int h, int pad) {
            double[] rg = modelRange();
            if (rg == null) return;
            List<Seg> todo = scopeSel() ? (sel == null ? new ArrayList<Seg>() : Arrays.asList(sel)) : segs;
            long vl = wave.xToMs(c.x) - 300, vr = wave.xToMs(c.x + c.width) + 300;
            double decl = declSt();
            Stroke old = g.getStroke();
            g.setColor(modelColour());
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{9f, 6f}, 0f));
            for (Seg s : todo) {
                if (s.e < vl || s.s > vr) continue;
                List<Long> bb = syllableBounds(s);
                SylCh sc = sylChars(s, bb, true);
                double[][] pts = qtaChain(bb, sylTones, activeSys, sandhiOn, sc.ch, s.s, s.e, rg[0], rg[1], decl);
                Path2D.Double path = new Path2D.Double();
                boolean pen = false;
                for (int k = 0; k < pts[0].length; k++) {
                    if (Double.isNaN(pts[1][k])) { pen = false; continue; }
                    long ms = (long) pts[0][k];
                    double x = wave.msToX(ms), y = yOf(pts[1][k], viewLoAt(ms), viewHiAt(ms), h, pad);
                    if (!pen) { path.moveTo(x, y); path.lineTo(x + 0.1, y); pen = true; } else path.lineTo(x, y);
                }
                g.draw(path);
            }
            g.setStroke(old);
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

    /** The aligned-characters bar: each character centred under its syllable, on the same time axis as the waveform. */
    class CharBar extends JComponent {
        CharBar() {
            setVisible(false);
            addMouseWheelListener(e -> handleWheel(e));
            addMouseListener(new MouseAdapter() { // click a syllable's character to choose its tone
                public void mousePressed(MouseEvent e) {
                    if (!toneUi()) return;
                    long ms = wave.xToMs(e.getX());
                    for (Seg s : segs) {
                        if (ms < s.s || ms >= s.e) continue;
                        List<Long> b = syllableBounds(s);
                        for (int i = 0; i + 1 < b.size(); i++)
                            if (ms >= b.get(i) && ms < b.get(i + 1)) { showToneMenu(CharBar.this, e.getX(), e.getY(), b.get(i)); return; }
                    }
                }
            });
        }

        int markKind(String t) { return toneMarkKind(t); }

        int toneW(FontMetrics fm, String t) {
            if (t.isEmpty()) return 8;
            int k = markKind(t);
            if (k == 0) return fm.stringWidth(t);
            String suf = t.substring(1);
            return MARK_W + (suf.isEmpty() ? 0 : 3 + fm.stringWidth(suf));
        }

        /** Draws a tone mark (or any text) centred on cx, in the tone row. */
        void drawTone(Graphics2D g, FontMetrics fm, String t, int cx, int baseline) {
            int k = markKind(t);
            if (k == 0) { g.drawString(t, cx - fm.stringWidth(t) / 2, baseline); return; }
            String suf = t.substring(1);
            int total = toneW(fm, t), x = cx - total / 2, y = (TONE_ROW - MARK_H) / 2;
            paintToneMark(g, k, x, y);
            if (!suf.isEmpty()) g.drawString(suf, x + MARK_W + 3, baseline);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            Rectangle c = g.getClipBounds();
            if (c == null) c = new Rectangle(0, 0, getWidth(), getHeight());
            g.setColor(boxBg());
            g.fillRect(c.x, c.y, c.width, c.height);
            g.setColor(new Color(128, 128, 128, 90));
            g.drawLine(c.x, 0, c.x + c.width, 0);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean tu = toneUi();
            int toneH = tu ? TONE_ROW : 0;
            Font cf = text.getFont().deriveFont((float) alignFontPx()), tf = text.getFont().deriveFont(Font.BOLD, 22f);
            FontMetrics cfm = g.getFontMetrics(cf), tfm = g.getFontMetrics(tf);
            int charBase = toneH + (charRowH() + cfm.getAscent() - cfm.getDescent()) / 2, toneBase = (TONE_ROW + tfm.getAscent() - tfm.getDescent()) / 2;
            Color ink = curDark ? Color.WHITE : Color.BLACK;
            for (Seg s : segs) {
                if (wave.msToX(s.e) < c.x - 40 || wave.msToX(s.s) > c.x + c.width + 40) continue;
                List<Long> b = syllableBounds(s);
                int n = b.size() - 1;
                SylCh sc = sylChars(s, b, tu);
                Surf[] sf = tu ? surfaces(b, sylTones, activeSys, sandhiOn, sc.ch) : null;
                int[] cx = new int[n];
                for (int i = 0; i < n; i++) cx[i] = (int) wave.msToX(Math.round(sc.ms[i]));
                boolean charsFit = true, toneFit = true; // zoomed out too far, neighbours would sit on top of each other: hide that row for this segment
                for (int i = 0; i + 1 < n; i++) {
                    int gap = cx[i + 1] - cx[i];
                    int wc = Math.max(sc.ch[i] == null ? 6 : cfm.stringWidth(sc.ch[i]), sc.ch[i + 1] == null ? 6 : cfm.stringWidth(sc.ch[i + 1])) + 4;
                    if (gap < wc) charsFit = false;
                    if (tu) {
                        int wt = Math.max(toneW(tfm, toneText(sf[i])), toneW(tfm, toneText(sf[i + 1]))) + 4;
                        if (gap < wt) toneFit = false;
                    }
                }
                if (charsFit) {
                    g.setFont(cf);
                    for (int i = 0; i < n; i++) {
                        if (cx[i] < c.x - 40 || cx[i] > c.x + c.width + 40) continue;
                        if (sc.ch[i] != null) { g.setColor(ink); g.drawString(sc.ch[i], cx[i] - cfm.stringWidth(sc.ch[i]) / 2, charBase); }
                        else if (tu) { g.setColor(new Color(128, 128, 128, 140)); g.drawString("\u00b7", cx[i] - cfm.stringWidth("\u00b7") / 2, charBase); } // something to click
                    }
                }
                if (tu && toneFit) {
                    g.setFont(tf);
                    g.setColor(modelColour());
                    for (int i = 0; i < n; i++) {
                        String t = toneText(sf[i]);
                        if (t.isEmpty() || cx[i] < c.x - 40 || cx[i] > c.x + c.width + 40) continue;
                        drawTone(g, tfm, t, cx[i], toneBase);
                    }
                }
            }
        }
    }

    /** The dragged range's box, drawn once over both the pitch panel and the waveform so the two can never disagree. */
    class SelOverlay extends JComponent {
        int dragIdx = -1;            // index in sylMarks of the boundary being dragged
        long dragKey;                // its current time
        boolean dragMoved;
        Seg dragSeg;                 // the segment it belongs to
        MarkUndo dragSnap;

        SelOverlay() {
            setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)); // the overlay is only ever under the mouse on a boundary line
            MouseAdapter ma = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) return;
                    int i = markAt(e.getX());
                    if (i < 0) return;
                    long m = sylMarks.get(i);
                    Seg owner = null;
                    for (Seg s : segs) if (m > s.s && m < s.e) { owner = s; break; }
                    if (owner == null) return;
                    dragIdx = i; dragKey = m; dragSeg = owner; dragMoved = false;
                    dragSnap = markSnap();
                    dragSnap.alignSeg = owner; dragSnap.alignAms = owner.ams == null ? null : owner.ams.clone();
                    wave.requestFocusInWindow();
                }
                public void mouseDragged(MouseEvent e) {
                    if (dragIdx < 0) return;
                    long lo = dragSeg.s, hi = dragSeg.e; // it stays inside its segment and clear of its neighbours
                    for (long m : sylMarks) {
                        if (m == dragKey || m <= dragSeg.s || m >= dragSeg.e) continue;
                        if (m < dragKey) lo = Math.max(lo, m); else hi = Math.min(hi, m);
                    }
                    long nm = Math.max(lo + MIN_PIECE, Math.min(hi - MIN_PIECE, wave.xToMs(e.getX())));
                    if (nm == dragKey) return;
                    if (!dragMoved) { pushUndoItem(dragSnap); dragMoved = true; }
                    sylMarks.set(dragIdx, nm);
                    Integer tone = sylTones.remove(dragKey); // the tone belongs to the syllable that starts here, so it moves too
                    if (tone != null) sylTones.put(nm, tone);
                    dragKey = nm;
                    recentreAlign(dragSeg);
                    status.setText(" Syllable boundary at " + fmt(nm) + ".");
                    wave.repaint(); pitch.repaint(); charBar.repaint(); repaint();
                }
                public void mouseReleased(MouseEvent e) {
                    if (dragIdx < 0) return;
                    if (dragMoved) saveRegs();
                    dragIdx = -1; dragSeg = null; dragSnap = null;
                    wave.repaint(); pitch.repaint(); charBar.repaint(); repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(e -> handleWheel(e));
        }

        /** Index of the syllable boundary within a few pixels of x, or -1. */
        int markAt(int x) {
            if (!showSyl || drawMode || moveMode) return -1;
            int best = -1;
            double bd = 5;
            for (int i = 0; i < sylMarks.size(); i++) {
                double d = Math.abs(wave.msToX(sylMarks.get(i)) - x);
                if (d <= bd) { bd = d; best = i; }
            }
            return best;
        }

        // takes mouse events only on a boundary line (or while dragging one); everywhere else they fall through to the waveform and tone bar
        @Override public boolean contains(int x, int y) { return dragIdx >= 0 || markAt(x) >= 0; }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            if (showSyl && !sylMarks.isEmpty()) { // syllable boundaries: pale dotted lines over the pitch panel and the waveform
                Stroke old = g.getStroke();
                g.setColor(curDark ? new Color(255, 255, 255, 150) : new Color(0, 0, 0, 140));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 4f}, 0f));
                int hh = getHeight();
                for (long m : sylMarks) { int mx = (int) wave.msToX(m); g.drawLine(mx, 0, mx, hh); }
                g.setStroke(old);
            }
            Seg r = range;
            if (r == null) return;
            int x1 = (int) wave.msToX(r.s), x2 = (int) wave.msToX(r.e), h = getHeight();
            g.setColor(recording && recMode == 1 ? REC_RED : wave.inkSel);
            g.fillRect(x1, 0, Math.max(2, x2 - x1 + 1), 2);
            g.fillRect(x1, h - 2, Math.max(2, x2 - x1 + 1), 2); // bottom line
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
