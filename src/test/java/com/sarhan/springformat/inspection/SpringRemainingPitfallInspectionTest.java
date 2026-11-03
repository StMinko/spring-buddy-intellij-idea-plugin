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

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * @author Akmal Sarhan
 */
public class SpringRemainingPitfallInspectionTest extends BasePlatformTestCase {

    @Override
    protected void tearDown() throws Exception {
        SpringBoot4TestSupport.reset(getProject());
        super.tearDown();
    }

    public void testReportsServiceMultipleRepositoriesWithoutTransaction() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    void <warning descr="Service method uses multiple repositories without an explicit @Transactional boundary">save</warning>() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMultipleRepositoriesReadOnly() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    void load() { userRepository.findAll(); orderRepository.findAll(); }
                }
                interface UserRepository { java.util.List findAll(); }
                interface OrderRepository { java.util.List findAll(); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMultipleRepositoriesWithTransactionalOnClass() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @Transactional
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    void save() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsSpringBootTestSliceCandidate() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.SpringBootTestSlice());
        this.myFixture.configureByText("SampleTest.java", """
                @SpringBootTest
                class <warning descr="@SpringBootTest may be broader than needed; consider a focused test slice">SampleTest</warning> { MockMvc mvc; }
                class MockMvc { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsMockBeanContextCacheFragmentation() {
        SpringBoot4TestSupport.setBoot3(getProject());
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.MockBeanContextCache());
        this.myFixture.configureByText("SampleTest.java", """
                @SpringBootTest
                class SampleTest {
                    <warning descr="@MockBean changes the Spring test context cache key; centralize common mocks to avoid extra context loads">@MockBean</warning>
                    EmailService emailService;
                }
                class EmailService { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsMockBeanInAutoDetectModeWithoutBoot4() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.MockBeanContextCache());
        this.myFixture.configureByText("SampleTest.java", """
                @SpringBootTest
                class SampleTest {
                    <warning descr="@MockBean changes the Spring test context cache key; centralize common mocks to avoid extra context loads">@MockBean</warning>
                    EmailService emailService;
                }
                class EmailService { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMockBeanOnBoot4Target() {
        SpringBoot4TestSupport.setBoot4(getProject());
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.MockBeanContextCache());
        this.myFixture.configureByText("SampleTest.java", """
                @SpringBootTest
                class SampleTest {
                    @MockBean
                    EmailService emailService;
                }
                class EmailService { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMockBeanOutsideSpringTestContext() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.MockBeanContextCache());
        this.myFixture.configureByText("SampleTest.java", """
                class SampleTest {
                    @MockBean
                    EmailService emailService;
                }
                class EmailService { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsConfigurationPropertiesMissingValidated() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ConfigurationPropertiesMissingValidated());
        this.myFixture.configureByText("SampleProperties.java", """
                @ConfigurationProperties
                class <warning descr="@ConfigurationProperties uses constraints but is missing @Validated">SampleProperties</warning> { @NotNull String url; }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsScheduledWithoutLock() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ScheduledWithoutLock());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Scheduled(cron = "*")
                    void <warning descr="@Scheduled method may run on every cluster node; consider a distributed lock such as @SchedulerLock">run</warning>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsServiceSwallowedException() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceSwallowedException());
        this.myFixture.configureByText("SampleService.java", """
                class SampleService {
                    void run() { try { work(); } catch (<warning descr="Service method catches a broad exception without rethrowing it">Exception</warning> ex) { log(ex); } }
                    void work() throws Exception { }
                    void log(Exception ex) { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportServiceSwallowedExceptionWhenLogging() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceSwallowedException());
        this.myFixture.configureByText("SampleService.java", """
                @Service
                class SampleService {
                    Logger log;
                    void run() { try { work(); } catch (Exception ex) { log.warn("failed", ex); } }
                    void work() throws Exception { }
                }
                class Logger { void warn(String message, Exception ex) { } }
                """);
        this.assertNoInspectionHighlight("Service method catches a broad exception without rethrowing it");
    }

    public void testReportsServiceSwallowedExceptionWhenLoggingOnlyAtDebugLevel() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceSwallowedException());
        this.myFixture.configureByText("SampleService.java", """
                @Service
                class SampleService {
                    Logger logger;
                    void run() { try { work(); } catch (<warning descr="Service method catches a broad exception without rethrowing it">Exception</warning> ex) { logger.debug("failed", ex); } }
                    void work() throws Exception { }
                }
                class Logger { void debug(String message, Exception ex) { } }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportPrivateRepositoryHelperCalledFromTransactionalMethod() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    @Transactional void save() { saveBoth(); }
                    private void saveBoth() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.assertNoInspectionHighlight("Service method uses multiple repositories without an explicit @Transactional boundary");
    }

    public void testReportsPrivateRepositoryHelperCalledFromNonTransactionalMethodWithoutQuickFix() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    public void save() { saveBoth(); }
                    private void <warning descr="Service method uses multiple repositories without an explicit @Transactional boundary"><caret>saveBoth</warning>() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        org.assertj.core.api.Assertions.assertThat(this.myFixture.doHighlighting())
                .as("An untransactional public caller still reports its private repository helper")
                .anyMatch(highlight -> "Service method uses multiple repositories without an explicit @Transactional boundary"
                        .equals(highlight.getDescription()));
        org.assertj.core.api.Assertions.assertThat(this.myFixture.getAvailableIntentions())
                .as("A private method does not offer an ineffective @Transactional quick fix")
                .noneMatch(action -> "Add @Transactional annotation".equals(action.getText()));
    }

    public void testQuickFixAddsTransactionalToPublicServiceMethod() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @interface Service { }
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    void <caret>save() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.myFixture.launchAction(this.myFixture.findSingleIntention("Add @Transactional annotation"));
        this.myFixture.checkResult("""
                @interface Service { }
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    @org.springframework.transaction.annotation.Transactional
                    void save() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
    }

    public void testDoesNotReportPrivateRepositoryHelperWithoutCallers() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    private void saveBoth() { userRepository.save(new Object()); orderRepository.save(new Object()); }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.assertNoInspectionHighlight("Service method uses multiple repositories without an explicit @Transactional boundary");
    }

    public void testDoesNotReportRepositoryWritesInsideTransactionTemplateCallback() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("TransactionTemplate.java", """
                package org.springframework.transaction.support;
                public class TransactionTemplate {
                    public <T> T execute(java.util.function.Function<Object, T> callback) { return null; }
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.transaction.support.TransactionTemplate;
                @interface Service { }
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    TransactionTemplate transactionTemplate;
                    void save() {
                        transactionTemplate.execute(status -> {
                            userRepository.save(new Object());
                            orderRepository.save(new Object());
                            return null;
                        });
                    }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.assertNoInspectionHighlight("Service method uses multiple repositories without an explicit @Transactional boundary");
    }

    public void testDoesNotReportRepositoryWritesInsideTransactionOperationsAnonymousCallback() {
        this.myFixture.enableInspections(new SpringRemainingPitfallInspection.ServiceMultipleRepositoriesWithoutTransaction());
        this.myFixture.configureByText("TransactionCallback.java", """
                package org.springframework.transaction.support;
                public interface TransactionCallback<T> { T doInTransaction(Object status); }
                """);
        this.myFixture.configureByText("TransactionOperations.java", """
                package org.springframework.transaction.support;
                public interface TransactionOperations {
                    <T> T execute(TransactionCallback<T> callback);
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                import org.springframework.transaction.support.TransactionCallback;
                import org.springframework.transaction.support.TransactionOperations;
                @interface Service { }
                @Service
                class SampleService {
                    UserRepository userRepository;
                    OrderRepository orderRepository;
                    TransactionOperations transactionOperations;
                    void save() {
                        transactionOperations.execute(new TransactionCallback<Object>() {
                            @Override
                            public Object doInTransaction(Object status) {
                                userRepository.save(new Object());
                                orderRepository.save(new Object());
                                return null;
                            }
                        });
                    }
                }
                interface UserRepository { void save(Object value); }
                interface OrderRepository { void save(Object value); }
                """);
        this.assertNoInspectionHighlight("Service method uses multiple repositories without an explicit @Transactional boundary");
    }

    private void assertNoInspectionHighlight(String message) {
        org.assertj.core.api.Assertions.assertThat(this.myFixture.doHighlighting())
                .as("No inspection highlight should be present for %s", message)
                .noneMatch(highlight -> message.equals(highlight.getDescription()));
    }
}
