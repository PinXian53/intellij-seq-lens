package com.pino.seqlens.analysis;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.JavaPsiRecordUtil;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.DiagramEvent.FragmentType;
import com.pino.seqlens.model.MessageKind;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.ParticipantKind;
import com.pino.seqlens.model.SequenceDiagram;
import com.pino.seqlens.settings.SeqLensSettings;
import com.pino.seqlens.sql.SqlOperation;
import com.pino.seqlens.sql.TableAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 從一個 Java 方法開始，靜態走訪 PSI 產生循序圖。
 * 必須在 read action 中執行。
 */
public final class SequenceAnalyzer {

    public static final String ACTOR_ID = "__actor__";

    private static final Set<String> ITERATING_METHODS = Set.of(
            "forEach", "forEachOrdered", "map", "flatMap", "filter", "peek", "anyMatch", "allMatch", "noneMatch",
            "mapToInt", "mapToLong", "mapToObj", "mapToDouble", "removeIf", "replaceAll", "takeWhile", "dropWhile",
            "sorted", "max", "min", "reduce", "collect", "forEachRemaining", "computeIfAbsent", "computeIfPresent", "compute", "merge");

    private static final Set<String> BOUNDARY_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController",
            "jakarta.ws.rs.Path", "javax.ws.rs.Path");
    private static final Set<String> REPOSITORY_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Repository", "org.apache.ibatis.annotations.Mapper");

    private final Project project;
    private final SeqLensSettings.State settings;
    private final ProjectFileIndex fileIndex;
    private final PersistenceDetector persistence;
    private final HttpClientDetector http;
    private final CallPatternMatcher stopList;
    private final Deque<PsiMethod> stack = new ArrayDeque<>();
    private final Map<PsiMethod, PsiMethod> singleImplCache = new HashMap<>();
    private SequenceDiagram diagram;
    private List<DiagramEvent> events;
    private int callCount;
    private boolean stopped;

    /**
     * @param self      目前執行中的參與者
     * @param caller    呼叫目前方法的參與者（throw 的目標）
     */
    private record Ctx(String self, @Nullable PsiClass selfClass, int depth, String caller) {
    }

    public SequenceAnalyzer(@NotNull Project project, @NotNull SeqLensSettings.State settings) {
        this.project = project;
        this.settings = settings;
        this.fileIndex = ProjectFileIndex.getInstance(project);
        StringResolver strings = new StringResolver(project);
        this.persistence = new PersistenceDetector(project, strings);
        this.stopList = CallPatternMatcher.parse(settings.stopPatterns);
        this.http = new HttpClientDetector(strings);
    }

    public @NotNull SequenceDiagram analyze(@NotNull PsiMethod root) {
        PsiClass cls = root.getContainingClass();
        diagram = new SequenceDiagram((cls != null ? cls.getName() + "." : "") + root.getName());
        diagram.setMaxDepth(settings.maxDepth);
        events = diagram.mutableEvents();
        stack.clear();
        callCount = 0;
        stopped = false;

        diagram.addParticipant(new Participant(ACTOR_ID, "Caller", ParticipantKind.ACTOR, null, null));
        String rootId = participantFor(cls, root);
        events.add(new DiagramEvent.Call(ACTOR_ID, rootId, rootLabel(root), MessageKind.CALL,
                tooltip(root, methodSignature(root)), pointer(root)));
        stack.push(root);
        if (root.getBody() != null) {
            processBlock(root.getBody(), new Ctx(rootId, cls, 1, ACTOR_ID));
        }
        stack.pop();
        events.add(new DiagramEvent.Return(rootId, ACTOR_ID, typeLabel(root.getReturnType()), isVisibleReturn(root.getReturnType())));
        diagram.pruneUnusedParticipants();
        return diagram;
    }

    // ================================================================ 結構化走訪（控制流程 → 組合片段）

    private void processBlock(@Nullable PsiCodeBlock block, Ctx ctx) {
        if (block == null) {
            return;
        }
        for (PsiStatement statement : block.getStatements()) {
            if (stopped) {
                return;
            }
            processStatement(statement, ctx);
        }
    }

    private void processStatement(@Nullable PsiStatement statement, Ctx ctx) {
        if (statement == null || stopped) {
            return;
        }
        ProgressManager.checkCanceled();
        switch (statement) {
            case PsiBlockStatement block -> processBlock(block.getCodeBlock(), ctx);
            case PsiIfStatement ifStatement -> processIf(ifStatement, ctx);
            case PsiForStatement forStatement -> {
                processStatement(forStatement.getInitialization(), ctx);
                int start = beginFragment(FragmentType.LOOP, "for " + shorten(textOf(forStatement.getCondition()), 50));
                visitExpression(forStatement.getCondition(), ctx);
                processStatement(forStatement.getBody(), ctx);
                processStatement(forStatement.getUpdate(), ctx);
                endFragment(start);
            }
            case PsiForeachStatementBase foreach -> {
                visitExpression(foreach.getIteratedValue(), ctx);
                String var = foreach instanceof PsiForeachStatement fe ? fe.getIterationParameter().getName() : "item";
                int start = beginFragment(FragmentType.LOOP,
                        "for each " + var + " : " + shorten(textOf(foreach.getIteratedValue()), 40));
                processStatement(foreach.getBody(), ctx);
                endFragment(start);
            }
            case PsiWhileStatement whileStatement -> {
                int start = beginFragment(FragmentType.LOOP, "while " + shorten(textOf(whileStatement.getCondition()), 50));
                visitExpression(whileStatement.getCondition(), ctx);
                processStatement(whileStatement.getBody(), ctx);
                endFragment(start);
            }
            case PsiDoWhileStatement doWhile -> {
                int start = beginFragment(FragmentType.LOOP, "do … while " + shorten(textOf(doWhile.getCondition()), 50));
                processStatement(doWhile.getBody(), ctx);
                visitExpression(doWhile.getCondition(), ctx);
                endFragment(start);
            }
            case PsiTryStatement tryStatement -> processTry(tryStatement, ctx);
            case PsiSwitchStatement switchStatement -> processSwitch(switchStatement, ctx);
            case PsiSynchronizedStatement sync -> {
                visitExpression(sync.getLockExpression(), ctx);
                processBlock(sync.getBody(), ctx);
            }
            case PsiLabeledStatement labeled -> processStatement(labeled.getStatement(), ctx);
            case PsiThrowStatement throwStatement -> processThrow(throwStatement, ctx);
            default -> visitElement(statement, ctx);
        }
    }

    private void processThrow(PsiThrowStatement statement, Ctx ctx) {
        PsiExpression exception = statement.getException();
        // 先處理例外建構參數中的呼叫（例如 new XException(msg(id))），但不畫 «create»
        visitExpression(exception, ctx);
        if (!settings.showThrows || stopped || exception == null) {
            return;
        }
        PsiType type = exception.getType();
        String typeName = type != null ? type.getPresentableText() : shorten(exception.getText(), 40);
        String label = "throw " + typeName;
        if (PsiUtil.skipParenthesizedExprDown(exception) instanceof PsiNewExpression newExpr
                && newExpr.getArgumentList() != null && !newExpr.getArgumentList().isEmpty()) {
            label += "(" + argumentsLabel(newExpr.getArgumentList()) + ")";
        }
        events.add(new DiagramEvent.Throw(ctx.self(), ctx.caller(), label,
                tooltip(statement, statement.getText().replaceAll("\\s+", " ")), pointer(statement)));
    }

    private void processIf(PsiIfStatement ifStatement, Ctx ctx) {
        visitExpression(ifStatement.getCondition(), ctx);
        boolean hasElse = ifStatement.getElseBranch() != null;
        int start = beginFragment(hasElse ? FragmentType.ALT : FragmentType.OPT,
                shorten(textOf(ifStatement.getCondition()), 60));
        processStatement(ifStatement.getThenBranch(), ctx);
        PsiStatement elseBranch = ifStatement.getElseBranch();
        while (elseBranch != null && !stopped) {
            if (elseBranch instanceof PsiIfStatement elseIf) {
                events.add(new DiagramEvent.FragmentElse("else if " + shorten(textOf(elseIf.getCondition()), 55)));
                visitExpression(elseIf.getCondition(), ctx);
                processStatement(elseIf.getThenBranch(), ctx);
                elseBranch = elseIf.getElseBranch();
            } else {
                events.add(new DiagramEvent.FragmentElse("else"));
                processStatement(elseBranch, ctx);
                elseBranch = null;
            }
        }
        endFragment(start);
    }

    private void processTry(PsiTryStatement tryStatement, Ctx ctx) {
        PsiResourceList resources = tryStatement.getResourceList();
        if (resources != null) {
            visitElement(resources, ctx);
        }
        int start = beginFragment(FragmentType.TRY, "try");
        processBlock(tryStatement.getTryBlock(), ctx);
        int handlersStart = events.size();
        PsiCodeBlock[] catchBlocks = tryStatement.getCatchBlocks();
        PsiParameter[] catchParams = tryStatement.getCatchBlockParameters();
        for (int i = 0; i < catchBlocks.length && !stopped; i++) {
            String type = i < catchParams.length ? catchParams[i].getType().getPresentableText() : "Exception";
            events.add(new DiagramEvent.FragmentElse("catch " + type));
            processBlock(catchBlocks[i], ctx);
        }
        if (tryStatement.getFinallyBlock() != null && !stopped) {
            events.add(new DiagramEvent.FragmentElse("finally"));
            processBlock(tryStatement.getFinallyBlock(), ctx);
        }
        if (!hasCallFrom(handlersStart)) {
            // catch / finally 中沒有呼叫：攤平，不畫 try 片段
            truncate(handlersStart);
            events.remove(start);
            return;
        }
        endFragment(start);
    }

    private void processSwitch(PsiSwitchStatement switchStatement, Ctx ctx) {
        visitExpression(switchStatement.getExpression(), ctx);
        PsiCodeBlock body = switchStatement.getBody();
        if (body == null) {
            return;
        }
        String subject = shorten(textOf(switchStatement.getExpression()), 30);
        int start = -1;
        for (PsiStatement statement : body.getStatements()) {
            if (stopped) {
                break;
            }
            if (statement instanceof PsiSwitchLabelStatementBase label) {
                String text = label.isDefaultCase() ? "default"
                        : "case " + shorten(textOf(label.getCaseLabelElementList()), 40);
                if (start < 0) {
                    start = beginFragment(FragmentType.ALT, subject + " → " + text);
                } else {
                    events.add(new DiagramEvent.FragmentElse(text));
                }
                if (label instanceof PsiSwitchLabeledRuleStatement rule) {
                    PsiStatement ruleBody = rule.getBody();
                    processStatement(ruleBody, ctx);
                }
            } else {
                processStatement(statement, ctx);
            }
        }
        if (start >= 0) {
            endFragment(start);
        }
    }

    private int beginFragment(FragmentType type, String condition) {
        int index = events.size();
        events.add(new DiagramEvent.FragmentStart(type, condition));
        return index;
    }

    private void endFragment(int startIndex) {
        if (!hasCallFrom(startIndex)) {
            truncate(startIndex);
        } else {
            events.add(new DiagramEvent.FragmentEnd());
        }
    }

    private boolean hasCallFrom(int index) {
        for (int i = index; i < events.size(); i++) {
            if (events.get(i) instanceof DiagramEvent.Call || events.get(i) instanceof DiagramEvent.Throw) {
                return true;
            }
        }
        return false;
    }

    private void truncate(int size) {
        while (events.size() > size) {
            events.removeLast();
        }
    }

    // ================================================================ 運算式走訪（找出呼叫）

    private void visitExpression(@Nullable PsiExpression expression, Ctx ctx) {
        if (expression != null) {
            visitElement(expression, ctx);
        }
    }

    private void visitElement(@NotNull PsiElement element, Ctx ctx) {
        element.accept(new CallVisitor(ctx));
    }

    /** 以後序走訪（先參數、後呼叫本身）符合 Java 執行順序。 */
    private final class CallVisitor extends JavaRecursiveElementVisitor {
        private final Ctx ctx;

        CallVisitor(Ctx ctx) {
            this.ctx = ctx;
        }

        @Override
        public void visitElement(@NotNull PsiElement element) {
            if (!stopped) {
                super.visitElement(element);
            }
        }

        @Override
        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
            super.visitMethodCallExpression(call);
            if (stopped) {
                return;
            }
            PsiMethod method = call.resolveMethod();
            if (method != null) {
                invoke(call, method, call.getMethodExpression().getQualifierExpression(),
                        argumentsLabel(call.getArgumentList()), ctx);
            }
        }

        @Override
        public void visitNewExpression(@NotNull PsiNewExpression expression) {
            // 只走訪參數，不進入匿名類別的內容
            PsiExpressionList args = expression.getArgumentList();
            if (args != null) {
                args.accept(this);
            }
            PsiArrayInitializerExpression arrayInit = expression.getArrayInitializer();
            if (arrayInit != null) {
                arrayInit.accept(this);
            }
            boolean thrown = PsiUtil.skipParenthesizedExprUp(expression.getParent()) instanceof PsiThrowStatement;
            if (!stopped && settings.showConstructors && !(thrown && settings.showThrows)
                    && expression.getArrayDimensions().length == 0 && arrayInit == null) {
                handleNew(expression, ctx);
            }
        }

        @Override
        public void visitMethodReferenceExpression(@NotNull PsiMethodReferenceExpression expression) {
            super.visitMethodReferenceExpression(expression);
            if (stopped) {
                return;
            }
            if (expression.resolve() instanceof PsiMethod method) {
                PsiElement qualifier = expression.getQualifier();
                PsiExpression qualifierExpr = qualifier instanceof PsiExpression e ? e : null;
                int start = beginIteratingFragment(expression);
                invoke(expression, method, qualifierExpr, "", ctx);
                if (start >= 0) {
                    endFragment(start);
                }
            }
        }

        @Override
        public void visitLambdaExpression(@NotNull PsiLambdaExpression lambda) {
            int start = beginIteratingFragment(lambda);
            PsiElement body = lambda.getBody();
            if (body instanceof PsiCodeBlock block) {
                processBlock(block, ctx);
            } else if (body != null) {
                body.accept(this);
            }
            if (start >= 0) {
                endFragment(start);
            }
        }

        @Override
        public void visitClass(@NotNull PsiClass aClass) {
            // 不進入區域類別 / 匿名類別
        }
    }

    /** 傳給 forEach / map 等方法的 lambda 視為迴圈。 */
    private int beginIteratingFragment(PsiExpression functional) {
        PsiElement parent = PsiUtil.skipParenthesizedExprUp(functional.getParent());
        if (parent instanceof PsiExpressionList list && list.getParent() instanceof PsiMethodCallExpression call) {
            String name = call.getMethodExpression().getReferenceName();
            if (name != null && ITERATING_METHODS.contains(name)) {
                return beginFragment(FragmentType.LOOP, name);
            }
        }
        return -1;
    }

    // ================================================================ 呼叫處理

    private void invoke(PsiElement site, PsiMethod method, @Nullable PsiExpression qualifier, String argsLabel, Ctx ctx) {
        if (settings.detectHttp && site instanceof PsiMethodCallExpression call) {
            if (http.isInnerFluentCall(call, method)) {
                return;
            }
            HttpCall httpCall = http.detect(call, method);
            if (httpCall != null) {
                emitHttp(call, method, httpCall, ctx);
                return;
            }
        }
        if (settings.detectJpa && site instanceof PsiMethodCallExpression call) {
            DataAccess access = persistence.detect(call, method);
            if (access != null) {
                emitDataAccess(call, method, access, argsLabel, ctx);
                return;
            }
        }
        PsiClass declaring = method.getContainingClass();
        if (declaring == null) {
            return;
        }
        boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);
        PsiClass target;
        PsiMethod body = method;
        if (isStatic) {
            target = declaring;
        } else if (qualifier == null || qualifier instanceof PsiThisExpression) {
            target = ctx.selfClass() != null && InheritanceUtil.isInheritorOrSelf(ctx.selfClass(), declaring, true)
                    ? ctx.selfClass() : declaring;
        } else if (qualifier instanceof PsiSuperExpression) {
            target = ctx.selfClass() != null ? ctx.selfClass() : declaring;
        } else {
            PsiClass qualifierClass = PsiUtil.resolveClassInClassTypeOnly(qualifier.getType());
            target = qualifierClass != null && !(qualifierClass instanceof PsiTypeParameter) ? qualifierClass : declaring;
        }
        // MapStruct mapper：只畫出呼叫 mapper，不展開（自動產生的）實作細節
        if (settings.collapseMapStruct && !isStatic) {
            PsiClass mapper = MapStruct.mapperOf(target);
            if (mapper == null) {
                mapper = MapStruct.mapperOf(declaring);
            }
            if (mapper != null) {
                emitMapperCall(site, method, mapper, argsLabel, ctx);
                return;
            }
        }
        // 虛擬分派：目標類別若覆寫了此方法，展開覆寫的版本
        if (!isStatic && !(qualifier instanceof PsiSuperExpression) && target != declaring) {
            PsiMethod override = target.findMethodBySignature(method, true);
            if (override != null) {
                body = override;
            }
        }
        // 介面 / 抽象方法只有一個實作時展開實作
        if (!isStatic && body.getBody() == null && settings.resolveSingleImplementation
                && (target.isInterface() || target.hasModifierProperty(PsiModifier.ABSTRACT))) {
            PsiMethod impl = findSingleImplementation(body);
            if (impl != null && impl.getContainingClass() != null) {
                body = impl;
                target = impl.getContainingClass();
            }
        }
        if (!shouldInclude(method, body, target)) {
            return;
        }
        String to = participantFor(target, null);
        if (!countCall()) {
            return;
        }
        String label = method.getName() + "(" + argsLabel + ")";
        DiagramEvent.Expansion expansion = expansionOf(method, body, target, ctx);
        events.add(new DiagramEvent.Call(ctx.self(), to, label, MessageKind.CALL,
                tooltip(site, methodSignature(body) + expansionNote(expansion)), pointer(site),
                expansion, targetKey(target, body)));
        if (expansion == DiagramEvent.Expansion.EXPANDED) {
            descend(body, to, target, ctx);
        }
        PsiType type = siteType(site, method);
        events.add(new DiagramEvent.Return(to, ctx.self(), typeLabel(type), isVisibleReturn(type)));
    }

    private void emitMapperCall(PsiElement site, PsiMethod method, PsiClass mapper, String argsLabel, Ctx ctx) {
        if (settings.isExcluded(mapper.getQualifiedName()) || !countCall()) {
            return;
        }
        String to = participantFor(mapper, null);
        String direction = MapStruct.direction(method);
        String detail = methodSignature(method) + (direction != null ? "\nMapStruct: " + direction : "")
                + "\nImplementation generated by MapStruct; not expanded";
        events.add(new DiagramEvent.Call(ctx.self(), to, method.getName() + "(" + argsLabel + ")", MessageKind.CALL,
                tooltip(site, detail), pointer(site), DiagramEvent.Expansion.EXPANDED, targetKey(mapper, method)));
        PsiType type = siteType(site, method);
        events.add(new DiagramEvent.Return(to, ctx.self(), typeLabel(type), isVisibleReturn(type)));
    }

    /** 呼叫處推導出的型別（已代入泛型），例如 save(user) 回傳 User 而不是 S。 */
    private static @Nullable PsiType siteType(PsiElement site, PsiMethod method) {
        if (site instanceof PsiMethodCallExpression call) {
            PsiType type = call.getType();
            if (type != null) {
                return type;
            }
        }
        return method.getReturnType();
    }

    private void handleNew(PsiNewExpression expression, Ctx ctx) {
        PsiJavaCodeReferenceElement ref = expression.getClassOrAnonymousClassReference();
        if (ref == null || !(ref.resolve() instanceof PsiClass cls)) {
            return;
        }
        if (!isProjectElement(cls) && !settings.includeLibraryCalls) {
            return;
        }
        if (settings.isExcluded(cls.getQualifiedName())) {
            return;
        }
        PsiMethod constructor = expression.resolveConstructor();
        String to = participantFor(cls, null);
        if (!countCall()) {
            return;
        }
        String args = expression.getArgumentList() != null ? argumentsLabel(expression.getArgumentList()) : "";
        // 沒有明確寫建構子時仍要比對不展開清單（樣式 Xxx#<init> 或整個類別）
        DiagramEvent.Expansion expansion = constructor != null
                ? expansionOf(constructor, constructor, cls, ctx)
                : stopList.matches(cls.getQualifiedName(), cls.getName(), "<init>")
                ? DiagramEvent.Expansion.STOPPED : DiagramEvent.Expansion.EXPANDED;
        events.add(new DiagramEvent.Call(ctx.self(), to, "«create» " + cls.getName() + "(" + args + ")",
                MessageKind.CREATE, tooltip(expression, "new " + cls.getQualifiedName() + expansionNote(expansion)),
                pointer(expression), expansion, (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName()) + "#<init>"));
        if (constructor != null && expansion == DiagramEvent.Expansion.EXPANDED) {
            descend(constructor, to, cls, ctx);
        }
        events.add(new DiagramEvent.Return(to, ctx.self(), "", false));
    }

    /**
     * 判斷呼叫是否往下展開：命中不展開清單 → STOPPED；有可展開的內容但已達最大深度 → DEPTH_LIMIT。
     *
     * @param called 呼叫處解析到的方法（可能是介面方法）
     * @param body   實際要展開的方法（實作）
     */
    private DiagramEvent.Expansion expansionOf(PsiMethod called, PsiMethod body, PsiClass target, Ctx ctx) {
        if (!stopList.isEmpty()) {
            String name = body.isConstructor() ? "<init>" : body.getName();
            PsiClass declared = called.getContainingClass();
            PsiClass bodyClass = body.getContainingClass();
            for (PsiClass cls : new PsiClass[]{target, declared, bodyClass}) {
                if (cls != null && stopList.matches(cls.getQualifiedName(), cls.getName(), name)) {
                    return DiagramEvent.Expansion.STOPPED;
                }
            }
        }
        if (ctx.depth() >= settings.maxDepth && body.getBody() != null && !stack.contains(body)
                && isProjectElement(body)
                && PsiTreeUtil.findChildOfAnyType(body.getBody(), PsiCallExpression.class, PsiMethodReferenceExpression.class) != null) {
            return DiagramEvent.Expansion.DEPTH_LIMIT;
        }
        return DiagramEvent.Expansion.EXPANDED;
    }

    private String expansionNote(DiagramEvent.Expansion expansion) {
        return switch (expansion) {
            case STOPPED -> "\n⊘ Matches the stop list; not expanded";
            case DEPTH_LIMIT -> "\n⋯ Maximum depth " + settings.maxDepth + " reached; inner calls are not shown";
            case EXPANDED -> "";
        };
    }

    private static String targetKey(PsiClass target, PsiMethod body) {
        String cls = target.getQualifiedName() != null ? target.getQualifiedName() : target.getName();
        return cls + "#" + body.getName();
    }

    private void descend(PsiMethod body, String to, PsiClass target, Ctx ctx) {
        if (ctx.depth() >= settings.maxDepth || body.getBody() == null || stack.contains(body)
                || !isProjectElement(body)) {
            return;
        }
        stack.push(body);
        try {
            processBlock(body.getBody(), new Ctx(to, target, ctx.depth() + 1, ctx.self()));
        } finally {
            stack.pop();
        }
    }

    private boolean countCall() {
        if (++callCount > settings.maxCalls) {
            stopped = true;
            diagram.setTruncated(true);
            return false;
        }
        return true;
    }

    private void emitDataAccess(PsiMethodCallExpression call, PsiMethod method, DataAccess access, String argsLabel, Ctx ctx) {
        if (!countCall()) {
            return;
        }
        PsiClass component = access.componentClass();
        String componentId = participantFor(component, null);
        String label = method.getName() + "(" + argsLabel + ")";
        String sql = access.info().sql();
        events.add(new DiagramEvent.Call(ctx.self(), componentId, label, MessageKind.CALL,
                tooltip(call, sql != null ? sql : methodSignature(method)), pointer(call)));

        if (settings.showSqlNotes && sql != null && !sql.isBlank()) {
            String note = (access.derived() ? "≈ " : "") + formatSql(sql);
            PsiElement noteTarget = access.sqlSource() != null ? access.sqlSource() : call;
            events.add(new DiagramEvent.Note(componentId, note, sql, pointer(noteTarget)));
        }
        for (TableAccess table : access.info().accesses()) {
            PsiClass entity = access.entityByTable().get(table.table().toLowerCase(Locale.ROOT));
            String tableId = tableParticipant(table.table(), entity);
            String opLabel = table.operation() == SqlOperation.UPSERT ? "INSERT / UPDATE" : table.operation().name();
            PsiElement nav = access.sqlSource() != null ? access.sqlSource() : call;
            events.add(new DiagramEvent.Call(componentId, tableId, opLabel, MessageKind.SQL,
                    sql != null ? sql : opLabel + " " + table.table(), pointer(nav)));
            boolean rows = table.operation().returnsRows();
            events.add(new DiagramEvent.Return(tableId, componentId, rows ? "rows" : "",
                    rows && settings.showReturnArrows));
        }
        PsiType type = siteType(call, method);
        events.add(new DiagramEvent.Return(componentId, ctx.self(), typeLabel(type), isVisibleReturn(type)));
    }

    private void emitHttp(PsiMethodCallExpression call, PsiMethod method, HttpCall httpCall, Ctx ctx) {
        if (!countCall()) {
            return;
        }
        String id = "http:" + httpCall.host().toLowerCase(Locale.ROOT);
        String tip = "External API: " + httpCall.host() + "\nvia " + httpCall.client();
        diagram.addParticipant(new Participant(id, httpCall.host(), ParticipantKind.EXTERNAL, tip,
                pointer(httpCall.target())));
        String label = httpCall.method() + " " + shorten(httpCall.path(), 60);
        String detail = httpCall.method() + " " + httpCall.url() + "\n" + httpCall.client() + " · "
                + method.getName() + "(" + argumentsLabel(call.getArgumentList()) + ")";
        events.add(new DiagramEvent.Call(ctx.self(), id, label, MessageKind.HTTP, tooltip(call, detail), pointer(call)));
        String response = httpCall.responseType();
        events.add(new DiagramEvent.Return(id, ctx.self(), response != null ? response : "",
                settings.showReturnArrows && response != null && !response.equals("Void")));
    }

    private String tableParticipant(String table, @Nullable PsiClass entity) {
        String id = "table:" + table.toLowerCase(Locale.ROOT);
        String tip = "Table " + table + (entity != null ? "\nEntity: " + entity.getQualifiedName() : "");
        diagram.addParticipant(new Participant(id, table, ParticipantKind.TABLE, tip,
                entity != null ? pointer(entity) : null));
        return id;
    }

    // ================================================================ 篩選

    private boolean shouldInclude(PsiMethod method, PsiMethod body, PsiClass target) {
        if (settings.isExcluded(target.getQualifiedName())) {
            return false;
        }
        PsiClass declaring = method.getContainingClass();
        if (declaring != null && settings.isExcluded(declaring.getQualifiedName())
                && !isProjectElement(body)) {
            return false;
        }
        if (!settings.includeLibraryCalls && !isProjectElement(target) && !isProjectElement(body)) {
            return false;
        }
        if (settings.skipPrivateMethods && method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return false;
        }
        if (settings.skipGettersSetters && isAccessor(body)) {
            return false;
        }
        return true;
    }

    private static boolean isAccessor(PsiMethod method) {
        if (JavaPsiRecordUtil.getRecordComponentForAccessor(method) != null) {
            return true;
        }
        if (method.getBody() != null) {
            // 必須檢查方法內容：isSimplePropertyGetter 只看名稱與簽章，會把 getApiV1Orders() 這類業務方法也當成 getter
            return com.intellij.psi.util.PropertyUtil.isSimpleGetter(method) || com.intellij.psi.util.PropertyUtil.isSimpleSetter(method);
        }
        // 沒有本體：只有類別中真的有對應欄位時才算（Lombok 產生的 getter / setter）。
        // 介面 / 抽象方法即使叫 getXxx() 也是業務方法，例如 getApiV1Orders()。
        PsiClass cls = method.getContainingClass();
        if (cls == null || cls.isInterface() || method.hasModifierProperty(PsiModifier.ABSTRACT)) {
            return false;
        }
        String property = PropertyUtilBase.getPropertyName(method);
        int params = method.getParameterList().getParametersCount();
        String name = method.getName();
        boolean accessorShape = params == 0 && (name.startsWith("get") || name.startsWith("is"))
                || params == 1 && name.startsWith("set");
        return accessorShape && property != null && cls.findFieldByName(property, true) != null;
    }

    private boolean isProjectElement(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        VirtualFile file = PsiUtilCore.getVirtualFile(element);
        return file != null && fileIndex.isInSourceContent(file);
    }

    /**
     * 找出介面 / 抽象方法唯一的實作：
     * 1. 專案中只有一個實作 → 用它
     * 2. 有多個時排除測試程式中的實作（測試用的假物件），只剩一個正式實作 → 用它
     * 3. 正式實作中只有一個標了 Spring {@code @Primary} → 用它
     */
    private @Nullable PsiMethod findSingleImplementation(PsiMethod method) {
        if (singleImplCache.containsKey(method)) {
            return singleImplCache.get(method);
        }
        List<PsiMethod> all = new java.util.ArrayList<>();
        OverridingMethodsSearch.search(method, GlobalSearchScope.projectScope(project), true).forEach(candidate -> {
            if (candidate.getBody() != null && !candidate.hasModifierProperty(PsiModifier.ABSTRACT)
                    && candidate.getContainingClass() != null
                    && !(candidate.getContainingClass() instanceof PsiAnonymousClass)) {
                all.add(candidate);
            }
            return all.size() < 50;
        });
        PsiMethod result = null;
        if (all.size() == 1) {
            result = all.getFirst();
        } else if (all.size() > 1) {
            List<PsiMethod> production = all.stream().filter(m -> !isTestElement(m)).toList();
            if (production.size() == 1) {
                result = production.getFirst();
            } else if (production.size() > 1) {
                List<PsiMethod> primary = production.stream()
                        .filter(m -> m.getContainingClass().hasAnnotation("org.springframework.context.annotation.Primary"))
                        .toList();
                if (primary.size() == 1) {
                    result = primary.getFirst();
                }
            }
        }
        singleImplCache.put(method, result);
        return result;
    }

    private boolean isTestElement(PsiElement element) {
        VirtualFile file = PsiUtilCore.getVirtualFile(element);
        return file != null && fileIndex.isInTestSourceContent(file);
    }

    // ================================================================ 參與者

    private String participantFor(@Nullable PsiClass cls, @Nullable PsiMethod fallbackOwner) {
        if (cls == null) {
            String name = fallbackOwner != null ? fallbackOwner.getName() : "?";
            return diagram.addParticipant(new Participant(name, name, ParticipantKind.CLASS, null, null)).id();
        }
        String fqn = cls.getQualifiedName();
        String name = cls.getName() != null ? cls.getName() : "anonymous";
        String id = fqn != null ? fqn : name + "@" + Integer.toHexString(System.identityHashCode(cls));
        if (diagram.hasParticipant(id)) {
            return id;
        }
        ParticipantKind kind = kindOf(cls);
        String tooltip = (kind == ParticipantKind.MAPPER ? "MapStruct mapper " : cls.isInterface() ? "interface " : "class ")
                + (fqn != null ? fqn : name);
        diagram.addParticipant(new Participant(id, name, kind, tooltip, pointer(cls)));
        return id;
    }

    private static ParticipantKind kindOf(PsiClass cls) {
        if (MapStruct.mapperOf(cls) != null) {
            return ParticipantKind.MAPPER;
        }
        for (String a : BOUNDARY_ANNOTATIONS) {
            if (cls.hasAnnotation(a)) {
                return ParticipantKind.BOUNDARY;
            }
        }
        for (String a : REPOSITORY_ANNOTATIONS) {
            if (cls.hasAnnotation(a)) {
                return ParticipantKind.REPOSITORY;
            }
        }
        if (PersistenceDetector.findAnnotationBySimpleName(cls, "DynamicRepository") != null) {
            return ParticipantKind.REPOSITORY;
        }
        if (InheritanceUtil.isInheritor(cls, PersistenceDetector.SPRING_DATA_REPOSITORY)) {
            return ParticipantKind.REPOSITORY;
        }
        String fqn = cls.getQualifiedName();
        if (fqn != null && (fqn.endsWith("EntityManager") || fqn.startsWith("org.hibernate.")
                || fqn.startsWith("org.springframework.jdbc."))) {
            return ParticipantKind.REPOSITORY;
        }
        return ParticipantKind.CLASS;
    }

    // ================================================================ 文字

    private static String rootLabel(PsiMethod method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    private static String argumentsLabel(@NotNull PsiExpressionList list) {
        PsiExpression[] args = list.getExpressions();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shorten(args[i].getText(), 20));
        }
        return shorten(sb.toString(), 48);
    }

    private static String typeLabel(@Nullable PsiType type) {
        if (type == null || PsiTypes.voidType().equals(type)) {
            return "";
        }
        return type.getPresentableText();
    }

    private boolean isVisibleReturn(@Nullable PsiType type) {
        return settings.showReturnArrows && type != null && !PsiTypes.voidType().equals(type);
    }

    private static String methodSignature(PsiMethod method) {
        PsiClass cls = method.getContainingClass();
        StringBuilder sb = new StringBuilder();
        if (cls != null) {
            sb.append(cls.getName()).append('#');
        }
        sb.append(method.getName()).append('(');
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getType().getPresentableText());
        }
        sb.append(')');
        PsiType type = method.getReturnType();
        if (type != null) {
            sb.append(": ").append(type.getPresentableText());
        }
        return sb.toString();
    }

    private String tooltip(PsiElement element, String detail) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return detail;
        }
        String location = file.getName();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document != null && element.getTextRange() != null) {
            location += ":" + (document.getLineNumber(element.getTextRange().getStartOffset()) + 1);
        }
        return detail + "\n" + location;
    }

    private static String textOf(@Nullable PsiElement element) {
        return element == null ? "" : element.getText().replaceAll("\\s+", " ").strip();
    }

    static String shorten(String text, int max) {
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** 把 SQL 斷行以便以附註顯示。 */
    static String formatSql(String sql) {
        // 以 -- 開頭的行（例如 @DynamicQuery 的說明）原樣保留在最後
        StringBuilder body = new StringBuilder();
        List<String> comments = new java.util.ArrayList<>();
        for (String line : sql.split("\n")) {
            if (line.strip().startsWith("--")) {
                comments.add(line.strip());
            } else {
                body.append(line).append(' ');
            }
        }
        String s = body.toString().replaceAll("\\s+", " ").strip();
        // @DynamicQuery 的選填片段 [ ... ] 各自一行（[ 後面接空白才算，避免影響 [dbo].[table] 這類識別字）
        s = s.replaceAll("\\s*\\[(?=\\s)", "\n[").replaceAll("(?<=\\s)]", "]");
        s = s.replaceAll("(?i)(?<!\\[)\\s+(FROM|WHERE|LEFT JOIN|RIGHT JOIN|INNER JOIN|JOIN|GROUP BY|ORDER BY|HAVING|SET|VALUES|LIMIT|UNION)\\b", "\n$1");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            for (String wrapped : StringUtil.splitByLines(wrap(line.strip(), 70))) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(wrapped);
            }
        }
        for (String comment : comments) {
            out.append('\n').append(comment);
        }
        return out.toString();
    }

    private static String wrap(String line, int width) {
        if (line.length() <= width) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (String word : line.split(" ")) {
            if (col > 0 && col + word.length() + 1 > width) {
                sb.append("\n  ");
                col = 2;
            } else if (col > 0) {
                sb.append(' ');
                col++;
            }
            sb.append(word);
            col += word.length();
        }
        return sb.toString();
    }

    private static @Nullable SmartPsiElementPointer<PsiElement> pointer(@Nullable PsiElement element) {
        if (element == null || !element.isValid()) {
            return null;
        }
        return SmartPointerManager.createPointer(element);
    }
}
