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

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.roots.LanguageLevelProjectExtension;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.assertj.core.api.Assertions;

/**
 * Tests for {@link SpringBoot4MigrationInspection}. The light test project is
 * reused between tests, so the Boot target is reset in {@link #tearDown()}.
 *
 * @author Akmal Sarhan
 */
public class SpringBoot4MigrationInspectionTests extends BasePlatformTestCase {

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

    public void testMockBean_reportsRemovedAnnotation() {
        this.myFixture.addFileToProject("org/springframework/boot/test/mock/mockito/MockBean.java",
                "package org.springframework.boot.test.mock.mockito;\npublic @interface MockBean { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.mock.mockito.MockBean;
                import org.example.Service;

                class Sample {
                    @MockBean
                    Service service;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed in Spring Boot 4"))
                .as("field-level @MockBean report and import report")
                .isEqualTo(2);
    }

    public void testMockBean_lookalikeShortNameIsIgnored() {
        this.myFixture.addFileToProject("org/example/MockBean.java",
                "package org.example;\npublic @interface MockBean { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean());
        this.myFixture.configureByText("Sample.java", """
                import org.example.MockBean;

                class Sample {
                    @MockBean
                    String name;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed in Spring Boot 4"))
                .as("unrelated MockBean annotation")
                .isZero();
    }

    public void testMockBean_quickFixReplacesAnnotation() {
        this.myFixture.addFileToProject("org/springframework/boot/test/mock/mockito/MockBean.java",
                "package org.springframework.boot.test.mock.mockito;\npublic @interface MockBean {\n"
                        + "    String name() default \"\";\n}\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.mock.mockito.MockBean;

                class Sample {
                    @<caret>MockBean(name = "svc")
                    String service;
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with @MockitoBean"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("migrated MockitoBean annotation keeps name attribute")
                .contains("MockitoBean")
                .contains("(name = \"svc\")");
    }

    public void testMockBean_targetBoot3IsSilent() {
        SpringBoot4TestSupport.setBoot3(getProject());
        this.myFixture.addFileToProject("org/springframework/boot/test/mock/mockito/MockBean.java",
                "package org.springframework.boot.test.mock.mockito;\npublic @interface MockBean { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.mock.mockito.MockBean;

                class Sample {
                    @MockBean
                    String service;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed in Spring Boot 4"))
                .as("Boot 4 inspection must stay silent on Boot 3 target")
                .isZero();
    }

    public void testJacksonPackage_reportsDatabindImport() {
        this.myFixture.addFileToProject("com/fasterxml/jackson/databind/ObjectMapper.java",
                "package com.fasterxml.jackson.databind;\npublic class ObjectMapper { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonPackage());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    ObjectMapper mapper;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "tools.jackson"))
                .as("Jackson 2 databind import")
                .isEqualTo(1);
    }

    public void testJacksonPackage_jacksonAnnotationsIsIgnored() {
        this.myFixture.addFileToProject("com/fasterxml/jackson/annotation/JsonIgnore.java",
                "package com.fasterxml.jackson.annotation;\npublic @interface JsonIgnore { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonPackage());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.annotation.JsonIgnore;

                class Sample {
                    @JsonIgnore
                    String name;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "tools.jackson"))
                .as("jackson.annotation package did not move")
                .isZero();
    }

    public void testJacksonRenamedType_reportsType() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonRenamedType());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.JsonSerializer;

                class Sample {
                    JsonSerializer serializer;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed in Jackson 3"))
                .as("renamed Jackson type import and reference")
                .isGreaterThanOrEqualTo(1);
    }

    public void testBootJacksonApi_reportsRenamedAnnotation() {
        this.myFixture.addFileToProject("org/springframework/boot/jackson/JsonComponent.java",
                "package org.springframework.boot.jackson;\npublic @interface JsonComponent { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.BootJacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.jackson.JsonComponent;

                @<caret>JsonComponent
                class Sample { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed in Spring Boot 4"))
                .as("Boot Jackson rename for annotation and import")
                .isEqualTo(2);
    }

    public void testListenableFuture_reportsRemovedType() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.ListenableFuture());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.util.concurrent.ListenableFuture;

                class Sample {
                    ListenableFuture<String> future() { return null; }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "ListenableFuture API was removed"))
                .as("removed ListenableFuture import")
                .isGreaterThanOrEqualTo(1);
    }

    public void testSecurityAndChain_reportsRemovedAndMethod() {
        this.myFixture.addFileToProject("org/springframework/security/config/annotation/web/Configurer.java",
                """
                        package org.springframework.security.config.annotation.web;
                        public class Configurer {
                            public Configurer and() { return this; }
                        }
                        """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SecurityAndChain());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.security.config.annotation.web.Configurer;

                class Sample {
                    void configure(Configurer configurer) {
                        configurer.<caret>and();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "and() was removed"))
                .as("removed and() on Security configurer")
                .isEqualTo(1);
    }

    public void testSecurityAndChain_ignoresUnresolvedAndMethod() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SecurityAndChain());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    void configure(Object other) {
                        other.and();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "and() was removed"))
                .as("unresolved receiver")
                .isZero();
    }

    public void testSecurityNonLambdaDsl_reportsZeroArgConfigurer() {
        this.myFixture.addFileToProject("org/springframework/security/config/annotation/web/builders/HttpSecurity.java",
                """
                        package org.springframework.security.config.annotation.web.builders;
                        public class HttpSecurity {
                            public HttpSecurity csrf() { return this; }
                            public HttpSecurity csrf(Customizer customizer) { return this; }
                        }
                        class Customizer { }
                        """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SecurityNonLambdaDsl());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;

                class Sample {
                    void configure(HttpSecurity http) {
                        http.<caret>csrf();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "without a Customizer argument"))
                .as("zero-arg csrf DSL call")
                .isEqualTo(1);
    }

    public void testSecurityNonLambdaDsl_lambaVariantIsIgnored() {
        this.myFixture.addFileToProject("org/springframework/security/config/annotation/web/builders/HttpSecurity.java",
                """
                        package org.springframework.security.config.annotation.web.builders;
                        public class HttpSecurity {
                            public HttpSecurity csrf(Customizer customizer) { return this; }
                        }
                        class Customizer { }
                        """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SecurityNonLambdaDsl());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;

                class Sample {
                    void configure(HttpSecurity http, Customizer customizer) {
                        http.csrf(customizer);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "without a Customizer argument"))
                .as("lambda DSL variant already present")
                .isZero();
    }

    public void testAntPathRequestMatcher_reportsRemovedType() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.AntPathRequestMatcher());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

                class Sample {
                    AntPathRequestMatcher matcher;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Spring Security 7"))
                .as("removed AntPathRequestMatcher import")
                .isGreaterThanOrEqualTo(1);
    }

    public void testSpringLangNullability_reportsNullableWithFix() {
        this.myFixture.addFileToProject("org/springframework/lang/Nullable.java",
                "package org.springframework.lang;\npublic @interface Nullable { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SpringLangNullability());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.lang.Nullable;

                class Sample {
                    @<caret>Nullable
                    String name;
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with JSpecify annotation"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("JSpecify annotation replacement")
                .contains("org.jspecify.annotations.Nullable");
    }

    public void testSpringLangNullability_jetbrainsNullableIsIgnored() {
        this.myFixture.addFileToProject("org/jetbrains/annotations/Nullable.java",
                "package org.jetbrains.annotations;\npublic @interface Nullable { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SpringLangNullability());
        this.myFixture.configureByText("Sample.java", """
                import org.jetbrains.annotations.Nullable;

                class Sample {
                    @Nullable
                    String name;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Spring Framework 7"))
                .as("JetBrains Nullable is unrelated")
                .isZero();
    }

    public void testRelocatedBootType_reportsRelocatedAnnotation() {
        this.myFixture.addFileToProject(
                "org/springframework/boot/test/autoconfigure/web/servlet/WebMvcTest.java",
                """
                        package org.springframework.boot.test.autoconfigure.web.servlet;
                        public @interface WebMvcTest { }
                        """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest
                class Sample { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "relocated by the Boot 4 modularisation"))
                .as("relocated @WebMvcTest annotation and import")
                .isEqualTo(2);
    }

    public void testUndertow_reportsEmbeddedUndertowType() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.Undertow());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;

                class Sample {
                    UndertowServletWebServerFactory factory;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Undertow is no longer a supported embedded server"))
                .as("Undertow import")
                .isEqualTo(1);
    }

    public void testUndertow_reportsInAutoModeOnBoot3Project() {
        SpringBoot4TestSupport.reset(getProject());
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.Undertow());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;

                class Sample {
                    UndertowServletWebServerFactory factory;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Undertow is no longer a supported embedded server"))
                .as("auto-detect keeps the migration pre-flight active on a Boot 3 classpath")
                .isEqualTo(1);
    }

    public void testUndertow_explicitBoot3TargetIsSilent() {
        SpringBoot4TestSupport.setBoot3(getProject());
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.Undertow());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;

                class Sample {
                    UndertowServletWebServerFactory factory;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Undertow is no longer a supported embedded server"))
                .as("explicit Boot 3 target opts out of the migration checks")
                .isZero();
    }

    public void testPathMatchSuffix_reportsRemovedSetter() {
        this.myFixture.addFileToProject(
                "org/springframework/web/servlet/config/annotation/PathMatchConfigurer.java",
                """
                        package org.springframework.web.servlet.config.annotation;
                        public class PathMatchConfigurer {
                            public PathMatchConfigurer setUseSuffixPatternMatch(boolean value) { return this; }
                        }
                        """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.PathMatchSuffix());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;

                class Sample {
                    void configure(PathMatchConfigurer configurer) {
                        configurer.<caret>setUseSuffixPatternMatch(true);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "was removed in Spring Framework 7"))
                .as("removed PathMatchConfigurer setter")
                .isEqualTo(1);
    }

    public void testHibernateRemovedAnnotation_reportsWhereAnnotation() {
        this.myFixture.addFileToProject("org/hibernate/annotations/Where.java",
                "package org.hibernate.annotations;\npublic @interface Where { String clause() default \"\"; }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.HibernateRemovedAnnotation());
        this.myFixture.configureByText("Sample.java", """
                import org.hibernate.annotations.Where;

                @interface Entity { }

                @Entity
                class Sample {
                    @<caret>Where(clause = "deleted = false")
                    String name;
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("@Where(clause = ...) to @SQLRestriction"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("SQLRestriction replacement keeps clause")
                .contains("@org.hibernate.annotations.SQLRestriction(\"deleted = false\")");
    }

    public void testHibernateRemovedAnnotation_whereLookalikeIsIgnored() {
        this.myFixture.addFileToProject("org/example/annotations/Where.java",
                "package org.example.annotations;\npublic @interface Where { String clause() default \"\"; }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.HibernateRemovedAnnotation());
        this.myFixture.configureByText("Sample.java", """
                import org.example.annotations.Where;

                class Sample {
                    @Where(clause = "x")
                    String name;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed in Hibernate 7"))
                .as("non-Hibernate @Where lookalike")
                .isZero();
    }

    public void testJunit4Runner_reportsSpringRunner() {
        this.myFixture.addFileToProject("org/springframework/test/context/junit4/SpringRunner.java",
                "package org.springframework.test.context.junit4;\npublic class SpringRunner { }\n");
        this.myFixture.addFileToProject("org/example/OtherRunner.java",
                "package org.example;\npublic class OtherRunner { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.Junit4Runner());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.test.context.junit4.SpringRunner;

                @interface RunWith { Class<?> value(); }

                @RunWith(SpringRunner.class)
                class Sample { }

                @RunWith(org.example.OtherRunner.class)
                class Other { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "JUnit 4 runner is deprecated"))
                .as("Spring runner import and annotation reported, other runner not")
                .isEqualTo(2);
    }

    public void testRelocatedBootType_autoConfigureMockMvcMovesHtmlUnitFlags() {
        this.myFixture.addFileToProject(
                "org/springframework/boot/test/autoconfigure/web/servlet/AutoConfigureMockMvc.java",
                """
                        package org.springframework.boot.test.autoconfigure.web.servlet;
                        public @interface AutoConfigureMockMvc {
                            boolean addFilters() default true;
                            boolean webClientEnabled() default true;
                            boolean webDriverEnabled() default true;
                        }
                        """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

                @<caret>AutoConfigureMockMvc(addFilters = false, webClientEnabled = false, webDriverEnabled = false)
                class Sample { }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with relocated annotation"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("HtmlUnit flags moved under htmlUnit attribute")
                .contains("addFilters = false")
                .contains("htmlUnit = @org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc"
                        + ".HtmlUnit(webClient = false, webDriver = false)")
                .doesNotContain("webClientEnabled");
    }

    public void testRelocatedBootType_bootstrapRegistryReported() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.BootstrapRegistry;

                class Sample {
                    BootstrapRegistry registry;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "relocated by the Boot 4 modularisation"))
                .as("BootstrapRegistry moved to org.springframework.boot.bootstrap")
                .isEqualTo(1);
    }

    public void testRelocatedBootType_environmentPostProcessorIsWarning() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.env.EnvironmentPostProcessor;

                class Sample { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(highlights)
                .as("deprecated EnvironmentPostProcessor reported as warning")
                .anyMatch((HighlightInfo h) -> h.getDescription() != null
                        && h.getDescription().contains("deprecated in Boot 4")
                        && h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.WARNING);
    }

    public void testRelocatedBootType_skipPropertyMappingReportedWithoutFix() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.autoconfigure.properties.<caret>SkipPropertyMapping;

                class Sample { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "now org.springframework.boot.test.context.PropertyMapping.Skip"))
                .as("renamed nested enum reported with target")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Replace with relocated type"))
                .as("simple name changes, so no import swap fix")
                .isEmpty();
    }

    public void testMockBean_classLevelBecomesMockitoBeanTypes() {
        this.myFixture.addFileToProject("org/springframework/boot/test/mock/mockito/MockBean.java",
                "package org.springframework.boot.test.mock.mockito;\npublic @interface MockBean {\n"
                        + "    Class<?>[] value() default {};\n}\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                }

                @<caret>org.springframework.boot.test.mock.mockito.MockBean({String.class, Integer.class})
                class Other { }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with @MockitoBean"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("class-level classes mapped to types attribute")
                .contains("MockitoBean(types = {String.class, Integer.class})");
    }

    public void testMockBean_fieldFixKeepsResetAndAnswer() {
        this.myFixture.addFileToProject("org/springframework/boot/test/mock/mockito/MockBean.java",
                "package org.springframework.boot.test.mock.mockito;\npublic @interface MockBean {\n"
                        + "    MockReset reset() default MockReset.AFTER;\n    String answer() default \"\";\n}\n");
        this.myFixture.addFileToProject("org/springframework/boot/test/mock/mockito/MockReset.java",
                "package org.springframework.boot.test.mock.mockito;\npublic enum MockReset { BEFORE, AFTER, NONE }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.test.mock.mockito.MockBean;
                import org.springframework.boot.test.mock.mockito.MockReset;

                class Sample {
                    @<caret>MockBean(reset = MockReset.BEFORE, answer = "x")
                    String service;
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with @MockitoBean"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("reset and answer carried over")
                .contains("reset = org.springframework.test.context.bean.override.mockito.MockReset.BEFORE")
                .contains("answers = \"x\"");
    }

    public void testJacksonPackage_wildcardImportReported() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonPackage());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.*;

                class Sample { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "tools.jackson"))
                .as("on-demand databind import")
                .isEqualTo(1);
    }

    public void testJacksonPackage_datatypeModuleReportedWithoutFix() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonPackage());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.datatype.jsr310.<caret>JavaTimeModule;

                class Sample { }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Jackson 2 modules moved"))
                .as("jsr310 module import")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Replace with tools.jackson import"))
                .as("module packages have no mechanical replacement")
                .isEmpty();
    }

    public void testJacksonRenamedType_genericQualifiedReferenceReported() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonRenamedType());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    com.fasterxml.jackson.databind.JsonSerializer<String> serializer;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed in Jackson 3"))
                .as("qualified generic reference matched without type arguments")
                .isEqualTo(1);
    }

