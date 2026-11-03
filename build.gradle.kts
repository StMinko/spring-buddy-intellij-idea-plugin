import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")

}

group = "com.sarhan.springformat"
version = "1.0.5"

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
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Spring Buddy"
        version = "1.0.5"
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
