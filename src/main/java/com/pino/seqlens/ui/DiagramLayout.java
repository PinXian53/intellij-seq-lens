package com.pino.seqlens.ui;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.ui.JBUI;
import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.MessageKind;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.ParticipantKind;
import com.pino.seqlens.model.SequenceDiagram;
import org.jetbrains.annotations.Nullable;

import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 將 {@link SequenceDiagram} 轉成可繪製的幾何資訊（未縮放的座標）。 */
final class DiagramLayout {

    static final int PAD_LEFT = JBUI.scale(36);
    static final int PAD_RIGHT = JBUI.scale(40);
    static final int PAD_TOP = JBUI.scale(14);
    /** 未計算版面時的預設標頭高度。 */
    static final int DEFAULT_HEADER_H = JBUI.scale(66);
    static final int BOX_PAD = JBUI.scale(14);
    static final int BOX_MIN_W = JBUI.scale(84);
    static final int COL_GAP = JBUI.scale(28);
    static final int ACT_W = JBUI.scale(10);
    static final int ACT_OFF = JBUI.scale(5);
    static final int ROW_GAP = JBUI.scale(10);
    static final int SELF_W = JBUI.scale(30);
    static final int SELF_H = JBUI.scale(14);
    static final int FRAG_PAD = JBUI.scale(12);

    record Column(Participant participant, int x, int boxWidth) {
    }

    /** 可點擊、可 hover 的項目。 */
    interface Hit {
        Rectangle bounds();

        @Nullable String tooltip();

        @Nullable SmartPsiElementPointer<? extends PsiElement> navigation();
    }

    record Message(int x1, int x2, int y, boolean self, int selfBottom, String label, MessageKind kind,
                   boolean isReturn, Rectangle bounds, @Nullable String tooltip,
                   @Nullable SmartPsiElementPointer<? extends PsiElement> navigation,
                   DiagramEvent.Expansion expansion, @Nullable String targetKey) implements Hit {

        Message(int x1, int x2, int y, boolean self, int selfBottom, String label, MessageKind kind,
                boolean isReturn, Rectangle bounds, @Nullable String tooltip,
                @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) {
            this(x1, x2, y, self, selfBottom, label, kind, isReturn, bounds, tooltip, navigation,
                    DiagramEvent.Expansion.EXPANDED, null);
        }
    }

    /** 未展開標記的文字（⊘ 不展開清單、⋯ 達最大深度），null 代表無標記。 */
    static @Nullable String badge(DiagramEvent.Expansion expansion) {
        return switch (expansion) {
            case STOPPED -> "⊘";
            case DEPTH_LIMIT -> "⋯";
            case EXPANDED -> null;
        };
    }

    static int badgeWidth(FontMetrics fm, DiagramEvent.Expansion expansion) {
        String b = badge(expansion);
        return b == null ? 0 : fm.stringWidth(b) + JBUI.scale(14);
    }

    record Note(Rectangle bounds, String[] lines, @Nullable String tooltip,
                @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) implements Hit {
    }

    record Activation(int x, int y1, int y2) {
    }

    record ElseLine(int y, String label) {
    }

    record Fragment(Rectangle bounds, DiagramEvent.FragmentType type, String condition, List<ElseLine> elses) {
    }

    final List<Column> columns = new ArrayList<>();
    final List<Message> messages = new ArrayList<>();
    final List<Note> notes = new ArrayList<>();
    final List<Activation> activations = new ArrayList<>();
    final List<Fragment> fragments = new ArrayList<>();
    final Map<String, Integer> columnIndex = new HashMap<>();
    int width;
    int height;
    /** 參與者方框高度與標頭高度，隨字體大小調整。 */
    int boxH;
    int headerH;
    boolean truncated;

    private DiagramLayout() {
    }

    // ------------------------------------------------------------------ 計算

    private record Frame(int col, int level, int startY) {
    }

    private static final class FragFrame {
        final DiagramEvent.FragmentStart start;
        final int startY;
        final int minWidth;
        final List<ElseLine> elses = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;

        FragFrame(DiagramEvent.FragmentStart start, int startY, int minWidth) {
            this.start = start;
            this.startY = startY;
            this.minWidth = minWidth;
        }

        void include(int x1, int x2) {
            minX = Math.min(minX, Math.min(x1, x2));
            maxX = Math.max(maxX, Math.max(x1, x2));
        }
    }

