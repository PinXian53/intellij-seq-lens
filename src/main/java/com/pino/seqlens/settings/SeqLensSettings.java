package com.pino.seqlens.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/** SeqLens 的全域設定。 */
@Service(Service.Level.APP)
@State(name = "SeqLensSettings", storages = @Storage("seqlens.xml"))
public final class SeqLensSettings implements PersistentStateComponent<SeqLensSettings.State> {

    public static final class State {
        /** 往下展開呼叫的最大深度。 */
        public int maxDepth = 5;
        /** 最多產生幾個呼叫，避免圖過大。 */
        public int maxCalls = 600;
        public boolean skipGettersSetters = true;
        public boolean skipPrivateMethods = false;
        public boolean includeLibraryCalls = false;
        public boolean showConstructors = true;
        public boolean resolveSingleImplementation = true;
        /** MapStruct mapper 只畫出呼叫，不展開產生的實作。 */
        public boolean collapseMapStruct = true;
        public boolean showReturnArrows = true;
        public boolean showThrows = true;
        public boolean showSqlNotes = true;
        public boolean detectJpa = true;
        public boolean detectHttp = true;
        /** 圖的字體大小（pt），0 = 跟隨 IDE。 */
        public int fontSize = 0;
        /** 工具視窗位置：RIGHT / BOTTOM / LEFT。 */
        public String toolWindowAnchor = "RIGHT";
        /** 是否顯示資源清單（API / 資料表）面板。 */
        public boolean showResourcePanel = false;
        /** 排除的套件/類別樣式，以逗號分隔，支援結尾 *。 */
        public String excludePatterns = "java.*, javax.*, jakarta.*, kotlin.*, org.slf4j.*, org.apache.logging.*, lombok.*";
        /** 不展開清單：命中的 class / method 仍會畫出呼叫，但不再往下追（每行一個樣式）。 */
        public String stopPatterns = "";
        /** 資源清單各區塊的顯示開關。 */
        public boolean resourceShowApis = true;
        public boolean resourceShowTables = true;
        public boolean resourceShowClasses = true;

        public State copy() {
            State s = new State();
            XmlSerializerUtil.copyBean(this, s);
            return s;
        }

        public List<String> excludeList() {
            return Arrays.stream(excludePatterns.split("[,;\\n]"))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .toList();
        }

        public boolean isExcluded(String qualifiedName) {
            if (qualifiedName == null) {
                return false;
            }
            for (String p : excludeList()) {
                if (p.endsWith("*")) {
                    if (qualifiedName.startsWith(p.substring(0, p.length() - 1))) {
                        return true;
                    }
                } else if (qualifiedName.equals(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    private State state = new State();

    public static SeqLensSettings getInstance() {
        return ApplicationManager.getApplication().getService(SeqLensSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
