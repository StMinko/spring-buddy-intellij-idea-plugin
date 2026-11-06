import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")

}

group = "com.sarhan.springformat"
version = "1.0.9"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(libs.hibernate.core) {
        isTransitive = false
    }
    implementation(libs.antlr4.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.3")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.spring")
        bundledPlugin("com.intellij.spring.boot")
        bundledPlugin("com.intellij.spring.data")
        bundledPlugin("com.intellij.spring.mvc")
        // Since 2026.2 JCEF classes ship in the bundled Web Browser (JCEF) plugin
        bundledPlugin("com.intellij.modules.jcef")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Spring Buddy"
        version = "1.0.9"
        description = """
            <h2>Make invisible Spring runtime behavior visible.</h2>
              <p>
                Spring Buddy is a correctness companion that models Spring consequences in real-time.
                It identifies "Legal Java" that results in "Illegal Spring," catching proxy bypasses,
                transaction pitfalls, and persistence risks before they reach your CI pipeline.
              </p>
            
              <h3>Key Capabilities</h3>
              <ul>
                <li><b>Transaction Visualization & Pitfalls:</b> Inline hints show propagation (Join, Start, Suspend, Nested). Detects proxy bypasses like self-invocation, private/final methods, and <code>@Transactional</code> on <code>@PostConstruct</code>.</li>
                <li><b>Persistence Performance:</b> Flags N+1 query risks in loops/streams, Open Session In View (OSIV) hazards, and full-load risks in projections.</li>
                <li><b>Test Ecosystem Intelligence:</b> Deep support for AssertJ migration and Mockito style alignment. Recognizes ArchUnit, MockMvc, and WebTestClient patterns as valid assertions.</li>
                <li><b>Spring Java Format:</b> Automates compliance for variable naming, import/method ordering, visibility rules, and utility constructors.</li>
                <li><b>Refactor Safety Net:</b> Bundled inspection runner to validate Spring semantics after large-scale AI or manual refactors.</li>
              </ul>
            
              <p><i>Stop debugging framework behavior and start seeing it directly in your editor.</i></p>
              <br/>
              <hr/>
              <p>
                <b>New to Spring Buddy?</b><br/>
                View our <a href="https://akmalsar.github.io/spring-buddy-intellij-idea-plugin/">Visual Feature Tour</a>
                to see how transaction proxies and persistence risks are surfaced in the IDE.
              </p>
        """.trimIndent()
        changeNotes = """
            <h3>1.0.9</h3>
            <ul>
              <li><b>Fewer false positives for weak transaction isolation:</b> PostgreSQL advisory locks (<code>pg_advisory_xact_lock</code>) now count as locks. <code>FOR UPDATE</code> locks are now also found in helpers of other beans, up to three calls deep, including SQL run through <code>JdbcTemplate</code>.</li>
              <li><b>Fewer false positives for long-running work inside <code>@Transactional</code>:</b> a <code>*Client</code> call is no longer reported when every implementation in the project only does local work, such as building an OAuth URL. <code>RestTemplate</code>, <code>RestOperations</code> and <code>WebClient</code> calls are still always reported.</li>
              <li><b>Fewer false positives for swallowed exceptions:</b> a catch block that ends with <code>return</code> is no longer reported when no write happens before or inside the <code>try</code>, because the transaction has nothing to commit yet.</li>
              <li><b>Fewer false positives for multiple writes without <code>@Transactional</code>:</b> a Javadoc <code>{@link}</code> to a private write helper no longer counts as a caller without a transaction.</li>
            </ul>
            <h3>1.0.8</h3>
            <ul>
              <li><b>Fixed a crash in the flow diagram tool windows on 2026.2 and later:</b> since 2026.2 the embedded browser (JCEF) classes moved out of the platform into the bundled Web Browser (JCEF) plugin. Spring Buddy did not declare that dependency, so opening a transaction or unexpected-rollback flow diagram could fail with <code>NoClassDefFoundError: com/intellij/ui/jcef/JBCefBrowserBase</code>. The plugin now declares the required <code>com.intellij.modules.jcef</code> dependency and the diagrams open again.</li>
            </ul>
            <h3>1.0.7</h3>
            <ul>
              <li><b>Fewer false positives for <code>@Async</code> with <code>@TransactionalEventListener</code>:</b> only listeners with <code>phase = BEFORE_COMMIT</code> are reported. The default <code>AFTER_COMMIT</code> phase, and <code>AFTER_ROLLBACK</code> and <code>AFTER_COMPLETION</code>, start the async task after the transaction has completed, so the listener only sees committed data.</li>
              <li><b>Fewer false positives for repository-level <code>@Transactional</code>:</b> <code>@Transactional(readOnly = true)</code> on a repository interface is no longer reported. Spring Data JPA recommends this pattern for declared query methods, and a service-layer transaction still overrides it. Writable repository-level <code>@Transactional</code> is still reported.</li>
              <li><b>Fewer false positives in the dirty-checking check:</b> passing a loaded entity to a writable <code>@Transactional</code> method of another bean now counts as an intentional write. Before, such methods were reported and the quick fix offered <code>readOnly = true</code>, which would have stopped the change from being saved. Passing the entity to a read-only bean is still reported.</li>
            </ul>
            <h3>1.0.6</h3>
            <ul>
              <li><b>Jackson 3 API migration:</b> new inspection reports Jackson 2 calls that were renamed or removed in Jackson 3, such as <code>JsonGenerator.writeFieldName()</code>, <code>JsonParser.getCurrentToken()</code>, <code>ObjectReader.readValue(value, type)</code>, <code>JsonNode.fields()</code> and the <code>ObjectMapper</code> setters that moved behind <code>JsonMapper.builder()</code>. A quick fix rewrites the mechanical renames in place.</li>
              <li><b>Jackson 3 constructor defaults:</b> new inspection reports classes where Jackson 3 binds the all-args constructor and skips field initializers. A quick fix marks the no-arg constructor <code>@JsonCreator</code>, or adds <code>onConstructor_ = @JsonCreator</code> to a Lombok <code>@NoArgsConstructor</code>.</li>
              <li><b>Jackson 3 unchecked exceptions:</b> new inspection reports <code>IOException</code> catches around Jackson calls, which no longer fire because Jackson 3 throws the unchecked <code>JacksonException</code>. A quick fix widens the catch to <code>IOException | JacksonException</code> when the catch block keeps compiling.</li>
              <li><b>WebTestClient <code>jsonPath</code> overload removal:</b> new inspection reports the <code>jsonPath(String, Object...)</code> overload removed in Spring Framework 7. A quick fix chains <code>.value(matcher)</code> for Hamcrest matchers, which the old overload never asserted, or formats template arguments into the expression with <code>formatted()</code>.</li>
              <li><b>Hibernate 7 artifacts:</b> new build-file inspection reports <code>jackson-datatype-hibernate5/6</code> and <code>hypersistence-utils-hibernate-63</code>. A quick fix moves them to <code>tools.jackson.datatype:jackson-datatype-hibernate7</code> and <code>hypersistence-utils-hibernate-71</code>.</li>
              <li><b>More Jackson 3 type renames:</b> <code>JsonParseException</code>, <code>JsonGenerationException</code>, <code>JsonFactory</code>, <code>Module</code>, <code>BeanSerializerModifier</code>, filter providers and other types now map to their Jackson 3 names. <code>ObjectCodec</code> is reported as removed.</li>
              <li><b>More Spring Boot 4 relocations:</b> Hibernate/JPA auto-configuration classes, <code>TestEntityManager</code>, <code>@AutoConfigureDataJpa</code>, <code>@AutoConfigureTestDatabase</code>, <code>@AutoConfigureWebTestClient</code> and <code>JacksonAutoConfiguration</code> are reported with their new packages.</li>
              <li><b>WebTestClient in <code>@SpringBootTest</code>:</b> an autowired <code>WebTestClient</code> without <code>@AutoConfigureWebTestClient</code> is now reported, with a quick fix that adds the annotation.</li>
            </ul>
            <h3>1.0.5</h3>
            <ul>
              <li><b>Fewer false positives in service swallowed-exception checks:</b> a broad <code>catch</code> that logs the failure at info level or higher (<code>info</code>, <code>warn</code>, <code>error</code>, and similar) is no longer reported. A catch that only logs at debug level is still reported.</li>
              <li><b>Fewer false positives in multiple-repositories-without-transaction checks:</b> repository writes inside <code>TransactionTemplate</code> or <code>TransactionOperations</code> callbacks are recognized as transactional. A private helper is no longer reported when every caller in its class is covered by a transaction.</li>
              <li><b>Fewer false positives in transactional self-invocation checks:</b> calls are not reported when the callee does not change transaction semantics; read-only callers invoking writable methods and callees with other transaction attribute overrides remain reported.</li>
              <li><b>Fewer false positives in transactional event-listener checks:</b> listeners that only delegate to another bean or write inside a <code>TransactionTemplate</code> or <code>TransactionOperations</code> callback are no longer reported. Repository writes in the listener or same-class helpers remain reported.</li>
              <li><b>Fewer false positives in multiple-write-without-transaction checks:</b> writes inside <code>TransactionTemplate</code> or <code>TransactionOperations</code> callbacks are excluded, and private helpers are covered when every in-class caller path reaches a transaction boundary.</li>
              <li><b>Fewer false positives in weak-isolation checks:</b> read-only transactions and methods that take pessimistic locks through repository <code>@Lock</code> or <code>@Query</code> methods with <code>FOR UPDATE</code> or <code>FOR NO KEY UPDATE</code> are no longer reported, including same-class helper calls.</li>
              <li><b>Fewer false positives in long-running-operation checks:</b> generic calls such as <code>get</code> and <code>execute</code> are no longer treated as slow work by name alone; reports use <code>Thread.sleep</code> or client-like receiver types.</li>
              <li><b>Fewer false positives in JPA bulk-update checks:</b> <code>@Modifying</code> methods are reported only when a caller reads the same entity type before and after the bulk operation in the same method or transaction-template callback. Uncalled methods remain reported.</li>
              <li><b>Fewer false positives in checked-exception rollback checks:</b> methods are reported only when a checked exception can escape the body; read-only transactions without detected writes are exempt.</li>
              <li><b>Fewer false positives in lazy-initialization prediction:</b> identifier-only proxy access, transaction-covered private helpers, and associations fetched by the source method's <code>JOIN FETCH</code> or <code>@EntityGraph</code> are no longer reported.</li>
              <li><b>Fixed:</b> a private service method is no longer offered the <code>@Transactional</code> quick fix, because Spring's proxy would ignore it.</li>
              <li><b>Markdown export:</b> more reliable severity and inspection-profile resolution when exporting findings.</li>
            </ul>
            <h3>1.0.4</h3>
            <ul>
              <li><b>Markdown export:</b> right-click the inspection results view and export the selected findings as a machine-friendly Markdown report — stable inspection IDs, problem description per inspection, and every affected file with line numbers, ready for an AI agent to read and verify.</li>
            </ul>
            <h3>1.0.3</h3>
            <ul>
              <li><b>Spring Boot 4 migration support:</b> 29 new inspections cover the Boot 3 to Boot 4 upgrade — Jackson 2 to Jackson 3 package and coordinate moves, Spring Security lambda DSL and chained request matchers, Undertow starter removal, renamed and relocated Boot starters and classes, removed build features, JUnit 4 test runners, and updated test-starter modules.</li>
              <li><b>New Spring Boot Target setting:</b> per-project Auto-detect, Boot 3, or Boot 4 target gates the migration inspections, with auto-detect reading the Boot version from the module classpath.</li>
              <li><b>Fixed:</b> <code>@MockBean</code>/<code>@SpyBean</code> context-cache warnings are reported again in auto-detect mode on Boot 3 projects.</li>
            </ul>
        """.trimIndent()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