    static DiagramLayout compute(SequenceDiagram diagram, FontMetrics fm, FontMetrics noteFm) {
        DiagramLayout layout = new DiagramLayout();
        layout.truncated = diagram.isTruncated();
        layout.boxH = Math.max(JBUI.scale(34), fm.getHeight() + JBUI.scale(16));
        layout.headerH = layout.boxH + JBUI.scale(32);
        List<Participant> participants = diagram.getParticipants();
        int n = participants.size();
        int[] boxW = new int[n];
        for (int i = 0; i < n; i++) {
            Participant p = participants.get(i);
            layout.columnIndex.put(p.id(), i);
            boxW[i] = Math.max(BOX_MIN_W, fm.stringWidth(p.displayName()) + BOX_PAD * 2
                    + (p.kind() == ParticipantKind.TABLE ? JBUI.scale(8) : 0));
        }

        // 相鄰欄位的中心距離
        int[] gap = new int[Math.max(0, n - 1)];
        for (int i = 0; i < n - 1; i++) {
            gap[i] = (boxW[i] + boxW[i + 1]) / 2 + COL_GAP;
        }
        record Constraint(int a, int b, int width) {
        }
        List<Constraint> constraints = new ArrayList<>();
        for (DiagramEvent e : diagram.getEvents()) {
            String from = null, to = null, label = null;
            int extra = 0;
            if (e instanceof DiagramEvent.Call c) {
                from = c.from();
                to = c.to();
                label = c.label();
                extra = badgeWidth(fm, c.expansion());
            } else if (e instanceof DiagramEvent.Return r && r.visible()) {
                from = r.from();
                to = r.to();
                label = r.label();
            } else if (e instanceof DiagramEvent.Throw th) {
                from = th.from();
                to = th.to();
                label = th.label();
            }
            if (from == null) {
                continue;
            }
            Integer a = layout.columnIndex.get(from), b = layout.columnIndex.get(to);
            if (a == null || b == null) {
                continue;
            }
            int w = fm.stringWidth(label) + extra;
            if (a.equals(b)) {
                if (a < n - 1) {
                    constraints.add(new Constraint(a, a + 1, w + SELF_W + JBUI.scale(30) + boxW[a + 1] / 2));
                }
            } else {
                constraints.add(new Constraint(Math.min(a, b), Math.max(a, b), w + JBUI.scale(36)));
            }
        }
        constraints.sort(Comparator.comparingInt(c -> c.b() - c.a()));
        for (Constraint c : constraints) {
            int current = 0;
            for (int i = c.a(); i < c.b(); i++) {
                current += gap[i];
            }
            int deficit = c.width() - current;
            if (deficit > 0) {
                int span = c.b() - c.a();
                for (int i = c.a(); i < c.b(); i++) {
                    gap[i] += deficit / span + (i == c.b() - 1 ? deficit % span : 0);
                }
            }
        }
        int x = PAD_LEFT + (n > 0 ? boxW[0] / 2 : 0);
        int[] colX = new int[n];
        for (int i = 0; i < n; i++) {
            colX[i] = x;
            layout.columns.add(new Column(participants.get(i), x, boxW[i]));
            if (i < n - 1) {
                x += gap[i];
            }
        }
        int maxRight = n > 0 ? colX[n - 1] + boxW[n - 1] / 2 : PAD_LEFT;

        // 依序排列事件
        int fh = fm.getHeight();
        int y = PAD_TOP;
        int[] active = new int[n];
        Deque<Frame> frames = new ArrayDeque<>();
        Deque<FragFrame> fragStack = new ArrayDeque<>();

        for (DiagramEvent event : diagram.getEvents()) {
            switch (event) {
                case DiagramEvent.Call c -> {
                    int f = layout.columnIndex.getOrDefault(c.from(), 0);
                    int t = layout.columnIndex.getOrDefault(c.to(), 0);
                    int labelW = fm.stringWidth(c.label()) + badgeWidth(fm, c.expansion());
                    int arrowY = y + fh + JBUI.scale(3);
                    if (f == t) {
                        int x1 = edge(colX[f], active[f] - 1, 1);
                        int x2 = edge(colX[f], active[f], 1);
                        int bottom = arrowY + SELF_H;
                        Rectangle bounds = new Rectangle(x1, y, Math.max(labelW + JBUI.scale(8), SELF_W + 4), bottom - y + 4);
                        layout.messages.add(new Message(x1, x2, arrowY, true, bottom, c.label(), c.kind(), false,
                                bounds, c.tooltip(), c.navigation(), c.expansion(), c.targetKey()));
                        frames.push(new Frame(t, active[t], bottom));
                        active[t]++;
                        y = bottom + ROW_GAP;
                        include(fragStack, colX[f] - ACT_W, x1 + Math.max(labelW + JBUI.scale(8), SELF_W));
                        maxRight = Math.max(maxRight, x1 + labelW + JBUI.scale(12));
                    } else {
                        int dir = colX[t] > colX[f] ? 1 : -1;
                        int x1 = edge(colX[f], active[f] - 1, dir);
                        int x2 = edge(colX[t], active[t], -dir);
                        Rectangle bounds = new Rectangle(Math.min(x1, x2), y, Math.abs(x2 - x1), arrowY - y + JBUI.scale(5));
                        layout.messages.add(new Message(x1, x2, arrowY, false, 0, c.label(), c.kind(), false,
                                bounds, c.tooltip(), c.navigation(), c.expansion(), c.targetKey()));
                        frames.push(new Frame(t, active[t], arrowY));
                        active[t]++;
                        y = arrowY + ROW_GAP;
                        includeColumns(fragStack, colX[f], colX[t], Math.max(active[f], active[t]));
                    }
                }
                case DiagramEvent.Return r -> {
                    int f = layout.columnIndex.getOrDefault(r.from(), 0);
                    int t = layout.columnIndex.getOrDefault(r.to(), 0);
                    Frame frame = popFrame(frames, f);
                    if (frame != null) {
                        active[f] = Math.max(0, active[f] - 1);
                    }
                    int endY;
                    if (r.visible() && f != t) {
                        int arrowY = y + fh + JBUI.scale(3);
                        int dir = colX[t] > colX[f] ? 1 : -1;
                        int level = frame != null ? frame.level() : 0;
                        int x1 = edge(colX[f], level, dir);
                        int x2 = edge(colX[t], active[t] - 1, -dir);
                        Rectangle bounds = new Rectangle(Math.min(x1, x2), y, Math.abs(x2 - x1), arrowY - y + JBUI.scale(5));
                        layout.messages.add(new Message(x1, x2, arrowY, false, 0, r.label(), MessageKind.CALL, true,
                                bounds, r.label(), null));
                        includeColumns(fragStack, colX[f], colX[t], Math.max(active[f], active[t]) + 1);
                        endY = arrowY;
                        y = arrowY + ROW_GAP;
                    } else {
                        endY = y + JBUI.scale(2);
                        y += JBUI.scale(6);
                    }
                    if (frame != null) {
                        layout.activations.add(new Activation(colX[f] + frame.level() * ACT_OFF, frame.startY(), Math.max(endY, frame.startY() + JBUI.scale(8))));
                    }
                }
                case DiagramEvent.Throw th -> {
                    int f = layout.columnIndex.getOrDefault(th.from(), 0);
                    int t = layout.columnIndex.getOrDefault(th.to(), 0);
                    int labelW = fm.stringWidth(th.label());
                    int arrowY = y + fh + JBUI.scale(3);
                    if (f == t) {
                        int x1 = edge(colX[f], active[f] - 1, 1);
                        int bottom = arrowY + SELF_H;
                        Rectangle bounds = new Rectangle(x1, y, Math.max(labelW + JBUI.scale(8), SELF_W + 4), bottom - y + 4);
                        layout.messages.add(new Message(x1, x1, arrowY, true, bottom, th.label(), MessageKind.THROW,
                                false, bounds, th.tooltip(), th.navigation()));
                        include(fragStack, colX[f] - ACT_W, x1 + Math.max(labelW + JBUI.scale(8), SELF_W));
                        y = bottom + ROW_GAP;
                    } else {
                        int dir = colX[t] > colX[f] ? 1 : -1;
                        int x1 = edge(colX[f], active[f] - 1, dir);
                        int x2 = edge(colX[t], active[t] - 1, -dir);
                        Rectangle bounds = new Rectangle(Math.min(x1, x2), y, Math.abs(x2 - x1), arrowY - y + JBUI.scale(5));
                        layout.messages.add(new Message(x1, x2, arrowY, false, 0, th.label(), MessageKind.THROW,
                                false, bounds, th.tooltip(), th.navigation()));
                        includeColumns(fragStack, colX[f], colX[t], Math.max(active[f], active[t]));
                        y = arrowY + ROW_GAP;
                    }
                }
                case DiagramEvent.FragmentStart s -> {
                    y += JBUI.scale(4);
                    int tabW = fm.stringWidth(s.type().keyword()) + JBUI.scale(18);
                    int minW = tabW + fm.stringWidth("[" + s.condition() + "]") + JBUI.scale(24);
                    fragStack.push(new FragFrame(s, y, minW));
                    y += fh + JBUI.scale(10);
                }
                case DiagramEvent.FragmentElse e -> {
                    FragFrame top = fragStack.peek();
                    y += JBUI.scale(4);
                    if (top != null) {
                        top.elses.add(new ElseLine(y, e.condition()));
                    }
                    y += fh + JBUI.scale(8);
                }
                case DiagramEvent.FragmentEnd ignored -> {
                    FragFrame top = fragStack.poll();
                    y += JBUI.scale(6);
                    if (top != null) {
                        int x1 = (top.minX == Integer.MAX_VALUE ? PAD_LEFT : top.minX) - FRAG_PAD;
                        int x2 = (top.maxX == Integer.MIN_VALUE ? PAD_LEFT : top.maxX) + FRAG_PAD;
                        if (x2 - x1 < top.minWidth) {
                            x2 = x1 + top.minWidth;
                        }
                        x1 = Math.max(JBUI.scale(4), x1);
                        layout.fragments.add(new Fragment(new Rectangle(x1, top.startY, x2 - x1, y - top.startY),
                                top.start.type(), top.start.condition(), top.elses));
                        FragFrame parent = fragStack.peek();
                        if (parent != null) {
                            parent.include(x1, x2);
                        }
                        maxRight = Math.max(maxRight, x2);
                    }
                    y += JBUI.scale(8);
                }
                case DiagramEvent.Note note -> {
                    int col = layout.columnIndex.getOrDefault(note.over(), 0);
                    String[] lines = note.text().split("\n");
                    int w = 0;
                    for (String line : lines) {
                        w = Math.max(w, noteFm.stringWidth(line));
                    }
                    w += JBUI.scale(16);
                    int h = lines.length * noteFm.getHeight() + JBUI.scale(10);
                    int nx = colX[col] + ACT_W + JBUI.scale(6) + Math.max(0, active[col] - 1) * ACT_OFF;
                    Rectangle bounds = new Rectangle(nx, y + JBUI.scale(2), w, h);
                    layout.notes.add(new Note(bounds, lines, note.tooltip(), note.navigation()));
                    include(fragStack, nx, nx + w);
                    maxRight = Math.max(maxRight, nx + w);
                    y += h + JBUI.scale(10);
                }
            }
        }
        // 未結束的 activation（截斷時）
        while (!frames.isEmpty()) {
            Frame frame = frames.pop();
            layout.activations.add(new Activation(colX[frame.col()] + frame.level() * ACT_OFF, frame.startY(), y));
        }
        for (Message m : layout.messages) {
            maxRight = Math.max(maxRight, m.bounds().x + m.bounds().width + JBUI.scale(8));
        }
        layout.width = maxRight + PAD_RIGHT;
        layout.height = y + JBUI.scale(24);
        return layout;
    }

