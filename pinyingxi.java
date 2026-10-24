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

    void applyTextStyle() {
        text.setBackground(boxBg());
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
    }

    final Wave wave = new Wave();
    final Pitch pitch = new Pitch();
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
        JPanel stack = new JPanel(null);
        stack.setLayout(new LayoutManager() { // pitch panel and waveform get equal heights (waveform fills all if pitch is hidden)
            public void addLayoutComponent(String n, Component c) { }
            public void removeLayoutComponent(Component c) { }
            public Dimension preferredLayoutSize(Container p) { return new Dimension(wave.getPreferredSize().width, 220); }
            public Dimension minimumLayoutSize(Container p) { return new Dimension(1, 120); }
            public void layoutContainer(Container p) {
                int w = p.getWidth(), h = p.getHeight();
                if (pitch.isVisible()) { int ph = h / 2; pitch.setBounds(0, 0, w, ph); wave.setBounds(0, ph, w, h - ph); }
                else wave.setBounds(0, 0, w, h);
            }
        });
        stack.add(pitch);
        stack.add(wave);
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
        JPanel transport = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        recBtn = symBtn(REC, "Record (R): record the microphone for the length of the selected segment", this::recordSegment);
        transport.add(recBtn);
        transport.add(symBtn("\u23EA\uFE0E", "Previous segment (Left arrow)", () -> step(-1)));
        playBtn = symBtn(PLAY, "Play / pause (Space)", this::togglePlay);
        transport.add(playBtn);
        transport.add(symBtn("\u23E9\uFE0E", "Next segment (Right arrow)", () -> step(1)));
        loop.setFont(new Font(Font.DIALOG, Font.PLAIN, 22));
        loop.setToolTipText("Loop the selected segment");
        loop.setFocusable(false);
        loop.setPreferredSize(new Dimension(56, 38));
        transport.add(loop);
        transport.add(symBtn("\u2702\uFE0E", "Split (S): split the selected segment at the click position, or make the dragged range its own segment", this::splitAction));
        transport.add(symBtn("\u21B6", "Undo the last split / segment change (Ctrl+Z)", this::undo));
        transport.add(Box.createHorizontalStrut(24));
        transport.add(symBtn("-", "Zoom out (Ctrl+-)", () -> zoom(1.5)));
        transport.add(symBtn("+", "Zoom in (Ctrl++)", () -> zoom(1 / 1.5)));
        JPanel upper = new JPanel(new BorderLayout());
        upper.add(sp, BorderLayout.CENTER);
        upper.add(transport, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, new JScrollPane(text));
        split.setResizeWeight(0.65);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        tools.add(btn("Open...", this::openDialog));
        tools.add(btn("Save", this::saveSrt));
        trackBtn = btn("Record new track", this::recordTrack);
        tools.add(trackBtn);
        tools.add(new JLabel("Microphone:"));
        micBox.setFocusable(false);
        micBox.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxxxxxxx");
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
        final JLabel pitchLbl = new JLabel();
        final RangeSlider pitchSlider = new RangeSlider(0, 500, PREFS.getInt("viewLo", 50), PREFS.getInt("viewHi", 300));
        pitchSlider.setToolTipText("Pitch display range (Hz)");
        Runnable showRange = () -> pitchLbl.setText(pitchSlider.lo + "\u2013" + pitchSlider.hi + " Hz");
        showRange.run();
        pitchSlider.onChange = () -> {
            showRange.run();
            PREFS.putInt("viewLo", pitchSlider.lo); PREFS.putInt("viewHi", pitchSlider.hi);
            pitch.repaint();
        };
        pitchSlider.onRelease = () -> wave.requestFocusInWindow();
        pitchLbl.setPreferredSize(new Dimension(100, 20));
        tools.add(pitchSlider);
        tools.add(pitchLbl);
        JToggleButton blueTog = new JToggleButton("source contour", PREFS.getBoolean("showBlue", true));
        blueTog.setForeground(new Color(40, 140, 255));
        blueTog.setToolTipText("Show the pitch contour of the original audio");
        JToggleButton orangeTog = new JToggleButton("shadow contour", PREFS.getBoolean("showOrange", false));
        orangeTog.setForeground(new Color(255, 140, 0));
        orangeTog.setToolTipText("Show the pitch contour of your latest recording");
        blueTog.setFocusable(false); orangeTog.setFocusable(false);
        blueTog.addActionListener(e -> { PREFS.putBoolean("showBlue", blueTog.isSelected()); pitch.repaint(); });
        orangeTog.addActionListener(e -> { PREFS.putBoolean("showOrange", orangeTog.isSelected()); pitch.repaint(); });
        tools.add(blueTog);
        tools.add(orangeTog);
        tools.add(btn("Auto-split", this::autoSplit));
        add(tools, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        bind("alt RIGHT", () -> step(1));
        bind("alt LEFT", () -> step(-1));
        bind("ctrl S", this::saveSrt);
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
        JCheckBoxMenuItem autoRec = new JCheckBoxMenuItem("Auto-record after playback", PREFS.getBoolean("autoRecord", false));
        autoRec.addActionListener(e -> PREFS.putBoolean("autoRecord", autoRec.isSelected()));
        settings.add(autoRec);
        JCheckBoxMenuItem showSpec = new JCheckBoxMenuItem("Show spectrogram behind pitch", PREFS.getBoolean("showSpec", true));
        showSpec.addActionListener(e -> {
            PREFS.putBoolean("showSpec", showSpec.isSelected());
            if (showSpec.isSelected() && spec == null) computeSpectrogram();
            pitch.repaint();
        });
        settings.add(showSpec);
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
        mb.add(settings);
        setJMenuBar(mb);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { if (recStop != null) recStop.set(true); stopPlay(); saveSrt(); System.exit(0); }
            public void windowActivated(WindowEvent e) { refreshTheme(false); }
        });
        pack();
        setLocationRelativeTo(null);
    }

    static final String PLAY = "\u23EF\uFE0E", PAUSE = PLAY, REC = "\u23FA\uFE0E", STOP = "\u23F9\uFE0E", DOT = "\u25CF\uFE0E";
    JButton playBtn;

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

    // ---------------------------------------------------------------- pitch (F0)
    static class Spec {
        final byte[] d; final int cols, bins; final double binHz;
        Spec(byte[] d, int cols, int bins, double binHz) { this.d = d; this.cols = cols; this.bins = bins; this.binHz = binHz; }
    }
    volatile Spec spec, recSpec; // recSpec: spectrogram of your latest recording (replaces the original's where it overlaps)
    volatile int specGen;

    void computeSpectrogram() {
        final int gen = ++specGen;
        final short[] src = pcm; final int sr = rate;
        spec = null; pitch.repaint();
        if (src.length == 0 || !PREFS.getBoolean("showSpec", true)) return;
        if (src.length / sr > 1800) { status.setText(" Spectrogram skipped: audio is longer than 30 minutes."); return; }
        new Thread(() -> {
            Spec r = spectrogram(src, sr, gen);
            if (r != null) SwingUtilities.invokeLater(() -> { if (gen == specGen) { spec = r; pitch.repaint(); } });
        }).start();
    }

    /** Narrow-band spectrum of the low frequencies only (0-520 Hz, the range of the pitch view):
     *  50 ms Hann window, one column per 10 ms, 45 dB range. The bright ridge follows F0. */
    Spec spectrogram(short[] src, int sr, int gen) {
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
            for (int c = 0; c < cols; c += pass == 0 ? 8 : 1) {
                if ((c & 255) == 0 && gen != specGen) return null;
                int start = c * hop - win / 2;
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
        return new Spec(out, cols, bins, binHz);
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
            int start = i * hop - N / 2 - maxLag / 4;
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
        stopPlay(); saveSrt();
        final long resume = f.getAbsolutePath().equals(PREFS.get("lastFile", "")) ? PREFS.getLong("lastSegMs", -1) : -1;
        PREFS.put("lastFile", f.getAbsolutePath());
        if (resume < 0) PREFS.putLong("lastSegMs", -1);
        if (f.getAbsoluteFile().getParent() != null) PREFS.put("lastDir", f.getAbsoluteFile().getParent());
        status.setText(" Decoding " + f.getName() + " ...");
        new Thread(() -> {
            try {
                decode(f);
                SwingUtilities.invokeLater(() -> {
                    String n = f.getName();
                    int dot = n.lastIndexOf('.');
                    srt = new File(f.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".srt");
                    segs.clear(); undoStack.clear(); select(null);
                    if (srt.exists()) loadSrt();
                    wave.spp = rate / 100.0; // 100 px per second
                    wave.updateSize();
                    computePitch();
                    computeSpectrogram();
                    recF0 = new float[0]; recSpec = null;
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
        wave.repaint(); pitch.repaint();
    }

    void step(int d) {
        if (segs.isEmpty()) return;
        int i = sel == null ? (d > 0 ? -1 : segs.size()) : segs.indexOf(sel);
        i = Math.max(0, Math.min(segs.size() - 1, i + d));
        select(segs.get(i));
    }

    void deleteSel() {
        if (sel == null) return;
        pushUndo(); segs.remove(sel); select(null); saveSrt();
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
    static final long MIN_PIECE = 100;

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
    final java.util.ArrayDeque<List<Snap>> undoStack = new java.util.ArrayDeque<>();

    List<Snap> snapshot() { List<Snap> l = new ArrayList<>(); for (Seg g : segs) l.add(new Snap(g)); return l; }
    void pushUndo(List<Snap> snap) { undoStack.push(snap); while (undoStack.size() > 50) undoStack.removeLast(); }
    void pushUndo() { pushUndo(snapshot()); }

    void undo() {
        if (undoStack.isEmpty()) { status.setText(" Nothing to undo."); return; }
        stopPlay();
        List<Snap> snap = undoStack.pop();
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
    void zoomToSeg(Seg s) {
        JViewport vp = sp.getViewport();
        int vw = Math.max(100, vp.getWidth());
        double fit = Math.max(1, Math.min(rate * 2.0, (s.e - s.s) / 1000.0 * rate / (vw * 0.96)));
        boolean already = Math.abs(wave.spp - fit) < 0.5;
        double target;
        if (already && prevSpp > 0) { target = prevSpp; prevSpp = -1; }
        else { if (!already) prevSpp = wave.spp; target = fit; }
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
    static final long REC_EXTRA_MS = 1000; // extra recording time after the segment length, for slower reading
    static final int[] MIC_RATES = {44100, 48000, 32000, 22050, 16000, 8000};
    JButton recBtn, trackBtn;
    final JComboBox<String> micBox = new JComboBox<>();
    boolean micUpdating;
    volatile boolean recording;
    volatile int recMode; // 1 = segment (shadowing), 2 = free-form new track
    volatile AtomicBoolean recStop;
    volatile float[] recF0 = new float[0];
    volatile long recStartMs, recEndMs;
    short[] recPcm = new short[0];

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
                l.open(f);
                fmtOut[0] = f;
                return l;
            }
        }
        throw new IllegalStateException("No usable microphone found");
    }

    void updateRecUi() {
        recBtn.setText(REC);
        recBtn.setForeground(recording && recMode == 1 ? new Color(235, 40, 40)
                : new javax.swing.plaf.ColorUIResource(UIManager.getColor("Button.foreground"))); // red only while recording
        trackBtn.setText(recording && recMode == 2 ? "Stop & save" : "Record new track");
        recBtn.setEnabled(!recording || recMode == 1);
        trackBtn.setEnabled(!recording || recMode == 2);
        micBox.setEnabled(!recording);
    }

    /** Shadowing: record the microphone for exactly the length of the selected segment, then play it back. */
    void recordSegment() {
        if (recording) { if (recMode == 1) recStop.set(true); return; }
        if (pcm.length == 0 || sel == null) { status.setText(" Select a segment first, then record."); return; }
        startRecording(1, sel);
    }

    /** Record your own free-form track; saved as a WAV and opened like any other audio file. */
    void recordTrack() {
        if (recording) { if (recMode == 2) recStop.set(true); return; }
        startRecording(2, null);
    }

    void startRecording(int mode, Seg seg) {
        stopPlay();
        final long maxMs = mode == 1 ? seg.e - seg.s + REC_EXTRA_MS : Long.MAX_VALUE;
        final AtomicBoolean stop = new AtomicBoolean(false);
        recStop = stop; recMode = mode; recording = true;
        updateRecUi();
        status.setText(mode == 1
                ? String.format(" \u25CF Recording %.1f s - speak now (R to cancel)", maxMs / 1000.0)
                : " \u25CF Recording a new track - press \"Stop & save\" when finished");
        new Thread(() -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            AudioFormat[] fo = new AudioFormat[1];
            String err = null; boolean complete = false;
            try {
                TargetDataLine l = openMic(fo);
                long target = mode == 1 ? (long) (maxMs * fo[0].getFrameRate() / 1000) * 2 : Long.MAX_VALUE;
                l.start();
                byte[] buf = new byte[4096];
                while (!stop.get() && bos.size() < target) {
                    int n = l.read(buf, 0, (int) Math.min(buf.length, target - bos.size()));
                    if (n > 0) bos.write(buf, 0, n);
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

    static short[] toShorts(byte[] b) {
        short[] out = new short[b.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((b[2 * i + 1] << 8) | (b[2 * i] & 0xff));
        return out;
    }

    void recordingDone(int mode, Seg seg, byte[] bytes, AudioFormat fmt, boolean ok, String error) {
        recording = false; recMode = 0; updateRecUi();
        if (error != null) {
            status.setText(" Recording failed: " + error);
            JOptionPane.showMessageDialog(this, "Could not record: " + error + "\n\nCheck the microphone selection and that your system exposes it to Java (ALSA).");
            return;
        }
        if (!ok || bytes.length < 400) { status.setText(" Recording cancelled."); return; }
        if (mode == 2) { saveTrack(bytes, fmt); return; }
        final short[] data = toShorts(bytes);
        final int sr = (int) fmt.getSampleRate();
        // line up where speech starts in the recording with where it starts in the original segment
        long origOn = onsetMs(pcm, rate, (int) (seg.s * rate / 1000), (int) Math.min(pcm.length, seg.e * rate / 1000));
        long recOn = onsetMs(data, sr, 0, data.length);
        long base = seg.s + origOn - recOn;
        recPcm = data; recStartMs = base; recEndMs = seg.e; recF0 = new float[0]; recSpec = null;
        pitch.repaint();
        startPlay(data, sr, 0, data.length, false, base, null);
        status.setText(" Playing back your recording...");
        final int gen = pitchGen;
        final double fl = ANALYSIS_LO, ce = ANALYSIS_HI;
        new Thread(() -> {
            float[] o = pitchTrack(data, sr, fl, ce, gen);
            if (o != null) SwingUtilities.invokeLater(() -> { if (recPcm == data) { recF0 = smooth(o); pitch.repaint(); } });
            Spec rs = spectrogram(data, sr, specGen);
            if (rs != null) SwingUtilities.invokeLater(() -> { if (recPcm == data) { recSpec = rs; pitch.repaint(); } });
        }).start();
    }

    void saveTrack(byte[] bytes, AudioFormat fmt) {
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setFileFilter(new FileNameExtensionFilter("WAV audio", "wav"));
        fc.setSelectedFile(new File(lastDir(), "recording-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".wav"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { status.setText(" Recording discarded."); return; }
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".wav")) f = new File(f.getParentFile(), f.getName() + ".wav");
        if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " exists. Overwrite?", "Overwrite", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(bytes), fmt, bytes.length / fmt.getFrameSize())) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, f);
        } catch (IOException ex) { status.setText(" Could not save: " + ex.getMessage()); return; }
        open(f);
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
            getActionMap().put("del", new AbstractAction() { public void actionPerformed(ActionEvent e) { deleteSel(); } });
            getInputMap().put(KeyStroke.getKeyStroke("A"), "zoomAll");
            getActionMap().put("zoomAll", new AbstractAction() { public void actionPerformed(ActionEvent e) { zoomAll(); } });
            getActionMap().put("edit", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { if (sel != null) text.requestFocusInWindow(); }
            });
            getActionMap().put("play", new AbstractAction() { public void actionPerformed(ActionEvent e) { togglePlay(); } });
            getActionMap().put("prev", new AbstractAction() { public void actionPerformed(ActionEvent e) { step(-1); } });
            getActionMap().put("next", new AbstractAction() { public void actionPerformed(ActionEvent e) { step(1); } });
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
                    mode = 1; anchor = ms; drag = new Seg(ms, ms);
                }
                public void mouseDragged(MouseEvent e) {
                    if (mode == 0) return;
                    long ms = clamp(xToMs(e.getX()));
                    if (mode == 1) { drag.s = Math.min(anchor, ms); drag.e = Math.max(anchor, ms); }
                    else if (mode == 2) drag.s = Math.min(ms, drag.e - 20);
                    else if (mode == 3) drag.e = Math.max(ms, drag.s + 20);
                    else if (mode == 4) {
                        if (range == null && Math.abs(e.getX() - msToX(anchor)) < 4) return; // still just a click
                        if (range == null) range = new Seg(anchor, anchor);
                        range.s = Math.min(anchor, ms); range.e = Math.max(anchor, ms);
                    }
                    scrollRectToVisible(new Rectangle(e.getX() - 20, 0, 40, 1));
                    repaint(); pitch.repaint();
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
                        if (range.e - range.s < 100) range = null;
                        else status.setText(String.format(" Range %.2f s selected: Space plays it, S makes it its own segment.", (range.e - range.s) / 1000.0));
                    }
                    mode = 0; drag = null; repaint(); pitch.repaint();
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
                    if (e.isControlDown()) zoom(e.getWheelRotation() > 0 ? 1.25 : 1 / 1.25);
                    else sp.getHorizontalScrollBar().setValue(
                            sp.getHorizontalScrollBar().getValue() + e.getWheelRotation() * 60);
                }
            };
            addMouseListener(m); addMouseMotionListener(m); addMouseWheelListener(m);
        }

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
            if (range != null) paintSeg(g, range, true);
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
            addMouseListener(new MouseAdapter() { public void mousePressed(MouseEvent e) { wave.requestFocusInWindow(); } });
            addMouseWheelListener(e -> {
                if (e.isControlDown()) zoom(e.getWheelRotation() > 0 ? 1.25 : 1 / 1.25);
                else sp.getHorizontalScrollBar().setValue(sp.getHorizontalScrollBar().getValue() + e.getWheelRotation() * 60);
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle c = g.getClipBounds(), vis = getVisibleRect();
            int h = getHeight(), pad = 16;
            double fl = PREFS.getInt("viewLo", 50), ce = PREFS.getInt("viewHi", 300);
            g.setColor(boxBg());
            g.fillRect(c.x, c.y, c.width, c.height);
            Spec sp0 = spec;
            halo = sp0 != null && PREFS.getBoolean("showSpec", true);
            if (halo) drawSpec(g, sp0, c, h, fl, ce, pad);
            float[] f = f0;
            if (f.length == 0 && pcm.length > 0) {
                g.setColor(Color.GRAY);
                g.drawString("Calculating pitch...", vis.x + 70, h / 2);
            }
            if (PREFS.getBoolean("showBlue", true)) {
                g.setColor(curDark ? new Color(90, 200, 255) : new Color(0, 70, 200));
                drawContour(g, f, 0, c, fl, ce, h, pad);
            }
            if (PREFS.getBoolean("showOrange", false)) {
                g.setColor(new Color(255, 140, 0));
                drawContour(g, recF0, recStartMs, c, fl, ce, h, pad);
            }
        }

        boolean halo; // outline the contours when they sit on top of the spectrogram

        void drawSpec(Graphics2D g, Spec orig, Rectangle c, int h, double fl, double ce, int pad) {
            int w = c.width;
            if (w <= 0 || h <= 0) return;
            Spec rs = recSpec;
            Spec[] specs = {orig, rs};
            double sigma = PREFS.getDouble("specSigma", 2.5);
            int r = sigma > 0 ? (int) Math.ceil(3 * sigma) : 0, W = w + 2 * r;
            // row -> fractional frequency bin, on exactly the same scale as yOf() so the ridge lines up with the contour
            int[][] rb = new int[2][h]; double[][] rf = new double[2][h];
            for (int si = 0; si < 2; si++) {
                if (specs[si] == null) continue;
                for (int y = 0; y < h; y++) {
                    double t = (h - pad - y) / (double) Math.max(1, h - 2 * pad);
                    double bf = (fl + t * (ce - fl)) / specs[si].binHz;
                    if (bf < 0 || bf > specs[si].bins - 1) rb[si][y] = -1;
                    else { rb[si][y] = (int) Math.floor(bf); rf[si][y] = bf - rb[si][y]; }
                }
            }
            long rStart = recStartMs;
            float[] a = new float[W * h];
            for (int xx = 0; xx < W; xx++) { // bilinear interpolation between the 10 ms columns and the frequency bins
                double ms = (c.x - r + xx) * wave.spp * 1000.0 / rate;
                int si = 0; double tm = ms;
                if (rs != null && ms >= rStart && ms < rStart + rs.cols * 10.0) { si = 1; tm = ms - rStart; }
                Spec q = specs[si];
                double cf = tm / 10.0;
                if (q == null || cf < 0 || cf > q.cols - 1) continue;
                int c0 = (int) Math.floor(cf), c1 = Math.min(c0 + 1, q.cols - 1);
                double fx = cf - c0;
                for (int y = 0; y < h; y++) {
                    int b0 = rb[si][y];
                    if (b0 < 0) continue;
                    int b1 = Math.min(b0 + 1, q.bins - 1);
                    double fy = rf[si][y];
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
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            int[] px = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int[] lut = new int[256];
            for (int v = 0; v < 256; v++) {
                int gray = curDark ? 30 + (int) (v * 0.65) : 255 - (int) (v * 0.80); // louder = brighter (dark) / darker (light)
                lut[v] = (gray << 16) | (gray << 8) | gray;
            }
            for (int i = 0; i < px.length; i++) px[i] = lut[Math.max(0, Math.min(255, Math.round(out[i])))];
            g.drawImage(img, c.x, 0, null);
        }

        /** Draws each voiced run as one smooth Catmull-Rom curve (no dots). */
        void drawContour(Graphics2D g, float[] f, long baseMs, Rectangle c, double fl, double ce, int h, int pad) {
            if (f.length == 0) return;
            int i0 = Math.max(0, (int) ((wave.xToMs(c.x) - baseMs) / 10) - 3);
            int i1 = Math.min(f.length - 1, (int) ((wave.xToMs(c.x + c.width) - baseMs) / 10) + 3);
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int i = i0;
            while (i <= i1) {
                if (f[i] <= 0 || f[i] < fl || f[i] > ce) { i++; continue; } // outside the displayed range = gap
                int j = i;
                while (j + 1 <= i1 && f[j + 1] > 0 && f[j + 1] >= fl && f[j + 1] <= ce) j++;
                int n = j - i + 1;
                double[] xs = new double[n], ys = new double[n];
                for (int k = 0; k < n; k++) {
                    xs[k] = wave.msToX(baseMs + (i + k) * 10L);
                    ys[k] = yOf(f[i + k], fl, ce, h, pad);
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
