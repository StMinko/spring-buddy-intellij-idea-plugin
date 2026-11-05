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

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.assertj.core.api.Assertions;

/**
 * Tests for {@link SpringBoot4FileMigrationInspection}. The light test project
 * is reused between tests, so the Boot target is reset in {@link #tearDown()}.
 *
 * @author Akmal Sarhan
 */
public class SpringBoot4FileMigrationInspectionTests extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        SpringBoot4TestSupport.setBoot4(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        SpringBoot4TestSupport.reset(getProject());
        super.tearDown();
    }

    public void testUndertowStarter_reportsPomDependency() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-undertow</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.UndertowStarter(), pom))
                .as("Undertow starter flagged")
                .isEqualTo(1);
    }

    public void testUndertowStarter_reportsInAutoMode() {
        SpringBoot4TestSupport.reset(getProject());
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-undertow</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.UndertowStarter(), pom))
                .as("auto-detect keeps the migration pre-flight active")
                .isEqualTo(1);
    }

    public void testRenamedStarter_silentOnBoot3Target() {
        SpringBoot4TestSupport.setBoot3(getProject());
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RenamedStarter(), pom))
                .as("Boot 4 file inspection stays silent on Boot 3 target")
                .isZero();
    }

    public void testRenamedStarter_reportsOldStarter() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RenamedStarter(), pom))
                .as("renamed starter flagged")
                .isEqualTo(1);
    }

    public void testRenamedStarter_ignoredWhenAlreadyWebmvc() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-webmvc</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RenamedStarter(), pom))
                .as("already renamed starter not flagged")
                .isZero();
    }

    public void testRenamedStarter_quickFixShieldsExistingWebmvc() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-webmvc</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>spring-boot-starter-aop</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        applyFix(new SpringBoot4FileMigrationInspection.RenamedStarter(), pom);

        Assertions.assertThat(pom.getText())
                .as("existing webmvc stays intact, aop renamed once")
                .contains("spring-boot-starter-webmvc")
                .contains("spring-boot-starter-aspectj")
                .doesNotContain("spring-boot-starter-aop")
                .doesNotContain("webmvcmvc");
    }

    public void testRenamedStarter_webfluxAndWebmvcAreIgnored() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-webflux</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>spring-boot-starter-webmvc</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RenamedStarter(), pom))
                .as("webflux and webmvc are not the old web starter")
                .isZero();
    }

    public void testRenamedStarter_quickFixRenamesExactArtifactsOnly() {
        PsiFile build = this.myFixture.addFileToProject("build.gradle", """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    implementation 'org.springframework.boot:spring-boot-starter-webflux'
                    implementation 'org.springframework.boot:spring-boot-starter-web-services'
                    implementation 'org.springframework.boot:spring-boot-starter-oauth2-authorization-server'
                }
                """);

        applyFix(new SpringBoot4FileMigrationInspection.RenamedStarter(), build);

        Assertions.assertThat(build.getText())
                .as("each starter renamed to its exact Boot 4 artifact")
                .contains("'org.springframework.boot:spring-boot-starter-webmvc'")
                .contains("'org.springframework.boot:spring-boot-starter-webflux'")
                .contains("'org.springframework.boot:spring-boot-starter-webservices'")
                .contains("'org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server'")
                .doesNotContain("webmvcflux");
    }

    public void testTestStarterModules_webfluxDoesNotRequireWebmvcTest() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-test</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>spring-boot-starter-webflux</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.TestStarterModules(), pom))
                .as("webflux project is not told to add webmvc-test")
                .isZero();
    }

    public void testJacksonCoordinates_quickFixAnchorsToArtifact() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-annotations</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        applyFix(new SpringBoot4FileMigrationInspection.JacksonCoordinates(), pom);

        Assertions.assertThat(pom.getText())
                .as("jackson.core groupId only replaced for databind/core artifacts")
                .contains("<groupId>com.fasterxml.jackson.core</groupId>\n            <artifactId>jackson-annotations</artifactId>")
                .contains("<groupId>tools.jackson.core</groupId>\n            <artifactId>jackson-databind</artifactId>")
                .doesNotContain("com.fasterxml.jackson.core</groupId>\n            <artifactId>jackson-databind");
    }

    public void testRenamedProperties_reportsAndFixesReadWrite() {
        PsiFile properties = this.myFixture.configureByText("application.properties",
                "spring.jackson.read.allow-comments=true\nspring.jackson.write.indent-output=true\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RenamedProperties(), properties))
                .as("renamed property flagged")
                .isEqualTo(1);

        applyFix(new SpringBoot4FileMigrationInspection.RenamedProperties(), properties);

        Assertions.assertThat(properties.getText())
                .as("read/write moved under spring.jackson.json")
                .contains("spring.jackson.json.read.allow-comments=true")
                .contains("spring.jackson.json.write.indent-output=true");
    }

    public void testRenamedProperties_parserReportedWithoutFix() {
        PsiFile properties = this.myFixture.configureByText("application.properties",
                "spring.jackson.parser.allow-comments=true\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RenamedProperties(), properties))
                .as("partially replaced parser property flagged")
                .isEqualTo(1);
        Assertions.assertThat(new SpringBoot4FileMigrationInspection.RenamedProperties().quickFix(properties))
                .as("no blanket rename for partially replaced property")
                .isNull();
    }

    public void testBuildToolVersion_reportsOldGradle() {
        PsiFile wrapper = this.myFixture.configureByText("gradle-wrapper.properties",
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.BuildToolVersion(), wrapper))
                .as("Gradle < 8.14 flagged")
                .isEqualTo(1);
    }

    public void testBuildToolVersion_newGradleIsSilent() {
        PsiFile wrapper = this.myFixture.configureByText("gradle-wrapper.properties",
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.BuildToolVersion(), wrapper))
                .as("Gradle 8.14 satisfies requirement")
                .isZero();
    }

    public void testTestStarterModules_reportsMissingModularTestStarter() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>spring-boot-starter-test</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.TestStarterModules(), pom))
                .as("missing per-technology test starter flag")
                .isEqualTo(1);
    }

    public void testHibernateProcessor_reportsAndFixesJpamodelgen() {
        PsiFile build = this.myFixture.addFileToProject("build.gradle", """
                dependencies {
                    annotationProcessor 'org.hibernate.orm:hibernate-jpamodelgen'
                }
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.HibernateProcessor(), build))
                .as("jpamodelgen flagged")
                .isEqualTo(1);

        applyFix(new SpringBoot4FileMigrationInspection.HibernateProcessor(), build);

        Assertions.assertThat(build.getText())
                .as("artifact replaced by hibernate-processor")
                .contains("'org.hibernate.orm:hibernate-processor'");
    }

    public void testHibernateProcessor_processorIsIgnored() {
        PsiFile build = this.myFixture.addFileToProject("build.gradle",
                "dependencies { annotationProcessor 'org.hibernate.orm:hibernate-processor' }\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.HibernateProcessor(), build))
                .as("already migrated")
                .isZero();
    }

    public void testUnmanagedDependency_reportsUnversionedMavenRetry() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.retry</groupId>
                            <artifactId>spring-retry</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.UnmanagedDependency(), pom))
                .as("spring-retry without version")
                .isEqualTo(1);
    }

    public void testUnmanagedDependency_versionedRetryIsIgnored() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.retry</groupId>
                            <artifactId>spring-retry</artifactId>
                            <version>2.0.12</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        PsiFile build = this.myFixture.addFileToProject("build.gradle",
                "dependencies { implementation 'org.springframework.retry:spring-retry:2.0.12' }\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.UnmanagedDependency(), pom)
                        + checkFile(new SpringBoot4FileMigrationInspection.UnmanagedDependency(), build))
                .as("explicit versions are fine")
                .isZero();
    }

    public void testUnmanagedDependency_reportsAuthorizationServerVersionProperty() {
        PsiFile properties = this.myFixture.configureByText("gradle.properties",
                "spring-authorization-server.version=1.5.0\n");

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.UnmanagedDependency(), properties))
                .as("authorization server version property no longer honoured")
                .isEqualTo(1);
    }

    public void testRemovedBuildFeature_reportsLaunchScriptAndSpock() {
        PsiFile build = this.myFixture.addFileToProject("build.gradle", """
                bootJar {
                    launchScript()
                }
                """);
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.spockframework</groupId>
                            <artifactId>spock-spring</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RemovedBuildFeature(), build)
                        + checkFile(new SpringBoot4FileMigrationInspection.RemovedBuildFeature(), pom))
                .as("launch script and Spock reported")
                .isEqualTo(2);
    }

    public void testRemovedBuildFeature_reportsMavenExecutableJar() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <configuration>
                                    <executable>true</executable>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RemovedBuildFeature(), pom))
                .as("fully executable jar reported")
                .isEqualTo(1);
    }

    public void testRemovedBuildFeature_plainBootJarIsIgnored() {
        PsiFile build = this.myFixture.addFileToProject("build.gradle", """
                dependencies {
                    implementation 'org.springframework.session:spring-session-data-redis'
                }
                bootJar { archiveFileName = 'app.jar' }
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.RemovedBuildFeature(), build))
                .as("supported session store and plain bootJar")
                .isZero();
    }

    public void testHibernateArtifacts_reportsAndFixesJacksonDatatypeAndHypersistence() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.datatype</groupId>
                            <artifactId>jackson-datatype-hibernate5</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>io.hypersistence</groupId>
                            <artifactId>hypersistence-utils-hibernate-63</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>tools.jackson.datatype</groupId>
                            <artifactId>jackson-datatype-hibernate7</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.HibernateArtifacts(), pom))
                .as("old Hibernate 6/Jackson 2 coordinates flagged")
                .isEqualTo(1);

        applyFix(new SpringBoot4FileMigrationInspection.HibernateArtifacts(), pom);

        Assertions.assertThat(pom.getText())
                .as("artifact and coordinates migrated")
                .contains("<artifactId>jackson-datatype-hibernate7</artifactId>")
                .contains("<groupId>tools.jackson.datatype</groupId>")
                .contains("hypersistence-utils-hibernate-71");
    }

    public void testHibernateArtifacts_fixesGradleNotation() {
        PsiFile build = this.myFixture.addFileToProject("build.gradle", """
                dependencies {
                    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-hibernate6'
                    implementation 'io.hypersistence:hypersistence-utils-hibernate-63'
                }
                """);

        applyFix(new SpringBoot4FileMigrationInspection.HibernateArtifacts(), build);

        Assertions.assertThat(build.getText())
                .as("gradle notation migrated")
                .contains("'tools.jackson.datatype:jackson-datatype-hibernate7'")
                .contains("'io.hypersistence:hypersistence-utils-hibernate-71'");
    }

    public void testHibernateArtifacts_silentOnAlreadyMigratedCoordinates() {
        PsiFile pom = this.myFixture.addFileToProject("pom.xml", """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>tools.jackson.datatype</groupId>
                            <artifactId>jackson-datatype-hibernate7</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>io.hypersistence</groupId>
                            <artifactId>hypersistence-utils-hibernate-71</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Assertions.assertThat(checkFile(new SpringBoot4FileMigrationInspection.HibernateArtifacts(), pom))
                .as("already migrated coordinates are not flagged")
                .isZero();
    }

    private int checkFile(SpringBoot4FileMigrationInspection inspection, PsiFile file) {
        InspectionManager manager = InspectionManager.getInstance(getProject());
        ProblemDescriptor[] descriptors = inspection.checkFile(file, manager, true);
        return (descriptors == null) ? 0 : descriptors.length;
    }

    private void applyFix(SpringBoot4FileMigrationInspection inspection, PsiFile file) {
        LocalQuickFix fix = inspection.quickFix(file);
        Assertions.assertThat(fix)
                .as("inspection offers a quick fix")
                .isNotNull();
        InspectionManager manager = InspectionManager.getInstance(getProject());
        ProblemDescriptor descriptor = manager.createProblemDescriptor(file,
                TextRange.from(0, Math.min(1, file.getTextLength())), "",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, true);
        fix.applyFix(getProject(), descriptor);
    }
}
