package com.pino.seqlens.analysis;

import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiClass;
import com.intellij.testFramework.LightProjectDescriptor;
import org.jetbrains.annotations.NotNull;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.pino.seqlens.export.PlantUmlExporter;
import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.MessageKind;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.ParticipantKind;
import com.pino.seqlens.model.ResourceSummary;
import com.pino.seqlens.model.SequenceDiagram;
import com.pino.seqlens.settings.SeqLensSettings;

import java.util.List;

/** 以真實 PSI 驗證分析器：呼叫展開、介面單一實作、控制流程片段、Spring Data / JPA / JDBC 偵測。 */
public class SequenceAnalyzerTest extends LightJavaCodeInsightFixtureTestCase {

    public static final String SAMPLE_CLASS = "demo.UserService";

    /** 使用執行測試的 JDK，讓 java.lang.* 與 varargs 能正確解析。 */
    private static final LightProjectDescriptor DESCRIPTOR = new ProjectDescriptor(LanguageLevel.JDK_21) {
        @Override
        public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk("TestJdk", System.getProperty("java.home"), false);
        }
    };

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // --- 函式庫 stub ---
        myFixture.addClass("package jakarta.persistence; public @interface Entity { String name() default \"\"; }");
        myFixture.addClass("package jakarta.persistence; public @interface Table { String name() default \"\"; String schema() default \"\"; }");
        myFixture.addClass("package jakarta.persistence; public @interface Column { String name() default \"\"; }");
        myFixture.addClass("package jakarta.persistence; public interface EntityManager { void persist(Object o); <T> T find(Class<T> c, Object id); Object createQuery(String q); Object createNativeQuery(String q); }");
        myFixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.repository; public interface CrudRepository<T, ID> extends Repository<T, ID> { <S extends T> S save(S e); T findById(ID id); void deleteById(ID id); }");
        myFixture.addClass("package org.springframework.data.jpa.repository; public interface JpaRepository<T, ID> extends org.springframework.data.repository.CrudRepository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.jpa.repository; public @interface Query { String value(); boolean nativeQuery() default false; }");
        myFixture.addClass("package org.springframework.jdbc.core; public class JdbcTemplate { public int update(String sql, Object... args) { return 0; } }");

        // --- Spring HTTP client stub ---
        myFixture.addClass("package org.springframework.beans.factory.annotation; public @interface Value { String value(); }");
        myFixture.addClass("package org.springframework.http; public enum HttpMethod { GET, POST, PUT, PATCH, DELETE }");
        myFixture.addClass("""
                package org.springframework.web.client;
                public interface RestOperations {
                    <T> T getForObject(String url, Class<T> type, Object... vars);
                    <T> T exchange(String url, org.springframework.http.HttpMethod m, Object entity, Class<T> type, Object... vars);
                }
                """);
        myFixture.addClass("""
                package org.springframework.web.client;
                public class RestTemplate implements RestOperations {
                    public <T> T getForObject(String url, Class<T> type, Object... vars) { return null; }
                    public <T> T exchange(String url, org.springframework.http.HttpMethod m, Object entity, Class<T> type, Object... vars) { return null; }
                }
                """);
        myFixture.addClass("""
                package org.springframework.web.client;
                public interface RestClient {
                    static Builder builder() { return null; }
                    RequestHeadersUriSpec get();
                    RequestHeadersUriSpec post();
                    interface Builder { Builder baseUrl(String url); RestClient build(); }
                    interface RequestHeadersUriSpec { RequestHeadersUriSpec uri(String uri, Object... vars); ResponseSpec retrieve(); }
                    interface ResponseSpec { <T> T body(Class<T> type); }
                }
                """);
        myFixture.addClass("""
                package org.springframework.web.reactive.function.client;
                public interface WebClient {
                    RequestSpec post();
                    interface UriBuilder { UriBuilder path(String p); java.net.URI build(); }
                    interface RequestSpec { RequestSpec uri(java.util.function.Function<UriBuilder, java.net.URI> f); ResponseSpec retrieve(); }
                    interface ResponseSpec { <T> Object bodyToMono(Class<T> type); }
                }
                """);
        myFixture.addClass("package org.springframework.web.service.annotation; public @interface HttpExchange { String value() default \"\"; String url() default \"\"; String method() default \"\"; }");
        myFixture.addClass("package org.springframework.web.service.annotation; public @interface PostExchange { String value() default \"\"; String url() default \"\"; }");
        myFixture.addClass("package org.springframework.cloud.openfeign; public @interface FeignClient { String name() default \"\"; String value() default \"\"; String url() default \"\"; String path() default \"\"; String contextId() default \"\"; }");
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface GetMapping { String[] value() default {}; String[] path() default {}; }");

        // --- 專案程式碼 ---
        myFixture.addClass("""
                package demo;
                import jakarta.persistence.*;
                @Entity @Table(name = "app_user")
                public class User { @Column(name = "mail") private String email; private String status; }
                """);
        myFixture.addClass("""
                package demo;
                import jakarta.persistence.*;
                @Entity(name = "PurchaseOrder")
                public class Order { private User user; }
                """);
        myFixture.addClass("""
                package demo;
                import org.springframework.data.jpa.repository.*;
                public interface UserRepository extends JpaRepository<User, Long> {
                    User findByEmailAndStatus(String email, String status);
                    @Query("select o from PurchaseOrder o join o.user u where u.status = 'VIP'")
                    java.util.List<Order> vipOrders();
                    @Query(value = "UPDATE app_user SET status = 'X' WHERE id = ?1", nativeQuery = true)
                    void disable(Long id);
                }
                """);
        myFixture.addClass("""
                package demo;
                public interface Notifier { void send(String to); }
                """);
        myFixture.addClass("""
                package demo;
                public class MailNotifier implements Notifier {
                    public void send(String to) {
                        if (format(to).isEmpty()) {
                            throw new IllegalStateException("blank");
                        }
                    }
                    private String format(String s) { return s; }
                }
                """);
        myFixture.addClass("""
                package demo;
                import jakarta.persistence.EntityManager;
                import org.springframework.jdbc.core.JdbcTemplate;
                public class UserService {
                    private UserRepository repo;
                    private Notifier notifier;
                    private EntityManager em;
                    private JdbcTemplate jdbc;
                    private static final String AUDIT = "INSERT INTO audit_log (msg) VALUES (?)";
                    private String name;

                    public String getName() { return name; }

                    public User register(String email) {
                        if (email == null) {
                            throw new IllegalArgumentException("email");
                        }
                        User existing = repo.findByEmailAndStatus(email, "ACTIVE");
                        if (existing != null) {
                            notifier.send(email);
                        } else {
                            repo.save(new User());
                        }
                        for (Order o : repo.vipOrders()) {
                            em.persist(o);
                        }
                        try {
                            jdbc.update(AUDIT, email);
                        } catch (RuntimeException e) {
                            repo.disable(1L);
                        }
                        getName();
                        return em.find(User.class, 1L);
                    }
                }
                """);
    }

    protected void addGateway() {
        myFixture.addClass("""
                package demo;
                public class Stock {}
                """);
        myFixture.addClass("""
                package demo;
                import org.springframework.web.service.annotation.*;
                @HttpExchange(url = "https://pay.example.com", value = "/api")
                public interface PaymentClient {
                    @PostExchange("/charges/{id}")
                    String charge(String id);
                }
                """);
        myFixture.addClass("""
                package demo;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                @FeignClient(name = "shipping-service", path = "/v1")
                public interface ShippingClient {
                    @GetMapping("/shipments/{id}")
                    Stock track(String id);
                }
                """);
        myFixture.addClass("""
                package demo;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.HttpMethod;
                import org.springframework.web.client.*;
                import org.springframework.web.reactive.function.client.WebClient;
                public class OrderGateway {
                    private RestTemplate restTemplate;
                    @Value("${payment.url:http://localhost:8080}")
                    private String paymentUrl;
                    private final RestClient inventory = RestClient.builder().baseUrl("https://inventory.example.com").build();
                    private WebClient eventsClient;
                    private PaymentClient paymentClient;
                    private ShippingClient shippingClient;

                    public void place(String id) {
                        Order order = restTemplate.getForObject(paymentUrl + "/orders/{id}", Order.class, id);
                        restTemplate.exchange("https://legacy.example.com/api/sync", HttpMethod.POST, null, String.class);
                        Stock stock = inventory.get().uri("/stock/{id}", id).retrieve().body(Stock.class);
                        eventsClient.post().uri(b -> b.path("/events").build()).retrieve().bodyToMono(Void.class);
                        paymentClient.charge(id);
                        shippingClient.track(id);
                    }
                }
                """);
    }

    protected SequenceDiagram analyzeGateway() {
        addGateway();
        PsiMethod method = myFixture.findClass("demo.OrderGateway").findMethodsByName("place", false)[0];
        return new SequenceAnalyzer(getProject(), new SeqLensSettings.State()).analyze(method);
    }

    public void testHttpClients() {
        SequenceDiagram d = analyzeGateway();
        List<String> calls = d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.kind() == MessageKind.HTTP)
                .map(e -> {
                    DiagramEvent.Call c = (DiagramEvent.Call) e;
                    return d.getParticipant(c.to()).displayName() + " | " + c.label();
                })
                .toList();
        assertEquals(List.of(
                "${payment.url} | GET /orders/{id}",                 // RestTemplate + @Value
                "legacy.example.com | POST /api/sync",               // RestTemplate.exchange
                "inventory.example.com | GET /stock/{id}",           // RestClient + baseUrl
                "eventsClient | POST /events",                       // WebClient + UriBuilder lambda
                "pay.example.com | POST /api/charges/{id}",          // @HttpExchange
                "shipping-service | GET /v1/shipments/{id}"          // Feign
        ), calls);
        assertEquals(ParticipantKind.EXTERNAL, d.getParticipant("http:inventory.example.com").kind());
        // fluent 呼叫鏈的中間呼叫（uri / retrieve / body）不應另外出現
        assertFalse(d.getEvents().stream().anyMatch(e -> e instanceof DiagramEvent.Call c
                && (c.label().startsWith("retrieve") || c.label().startsWith("build"))));
        // 回應型別
        List<String> returns = d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Return r && r.from().startsWith("http:") && r.visible())
                .map(e -> ((DiagramEvent.Return) e).label()).toList();
        assertEquals(List.of("Order", "String", "Stock", "String", "Stock"), returns);
        assertTrue(PlantUmlExporter.export(d).contains("<<external>>"));
    }

    /** 線上書店：以 @DynamicRepository 動態查詢書籍與暢銷書。 */
    protected SequenceDiagram analyzeDynamic() {
        myFixture.addClass("package demo.dynamic.annotation; public @interface DynamicRepository {}");
        myFixture.addClass("package demo.dynamic.annotation; public @interface DynamicQuery { String value(); boolean nativeQuery() default false; String countQuery() default \"\"; }");
        myFixture.addClass("package org.springframework.data.domain; public class Sort {}");
        myFixture.addClass("package org.springframework.data.domain; public interface Pageable {}");
        myFixture.addClass("package org.springframework.data.domain; public interface Page<T> {}");
        myFixture.addClass("package demo.bookstore.entity; import jakarta.persistence.*; @Entity @Table(name = \"book\") public class BookEntity {}");
        myFixture.addClass("package demo.bookstore.entity; import jakarta.persistence.*; @Entity public class PublisherEntity {}");
        myFixture.addClass("package demo.bookstore.vo; public record BookCardVo(String isbn, String title, String publisherName) {}");
        myFixture.addClass("""
                package demo.bookstore.repository;
                import demo.dynamic.annotation.*;
                import demo.bookstore.vo.BookCardVo;
                import org.springframework.data.domain.*;
                import java.util.*;
                @DynamicRepository
                public interface BookDynamicRepository {
                    @DynamicQuery(""\"
                            SELECT new demo.bookstore.vo.BookCardVo(b.isbn, b.title, p.name)
                            FROM BookEntity b
                            LEFT JOIN PublisherEntity p ON b.publisherId = p.id
                            WHERE b.status = :status
                            [ AND b.genre IN :genres ]
                            ""\")
                    List<BookCardVo> searchBooks(String status, Collection<String> genres, Sort sort);

                    @DynamicQuery(value = ""\"
                            WITH best AS (SELECT book_id FROM monthly_sales_view WHERE qty > 100)
                            SELECT b.* FROM book b JOIN best s ON s.book_id = b.id
                            [ WHERE b.language = :language ]
                            ""\", nativeQuery = true)
                    Page<BookCardVo> findBestSellers(String language, Pageable pageable);

                    default List<BookCardVo> findAvailable(String status) {
                        return searchBooks(status, null, null);
                    }
                }
                """);
        myFixture.addClass("""
                package demo.bookstore.service;
                import demo.bookstore.repository.BookDynamicRepository;
                public class BookSearchService {
                    private final BookDynamicRepository repository;
                    public BookSearchService(BookDynamicRepository repository) { this.repository = repository; }
                    public void browse(String language) {
                        repository.findAvailable("ON_SALE");
                        repository.findBestSellers(language, null);
                    }
                }
                """);
        PsiMethod method = myFixture.findClass("demo.bookstore.service.BookSearchService").findMethodsByName("browse", false)[0];
        return new SequenceAnalyzer(getProject(), new SeqLensSettings.State()).analyze(method);
    }

    public void testDynamicQuery() {
        SequenceDiagram d = analyzeDynamic();
        Participant repo = d.getParticipant("demo.bookstore.repository.BookDynamicRepository");
        assertNotNull(repo);
        assertEquals(ParticipantKind.REPOSITORY, repo.kind());

        List<String> sql = d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.kind() == MessageKind.SQL)
                .map(e -> d.getParticipant(((DiagramEvent.Call) e).to()).displayName() + " " + ((DiagramEvent.Call) e).label())
                .toList();
        assertEquals(List.of(
                "book SELECT",                 // JPQL：@Table(name = "book")
                "publisher_entity SELECT",     // LEFT JOIN 實體名 ON …（無 @Table 時 snake_case）
                "monthly_sales_view SELECT",   // native：CTE 名稱 best 不算表
                "book SELECT"
        ), sql);
        // default method 照常展開，內部呼叫 searchBooks
        List<String> labels = d.getEvents().stream().filter(e -> e instanceof DiagramEvent.Call c && c.kind() == MessageKind.CALL)
                .map(e -> ((DiagramEvent.Call) e).label()).toList();
        assertTrue(labels.toString(), labels.contains("findAvailable(\"ON_SALE\")"));
        assertTrue(labels.toString(), labels.contains("searchBooks(status, null, null)"));
        // 附註只顯示原始 SQL，[ ] 選填片段各自一行
        List<String> notes = d.getEvents().stream().filter(e -> e instanceof DiagramEvent.Note)
                .map(e -> ((DiagramEvent.Note) e).text()).toList();
        assertTrue(notes.get(0), notes.get(0).endsWith("\n[ AND b.genre IN :genres ]"));
        assertTrue("[ 與關鍵字同一行: " + notes.get(1), notes.get(1).endsWith("\n[ WHERE b.language = :language ]"));
        assertFalse("不顯示說明文字", notes.stream().anyMatch(n -> n.contains("--")));
        // 資料表可跳到實體；沒有實體的 view 不能跳轉
        assertNotNull(d.getParticipant("table:book").navigation());
        assertNull(d.getParticipant("table:monthly_sales_view").navigation());
    }

    private PsiMethod addMapStructSample() {
        myFixture.addClass("package org.mapstruct; public @interface Mapper { String componentModel() default \"\"; }");
        myFixture.addClass("package org.mapstruct; public @interface MappingTarget {}");
        myFixture.addClass("package demo.dto; public class UserDto { public void setEmail(String e) {} }");
        myFixture.addClass("""
                package demo.mapper;
                import org.mapstruct.*;
                import demo.User;
                import demo.dto.UserDto;
                @Mapper(componentModel = "spring")
                public interface UserMapper {
                    UserDto toDto(User user);
                    void update(UserDto dto, @MappingTarget User user);
                    default java.util.List<UserDto> toDtos(java.util.List<User> users) {
                        return users.stream().map(this::toDto).toList();
                    }
                }
                """);
        // 模擬 MapStruct 產生的實作（在 generated sources 裡，也算專案原始碼）
        myFixture.addClass("""
                package demo.mapper;
                import demo.User;
                import demo.dto.UserDto;
                @javax.annotation.processing.Generated(value = "org.mapstruct.ap.MappingProcessor")
                public class UserMapperImpl implements UserMapper {
                    public UserDto toDto(User user) {
                        if (user == null) { return null; }
                        UserDto dto = new UserDto();
                        dto.setEmail(String.valueOf(user.hashCode()));
                        helper(dto);
                        return dto;
                    }
                    public void update(UserDto dto, User user) { helper(dto); }
                    private void helper(UserDto dto) { new UserDto(); }
                }
                """);
        PsiClass facade = myFixture.addClass("""
                package demo;
                import demo.mapper.*;
                public class UserFacade {
                    private UserMapper mapper;
                    private UserMapperImpl impl;
                    public void show(User user, java.util.List<User> users) {
                        mapper.toDto(user);
                        mapper.toDtos(users);
                        mapper.update(null, user);
                        impl.toDto(user);
                    }
                }
                """);
        return facade.findMethodsByName("show", false)[0];
    }

    public void testMapStructNotExpanded() {
        PsiMethod show = addMapStructSample();
        SequenceDiagram d = new SequenceAnalyzer(getProject(), new SeqLensSettings.State()).analyze(show);
        Participant mapper = d.getParticipant("demo.mapper.UserMapper");
        assertNotNull(mapper);
        assertEquals(ParticipantKind.MAPPER, mapper.kind());
        assertNull("產生的 Impl 不應成為參與者", d.getParticipant("demo.mapper.UserMapperImpl"));
        assertNull("不應展開到 UserDto", d.getParticipant("demo.dto.UserDto"));
        List<String> calls = d.getEvents().stream().filter(e -> e instanceof DiagramEvent.Call)
                .map(e -> ((DiagramEvent.Call) e).from() + " → " + ((DiagramEvent.Call) e).label()).toList();
        assertEquals(List.of(
                "__actor__ → show(user, users)",
                "demo.UserFacade → toDto(user)",
                "demo.UserFacade → toDtos(users)",
                "demo.UserFacade → update(null, user)",
                "demo.UserFacade → toDto(user)"          // 直接呼叫 Impl 也折回 UserMapper
        ), calls);
        DiagramEvent.Call toDto = (DiagramEvent.Call) d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.label().equals("toDto(user)")).findFirst().orElseThrow();
        assertEquals("demo.mapper.UserMapper", toDto.to());
        assertTrue(toDto.tooltip(), toDto.tooltip().contains("MapStruct: User → UserDto"));
        DiagramEvent.Call update = (DiagramEvent.Call) d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.label().startsWith("update")).findFirst().orElseThrow();
        assertTrue(update.tooltip(), update.tooltip().contains("MapStruct: UserDto → User"));

        // 關閉後恢復原本行為：展開進產生的實作
        SeqLensSettings.State off = new SeqLensSettings.State();
        off.collapseMapStruct = false;
        SequenceDiagram expanded = new SequenceAnalyzer(getProject(), off).analyze(show);
        assertNotNull(expanded.getParticipant("demo.mapper.UserMapperImpl"));
    }

    /** 重現：介面上叫 getXxx() 的業務方法被誤判成 getter；測試目錄的假實作讓「唯一實作」判斷失敗。 */
    public void testServiceMethodNamedLikeGetterIsExpanded() throws Exception {
        myFixture.addClass("package org.springframework.http; public class ResponseEntity<T> { public static <T> ResponseEntity<T> ok(T body) { return null; } }");
        myFixture.addClass("package org.springframework.security.access.prepost; public @interface PreAuthorize { String value(); }");
        myFixture.addClass("package demo.payment; public class PaymentListResponse {}");
        // OpenAPI generator 產生的 API 介面
        myFixture.addClass("""
                package demo.payment;
                import org.springframework.http.ResponseEntity;
                public interface PaymentApi {
                    ResponseEntity<PaymentListResponse> getApiV1Payments() throws Exception;
                }
                """);
        myFixture.addClass("""
                package demo.payment;
                public interface PaymentQueryService {
                    PaymentListResponse getApiV1Payments();
                }
                """);
        myFixture.addClass("""
                package demo.payment;
                public class PaymentQueryServiceImpl implements PaymentQueryService {
                    private final demo.UserRepository repository = null;
                    @Override
                    public PaymentListResponse getApiV1Payments() {
                        repository.findById(1L);
                        return new PaymentListResponse();
                    }
                }
                """);
        PsiClass controller = myFixture.addClass("""
                package demo.payment;
                import org.springframework.http.ResponseEntity;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class PaymentController implements PaymentApi {
                    private PaymentQueryService paymentQueryService;
                    @PreAuthorize("hasAuthority('PAYMENT_READ')")
                    @Override
                    public ResponseEntity<PaymentListResponse> getApiV1Payments() throws Exception {
                        return ResponseEntity.ok(paymentQueryService.getApiV1Payments());
                    }
                }
                """);
        // 測試目錄中的假實作
        com.intellij.openapi.vfs.VirtualFile testRoot = myFixture.getTempDirFixture().findOrCreateDir("testSrc");
        com.intellij.testFramework.PsiTestUtil.addSourceRoot(getModule(), testRoot, true);
        try {
            myFixture.addFileToProject("testSrc/demo/payment/FakePaymentQueryService.java", """
                    package demo.payment;
                    public class FakePaymentQueryService implements PaymentQueryService {
                        public PaymentListResponse getApiV1Payments() { return new PaymentListResponse(); }
                    }
                    """);
            PsiMethod root = controller.findMethodsByName("getApiV1Payments", false)[0];
            SeqLensSettings.State settings = new SeqLensSettings.State();
            settings.excludePatterns += ", org.springframework.*"; // 實際專案中 ResponseEntity 屬於函式庫，不會畫出
            SequenceDiagram d = new SequenceAnalyzer(getProject(), settings).analyze(root);
            List<String> calls = d.getEvents().stream()
                    .filter(e -> e instanceof DiagramEvent.Call c && c.kind() != MessageKind.SQL)
                    .map(e -> d.getParticipant(((DiagramEvent.Call) e).to()).displayName() + "." + ((DiagramEvent.Call) e).label())
                    .toList();
            assertEquals(List.of(
                    "PaymentController.getApiV1Payments()",
                    "PaymentQueryServiceImpl.getApiV1Payments()",       // 展開正式實作，不是測試的假實作
                    "UserRepository.findById(1L)",                      // 實作內部的呼叫也有爬到
                    "PaymentListResponse.«create» PaymentListResponse()"
            ), calls);
        } finally {
            com.intellij.testFramework.PsiTestUtil.removeSourceRoot(getModule(), testRoot);
        }
    }

    public void testRealGettersAreStillSkipped() {
        PsiClass cls = myFixture.addClass("""
                package demo.y;
                public class Account {
                    private String name;
                    private boolean active;
                    public String getName() { return name; }
                    public boolean isActive() { return this.active; }
                    public void setName(String name) { this.name = name; }
                    public String getDisplayName() { return name.toUpperCase(); }
                    public void run() { getName(); isActive(); setName("x"); getDisplayName(); }
                }
                """);
        SequenceDiagram d = new SequenceAnalyzer(getProject(), new SeqLensSettings.State())
                .analyze(cls.findMethodsByName("run", false)[0]);
        assertNull(findCall(d, "getName("));
        assertNull(findCall(d, "isActive("));
        assertNull(findCall(d, "setName("));
        assertNotNull("有實際邏輯的 getXxx() 不是 getter", findCall(d, "getDisplayName("));
    }

    public void testInterfaceGetterNameWithoutImplementationIsStillShown() {
        myFixture.addClass("package demo.x; public interface ReportService { String getMonthlyReport(); }");
        PsiClass caller = myFixture.addClass("""
                package demo.x;
                public class ReportController {
                    private ReportService service;
                    public String show() { return service.getMonthlyReport(); }
                }
                """);
        SequenceDiagram d = new SequenceAnalyzer(getProject(), new SeqLensSettings.State())
                .analyze(caller.findMethodsByName("show", false)[0]);
        assertNotNull("沒有實作的介面方法也要畫出呼叫", findCall(d, "getMonthlyReport("));
    }

    protected SequenceDiagram analyze(SeqLensSettings.State settings) {
        PsiClass service = myFixture.findClass("demo.UserService");
        PsiMethod method = service.findMethodsByName("register", false)[0];
        return new SequenceAnalyzer(getProject(), settings).analyze(method);
    }

    private static DiagramEvent.Call findCall(SequenceDiagram d, String labelPrefix) {
        return d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.label().startsWith(labelPrefix))
                .map(e -> (DiagramEvent.Call) e).findFirst().orElse(null);
    }

    public void testStopList() {
        SeqLensSettings.State settings = new SeqLensSettings.State();
        settings.stopPatterns = "# 介面名稱也能命中實作\nNotifier#send";
        SequenceDiagram d = analyze(settings);
        DiagramEvent.Call send = findCall(d, "send(");
        assertNotNull("命中的呼叫仍要畫出", send);
        assertEquals(DiagramEvent.Expansion.STOPPED, send.expansion());
        assertEquals("demo.MailNotifier#send", send.targetKey());
        assertNull("不應再往下展開", findCall(d, "format("));
        assertFalse("MailNotifier 內的 throw 不應出現", d.getEvents().stream()
                .anyMatch(e -> e instanceof DiagramEvent.Throw t && t.from().equals("demo.MailNotifier")));
        assertEquals(0, d.depthLimitedCount());
        assertTrue(PlantUmlExporter.export(d).contains("send(email) ⊘"));

        settings.stopPatterns = "User#<init>";
        assertEquals("沒有明確建構子也要能命中", DiagramEvent.Expansion.STOPPED,
                findCall(analyze(settings), "«create» User").expansion());

        settings.stopPatterns = "demo.*";
        assertEquals(DiagramEvent.Expansion.STOPPED, findCall(analyze(settings), "send(").expansion());
    }

    public void testDepthLimit() {
        SeqLensSettings.State settings = new SeqLensSettings.State();
        settings.maxDepth = 1;
        SequenceDiagram d = analyze(settings);
        assertEquals(1, d.getMaxDepth());
        DiagramEvent.Call send = findCall(d, "send(");
        assertEquals(DiagramEvent.Expansion.DEPTH_LIMIT, send.expansion());
        assertTrue(send.tooltip(), send.tooltip().contains("Maximum depth 1 reached"));
        assertNull(findCall(d, "format("));
        assertEquals(1, d.depthLimitedCount());
        // 預設深度 5 時全部展開，沒有提醒
        assertEquals(0, analyze().depthLimitedCount());
        // 沒有內部呼叫的方法（User 預設建構子）不算
        assertEquals(DiagramEvent.Expansion.EXPANDED, findCall(d, "«create» User").expansion());
    }

    public void testResourceSummaryClasses() {
        SeqLensSettings.State settings = new SeqLensSettings.State();
        settings.maxDepth = 1;
        ResourceSummary summary = ResourceSummary.of(analyze(settings));
        assertEquals(List.of("UserService", "UserRepository", "MailNotifier", "User", "EntityManager", "JdbcTemplate"),
                List.copyOf(summary.classes().keySet()));
        assertEquals(List.of("register()"), List.copyOf(summary.classes().get("UserService").keySet()));
        assertEquals(List.of("«create»"), List.copyOf(summary.classes().get("User").keySet()));
        ResourceSummary.Usage send = summary.classes().get("MailNotifier").get("send()").getFirst();
        assertEquals("UserService", send.caller());
        assertEquals(DiagramEvent.Expansion.DEPTH_LIMIT, send.expansion());
        assertNotNull(summary.classParticipant("MailNotifier").navigation());
        String md = summary.toMarkdown("t", false, false, true);
        assertFalse(md, md.contains("## External APIs"));
        assertTrue(md, md.contains("| MailNotifier | `send()` ⋯ | UserService (UserService.java:"));
    }

    protected SequenceDiagram analyze() {
        PsiClass service = myFixture.findClass("demo.UserService");
        PsiMethod method = service.findMethodsByName("register", false)[0];
        return new SequenceAnalyzer(getProject(), new SeqLensSettings.State()).analyze(method);
    }

    public void testParticipantsIncludeTables() {
        SequenceDiagram d = analyze();
        List<String> names = d.getParticipants().stream().map(Participant::displayName).toList();
        assertTrue(names.toString(), names.contains("UserService"));
        assertTrue(names.toString(), names.contains("UserRepository"));
        assertTrue("單一實作應展開為 MailNotifier: " + names, names.contains("MailNotifier"));
        assertTrue(names.toString(), names.contains("app_user"));
        assertTrue(names.toString(), names.contains("purchase_order"));
        assertTrue(names.toString(), names.contains("audit_log"));
        assertFalse("getter 應略過", d.getEvents().stream()
                .anyMatch(e -> e instanceof DiagramEvent.Call c && c.label().startsWith("getName")));
        Participant table = d.getParticipant("table:app_user");
        assertNotNull(table);
        assertEquals(ParticipantKind.TABLE, table.kind());
        assertNotNull("資料表應可跳轉到實體", table.navigation());
    }

    public void testSqlMessages() {
        SequenceDiagram d = analyze();
        List<String> sql = d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.kind() == MessageKind.SQL)
                .map(e -> ((DiagramEvent.Call) e).to() + " " + ((DiagramEvent.Call) e).label())
                .toList();
        assertEquals(List.of(
                "table:app_user SELECT",          // findByEmailAndStatus
                "table:app_user INSERT",          // save(new User())
                "table:purchase_order SELECT",    // @Query JPQL
                "table:app_user SELECT",          // join
                "table:purchase_order INSERT",    // em.persist(o)
                "table:audit_log INSERT",         // jdbc.update(AUDIT)
                "table:app_user UPDATE",          // native @Query
                "table:app_user SELECT"           // em.find
        ), sql);
        String notes = d.getEvents().stream().filter(e -> e instanceof DiagramEvent.Note)
                .map(e -> ((DiagramEvent.Note) e).text()).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(notes, notes.contains("WHERE mail = ? AND status = ?"));
    }

    public void testThrows() {
        SequenceDiagram d = analyze();
        List<DiagramEvent.Throw> throwsList = d.getEvents().stream()
                .filter(e -> e instanceof DiagramEvent.Throw).map(e -> (DiagramEvent.Throw) e).toList();
        assertEquals(2, throwsList.size());
        // 根方法的 throw 丟回 Caller
        assertEquals(SequenceAnalyzer.ACTOR_ID, throwsList.get(0).to());
        assertEquals("throw IllegalArgumentException(\"email\")", throwsList.get(0).label());
        assertNotNull(throwsList.get(0).navigation());
        // 被呼叫方法的 throw 丟回 UserService
        assertEquals("demo.MailNotifier", throwsList.get(1).from());
        assertEquals("demo.UserService", throwsList.get(1).to());
        // 只有 throw 的 if 仍應保留成 opt 片段，且例外不畫成 «create»
        String uml = PlantUmlExporter.export(d);
        assertTrue(uml, uml.contains("opt email == null"));
        assertTrue(uml, uml.contains("p1 -[#E5484D]->x p0 : throw IllegalArgumentException"));
        assertFalse(uml, uml.contains("«create» IllegalArgumentException"));
    }

    public void testResourceSummary() {
        ResourceSummary tables = ResourceSummary.of(analyze());
        assertEquals(List.of("app_user", "purchase_order", "audit_log"), List.copyOf(tables.tables().keySet()));
        assertEquals(List.of("SELECT", "INSERT", "UPDATE"), List.copyOf(tables.tables().get("app_user").keySet()));
        ResourceSummary.Usage usage = tables.tables().get("app_user").get("SELECT").getFirst();
        assertEquals("UserRepository.findByEmailAndStatus(email, \"ACTIVE\")", usage.via());
        assertEquals("UserService", usage.caller());
        assertTrue(usage.location(), usage.location().matches("UserService\\.java:\\d+"));
        assertNotNull(usage.navigation());
        assertTrue(tables.apis().isEmpty());

        ResourceSummary apis = ResourceSummary.of(analyzeGateway());
        assertEquals(6, apis.apis().size());
        assertEquals(List.of("GET /stock/{id}"), List.copyOf(apis.apis().get("inventory.example.com").keySet()));
        String md = apis.toMarkdown("OrderGateway.place");
        assertTrue(md, md.contains("| shipping-service | `GET /v1/shipments/{id}` | OrderGateway (OrderGateway.java:"));
        assertTrue(md, md.contains("## Tables\n\n(none)"));
    }

    public void testFragments() {
        SequenceDiagram d = analyze();
        String uml = PlantUmlExporter.export(d);
        assertTrue(uml, uml.contains("alt existing != null"));
        assertTrue(uml, uml.contains("loop for each o : repo.vipOrders()"));
        assertTrue(uml, uml.contains("group try"));
        assertTrue(uml, uml.contains("else catch RuntimeException"));
        assertFalse(uml, uml.contains("else else"));
        assertTrue("save() 的回傳型別應代入泛型: " + uml, uml.contains("p2 --> p1 : User\ndeactivate p2\nend"));
        // 每個 Call 都要有對應的 Return
        long calls = d.getEvents().stream().filter(e -> e instanceof DiagramEvent.Call).count();
        long returns = d.getEvents().stream().filter(e -> e instanceof DiagramEvent.Return).count();
        assertEquals(calls, returns);
    }
}
