/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sarhan.springformat.inspection;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.sarhan.springformat.settings.SpringBootVersionResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Spring Boot 4 migration inspections for build and configuration files.
 * Migration checks run before the upgrade; they are silenced only when the
 * project is explicitly pinned to Boot 3.x. Quick fixes are only offered for
 * verified, mechanical renames.
 *
 * @author Akmal Sarhan
 */
public class SpringBoot4FileMigrationInspection extends LocalInspectionTool {

    /**
     * Exact artifact match for the old web starter: the boundary excludes
     * {@code webmvc}, {@code webflux}, {@code web-services} and any other suffix.
     */
    private static final Pattern RENAMED_STARTER_WEB = Pattern.compile("spring-boot-starter-web(?![\\w-])");

    /**
     * Starter renames from the Spring Boot 4.0 migration guide, each anchored to the
     * exact artifact so prefixes of other starters are never rewritten.
     */
    private static final List<Replacement> STARTER_RENAMES = List.of(
            replacement(RENAMED_STARTER_WEB.pattern(), "spring-boot-starter-webmvc"),
            replacement("spring-boot-starter-web-services(?![\\w-])", "spring-boot-starter-webservices"),
            replacement("spring-boot-starter-aop(?![\\w-])", "spring-boot-starter-aspectj"),
            replacement("spring-boot-starter-oauth2-(client|resource-server|authorization-server)(?![\\w-])",
                    "spring-boot-starter-security-oauth2-$1"));

    private static final Pattern HIBERNATE_JPAMODELGEN = Pattern.compile("hibernate-jpamodelgen(?![\\w-])");

    /** Maven spring-retry dependency whose block has no {@code <version>}. */
    private static final Pattern MAVEN_UNVERSIONED_RETRY = Pattern.compile(
            "<artifactId>spring-retry</artifactId>(?:(?!<version>)(?!</dependency>)[\\s\\S])*</dependency>");

    /** Gradle {@code org.springframework.retry:spring-retry} notation without a version. */
    private static final Pattern GRADLE_UNVERSIONED_RETRY = Pattern.compile("org\\.springframework\\.retry:spring-retry['\"]");

    private static final Pattern GRADLE_LAUNCH_SCRIPT = Pattern.compile("\\blaunchScript\\s*[({]");

    private static final Pattern MAVEN_EXECUTABLE = Pattern.compile("<executable>\\s*true\\s*</executable>");

    private static final List<Pattern> REMOVED_FEATURE_ARTIFACTS = List.of(
            Pattern.compile("spring-session-hazelcast(?![\\w-])"),
            Pattern.compile("spring-session-data-mongodb(?![\\w-])"),
            Pattern.compile("spring-boot-starter-pulsar-reactive(?![\\w-])"),
            Pattern.compile("spring-pulsar-reactive(?![\\w-])"),
            Pattern.compile("org\\.spockframework"));

    private static final Pattern GRADLE_DISTRIBUTION_VERSION = Pattern.compile(
            "gradle-([0-9]+)\\.([0-9]+)(\\.[0-9]+)?-(?:bin|all)\\.zip");

    /**
     * Hibernate 7 needs the new hypersistence-utils artifact line and the Jackson 3
     * hibernate module moved to tools.jackson.datatype.
     */
    private static final Pattern HYPERSISTENCE_HIBERNATE_63 = Pattern.compile("hypersistence-utils-hibernate-63(?![\\w-])");

    private static final Pattern HIBERNATE_DATAFORMAT_ARTIFACT = Pattern.compile("jackson-datatype-hibernate[56](?![\\w-])");

    private static final Pattern MAVEN_JACKSON_DATATYPE_GROUP = Pattern.compile(
            "<groupId>com\\.fasterxml\\.jackson\\.datatype</groupId>(?=\\s*<artifactId>jackson-datatype-hibernate7</artifactId>)");

    private static final Pattern GRADLE_JACKSON_DATATYPE_GROUP = Pattern.compile(
            "com\\.fasterxml\\.jackson\\.datatype(?=:jackson-datatype-hibernate7)");

    private static final Pattern KOTLIN_GRADLE_VERSION = Pattern.compile(
            "org\\.jetbrains\\.kotlin[a-z.\\-]*[:\\s]([0-9]+)\\.([0-9]+)(?:\\.[0-9]+)?");

    private static final Pattern KOTLIN_POM_VERSION = Pattern.compile(
            "<kotlin\\.version>\\s*([0-9]+)\\.([0-9]+)(?:\\.[0-9]+)?");

