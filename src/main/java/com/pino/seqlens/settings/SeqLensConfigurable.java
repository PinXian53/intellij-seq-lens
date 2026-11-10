package com.pino.seqlens.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ContextHelpLabel;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;

/**
 * Settings › Tools › SeqLens。
 * 依功能分區塊；詳細說明收在每個選項旁的 ? 按鈕。
 */
public final class SeqLensConfigurable implements Configurable {

    private record AnchorOption(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final AnchorOption[] ANCHORS = {
            new AnchorOption("RIGHT", "Right"), new AnchorOption("BOTTOM", "Bottom"), new AnchorOption("LEFT", "Left")};

    // 顯示
    private ComboBox<AnchorOption> anchor;
    private JSpinner fontSize;
    private JBCheckBox showReturns;
    private JBCheckBox showThrows;
    private JBCheckBox showConstructors;
    // 分析範圍
    private JSpinner maxDepth;
    private JSpinner maxCalls;
    private JBCheckBox skipGettersSetters;
    private JBCheckBox skipPrivate;
    private JBCheckBox includeLibrary;
    private JBCheckBox resolveImpl;
    // 不展開與排除
    private JBTextArea stopPatterns;
    private JBTextField excludes;
    // 資料存取 / 外部 API / 框架
    private JBCheckBox detectJpa;
    private JBCheckBox showSqlNotes;
    private JBCheckBox detectHttp;
    private JBCheckBox collapseMapStruct;

    @Override
    public @Nls String getDisplayName() {
        return "SeqLens";
    }

    @Override
    public @Nullable JComponent createComponent() {
        anchor = new ComboBox<>(ANCHORS);
        fontSize = new JSpinner(new SpinnerNumberModel(0, 0, 40, 1));
        showReturns = new JBCheckBox("Show return arrows");
        showThrows = new JBCheckBox("Show throw");
        showConstructors = new JBCheckBox("Show constructor calls (new)");

        maxDepth = new JSpinner(new SpinnerNumberModel(5, 1, 30, 1));
        maxCalls = new JSpinner(new SpinnerNumberModel(600, 50, 5000, 50));
        skipGettersSetters = new JBCheckBox("Skip getters / setters");
        skipPrivate = new JBCheckBox("Skip private methods");
        includeLibrary = new JBCheckBox("Include library calls");
        resolveImpl = new JBCheckBox("Expand the implementation when an interface has only one");

        stopPatterns = new JBTextArea(5, 40);
        stopPatterns.getEmptyText().setText("e.g. com.foo.AuditService, *Client, LogUtil#*");
        excludes = new JBTextField();

        detectJpa = new JBCheckBox("Detect JPA / SQL and show tables");
        showSqlNotes = new JBCheckBox("Show SQL as notes");
        detectHttp = new JBCheckBox("Detect HTTP clients and show external APIs");
        collapseMapStruct = new JBCheckBox("Don't expand MapStruct mapper implementations");

        JPanel display = section("Display", FormBuilder.createFormBuilder()
                .addLabeledComponent("Tool window position:", anchor)
                .addLabeledComponent("Font size:", withHelp(fontSize,
                        "Font size of the diagram text (pt).<br><b>0</b> follows the IDE font size.<br>"
                                + "Unlike the toolbar zoom, changing the font size re-lays out the diagram; zoom only scales it visually."))
                .addComponent(showReturns)
                .addComponent(withHelp(showThrows,
                        "<code>throw</code> is drawn as a red dashed arrow (marked × at its start) back to the caller, "
                                + "labeled with the exception type and arguments."))
                .addComponent(showConstructors));

        JPanel scope = section("Analysis Scope", FormBuilder.createFormBuilder()
                .addLabeledComponent("Maximum call depth:", withHelp(maxDepth,
                        "How many levels to expand from the starting method.<br>"
                                + "Methods that hit the limit but still contain calls are marked <b>⋯</b> and a warning appears above the diagram. "
                                + "Use 'Regenerate 2 levels deeper' to go deeper for that diagram only."))
                .addLabeledComponent("Maximum calls:", withHelp(maxCalls,
                        "Maximum number of calls drawn in one diagram, to keep it manageable. "
                                + "When exceeded, the diagram is truncated and a warning is shown."))
                .addComponent(withHelp(skipGettersSetters,
                        "Skips simple getters / setters, record accessors and Lombok-generated accessors."))
                .addComponent(skipPrivate)
                .addComponent(withHelp(includeLibrary,
                        "By default only calls into project sources are drawn. When enabled, library method calls are drawn too "
                                + "(their internals are never expanded). 'Excluded packages' still apply."))
                .addComponent(withHelp(resolveImpl,
                        "When calling an interface or abstract method that has exactly one implementation in the project, "
                                + "expand that implementation. With several implementations the diagram stops at the interface.")));

        JPanel stopAndExclude = section("Stop List and Exclusions", FormBuilder.createFormBuilder()
                .addLabeledComponent(labelWithHelp("Stop list:",
                        "Matching classes / methods are <b>still drawn</b>, but not expanded further (marked <b>⊘</b>).<br>"
                                + "One pattern per line, * is a wildcard:<br>"
                                + "• <code>com.foo.AuditService</code>: a class<br>"
                                + "• <code>com.foo.util.*</code>: a whole package<br>"
                                + "• <code>AuditService</code>, <code>*Client</code>: without a dot, matches the simple class name<br>"
                                + "• <code>AuditService#log*</code>, <code>*#toString</code>: methods<br>"
                                + "• <code>User#&lt;init&gt;</code>: constructors<br>"
                                + "An interface name also matches its implementations. Lines starting with # are comments.<br>"
                                + "You can also right-click a message in the diagram → 'Don't Expand…'."),
                        scroll(stopPatterns), true)
                .addLabeledComponent(labelWithHelp("Excluded packages / classes:",
                        "These calls are <b>not drawn at all</b>. Comma-separated; a trailing * matches a prefix, "
                                + "e.g. <code>java.*, org.slf4j.*</code>."),
                        excludes));

        JPanel dataAccess = section("Data Access", FormBuilder.createFormBuilder()
                .addComponent(withHelp(detectJpa,
                        "Draws data access as SELECT / INSERT / UPDATE / DELETE against database tables. Supports:<br>"
                                + "• Spring Data repositories: derived queries, built-in CRUD, <code>@Query</code>, <code>@NamedQuery</code><br>"
                                + "• <code>@DynamicRepository</code> / <code>@DynamicQuery</code> (including <code>[ ]</code> optional fragments)<br>"
                                + "• JPA <code>EntityManager</code>, Hibernate <code>Session</code><br>"
                                + "• <code>JdbcTemplate</code>, <code>NamedParameterJdbcTemplate</code>, <code>JdbcClient</code><br>"
                                + "JPQL entity names are mapped to table names (<code>@Table</code> → <code>@Entity(name)</code> → snake_case)."))
                .addComponent(withHelp(showSqlNotes,
                        "Shows the SQL as a note next to the data access component.<br>"
                                + "SQL starting with <b>≈</b> is derived from the method name for illustration; it is not the exact statement executed.")));

        JPanel http = section("External APIs", FormBuilder.createFormBuilder()
                .addComponent(withHelp(detectHttp,
                        "Draws outgoing HTTP calls to an '«external api»' participant, with arrows labeled like "
                                + "<code>GET /orders/{id}</code>. Supports:<br>"
                                + "• <code>RestTemplate</code><br>"
                                + "• <code>RestClient</code> / <code>WebClient</code> call chains (including <code>baseUrl</code>)<br>"
                                + "• Declarative <code>@HttpExchange</code>, <code>@GetExchange</code>… interfaces<br>"
                                + "• OpenFeign <code>@FeignClient</code><br>"
                                + "The service name comes from the URL host, <code>@Value(\"${...}\")</code> or the Feign client name.")));

        JPanel frameworks = section("Frameworks", FormBuilder.createFormBuilder()
                .addComponent(withHelp(collapseMapStruct,
                        "MapStruct <code>@Mapper</code> calls are drawn as a single call (marked «mapper», tooltip shows "
                                + "<code>User → UserDto</code>) without expanding the generated <code>XxxMapperImpl</code>.")));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        for (JPanel p : new JPanel[]{display, scope, stopAndExclude, dataAccess, http, frameworks}) {
            p.setAlignmentX(0f);
            content.add(p);
        }
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(content, BorderLayout.NORTH);
        reset();
        return wrapper;
    }

    // ------------------------------------------------------------------ 版面輔助

    private static JPanel section(String title, FormBuilder builder) {
        JPanel panel = builder.getPanel();
        panel.setBorder(IdeBorderFactory.createTitledBorder(title, false, JBUI.insetsTop(8)));
        return panel;
    }

    /** 元件右邊加上 ? 說明按鈕。 */
    private static JComponent withHelp(JComponent component, String helpHtml) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(component);
        JComponent help = ContextHelpLabel.create("<html>" + helpHtml + "</html>");
        help.setBorder(JBUI.Borders.emptyLeft(6));
        row.add(help);
        return row;
    }