    private static void includeColumns(Deque<FragFrame> stack, int cx1, int cx2, int levels) {
        int left = Math.min(cx1, cx2) - ACT_W;
        int right = Math.max(cx1, cx2) + ACT_W + Math.max(0, levels) * ACT_OFF;
        include(stack, left, right);
    }

    private static void include(Deque<FragFrame> stack, int x1, int x2) {
        for (FragFrame f : stack) {
            f.include(x1, x2);
        }
    }

    private static @Nullable Frame popFrame(Deque<Frame> frames, int col) {
        if (frames.isEmpty()) {
            return null;
        }
        if (frames.peek().col() == col) {
            return frames.pop();
        }
        for (Frame f : frames) {
            if (f.col() == col) {
                frames.remove(f);
                return f;
            }
        }
        return null;
    }

    /** activation 邊緣的 x；level < 0 代表無 activation（生命線中心）。 */
    private static int edge(int cx, int level, int side) {
        if (level < 0) {
            return cx;
        }
        int base = cx + level * ACT_OFF;
        return side > 0 ? base + ACT_W / 2 : base - ACT_W / 2;
    }

    // ------------------------------------------------------------------ 命中測試

    @Nullable Hit hitBody(int x, int y) {
        for (Note note : notes) {
            if (note.bounds().contains(x, y)) {
                return note;
            }
        }
        Message best = null;
        for (Message m : messages) {
            if (!m.isReturn() && grow(m.bounds()).contains(x, y)) {
                best = m;
            }
        }
        return best;
    }

    @Nullable Column hitHeader(int x, int y) {
        for (Column c : columns) {
            if (Math.abs(x - c.x()) <= c.boxWidth() / 2 && y >= 0 && y <= headerH) {
                return c;
            }
        }
        return null;
    }

    private static Rectangle grow(Rectangle r) {
        Rectangle g = new Rectangle(r);
        g.grow(JBUI.scale(2), JBUI.scale(3));
        return g;
    }
}
