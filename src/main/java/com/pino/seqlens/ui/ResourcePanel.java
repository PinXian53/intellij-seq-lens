package com.pino.seqlens.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.ResourceSummary;
import com.pino.seqlens.settings.SeqLensSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 循序圖旁的「資源清單」：用到的外部 API、資料表、類別 / 方法，點擊跳到程式碼。
 * 上方的開關可分別顯示 / 隱藏各區塊（設定會記住）。
 */
final class ResourcePanel extends JPanel {

    private enum Kind { SECTION, API, TABLE, CLASS, OPERATION, USAGE }

    /** 樹節點內容。 */
    private record Item(Kind kind, String text, String detail, Icon icon,
                        @Nullable ResourceSummary.Usage usage, @Nullable Participant participant) {
    }

    private final Project project;
    private final Tree tree = new Tree();
    private final JBLabel header = new JBLabel();
    private ResourceSummary summary;

    ResourcePanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getEmptyText().setText("Nothing to show");
        tree.setCellRenderer(new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded,
                                              boolean leaf, int row, boolean hasFocus) {
                if (!(value instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof Item item)) {
                    return;
                }
                setIcon(item.icon());
                SimpleTextAttributes main = switch (item.kind()) {
                    case SECTION, API, TABLE, CLASS -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;
                    case USAGE -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES;
                    default -> SimpleTextAttributes.REGULAR_ATTRIBUTES;
                };
                append(item.text(), main);
                if (!item.detail().isEmpty()) {
                    append("  " + item.detail(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null && (e.getClickCount() == 2 || isUsage(path))) {
                    navigate(path);
                }
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && tree.getSelectionPath() != null) {
                    navigate(tree.getSelectionPath());
                }
            }
        });

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new SectionToggle("External APIs", AllIcons.General.Web,
                s -> s.resourceShowApis, (s, v) -> s.resourceShowApis = v));
        group.add(new SectionToggle("Tables", AllIcons.Nodes.DataTables,
                s -> s.resourceShowTables, (s, v) -> s.resourceShowTables = v));
        group.add(new SectionToggle("Classes / Methods", AllIcons.Nodes.Class,
                s -> s.resourceShowClasses, (s, v) -> s.resourceShowClasses = v));
        group.addSeparator();
        group.add(new DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                for (int i = 0; i < tree.getRowCount(); i++) {
                    tree.expandRow(i);
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                for (int i = tree.getRowCount() - 1; i >= 0; i--) {
                    tree.collapseRow(i);
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("SeqLensResources", group, true);
        toolbar.setTargetComponent(this);

        header.setBorder(JBUI.Borders.empty(4, 8, 0, 8));
        header.setForeground(UIUtil.getContextHelpForeground());
        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(toolbar.getComponent(), BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);
    }

    void setSummary(@NotNull ResourceSummary summary) {
        this.summary = summary;
        rebuild();
    }

    /** 依目前的區塊開關重建樹。 */
    void rebuild() {
        if (summary == null) {
            return;
        }
        SeqLensSettings.State s = SeqLensSettings.getInstance().getState();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        int endpoints = summary.apis().values().stream().mapToInt(Map::size).sum();
        int methods = summary.classes().values().stream().mapToInt(Map::size).sum();
        if (s.resourceShowApis && !summary.apis().isEmpty()) {
            root.add(section("External APIs", plural(summary.apis().size(), "service") + " · " + plural(endpoints, "endpoint"),
                    AllIcons.General.Web, summary.apis(), Kind.API, AllIcons.Nodes.Interface));
        }
        if (s.resourceShowTables && !summary.tables().isEmpty()) {
            root.add(section("Tables", plural(summary.tables().size(), "table"), AllIcons.Nodes.DataTables,
                    summary.tables(), Kind.TABLE, AllIcons.Nodes.DataTables));
        }
        if (s.resourceShowClasses && !summary.classes().isEmpty()) {
            root.add(section("Classes / Methods", plural(summary.classes().size(), "class") + " · " + plural(methods, "method"),
                    AllIcons.Nodes.Class, summary.classes(), Kind.CLASS, AllIcons.Nodes.Class));
        }
        header.setText(plural(summary.apis().size(), "external service") + " · " + plural(summary.tables().size(), "table")
                + " · " + plural(summary.classes().size(), "class"));
        tree.setModel(new DefaultTreeModel(root));
        // 預設展開到第二層（服務 / 表 / 類別），API 與資料表再展開到操作層
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            boolean isClassSection = path.getPathCount() >= 2 && path.getPathComponent(1) instanceof DefaultMutableTreeNode n
                    && n.getUserObject() instanceof Item item && item.text().startsWith("Classes");
            if (path.getPathCount() <= (isClassSection ? 2 : 3)) {
                tree.expandRow(i);
            }
        }
    }

    private DefaultMutableTreeNode section(String title, String detail, Icon icon,
                                           Map<String, Map<String, List<ResourceSummary.Usage>>> data,
                                           Kind kind, Icon itemIcon) {
        DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Item(Kind.SECTION, title, detail, icon, null, null));
        data.forEach((name, operations) -> {
            String ops = switch (kind) {
                case TABLE -> String.join(" · ", operations.keySet());
                case API -> plural(operations.size(), "endpoint");
                default -> plural(operations.size(), "method");
            };
            Participant participant = kind == Kind.CLASS ? summary.classParticipant(name) : null;
            DefaultMutableTreeNode resource = new DefaultMutableTreeNode(new Item(kind, name, ops, itemIcon, null, participant));
            operations.forEach((operation, usages) -> {
                ResourceSummary.Usage single = usages.size() == 1 ? usages.getFirst() : null;
                String detail2 = (usages.size() > 1 ? "×" + usages.size() : "") + expansionMark(usages);
                Icon opIcon = switch (kind) {
                    case TABLE -> operation.startsWith("SELECT") ? AllIcons.Actions.Find : AllIcons.Actions.Edit;
                    case CLASS -> AllIcons.Nodes.Method;
                    default -> AllIcons.Actions.Execute;
                };
                DefaultMutableTreeNode op = new DefaultMutableTreeNode(new Item(Kind.OPERATION, operation, detail2.strip(),
                        opIcon, single, null));
                for (ResourceSummary.Usage usage : usages) {
                    String text = kind == Kind.TABLE ? usage.via() : usage.caller();
                    op.add(new DefaultMutableTreeNode(new Item(Kind.USAGE, text, usage.location(),
                            AllIcons.Actions.Forward, usage, null)));
                }
                resource.add(op);
            });
            section.add(resource);
        });
        return section;
    }

    static String plural(int count, String noun) {
        String word = count == 1 ? noun : noun.endsWith("s") ? noun + "es" : noun + "s";
        return count + " " + word;
    }

    private static String expansionMark(List<ResourceSummary.Usage> usages) {
        if (usages.stream().anyMatch(u -> u.expansion() == DiagramEvent.Expansion.DEPTH_LIMIT)) {
            return "  ⋯ not expanded (max depth reached)";
        }
        if (usages.stream().anyMatch(u -> u.expansion() == DiagramEvent.Expansion.STOPPED)) {
            return "  ⊘ not expanded (stop list)";
        }
        return "";
    }

    private static boolean isUsage(TreePath path) {
        return path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof Item item && item.kind() == Kind.USAGE;
    }

    private void navigate(TreePath path) {
        if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof Item item)) {
            return;
        }
        if (item.usage() != null) {
            Navigation.navigate(project, item.usage().navigation());
        } else if (item.participant() != null) {
            Navigation.navigate(project, item.participant().navigation());
        }
    }

    /** 區塊顯示開關。 */
    private final class SectionToggle extends DumbAwareToggleAction {
        private final Predicate<SeqLensSettings.State> getter;
        private final java.util.function.BiConsumer<SeqLensSettings.State, Boolean> setter;

        SectionToggle(String text, Icon icon, Predicate<SeqLensSettings.State> getter,
                      java.util.function.BiConsumer<SeqLensSettings.State, Boolean> setter) {
            super("Show " + text, "Show or hide the '" + text + "' section", icon);
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return getter.test(SeqLensSettings.getInstance().getState());
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            setter.accept(SeqLensSettings.getInstance().getState(), state);
            rebuild();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