    private static final int MINIMUM_GRADLE_MAJOR = 8;

    private static final int MINIMUM_GRADLE_MINOR = 14;

    private static final int MINIMUM_KOTLIN_MAJOR = 2;

    private static final int MINIMUM_KOTLIN_MINOR = 2;

    private final Kind kind;

    protected SpringBoot4FileMigrationInspection(@NotNull Kind kind) {
        this.kind = kind;
    }

    @Override
    public boolean runForWholeFile() {
        return true;
    }

    /**
     * Package-private access for tests: the quick fix this inspection offers
     * for the given file, or null when there is none.
     */
    @Nullable
    LocalQuickFix quickFix(@NotNull PsiFile file) {
        return this.kind.fix(file);
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();
        if (migrationChecksActive(file) && matches(file)) {
            holder.registerProblem(file, this.kind.message);
        }
        return PsiElementVisitor.EMPTY_VISITOR;
    }

    @Override
    public ProblemDescriptor @NotNull [] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager,
            boolean isOnTheFly) {
        if (!migrationChecksActive(file) || !matches(file)) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }
        ProblemHighlightType level = (this.kind == Kind.TEST_STARTER_MODULES)
                ? ProblemHighlightType.WEAK_WARNING
                : ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
        LocalQuickFix fix = this.kind.fix(file);
        return new ProblemDescriptor[] {
                manager.createProblemDescriptor(file, TextRange.from(0, Math.min(1, file.getTextLength())),
                        this.kind.message, level, isOnTheFly,
                        fix == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[] { fix })
        };
    }

    private static boolean migrationChecksActive(@NotNull PsiFile file) {
        return SpringBootVersionResolver.migrationChecksActive(file.getProject());
    }

    private boolean matches(PsiFile file) {
        String name = file.getName();
        if (!isRelevantFile(this.kind, name)) {
            return false;
        }
        String text = file.getText();
        return switch (this.kind) {
            case UNDERTOW_STARTER -> text.contains("spring-boot-starter-undertow");
            case RENAMED_STARTER -> hasRenamedStarter(text);
            case JACKSON_COORDINATES -> text.contains("com.fasterxml.jackson.core:")
                    || (text.contains("<groupId>com.fasterxml.jackson.core</groupId>")
                            && (text.contains("<artifactId>jackson-databind</artifactId>")
                                    || text.contains("<artifactId>jackson-core</artifactId>")));
            case RENAMED_PROPERTIES -> hasRenamedProperty(text);
            case BUILD_TOOL_VERSION -> hasTooOldGradle(text) || hasTooOldKotlin(text);
            case TEST_STARTER_MODULES -> hasMissingModularTestStarter(text);
            case HIBERNATE_PROCESSOR -> HIBERNATE_JPAMODELGEN.matcher(text).find();
            case HIBERNATE_ARTIFACTS -> HYPERSISTENCE_HIBERNATE_63.matcher(text).find()
                    || HIBERNATE_DATAFORMAT_ARTIFACT.matcher(text).find();
            case UNMANAGED_DEPENDENCY -> MAVEN_UNVERSIONED_RETRY.matcher(text).find()
                    || GRADLE_UNVERSIONED_RETRY.matcher(text).find()
                    || text.contains("spring-authorization-server.version");
            case REMOVED_BUILD_FEATURE -> hasRemovedBuildFeature(text);
        };
    }

    private static boolean hasRemovedBuildFeature(String text) {
        if (GRADLE_LAUNCH_SCRIPT.matcher(text).find()
                || (text.contains("spring-boot-maven-plugin") && MAVEN_EXECUTABLE.matcher(text).find())) {
            return true;
        }
        for (Pattern artifact : REMOVED_FEATURE_ARTIFACTS) {
            if (artifact.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRelevantFile(Kind kind, String name) {
        return switch (kind) {
            case UNDERTOW_STARTER, RENAMED_STARTER, JACKSON_COORDINATES, TEST_STARTER_MODULES, HIBERNATE_PROCESSOR,
                    UNMANAGED_DEPENDENCY, REMOVED_BUILD_FEATURE, HIBERNATE_ARTIFACTS ->
                isBuildFile(name);
            case RENAMED_PROPERTIES -> isApplicationConfig(name);
            case BUILD_TOOL_VERSION -> isBuildFile(name) || name.equals("gradle-wrapper.properties");
        };
    }

    private static boolean isBuildFile(String name) {
        return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("gradle.properties");
    }

    private static boolean isApplicationConfig(String name) {
        return isSpringConfig(name, "application") || isSpringConfig(name, "bootstrap");
    }

    private static boolean isSpringConfig(String name, String prefix) {
        return name.equals(prefix + ".properties") || name.equals(prefix + ".yml") || name.equals(prefix + ".yaml")
                || name.startsWith(prefix + "-") && (name.endsWith(".properties") || name.endsWith(".yml")
                        || name.endsWith(".yaml"));
    }

    private static boolean hasRenamedStarter(String text) {
        for (Replacement rename : STARTER_RENAMES) {
            if (Pattern.compile(rename.oldText()).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRenamedProperty(String text) {
        return text.contains("spring.jackson.read.")
                || text.contains("spring.jackson.write.")
                || text.contains("spring.jackson.parser")
                || text.contains("spring.jackson.generator")
                || text.contains("spring.dao.exceptiontranslation.enabled")
                || text.contains("management.tracing.enabled")
                || text.contains("spring.flyway.clean-on-validation-error");
    }

    private static boolean hasTooOldGradle(String text) {
        Matcher matcher = GRADLE_DISTRIBUTION_VERSION.matcher(text);
        while (matcher.find()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            if (major < MINIMUM_GRADLE_MAJOR || (major == MINIMUM_GRADLE_MAJOR && minor < MINIMUM_GRADLE_MINOR)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTooOldKotlin(String text) {
        return isTooOldKotlinVersion(KOTLIN_GRADLE_VERSION, text)
                || isTooOldKotlinVersion(KOTLIN_POM_VERSION, text);
    }

    private static boolean isTooOldKotlinVersion(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            if (major < MINIMUM_KOTLIN_MAJOR || (major == MINIMUM_KOTLIN_MAJOR && minor < MINIMUM_KOTLIN_MINOR)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMissingModularTestStarter(String text) {
        if (!text.contains("spring-boot-starter-test")) {
            return false;
        }
        return text.contains("spring-boot-starter-data-jpa") && !text.contains("spring-boot-starter-data-jpa-test")
                || RENAMED_STARTER_WEB.matcher(text).find() && !text.contains("spring-boot-starter-webmvc-test")
                || text.contains("spring-boot-starter-webmvc") && !text.contains("spring-boot-starter-webmvc-test");
    }

    private enum Kind {
        UNDERTOW_STARTER("Undertow is no longer a supported embedded server in Spring Boot 4;"
                + " choose Tomcat or Jetty"),
        RENAMED_STARTER("Some Spring Boot starters were renamed in Spring Boot 4"
                + " (web -> webmvc, web-services -> webservices, aop -> aspectj, oauth2-* -> security-oauth2-*)"),
        JACKSON_COORDINATES("Jackson 2 core dependencies should be replaced by Jackson 3"
                + " (tools.jackson.core) in Spring Boot 4"),
        RENAMED_PROPERTIES("This configuration property was renamed or removed in Spring Boot 4"),
        BUILD_TOOL_VERSION("Spring Boot 4 requires Gradle 8.14 or later and Kotlin 2.2 or later"),
        TEST_STARTER_MODULES("Spring Boot 4 splits test support into per-technology test starters"
                + " such as spring-boot-starter-webmvc-test or spring-boot-starter-data-jpa-test"),
        HIBERNATE_PROCESSOR("hibernate-jpamodelgen is replaced by hibernate-processor in Spring Boot 4"
                + " dependency management"),
        UNMANAGED_DEPENDENCY("Spring Boot 4 no longer manages Spring Retry (an explicit version is required)"
                + " or the spring-authorization-server.version property (use spring-security.version)"),
        HIBERNATE_ARTIFACTS("Hibernate 7 and Jackson 3 need updated artifact coordinates:"
                + " jackson-datatype-hibernate5/6 becomes tools.jackson.datatype:jackson-datatype-hibernate7"
                + " and hypersistence-utils-hibernate-63 becomes hypersistence-utils-hibernate-71"),
        REMOVED_BUILD_FEATURE("This build feature was removed in Spring Boot 4 (embedded launch scripts, Spring Session"
                + " Hazelcast/MongoDB, Spring Pulsar Reactive or Spock integration)");

        private final String message;

        Kind(String message) {
            this.message = message;
        }

        @Nullable
        private LocalQuickFix fix(PsiFile file) {
            String text = file.getText();
            return switch (this) {
                case RENAMED_STARTER -> starterRenameFix();
                case JACKSON_COORDINATES -> jacksonCoordinatesFix();
                case RENAMED_PROPERTIES -> renamedPropertyFix(text);
                case HIBERNATE_PROCESSOR -> new RegexReplaceFix("Replace with hibernate-processor",
                        HIBERNATE_JPAMODELGEN, "hibernate-processor");
                case HIBERNATE_ARTIFACTS -> new CompositeFix("Replace with Hibernate 7 and Jackson 3 coordinates",
                        new RegexReplaceFix("Replace the Hibernate Jackson module artifact",
                                HIBERNATE_DATAFORMAT_ARTIFACT, "jackson-datatype-hibernate7"),
                        new RegexReplaceFix("Replace the Maven groupId with tools.jackson.datatype",
                                MAVEN_JACKSON_DATATYPE_GROUP, "<groupId>tools.jackson.datatype</groupId>"),
                        new RegexReplaceFix("Replace the Gradle notation with tools.jackson.datatype",
                                GRADLE_JACKSON_DATATYPE_GROUP, "tools.jackson.datatype"),
                        new RegexReplaceFix("Replace hypersistence-utils with the Hibernate 7 line",
                                HYPERSISTENCE_HIBERNATE_63, "hypersistence-utils-hibernate-71"));
                default -> null;
            };
        }

        /**
         * Every rename is an exact-artifact regex, so already-renamed starters and
         * starters sharing a prefix (webflux, webmvc) are left untouched.
         */
        private static LocalQuickFix starterRenameFix() {
            LocalQuickFix[] fixes = STARTER_RENAMES.stream()
                    .map((Replacement rename) -> new RegexReplaceFix("Replace with renamed Spring Boot 4 starter",
                            Pattern.compile(rename.oldText()), rename.newText()))
                    .toArray(LocalQuickFix[]::new);
            return new CompositeFix("Replace with renamed Spring Boot 4 starter", fixes);
        }

        /**
         * Only jackson-databind / jackson-core move to tools.jackson.core;
         * jackson-annotations keeps its coordinates in Jackson 3, so the
         * replacement is anchored to the neighboring artifact.
         */
        private static LocalQuickFix jacksonCoordinatesFix() {
            Pattern maven = Pattern.compile(
                    "<groupId>com\\.fasterxml\\.jackson\\.core</groupId>(?=\\s*<artifactId>jackson-(?:databind|core)</artifactId>)");
            Pattern gradle = Pattern.compile("com\\.fasterxml\\.jackson\\.core(?=:jackson-(?:databind|core))");
            return new CompositeFix("Replace Jackson 2 core coordinates with Jackson 3",
                    new RegexReplaceFix("Replace Jackson 2 core groupId with tools.jackson.core", maven,
                            "<groupId>tools.jackson.core</groupId>"),
                    new RegexReplaceFix("Replace Jackson 2 core notation with tools.jackson.core", gradle,
                            "tools.jackson.core"));
        }

        /**
         * {@code spring.jackson.parser/generator} are only partially replaced by
         * {@code spring.jackson.json.read/write} (some features need a
         * {@code JsonMapperBuilderCustomizer}), so they are reported without a fix.
         */
        @Nullable
        private static LocalQuickFix renamedPropertyFix(String text) {
            if (text.contains("spring.jackson.read.") || text.contains("spring.jackson.write.")) {
                return new ReplaceFileTextFix("Move spring.jackson.read/write under spring.jackson.json",
                        List.of(replacement("spring.jackson.read.", "spring.jackson.json.read."),
                                replacement("spring.jackson.write.", "spring.jackson.json.write.")));
            }
            if (text.contains("spring.dao.exceptiontranslation.enabled")) {
                return replaceFix("Rename spring.dao.exceptiontranslation.enabled",
                        "spring.dao.exceptiontranslation.enabled",
                        "spring.persistence.exceptiontranslation.enabled");
            }
            if (text.contains("management.tracing.enabled")) {
                return replaceFix("Rename management.tracing.enabled", "management.tracing.enabled",
                        "management.tracing.export.enabled");
            }
            if (text.contains("spring.flyway.clean-on-validation-error")) {
                return new DeleteMatchingLinesFix("Delete removed Flyway property",
                        "spring.flyway.clean-on-validation-error");
            }
            return null;
        }

        private static LocalQuickFix replaceFix(String name, String oldText, String newText) {
            return new ReplaceFileTextFix(name, List.of(replacement(oldText, newText)));
        }
    }

    private record Replacement(String oldText, String newText) {
    }

    private static Replacement replacement(String oldText, String newText) {
        return new Replacement(oldText, newText);
    }

    private static final class CompositeFix implements LocalQuickFix {

        private final String name;

        private final List<LocalQuickFix> fixes;

        private CompositeFix(String name, LocalQuickFix... fixes) {
            this.name = name;
            this.fixes = List.of(fixes);
        }

        @Override
        public @NotNull String getName() {
            return this.name;
        }

        @Override
        public @NotNull String getFamilyName() {
            return this.name;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            for (LocalQuickFix fix : this.fixes) {
                fix.applyFix(project, descriptor);
            }
        }
    }

    private static final class RegexReplaceFix implements LocalQuickFix {

        private final String name;

        private final Pattern pattern;

        private final String replacement;

        private RegexReplaceFix(String name, Pattern pattern, String replacement) {
            this.name = name;
            this.pattern = pattern;
            this.replacement = replacement;
        }

        @Override
        public @NotNull String getName() {
            return this.name;
        }

        @Override
        public @NotNull String getFamilyName() {
            return this.name;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement().getContainingFile();
            replaceFileText(project, file, this.pattern.matcher(file.getText()).replaceAll(this.replacement));
        }
    }

    private static final class ReplaceFileTextFix implements LocalQuickFix {

        private final String name;

        private final List<Replacement> replacements;

        private ReplaceFileTextFix(String name, List<Replacement> replacements) {
            this.name = name;
            this.replacements = replacements;
        }

        @Override
        public @NotNull String getName() {
            return this.name;
        }

        @Override
        public @NotNull String getFamilyName() {
            return this.name;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement().getContainingFile();
            String text = file.getText();
            for (Replacement replacement : this.replacements) {
                text = text.replace(replacement.oldText(), replacement.newText());
            }
            replaceFileText(project, file, text);
        }
    }

    private static final class DeleteMatchingLinesFix implements LocalQuickFix {

        private final String name;

        private final String marker;

        private DeleteMatchingLinesFix(String name, String marker) {
            this.name = name;
            this.marker = marker;
        }

        @Override
        public @NotNull String getName() {
            return this.name;
        }

        @Override
        public @NotNull String getFamilyName() {
            return this.name;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement().getContainingFile();
            StringBuilder result = new StringBuilder();
            for (String line : file.getText().split("\\R", -1)) {
                if (!line.contains(this.marker)) {
                    result.append(line).append("\n");
                }
            }
            replaceFileText(project, file, result.toString());
        }
    }

    private static void replaceFileText(Project project, PsiFile file, String text) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document document = PsiDocumentManager.getInstance(project).getDocument(file);
            if (document != null) {
                document.setText(text);
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }
        });
    }

    public static class UndertowStarter extends SpringBoot4FileMigrationInspection {
        public UndertowStarter() {
            super(Kind.UNDERTOW_STARTER);
        }
    }

    public static class RenamedStarter extends SpringBoot4FileMigrationInspection {
        public RenamedStarter() {
            super(Kind.RENAMED_STARTER);
        }
    }

    public static class JacksonCoordinates extends SpringBoot4FileMigrationInspection {
        public JacksonCoordinates() {
            super(Kind.JACKSON_COORDINATES);
        }
    }

    public static class RenamedProperties extends SpringBoot4FileMigrationInspection {
        public RenamedProperties() {
            super(Kind.RENAMED_PROPERTIES);
        }
    }

    public static class BuildToolVersion extends SpringBoot4FileMigrationInspection {
        public BuildToolVersion() {
            super(Kind.BUILD_TOOL_VERSION);
        }
    }

    public static class TestStarterModules extends SpringBoot4FileMigrationInspection {
        public TestStarterModules() {
            super(Kind.TEST_STARTER_MODULES);
        }
    }

    public static class HibernateProcessor extends SpringBoot4FileMigrationInspection {
        public HibernateProcessor() {
            super(Kind.HIBERNATE_PROCESSOR);
        }
    }

    public static class HibernateArtifacts extends SpringBoot4FileMigrationInspection {
        public HibernateArtifacts() {
            super(Kind.HIBERNATE_ARTIFACTS);
        }
    }

    public static class UnmanagedDependency extends SpringBoot4FileMigrationInspection {
        public UnmanagedDependency() {
            super(Kind.UNMANAGED_DEPENDENCY);
        }
    }

    public static class RemovedBuildFeature extends SpringBoot4FileMigrationInspection {
        public RemovedBuildFeature() {
            super(Kind.REMOVED_BUILD_FEATURE);
        }
    }
}