    public void testSpringBootTestAutoConfigure_reportsMockMvcAndAddsAnnotation() {
        addSpringBootTestStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SpringBootTestAutoConfigure());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;

                @SpringBootTest
                class SampleTests {
                    @Autowired
                    MockMvc <caret>mockMvc;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        Assertions.assertThat(countDescriptions(highlights, "no longer configures MockMvc"))
                .as("MockMvc injected without @AutoConfigureMockMvc")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Add @AutoConfigureMockMvc"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("annotation added and imported")
                .contains("import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;")
                .contains("@AutoConfigureMockMvc");
    }

    public void testSpringBootTestAutoConfigure_reportsTestRestTemplate() {
        addSpringBootTestStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SpringBootTestAutoConfigure());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.resttestclient.TestRestTemplate;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class SampleTests {
                    @Autowired
                    TestRestTemplate restTemplate;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "add @AutoConfigureTestRestTemplate"))
                .as("TestRestTemplate injected without @AutoConfigureTestRestTemplate")
                .isEqualTo(1);
    }

    public void testSpringBootTestAutoConfigure_annotatedOrNonSpringBootTestIsIgnored() {
        addSpringBootTestStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SpringBootTestAutoConfigure());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
                import org.springframework.test.web.servlet.MockMvc;

                @SpringBootTest
                @AutoConfigureMockMvc
                class SampleTests {
                    @Autowired
                    MockMvc mockMvc;
                }

                class NotSpringBootTests {
                    @Autowired
                    MockMvc mockMvc;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "no longer configures"))
                .as("already annotated and non-@SpringBootTest classes")
                .isZero();
    }

    public void testMockitoAnnotations_reportsMockInSpringTestAndAddsExtension() {
        addMockitoStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockitoAnnotations());
        this.myFixture.configureByText("SampleTests.java", """
                import org.mockito.Mock;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class SampleTests {
                    @Mock
                    Runnable <caret>task;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        Assertions.assertThat(countDescriptions(highlights, "MockitoTestExecutionListener was removed"))
                .as("@Mock without MockitoExtension")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Add @ExtendWith(MockitoExtension.class)"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("MockitoExtension registered")
                .contains("@ExtendWith(MockitoExtension.class)")
                .contains("import org.mockito.junit.jupiter.MockitoExtension;");
    }

    public void testMockitoAnnotations_extensionOrOpenMocksIsIgnored() {
        addMockitoStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockitoAnnotations());
        this.myFixture.configureByText("SampleTests.java", """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith(MockitoExtension.class)
                class SampleTests {
                    @Mock
                    Runnable task;
                }

                @SpringBootTest
                class OpenMocksTests {
                    @Mock
                    Runnable task;

                    void setUp() {
                        MockitoAnnotations.openMocks(this);
                    }
                }

                class PlainTests {
                    @Mock
                    Runnable task;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "MockitoTestExecutionListener was removed"))
                .as("Mockito initialised or not a Spring test")
                .isZero();
    }

    public void testObjectMapperBean_reportsObjectMapperButNotJsonMapper() {
        addBeanStub();
        this.myFixture.addFileToProject("com/fasterxml/jackson/databind/ObjectMapper.java",
                "package com.fasterxml.jackson.databind;\npublic class ObjectMapper { }\n");
        this.myFixture.addFileToProject("tools/jackson/databind/json/JsonMapper.java",
                "package tools.jackson.databind.json;\npublic class JsonMapper { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.ObjectMapperBean());
        this.myFixture.configureByText("JacksonConfig.java", """
                import com.fasterxml.jackson.databind.ObjectMapper;
                import org.springframework.context.annotation.Bean;
                import tools.jackson.databind.json.JsonMapper;

                class JacksonConfig {
                    @Bean
                    ObjectMapper objectMapper() {
                        return new ObjectMapper();
                    }

                    @Bean
                    JsonMapper jsonMapper() {
                        return new JsonMapper();
                    }

                    ObjectMapper notABean() {
                        return new ObjectMapper();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "ObjectMapper bean no longer replaces"))
                .as("only the ObjectMapper @Bean")
                .isEqualTo(1);
    }

    public void testHttpMessageConverterBean_reportsConverterSubtype() {
        addBeanStub();
        this.myFixture.addFileToProject("org/springframework/http/converter/HttpMessageConverter.java",
                "package org.springframework.http.converter;\npublic interface HttpMessageConverter<T> { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.HttpMessageConverterBean());
        this.myFixture.configureByText("WebConfig.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.http.converter.HttpMessageConverter;

                class CsvConverter implements HttpMessageConverter<String> { }

                class WebConfig {
                    @Bean
                    CsvConverter csvConverter() {
                        return new CsvConverter();
                    }

                    @Bean
                    String notAConverter() {
                        return "";
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "HttpMessageConverter beans are no longer picked up"))
                .as("converter subtype bean reported once")
                .isEqualTo(1);
    }

    public void testActuatorSpringLangNullable_reportsAndReplacesWithJSpecify() {
        addSpringLangNullableStub();
        this.myFixture.addFileToProject("org/springframework/boot/actuate/endpoint/annotation/ReadOperation.java",
                "package org.springframework.boot.actuate.endpoint.annotation;\npublic @interface ReadOperation { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.ActuatorSpringLangNullable(),
                new SpringBoot4MigrationInspection.SpringLangNullability());
        this.myFixture.configureByText("StatusEndpoint.java", """
                import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
                import org.springframework.lang.Nullable;

                class StatusEndpoint {
                    @ReadOperation
                    String status(@<caret>Nullable String name) {
                        return name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        Assertions.assertThat(countDescriptions(highlights, "no longer makes an actuator operation parameter optional"))
                .as("actuator parameter reported by dedicated inspection")
                .isEqualTo(1);
        Assertions.assertThat(countDescriptions(highlights, "deprecated in Spring Framework 7 in favour of JSpecify"))
                .as("generic nullability warning only on the import, not on the parameter annotation")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with JSpecify annotation"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("parameter now uses JSpecify")
                .contains("@org.jspecify.annotations.Nullable String name");
    }

    public void testActuatorSpringLangNullable_nonOperationIsIgnored() {
        addSpringLangNullableStub();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.ActuatorSpringLangNullable());
        this.myFixture.configureByText("Service.java", """
                import org.springframework.lang.Nullable;

                class Service {
                    String status(@Nullable String name) {
                        return name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "actuator operation parameter"))
                .as("plain method parameter")
                .isZero();
    }

    public void testPropertyMapperAlwaysApplying_reportsRemovedCall() {
        this.myFixture.addFileToProject("org/springframework/boot/context/properties/PropertyMapper.java", """
                package org.springframework.boot.context.properties;
                public final class PropertyMapper {
                    public static PropertyMapper get() { return new PropertyMapper(); }
                    public PropertyMapper alwaysApplyingWhenNonNull() { return this; }
                }
                """);
        this.myFixture.addFileToProject("org/example/OtherMapper.java", """
                package org.example;
                public class OtherMapper {
                    public OtherMapper alwaysApplyingWhenNonNull() { return this; }
                }
                """);
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.PropertyMapperAlwaysApplying());
        this.myFixture.configureByText("Sample.java", """
                import org.example.OtherMapper;
                import org.springframework.boot.context.properties.PropertyMapper;

                class Sample {
                    void map(OtherMapper other) {
                        PropertyMapper.get().alwaysApplyingWhenNonNull();
                        other.alwaysApplyingWhenNonNull();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "alwaysApplyingWhenNonNull() was removed"))
                .as("only the Boot PropertyMapper call")
                .isEqualTo(1);
    }

    public void testRelocatedBootType_hibernateJpaAutoConfigurationReported() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

                class Sample {
                    HibernateJpaAutoConfiguration config;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "relocated by the Boot 4 modularisation"))
                .as("HibernateJpaAutoConfiguration import")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with relocated type"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("HibernateJpaAutoConfiguration moved to the Boot 4 hibernate module")
                .contains("import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;");
    }

    public void testRelocatedBootType_autoConfigureDataJpaAnnotationReported() {
        this.myFixture.addFileToProject(
                "org/springframework/boot/test/autoconfigure/orm/jpa/AutoConfigureDataJpa.java",
                "package org.springframework.boot.test.autoconfigure.orm.jpa;\n"
                        + "public @interface AutoConfigureDataJpa { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.RelocatedBootType());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureDataJpa;

                @<caret>AutoConfigureDataJpa
                class SampleTests { }
                """);

        this.myFixture.doHighlighting();
        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with relocated annotation"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("AutoConfigureDataJpa moved to the data-jpa test module")
                .contains("org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa");
    }

    public void testJacksonRenamedType_jsonFactoryFixTargetsJsonPackage() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonRenamedType());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.core.<caret>JsonFactory;

                class Sample {
                    JsonFactory factory;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed in Jackson 3"))
                .as("JsonFactory import")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with Jackson 3 type"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("JsonFactory lives in tools.jackson.core.json, not tools.jackson.core")
                .contains("tools.jackson.core.json.JsonFactory");
    }

    public void testJacksonRenamedType_objectCodecReportedWithoutFix() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonRenamedType());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.core.<caret>ObjectCodec;

                class Sample {
                    ObjectCodec codec;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed in Jackson 3"))
                .as("ObjectCodec import")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Replace with Jackson 3 type"))
                .as("ObjectCodec has no mechanical replacement")
                .isEmpty();
    }

    public void testSpringBootTestAutoConfigure_reportsWebTestClient() {
        addSpringBootTestStubs();
        this.myFixture.addFileToProject("org/springframework/test/web/reactive/server/WebTestClient.java",
                "package org.springframework.test.web.reactive.server;\n"
                        + "public class WebTestClient { }\n");
        this.myFixture.addFileToProject(
                "org/springframework/boot/webtestclient/autoconfigure/AutoConfigureWebTestClient.java",
                "package org.springframework.boot.webtestclient.autoconfigure;\n"
                        + "public @interface AutoConfigureWebTestClient { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.SpringBootTestAutoConfigure());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.reactive.server.WebTestClient;

                @SpringBootTest
                class SampleTests {
                    @Autowired
                    WebTestClient <caret>client;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "no longer configures WebTestClient"))
                .as("WebTestClient injected without @AutoConfigureWebTestClient")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Add @AutoConfigureWebTestClient"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("annotation added and imported")
                .contains("import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;")
                .contains("@AutoConfigureWebTestClient");
    }

    public void testJacksonApi_generatorWriteFieldNameRenamed() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.core.JsonGenerator;

                class Sample {
                    void write(JsonGenerator gen) throws java.io.IOException {
                        gen.<caret>writeFieldName("size");
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed writeFieldName() to writeName()"))
                .as("generator field name call")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with Jackson 3 call"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("method call rewritten in place")
                .contains("gen.writeName(\"size\")")
                .doesNotContain("writeFieldName");
    }

    public void testJacksonApi_writeObjectFieldRenamedToWritePojoProperty() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.core.JsonGenerator;

                class Sample {
                    void write(JsonGenerator gen) throws java.io.IOException {
                        gen.writeObjectField("sort", new Object());
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed writeObjectField() to writePOJOProperty()"))
                .as("object field call")
                .isEqualTo(1);
    }

    public void testJacksonApi_objectReaderReadValueMovedToForType() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.ObjectReader;

                class Sample {
                    void read(ObjectReader reader, Class<?> type, String value) throws java.io.IOException {
                        reader.<caret>readValue(value, type);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "forType(type).readValue(value)"))
                .as("ObjectReader two-arg readValue")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with Jackson 3 call"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("readValue rewritten through forType")
                .contains("reader.forType(type).readValue(value)")
                .doesNotContain("readValue(value, type)");
    }

    public void testJacksonApi_objectMapperReadValueSurvives() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    void read(ObjectMapper mapper, Class<?> type, String value) throws java.io.IOException {
                        mapper.readValue(value, type);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Jackson 3"))
                .as("ObjectMapper.readValue(String, Class) survives in Jackson 3")
                .isZero();
    }

    public void testJacksonApi_objectMapperRegisterModuleRemovedWithoutFix() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    void setup(ObjectMapper mapper) {
                        mapper.<caret>registerModule(null);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed ObjectMapper.registerModule"))
                .as("builder-pattern removal reported without fix")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Replace with Jackson 3 call"))
                .as("no mechanical replacement for builder restructuring")
                .isEmpty();
    }

    public void testJacksonApi_jsonNodeFieldsRenamedToPropertiesIterator() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.Iterator;

                class Sample {
                    void walk(JsonNode node) {
                        Iterator<?> it = node.<caret>fields();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed fields() to properties().iterator()"))
                .as("JsonNode.fields() call")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with Jackson 3 call"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("iterator preserved through properties().iterator()")
                .contains("node.properties().iterator()");
    }

    public void testJacksonApi_foreignOwnerIsIgnored() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.core.JsonGenerator;

                class Sample {
                    void write(JsonGenerator gen) {
                        other().writeFieldName("size");
                        unknown.writeFieldName("size");
                    }

                    ForeignWriter other() { return new ForeignWriter(); }

                    ForeignWriter unknown;

                    static class ForeignWriter {
                        void writeFieldName(String name) { }
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "renamed writeFieldName"))
                .as("same-name methods on unrelated or unresolvable owners")
                .isZero();
    }

    public void testJacksonApi_renamedAccessorsRewritten() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.ObjectWriter;

                class Sample {
                    void use(JsonNode node, ObjectMapper mapper, ObjectWriter writer) {
                        node.with("meta");
                        mapper.getSerializationConfig();
                        mapper.getDeserializationConfig();
                        writer.withType(String.class);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "Jackson 3 renamed"))
                .as("with, getSerializationConfig, getDeserializationConfig and ObjectWriter.withType")
                .isEqualTo(4);
        Assertions.assertThat(countDescriptions(highlights, "Jackson 3 removed"))
                .as("no longer reported as removals")
                .isZero();

        for (int i = 0; i < 4; i++) {
            this.myFixture.launchAction(this.myFixture.getAllQuickFixes().stream()
                    .filter((com.intellij.codeInsight.intention.IntentionAction a) ->
                            "Replace with Jackson 3 call".equals(a.getText()))
                    .findFirst()
                    .orElseThrow());
        }

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("every call rewritten to its Jackson 3 name")
                .contains("node.withObject(\"meta\")")
                .contains("mapper.serializationConfig()")
                .contains("mapper.deserializationConfig()")
                .contains("writer.forType(String.class)");
    }

    private void addJacksonApiStubs() {
        addJdkStubs();
        this.myFixture.addFileToProject("com/fasterxml/jackson/core/JsonGenerator.java",
                "package com.fasterxml.jackson.core;\n"
                        + "public abstract class JsonGenerator {\n"
                        + "    public void writeFieldName(String name) throws java.io.IOException { }\n"
                        + "    public void writeObjectField(String name, Object value) throws java.io.IOException { }\n"
                        + "}\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/databind/ObjectReader.java",
                "package com.fasterxml.jackson.databind;\n"
                        + "public class ObjectReader {\n"
                        + "    public <T> T readValue(String value, Class<T> type) throws java.io.IOException { return null; }\n"
                        + "    public ObjectReader forType(Class<?> type) { return this; }\n"
                        + "}\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/databind/ObjectWriter.java",
                "package com.fasterxml.jackson.databind;\n"
                        + "public class ObjectWriter {\n"
                        + "    public ObjectWriter withType(Class<?> type) { return this; }\n"
                        + "}\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/databind/ObjectMapper.java",
                "package com.fasterxml.jackson.databind;\n"
                        + "public class ObjectMapper {\n"
                        + "    public <T> T readValue(String value, Class<T> type) throws java.io.IOException { return null; }\n"
                        + "    public void registerModule(Object module) { }\n"
                        + "    public Object getSerializationConfig() { return null; }\n"
                        + "    public Object getDeserializationConfig() { return null; }\n"
                        + "}\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/databind/JsonNode.java",
                "package com.fasterxml.jackson.databind;\n"
                        + "public class JsonNode {\n"
                        + "    public JsonNode with(String property) { return this; }\n"
                        + "    public java.util.Iterator<?> fields() { return null; }\n"
                        + "    public java.util.Set<?> properties() { return null; }\n"
                        + "    public boolean isContainerNode() { return false; }\n"
                        + "}\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/core/JsonParser.java",
                "package com.fasterxml.jackson.core;\n"
                        + "public abstract class JsonParser {\n"
                        + "    public <T> java.util.Iterator<T> readValuesAs(Class<T> type) { return null; }\n"
                        + "}\n");
    }

    /**
     * The light test fixture has no JDK, so java.lang.Class and java.io.IOException
     * do not resolve; stub them so the inspections see real resolved types.
     */
    private void addJdkStubs() {
        this.myFixture.addFileToProject("java/lang/Class.java", "package java.lang;\npublic final class Class<T> { }\n");
        this.myFixture.addFileToProject("java/io/IOException.java",
                "package java.io;\npublic class IOException extends Exception { }\n");
    }

    public void testJacksonApi_unqualifiedCallInSubclassIsRewrittenWithoutReceiver() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("MyGenerator.java", """
                abstract class MyGenerator extends com.fasterxml.jackson.core.JsonGenerator {
                    void write() throws java.io.IOException {
                        <caret>writeFieldName("size");
                    }
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with Jackson 3 call"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("unqualified call keeps no receiver")
                .contains("        writeName(\"size\");")
                .doesNotContain(".writeName");
    }

    public void testJacksonApi_readValuesAsReportedWithoutFix() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    java.util.Iterator<String> read(com.fasterxml.jackson.core.JsonParser parser) {
                        return parser.<caret>readValuesAs(String.class);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed JsonParser.readValuesAs()"))
                .as("readValuesAs has no Jackson 3 counterpart")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Replace with Jackson 3 call"))
                .as("readValueAs returns a single value, so no mechanical fix")
                .isEmpty();
    }

    public void testJacksonApi_isContainerNodeRenamed() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    boolean check(com.fasterxml.jackson.databind.JsonNode node) {
                        return node.<caret>isContainerNode();
                    }
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Replace with Jackson 3 call"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("renamed, not removed, in Jackson 3")
                .contains("node.isContainer()");
    }

    public void testJacksonApi_unresolvedTypeArgumentIsIgnored() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    void read(com.fasterxml.jackson.databind.ObjectReader reader, UnknownPsiClass marker)
                            throws java.io.IOException {
                        reader.readValue("{}", marker);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "forType(type)"))
                .as("an unresolved type whose name ends with Class is not a type argument")
                .isZero();
    }

    public void testJacksonConstructorDefaults_silentWhenOnlyOtherKindsAreEnabled() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.MockBean(),
                new SpringBoot4MigrationInspection.Undertow(), new SpringBoot4MigrationInspection.JacksonApi());
        this.myFixture.configureByText("Plain.java", """
                class Plain {
                    private int retries = 3;
                    private String name;

                    public Plain() {
                    }

                    public Plain(int retries, String name) {
                        this.retries = retries;
                        this.name = name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("the constructor check belongs to its own inspection only")
                .isZero();
    }

    public void testJsonPathMatcher_reportsTwoArgOverloadAndRewrites() {
        addJsonPathStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JsonPathMatcher());
        this.myFixture.configureByText("SampleTests.java", """
                import org.hamcrest.Matchers;
                import org.springframework.test.web.reactive.server.WebTestClient;

                class SampleTests {
                    void check(WebTestClient.BodyContentSpec spec) {
                        spec.<caret>jsonPath("$.errorDateTime", Matchers.notNullValue());
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "chain .value(matcher)"))
                .as("matcher passed to the two-argument jsonPath on the WebTestClient body spec")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Chain .value() on jsonPath()"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("matcher moved to the value() chain")
                .contains("spec.jsonPath(\"$.errorDateTime\").value(Matchers.notNullValue())")
                .doesNotContain("jsonPath(\"$.errorDateTime\", Matchers.notNullValue())");
    }

    public void testJsonPathMatcher_templateArgumentsFormattedIntoExpression() {
        addJsonPathStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JsonPathMatcher());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.test.web.reactive.server.WebTestClient;

                class SampleTests {
                    void check(WebTestClient.BodyContentSpec spec, int index, String field) {
                        spec.<caret>jsonPath("$.items[%s]." + "%s", index, field);
                    }
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Format arguments into the expression"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("compound expression parenthesized before formatted()")
                .contains("spec.jsonPath((\"$.items[%s].\" + \"%s\").formatted(index, field));");
    }

    public void testJsonPathMatcher_templateFixNeedsJava15() {
        addJsonPathStubs();
        LanguageLevel previous = LanguageLevelProjectExtension.getInstance(getProject()).getLanguageLevel();
        com.intellij.openapi.application.WriteAction.run(() ->
                LanguageLevelProjectExtension.getInstance(getProject()).setLanguageLevel(LanguageLevel.JDK_11));
        try {
            this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JsonPathMatcher());
            this.myFixture.configureByText("SampleTests.java", """
                    import org.springframework.test.web.reactive.server.WebTestClient;

                    class SampleTests {
                        void check(WebTestClient.BodyContentSpec spec, int index) {
                            spec.<caret>jsonPath("$.items[%s].name", index);
                        }
                    }
                    """);

            List<HighlightInfo> highlights = this.myFixture.doHighlighting();

            Assertions.assertThat(countDescriptions(highlights, "format the arguments into the expression"))
                    .as("still reported below Java 15")
                    .isEqualTo(1);
            Assertions.assertThat(this.myFixture.filterAvailableIntentions("Format arguments into the expression"))
                    .as("String.formatted() needs Java 15")
                    .isEmpty();
        }
        finally {
            com.intellij.openapi.application.WriteAction.run(() ->
                    LanguageLevelProjectExtension.getInstance(getProject()).setLanguageLevel(previous));
        }
    }

    public void testJsonPathMatcher_templateArgumentsNotChainedToValue() {
        addJsonPathStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JsonPathMatcher());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.test.web.reactive.server.WebTestClient;

                class SampleTests {
                    void check(WebTestClient.BodyContentSpec spec, int index) {
                        spec.<caret>jsonPath("$.items[%s].name", index);
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "format the arguments into the expression"))
                .as("template arguments are reported")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Chain .value() on jsonPath()"))
                .as("template arguments are not a matcher, so value() would change the meaning")
                .isEmpty();
    }

    public void testJsonPathMatcher_mockMvcResultMatchersIsIgnored() {
        addJsonPathStubs();
        this.myFixture.addFileToProject("org/springframework/test/web/servlet/result/MockMvcResultMatchers.java",
                "package org.springframework.test.web.servlet.result;\n"
                        + "public class MockMvcResultMatchers {\n"
                        + "    public static Object jsonPath(String expression, Object matcher) { return null; }\n"
                        + "}\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JsonPathMatcher());
        this.myFixture.configureByText("SampleTests.java", """
                import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

                class SampleTests {
                    void check() throws Exception {
                        MockMvcResultMatchers.jsonPath("$.errorDateTime", new Object());
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "removed in Spring Framework 7"))
                .as("MockMvc jsonPath(String, Matcher) still exists in Spring Framework 7")
                .isZero();
    }

    public void testJacksonConstructorDefaults_reportsAllArgsConstructor() {
        this.myFixture.addFileToProject("com/fasterxml/jackson/annotation/JsonCreator.java",
                "package com.fasterxml.jackson.annotation;\npublic @interface JsonCreator { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                class Criterion {
                    private String agg = "DEF";
                    private String name;

                    public Criterion() {
                    }

                    public Criterion<caret>(String agg, String name) {
                        this.agg = agg;
                        this.name = name;
                    }

                    public String getAgg() { return agg; }
                    public void setAgg(String value) { agg = value; }
                    public String getName() { return name; }
                    public void setName(String value) { name = value; }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("public all-args constructor next to a no-arg constructor")
                .isEqualTo(1);

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Add @JsonCreator to the no-arg constructor"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("no-arg constructor marked as creator")
                .contains("import com.fasterxml.jackson.annotation.JsonCreator;")
                .contains("@JsonCreator\n    public Criterion()");
    }

    public void testJacksonConstructorDefaults_reportsLombokPair() {
        this.myFixture.addFileToProject("lombok/AllArgsConstructor.java",
                "package lombok;\npublic @interface AllArgsConstructor { }\n");
        this.myFixture.addFileToProject("lombok/NoArgsConstructor.java",
                "package lombok;\npublic @interface NoArgsConstructor { }\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/annotation/JsonCreator.java",
                "package com.fasterxml.jackson.annotation;\npublic @interface JsonCreator { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                import lombok.AllArgsConstructor;
                import lombok.NoArgsConstructor;

                @AllArgsConstructor
                @NoArgsConstructor
                class <caret>Criterion {
                    private String agg = "DEF";
                    private String name;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("Lombok all-args and no-args pair")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Add @JsonCreator to the no-arg constructor"))
                .as("Lombok constructors are generated, so the hand-written constructor fix does not apply")
                .isEmpty();

        this.myFixture.launchAction(
                this.myFixture.findSingleIntention("Add onConstructor_ = @JsonCreator to @NoArgsConstructor"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("JsonCreator routed onto the generated no-arg constructor")
                .contains("import com.fasterxml.jackson.annotation.JsonCreator;")
                .contains("@NoArgsConstructor(onConstructor_ = @JsonCreator)");
    }

    public void testJacksonConstructorDefaults_lombokPairWithOnConstructorReportedWithoutFix() {
        this.myFixture.addFileToProject("lombok/AllArgsConstructor.java",
                "package lombok;\npublic @interface AllArgsConstructor { }\n");
        this.myFixture.addFileToProject("lombok/NoArgsConstructor.java",
                "package lombok;\npublic @interface NoArgsConstructor {\n    Object[] onConstructor_() default {};\n}\n");
        this.myFixture.addFileToProject("com/fasterxml/jackson/annotation/JsonIgnore.java",
                "package com.fasterxml.jackson.annotation;\npublic @interface JsonIgnore { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                import com.fasterxml.jackson.annotation.JsonIgnore;
                import lombok.AllArgsConstructor;
                import lombok.NoArgsConstructor;

                @AllArgsConstructor
                @NoArgsConstructor(onConstructor_ = @JsonIgnore)
                class <caret>Criterion {
                    private String agg = "DEF";
                    private String name;
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("still reported")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions(
                        "Add onConstructor_ = @JsonCreator to @NoArgsConstructor"))
                .as("an existing onConstructor_ would be overwritten")
                .isEmpty();
    }

    public void testJacksonConstructorDefaults_ignoresClassWithoutInitializers() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                class Criterion {
                    private String agg;
                    private String name;

                    public Criterion() {
                    }

                    public Criterion(String agg, String name) {
                        this.agg = agg;
                        this.name = name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("no field initializers, so no initializer can be bypassed")
                .isZero();
    }

    public void testJacksonConstructorDefaults_ignoresUnmatchedConstructorParameters() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                class Criterion {
                    private String agg = "DEF";
                    private String name;

                    public Criterion() {
                    }

                    public Criterion(String name) {
                        this.name = name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("the single parameter matches a field without an initializer")
                .isZero();
    }

    public void testJacksonConstructorDefaults_ignoresAmbiguousConstructors() {
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                class Criterion {
                    private String agg = "DEF";
                    private String name;

                    public Criterion() {
                    }

                    public Criterion(String agg, String name) {
                        this.agg = agg;
                        this.name = name;
                    }

                    public Criterion(String agg, String name, String extra) {
                        this.agg = agg;
                        this.name = name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("two multi-arg constructors leave the choice to Jackson and it uses the no-arg one")
                .isZero();
    }

    public void testJacksonConstructorDefaults_ignoresAlreadyAnnotatedConstructors() {
        this.myFixture.addFileToProject("com/fasterxml/jackson/annotation/JsonCreator.java",
                "package com.fasterxml.jackson.annotation;\npublic @interface JsonCreator { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonConstructorDefaults());
        this.myFixture.configureByText("Criterion.java", """
                import com.fasterxml.jackson.annotation.JsonCreator;

                class Criterion {
                    private String agg = "DEF";
                    private String name;

                    public Criterion() {
                    }

                    @JsonCreator
                    public Criterion(String agg, String name) {
                        this.agg = agg;
                        this.name = name;
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "field initializers are bypassed"))
                .as("all-args constructor already marked as creator")
                .isZero();
    }

    public void testJacksonIoException_reportsCatchAroundJacksonCalls() {
        addJacksonApiStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;

                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    String read(ObjectMapper mapper) {
                        try {
                            return mapper.readValue("{\\"a\\":1}", String.class);
                        }
                        catch (IOException<caret> ex) {
                            return null;
                        }
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "IOException catch no longer covers"))
                .as("IOException catch around a Jackson call")
                .isEqualTo(1);
    }

    public void testJacksonIoException_fixWidensCatchToJacksonException() {
        addJacksonApiStubs();
        addThrowableStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;

                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    String read(ObjectMapper mapper) {
                        try {
                            return mapper.readValue("{}", String.class);
                        }
                        catch (IOException<caret> ex) {
                            log(ex.getMessage(), ex);
                            throw new IllegalStateException(ex);
                        }
                    }

                    void log(String message, Throwable... causes) { }
                }
                """);
        this.myFixture.doHighlighting();

        this.myFixture.launchAction(this.myFixture.findSingleIntention("Also catch JacksonException"));

        Assertions.assertThat(this.myFixture.getFile().getText())
                .as("catch widened to the multi-catch and JacksonException imported")
                .contains("import tools.jackson.core.JacksonException;")
                .contains("catch (IOException | JacksonException ex)");
    }

    public void testJacksonIoException_noFixWhenParameterNeedsIoException() {
        addJacksonApiStubs();
        addThrowableStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;

                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    String read(ObjectMapper mapper) {
                        try {
                            return mapper.readValue("{}", String.class);
                        }
                        catch (IOException<caret> ex) {
                            handle(ex);
                            return null;
                        }
                    }

                    void handle(IOException ex) { }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "IOException catch no longer covers"))
                .as("still reported")
                .isEqualTo(1);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Also catch JacksonException"))
                .as("the multi-catch parameter is no longer an IOException")
                .isEmpty();
    }

    public void testJacksonIoException_noFixForExistingMultiCatch() {
        addJacksonApiStubs();
        this.myFixture.addFileToProject("java/lang/IllegalStateException.java",
                "package java.lang;\npublic class IllegalStateException extends RuntimeException { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;

                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    String read(ObjectMapper mapper) {
                        try {
                            return mapper.readValue("{}", String.class);
                        }
                        catch (IOException | IllegalStateException<caret> ex) {
                            return null;
                        }
                    }
                }
                """);
        this.myFixture.doHighlighting();

        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Also catch JacksonException"))
                .as("multi-catch already declared")
                .isEmpty();
    }

    public void testJacksonIoException_noFixWhenSiblingCatchHandlesJacksonException() {
        addJacksonApiStubs();
        this.myFixture.addFileToProject("tools/jackson/core/JacksonException.java",
                "package tools.jackson.core;\npublic class JacksonException extends RuntimeException { }\n");
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;

                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    String read(ObjectMapper mapper) {
                        try {
                            return mapper.readValue("{}", String.class);
                        }
                        catch (IOException<caret> ex) {
                            return null;
                        }
                        catch (tools.jackson.core.JacksonException ex) {
                            return "";
                        }
                    }
                }
                """);
        this.myFixture.doHighlighting();

        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Also catch JacksonException"))
                .as("the sibling catch would clash with the widened multi-catch")
                .isEmpty();
    }

    public void testJacksonIoException_noFixWhenEarlierCatchCoversRuntimeException() {
        addJacksonApiStubs();
        addThrowableStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;

                import com.fasterxml.jackson.databind.ObjectMapper;

                class Sample {
                    String read(ObjectMapper mapper) {
                        try {
                            return mapper.readValue("{}", String.class);
                        }
                        catch (RuntimeException ex) {
                            return "";
                        }
                        catch (IOException<caret> ex) {
                            return null;
                        }
                    }
                }
                """);
        this.myFixture.doHighlighting();

        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Also catch JacksonException"))
                .as("JacksonException is already caught earlier, so the multi-catch alternative is unreachable")
                .isEmpty();
    }

    private void addThrowableStubs() {
        this.myFixture.addFileToProject("java/lang/Throwable.java",
                "package java.lang;\npublic class Throwable {\n"
                        + "    public String getMessage() { return null; }\n}\n");
        this.myFixture.addFileToProject("java/lang/Exception.java",
                "package java.lang;\npublic class Exception extends Throwable { }\n");
        this.myFixture.addFileToProject("java/lang/RuntimeException.java",
                "package java.lang;\npublic class RuntimeException extends Exception { }\n");
        this.myFixture.addFileToProject("java/lang/IllegalStateException.java",
                "package java.lang;\npublic class IllegalStateException extends RuntimeException {\n"
                        + "    public IllegalStateException(Throwable cause) { }\n}\n");
        this.myFixture.addFileToProject("tools/jackson/core/JacksonException.java",
                "package tools.jackson.core;\npublic class JacksonException extends RuntimeException { }\n");
    }

    public void testJacksonIoException_ignoresNonJacksonTry() {
        addJdkStubs();
        this.myFixture.enableInspections(new SpringBoot4MigrationInspection.JacksonIoException());
        this.myFixture.configureByText("Sample.java", """
                import java.io.IOException;
                import java.io.InputStream;

                class Sample {
                    int read(InputStream stream) {
                        try {
                            return stream.read();
                        }
                        catch (IOException<caret> ex) {
                            return -1;
                        }
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        Assertions.assertThat(countDescriptions(highlights, "IOException catch no longer covers"))
                .as("stream reads still throw IOException")
                .isZero();
    }

    private void addJsonPathStubs() {
        this.myFixture.addFileToProject("org/springframework/test/web/reactive/server/WebTestClient.java",
                "package org.springframework.test.web.reactive.server;\n"
                        + "public class WebTestClient {\n"
                        + "    public interface BodyContentSpec {\n"
                        + "        JsonPathAssertions jsonPath(String expression);\n"
                        + "        JsonPathAssertions jsonPath(String expression, Object... args);\n"
                        + "    }\n"
                        + "    public interface JsonPathAssertions {\n"
                        + "        WebTestClient.BodyContentSpec value(Object matcher);\n"
                        + "    }\n"
                        + "}\n");
        this.myFixture.addFileToProject("org/hamcrest/Matcher.java",
                "package org.hamcrest;\npublic interface Matcher<T> { }\n");
        this.myFixture.addFileToProject("org/hamcrest/Matchers.java",
                "package org.hamcrest;\npublic class Matchers {\n"
                        + "    public static <T> Matcher<T> notNullValue() { return null; }\n}\n");
    }

    private void addSpringBootTestStubs() {
        this.myFixture.addFileToProject("org/springframework/boot/test/context/SpringBootTest.java",
                "package org.springframework.boot.test.context;\npublic @interface SpringBootTest { }\n");
        this.myFixture.addFileToProject("org/springframework/beans/factory/annotation/Autowired.java",
                "package org.springframework.beans.factory.annotation;\npublic @interface Autowired { }\n");
        this.myFixture.addFileToProject("org/springframework/test/web/servlet/MockMvc.java",
                "package org.springframework.test.web.servlet;\npublic final class MockMvc { }\n");
        this.myFixture.addFileToProject("org/springframework/boot/resttestclient/TestRestTemplate.java",
                "package org.springframework.boot.resttestclient;\npublic class TestRestTemplate { }\n");
        this.myFixture.addFileToProject(
                "org/springframework/boot/webmvc/test/autoconfigure/AutoConfigureMockMvc.java",
                "package org.springframework.boot.webmvc.test.autoconfigure;\npublic @interface AutoConfigureMockMvc { }\n");
        this.myFixture.addFileToProject(
                "org/springframework/boot/resttestclient/autoconfigure/AutoConfigureTestRestTemplate.java",
                "package org.springframework.boot.resttestclient.autoconfigure;\n"
                        + "public @interface AutoConfigureTestRestTemplate { }\n");
    }

    private void addMockitoStubs() {
        this.myFixture.addFileToProject("org/springframework/boot/test/context/SpringBootTest.java",
                "package org.springframework.boot.test.context;\npublic @interface SpringBootTest { }\n");
        this.myFixture.addFileToProject("org/mockito/Mock.java", "package org.mockito;\npublic @interface Mock { }\n");
        this.myFixture.addFileToProject("org/mockito/MockitoAnnotations.java",
                "package org.mockito;\npublic final class MockitoAnnotations {\n"
                        + "    public static AutoCloseable openMocks(Object test) { return null; }\n}\n");
        this.myFixture.addFileToProject("org/junit/jupiter/api/extension/ExtendWith.java",
                "package org.junit.jupiter.api.extension;\npublic @interface ExtendWith {\n"
                        + "    Class<?>[] value();\n}\n");
        this.myFixture.addFileToProject("org/mockito/junit/jupiter/MockitoExtension.java",
                "package org.mockito.junit.jupiter;\npublic class MockitoExtension { }\n");
    }

    private void addBeanStub() {
        this.myFixture.addFileToProject("org/springframework/context/annotation/Bean.java",
                "package org.springframework.context.annotation;\npublic @interface Bean { }\n");
    }

    private void addSpringLangNullableStub() {
        this.myFixture.addFileToProject("org/springframework/lang/Nullable.java",
                "package org.springframework.lang;\npublic @interface Nullable { }\n");
    }

    private static long countDescriptions(List<HighlightInfo> highlights, String description) {
        return highlights.stream()
                .filter((HighlightInfo h) -> h.getDescription() != null)
                .filter((HighlightInfo h) -> h.getDescription().contains(description))
                .map((HighlightInfo h) -> h.startOffset + ":" + h.getDescription())
                .distinct()
                .count();
    }

}