    private static JComponent labelWithHelp(String text, String helpHtml) {
        return withHelp(new javax.swing.JLabel(text), helpHtml);
    }

    private static JComponent scroll(JBTextArea area) {
        JBScrollPane pane = new JBScrollPane(area);
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        return pane;
    }

    // ------------------------------------------------------------------ 套用 / 還原

    private SeqLensSettings.State fromUi() {
        SeqLensSettings.State current = SeqLensSettings.getInstance().getState();
        SeqLensSettings.State s = current.copy();
        s.toolWindowAnchor = ((AnchorOption) Objects.requireNonNull(anchor.getSelectedItem())).id();
        s.fontSize = (Integer) fontSize.getValue();
        s.showReturnArrows = showReturns.isSelected();
        s.showThrows = showThrows.isSelected();
        s.showConstructors = showConstructors.isSelected();
        s.maxDepth = (Integer) maxDepth.getValue();
        s.maxCalls = (Integer) maxCalls.getValue();
        s.skipGettersSetters = skipGettersSetters.isSelected();
        s.skipPrivateMethods = skipPrivate.isSelected();
        s.includeLibraryCalls = includeLibrary.isSelected();
        s.resolveSingleImplementation = resolveImpl.isSelected();
        s.stopPatterns = stopPatterns.getText();
        s.excludePatterns = excludes.getText();
        s.detectJpa = detectJpa.isSelected();
        s.showSqlNotes = showSqlNotes.isSelected();
        s.detectHttp = detectHttp.isSelected();
        s.collapseMapStruct = collapseMapStruct.isSelected();
        return s;
    }

    @Override
    public boolean isModified() {
        SeqLensSettings.State cur = SeqLensSettings.getInstance().getState();
        SeqLensSettings.State ui = fromUi();
        return !Objects.equals(cur.toolWindowAnchor, ui.toolWindowAnchor)
                || cur.fontSize != ui.fontSize
                || cur.showReturnArrows != ui.showReturnArrows
                || cur.showThrows != ui.showThrows
                || cur.showConstructors != ui.showConstructors
                || cur.maxDepth != ui.maxDepth
                || cur.maxCalls != ui.maxCalls
                || cur.skipGettersSetters != ui.skipGettersSetters
                || cur.skipPrivateMethods != ui.skipPrivateMethods
                || cur.includeLibraryCalls != ui.includeLibraryCalls
                || cur.resolveSingleImplementation != ui.resolveSingleImplementation
                || !Objects.equals(cur.stopPatterns, ui.stopPatterns)
                || !Objects.equals(cur.excludePatterns, ui.excludePatterns)
                || cur.detectJpa != ui.detectJpa
                || cur.showSqlNotes != ui.showSqlNotes
                || cur.detectHttp != ui.detectHttp
                || cur.collapseMapStruct != ui.collapseMapStruct;
    }

    @Override
    public void apply() {
        SeqLensSettings.getInstance().loadState(fromUi());
        ApplicationManager.getApplication().getMessageBus().syncPublisher(SeqLensSettingsListener.TOPIC).settingsChanged();
    }

    @Override
    public void reset() {
        SeqLensSettings.State s = SeqLensSettings.getInstance().getState();
        for (AnchorOption option : ANCHORS) {
            if (option.id().equals(s.toolWindowAnchor)) {
                anchor.setSelectedItem(option);
            }
        }
        fontSize.setValue(s.fontSize);
        showReturns.setSelected(s.showReturnArrows);
        showThrows.setSelected(s.showThrows);
        showConstructors.setSelected(s.showConstructors);
        maxDepth.setValue(s.maxDepth);
        maxCalls.setValue(s.maxCalls);
        skipGettersSetters.setSelected(s.skipGettersSetters);
        skipPrivate.setSelected(s.skipPrivateMethods);
        includeLibrary.setSelected(s.includeLibraryCalls);
        resolveImpl.setSelected(s.resolveSingleImplementation);
        stopPatterns.setText(s.stopPatterns);
        excludes.setText(s.excludePatterns);
        detectJpa.setSelected(s.detectJpa);
        showSqlNotes.setSelected(s.showSqlNotes);
        detectHttp.setSelected(s.detectHttp);
        collapseMapStruct.setSelected(s.collapseMapStruct);
    }
}
