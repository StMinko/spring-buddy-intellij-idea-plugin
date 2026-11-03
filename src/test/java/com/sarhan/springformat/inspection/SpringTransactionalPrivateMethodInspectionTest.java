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

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.assertj.core.api.Assertions;

/**
 * @author Akmal Sarhan
 */
public class SpringTransactionalPrivateMethodInspectionTest extends BasePlatformTestCase {

    public void testReportsAndFixesPrivateTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    private void <error descr="@Transactional private methods are not proxied by Spring; make the method public"><caret>save</error>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Make method public");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class Sample {
                    @Transactional
                    public void save() { }
                }
                """);
    }

    public void testReportsAndFixesStaticTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.StaticMethod());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    static void <error descr="@Transactional static methods are not proxied by Spring"><caret>save</error>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Remove static modifier");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class Sample {
                    @Transactional
                    void save() { }
                }
                """);
    }

    public void testReportsAndFixesFinalTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.FinalMethod());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    final void <error descr="@Transactional final methods cannot be subclass-proxied by Spring"><caret>save</error>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Remove final modifier");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class Sample {
                    @Transactional
                    void save() { }
                }
                """);
    }

    public void testReportsAndFixesFinalTransactionalClass() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.FinalClass());
        this.myFixture.configureByText("Sample.java", """
                @Transactional
                final class <error descr="@Transactional final classes cannot be subclass-proxied by Spring"><caret>Sample</error> {
                    void save() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Remove final modifier");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                @Transactional
                class Sample {
                    void save() { }
                }
                """);
    }

    public void testReportsTransactionalMethodInFinalClass() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MethodInFinalClass());
        this.myFixture.configureByText("Sample.java", """
                final class Sample {
                    @Transactional
                    void <error descr="@Transactional methods in final classes cannot be subclass-proxied by Spring">save</error>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsCheckedExceptionWithoutRollbackRule() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.CheckedException());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void save() throws MyCheckedException { throw new MyCheckedException(); }
                    static class MyCheckedException extends Exception { }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("a body-thrown checked exception is reported")
                .anyMatch(highlight -> "Checked exception may not trigger transaction rollback; add rollbackFor/rollbackOn"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportDeclaredCheckedExceptionThatBodyDoesNotThrow() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.CheckedException());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void save() throws MyCheckedException { }
                    static class MyCheckedException extends Exception { }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("a declared exception that the body cannot throw is not reported")
                .noneMatch(highlight -> "Checked exception may not trigger transaction rollback; add rollbackFor/rollbackOn"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportCheckedExceptionCaughtInsideMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.CheckedException());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void save() throws MyCheckedException {
                        try { throw new MyCheckedException(); } catch (MyCheckedException ignored) { }
                    }
                    static class MyCheckedException extends Exception { }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("caught exceptions do not trigger a rollback warning")
                .noneMatch(highlight -> "Checked exception may not trigger transaction rollback; add rollbackFor/rollbackOn"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportCheckedExceptionInReadOnlyMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.CheckedException());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional(readOnly = true)
                    void save() throws MyCheckedException { throw new MyCheckedException(); }
                    static class MyCheckedException extends Exception { }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("read-only methods without writes are exempt")
                .noneMatch(highlight -> "Checked exception may not trigger transaction rollback; add rollbackFor/rollbackOn"
                        .equals(highlight.getDescription()));
    }

    public void testReportsCheckedExceptionThrownByCalledMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.CheckedException());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void save() throws MyBaseException { helper(); }
                    void helper() throws MyCheckedException { throw new MyCheckedException(); }
                    static class MyBaseException extends Exception { }
                    static class MyCheckedException extends MyBaseException { }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("checked exceptions propagated by a called method are reported")
                .anyMatch(highlight -> "Checked exception may not trigger transaction rollback; add rollbackFor/rollbackOn"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportCheckedExceptionCoveredByRollbackRule() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.ConflictingRollbackRules());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional(rollbackFor = MyCheckedException.class)
                    void save() throws MyCheckedException { }
                    static class MyCheckedException extends Exception { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsConflictingRollbackRules() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.InterfaceMethodWithCglib());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    <warning descr="Conflicting rollbackFor/rollbackOn and noRollbackFor/noRollbackOn rules make rollback behavior non-obvious">@Transactional(rollbackFor = Exception.class, noRollbackFor = MyCheckedException.class)</warning>
                    void save() throws MyCheckedException { }
                    static class MyCheckedException extends Exception { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsInterfaceTransactionalMethodWhenCglibEnabled() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.SelfInvocation(), new SpringTransactionalPrivateMethodInspection.RequiresNewSelfInvocation());
        this.myFixture.configureByText("Sample.java", """
                @EnableTransactionManagement(proxyTargetClass = true)
                class Config { }
                interface Sample {
                    <warning descr="@Transactional on interface methods is ignored when CGLIB proxyTargetClass=true is used">@Transactional</warning>
                    void save();
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsRequiresNewSelfInvocation() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.ReadOnlyWrite());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void <warning descr="Self-invocation of @Transactional method bypasses the Spring proxy"><warning descr="Self-invocation of @Transactional(REQUIRES_NEW) method ignores the REQUIRES_NEW boundary">outer</warning></warning>() { inner(); }
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    void inner() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsReadOnlyMethodThatWrites() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.TransactionalBeanMethod());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    @Transactional(readOnly = true)
                    void <warning descr="@Transactional(readOnly = true) method appears to perform write operations">save</warning>() { repository.save(new Object()); }
                    interface Repository { void save(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportPrivateWriteHelperCalledOnlyFromReadWriteTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.ReadOnlyWrite());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { boolean readOnly() default false; }
                @interface Service { }
                @interface CrudRepository { }
                @Service
                @Transactional(readOnly = true)
                class PartnerService {
                    Repository repository;
                    @Transactional(readOnly = false)
                    void finish() { update("id", 42L); }
                    private void update(String id, Long num) { repository.save(new Object()); }
                    interface Repository { void save(Object o); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAndFixesTransactionalBeanMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.TransactionalBeanMethod());
        this.myFixture.configureByText("Sample.java", """
                @Configuration
                class Sample {
                    <warning descr="@Transactional on @Bean methods in @Configuration classes is usually ineffective or misleading"><caret>@Transactional</warning>
                    @Bean
                    Object service() { return new Object(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Remove @Transactional annotation");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                @Configuration
                class Sample {
                    @Bean
                    Object service() { return new Object(); }
                }
                """);
    }

    public void testReportsAndFixesMultipleWritesWithoutTransaction() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    void <warning descr="Method performs multiple write operations without @Transactional"><caret>save</warning>() { repository.save(new Object()); repository.delete(new Object()); }
                    interface Repository { void save(Object value); void delete(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Add @Transactional annotation");
        this.myFixture.launchAction(fix);
        String text = this.myFixture.getFile().getText();
        assertTrue(text.contains("@org.springframework.transaction.annotation.Transactional"));
    }

    public void testDoesNotReportMultipleWritesInTestMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("SampleTest.java", """
                class SampleTest {
                    Repository repository;
                    @Test
                    void save() { repository.save(new Object()); repository.delete(new Object()); }
                    interface Repository { void save(Object value); void delete(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMultipleWritesInTransactionalClass() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Transactional
                class Sample {
                    Repository repository;
                    void save() { repository.save(new Object()); repository.delete(new Object()); }
                    interface Repository { void save(Object value); void delete(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportPrivateMultipleWritesOnlyCalledFromTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    @Transactional
                    void outer() { save(); }
                    private void save() { repository.save(new Object()); repository.delete(new Object()); }
                    interface Repository { void save(Object value); void delete(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsPublicMultipleWritesEvenIfOneTransactionalCallerExists() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    @Transactional
                    void outer() { save(); }
                    void <warning descr="Method performs multiple write operations without @Transactional">save</warning>() { repository.save(new Object()); repository.delete(new Object()); }
                    interface Repository { void save(Object value); void delete(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotCountStringUtilsRemoveAsWriteOperation() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    void checkAccountOwnerValidity(PaymentDetail paymentDetail, Errors errors, Partner partner) {
                        if (!StringUtils.equalsIgnoreCase(
                                StringUtils.remove(partner.getFirstname() + partner.getLastname(), " "),
                                StringUtils.remove(paymentDetail.getAccountOwner(), " "))) {
                            errors.reject("invalid", "invalid");
                            errors.rejectValue("accountOwner", "invalid", "invalid");
                        }
                    }
                    interface PaymentDetail { String getAccountOwner(); }
                    interface Partner { String getFirstname(); String getLastname(); }
                    interface Errors { void reject(String code, String message); void rejectValue(String field, String code, String message); }
                    static class StringUtils { static String remove(String value, String remove) { return value; } static boolean equalsIgnoreCase(String left, String right) { return true; } }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotCountPsiDeleteAsJpaWriteOperation() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    private static void removeJunitAssertionImports(PsiJavaFile javaFile) {
                        PsiImportList importList = javaFile.getImportList();
                        if (importList == null) { return; }
                        for (PsiImportStatement statement : importList.getImportStatements()) {
                            if (statement.getQualifiedName() != null) { statement.delete(); }
                        }
                        for (PsiImportStaticStatement statement : importList.getImportStaticStatements()) {
                            if (statement.getText() != null) { statement.delete(); }
                        }
                    }
                    interface PsiJavaFile { PsiImportList getImportList(); }
                    interface PsiImportList { PsiImportStatement[] getImportStatements(); PsiImportStaticStatement[] getImportStaticStatements(); }
                    interface PsiImportStatement { String getQualifiedName(); void delete(); }
                    interface PsiImportStaticStatement { String getText(); void delete(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsSwallowedExceptionInTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AsyncBoundary());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void <warning descr="Exception is swallowed inside @Transactional method; transaction may commit unexpectedly"><caret>save</warning>() {
                        try { work(); }
                        catch (Exception ex) { log(ex); }
                    }
                    void work() throws Exception { }
                    void log(Exception ex) { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAsyncTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AsyncBoundary());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Async
                    @Transactional
                    void <warning descr="@Async crosses a transaction boundary; transaction context is not propagated to the async thread">save</warning>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsTransactionalSelfInvocationOfAsyncMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AsyncBoundary());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void <warning descr="@Async crosses a transaction boundary; transaction context is not propagated to the async thread">outer</warning>() { inner(); }
                    @Async
                    void inner() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsLongRunningWorkInTransaction() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.LongRunningOperation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void <warning descr="Long-running work inside @Transactional may hold a database connection too long">save</warning>() throws Exception { Thread.sleep(1000); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsLikelyUnsupportedTimeoutDriver() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.UnsupportedTimeout());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    String driver = "org.sqlite.JDBC";
                    @Transactional(timeout = 5)
                    void <warning descr="@Transactional timeout may not be supported by the configured JDBC driver">save</warning>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsConstructorCallingTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.ConstructorTrap());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    <warning descr="Constructor calls a @Transactional method before the Spring proxy exists"><caret>Sample</warning>() { this.initData(); }
                    @Transactional
                    void initData() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsRestTemplateCallInsideTransaction() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.LongRunningOperation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    RestTemplate restTemplate;
                    @Transactional
                    void <warning descr="Long-running work inside @Transactional may hold a database connection too long">load</warning>() { this.restTemplate.getForObject("https://slow-api.com/data", String.class); }
                    static class RestTemplate { Object getForObject(String url, Class<?> type) { return null; } }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportInMemoryGetOrTransactionExecuteInsideTransaction() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.LongRunningOperation());
        this.myFixture.configureByText("Sample.java", """
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                @interface Transactional { }
                class Sample {
                    Map<String, String> values;
                    Optional<String> optional;
                    List<String> items;
                    TransactionTemplate transactionTemplate;
                    @Transactional
                    void load() {
                        this.values.get("key");
                        this.optional.get();
                        this.items.get(0);
                        this.transactionTemplate.execute();
                    }
                }
                class TransactionTemplate { void execute() { } }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .noneMatch(highlight -> "Long-running work inside @Transactional may hold a database connection too long"
                        .equals(highlight.getDescription()));
    }

    public void testReportsClientCallInsideTransactionByReceiverType() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.LongRunningOperation());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                class Sample {
                    PaymentClient payments;
                    @Transactional
                    void charge() {
                        this.payments.charge();
                    }
                }
                interface PaymentClient { void charge(); }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .anyMatch(highlight -> "Long-running work inside @Transactional may hold a database connection too long"
                        .equals(highlight.getDescription()));
    }

    public void testReportsTransactionalOnIntegrationTestClass() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.TransactionalTest());
        this.myFixture.configureByText("UserServiceIT.java", """
                @Transactional
                class <warning descr="Avoid @Transactional on tests because rollback can hide SQL/flush failures">UserServiceIT</warning> { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsTransactionalOnIntegrationTestMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.TransactionalTest());
        this.myFixture.configureByText("ServiceSupportBranchIntegrationTests.java", """
                class ServiceSupportBranchIntegrationTests extends AbstractBambooIntegrationTests {
                    @Transactional
                    @Test
                    void <warning descr="Avoid @Transactional on tests because rollback can hide SQL/flush failures">activateMainContract_whenCachedM7ResponseVaries_setsCommentsWithoutExternalCall</warning>() { }
                }
                class AbstractBambooIntegrationTests { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testSuppressesOtherTransactionalPitfallsOnTransactionalTestMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.LongRunningOperation());
        this.myFixture.configureByText("ServiceSupportBranchIntegrationTests.java", """
                class ServiceSupportBranchIntegrationTests extends AbstractBambooIntegrationTests {
                    RestTemplate restTemplate;
                    @Transactional
                    @Test
                    void activateMainContract_whenCachedM7ResponseVaries_setsCommentsWithoutExternalCall() { this.restTemplate.getForObject("url", String.class); }
                    static class RestTemplate { Object getForObject(String url, Class<?> type) { return null; } }
                }
                class AbstractBambooIntegrationTests { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsReadOnlyEntityFetch() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.ReadOnlyEntityFetch());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional(readOnly = true)
                    User <warning descr="Read-only transaction appears to fetch entities; prefer DTO projections">findUser</warning>() { return repository.findById(); }
                    Repository repository;
                    interface Repository { User findById(); }
                    @Entity
                    static class User { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportWeakIsolationForReadOnlyMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.WeakIsolation());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { boolean readOnly() default false; }
                class Sample {
                    UserRepository repository;
                    @Transactional(readOnly = true)
                    void load() {
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                    @Transactional
                    void <error descr="Multiple reads with default/weak isolation can allow non-repeatable reads">loadWritable</error>() {
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                }
                interface UserRepository { User findById(long id); }
                class User { }
                """);
        this.myFixture.checkHighlighting(true, false, true, true);
    }

    public void testDoesNotReportWeakIsolationWhenRepositoryReadUsesLockAnnotation() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.WeakIsolation());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { boolean readOnly() default false; }
                @interface Lock { }
                class Sample {
                    UserRepository repository;
                    @Transactional
                    void load() {
                        this.repository.findByIdForUpdate(1L);
                        this.repository.findByIdForUpdate(2L);
                    }
                    @Transactional
                    void <error descr="Multiple reads with default/weak isolation can allow non-repeatable reads">loadWithoutLock</error>() {
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                }
                interface UserRepository {
                    User findById(long id);
                    @Lock User findByIdForUpdate(long id);
                }
                class User { }
                """);
        this.myFixture.checkHighlighting(true, false, true, true);
    }

    public void testDoesNotReportWeakIsolationWhenRepositoryQueryUsesForUpdate() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.WeakIsolation());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { boolean readOnly() default false; }
                @interface Query { String value(); }
                class Sample {
                    UserRepository repository;
                    @Transactional
                    void load() {
                        this.repository.findByIdForUpdate(1L);
                        this.repository.findById(2L);
                    }
                    @Transactional
                    void loadWithNoKeyUpdate() {
                        this.repository.findByIdNoKeyUpdate(1L);
                        this.repository.findById(2L);
                    }
                    @Transactional
                    void <error descr="Multiple reads with default/weak isolation can allow non-repeatable reads">loadWithoutLock</error>() {
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                }
                interface UserRepository {
                    @Query("select u from User u where u.id = ?1 FOR UPDATE") User findByIdForUpdate(long id);
                    @Query("select u from User u where u.id = ?1 FOR NO KEY UPDATE") User findByIdNoKeyUpdate(long id);
                    User findById(long id);
                }
                class User { }
                """);
        this.myFixture.checkHighlighting(true, false, true, true);
    }

    public void testDoesNotReportWeakIsolationWhenLockIsTakenInSameClassHelper() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.WeakIsolation());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { boolean readOnly() default false; }
                @interface Lock { }
                class Sample {
                    UserRepository repository;
                    @Transactional
                    void load() {
                        this.lockRow();
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                    @Transactional
                    void <error descr="Multiple reads with default/weak isolation can allow non-repeatable reads">loadWithoutLock</error>() {
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                    private void lockRow() { this.repository.findByIdForUpdate(1L); }
                }
                interface UserRepository {
                    User findById(long id);
                    @Lock User findByIdForUpdate(long id);
                }
                class User { }
                """);
        this.myFixture.checkHighlighting(true, false, true, true);
    }

    public void testReportsWeakIsolationForMultipleReadsWithoutLock() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.WeakIsolation());
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { boolean readOnly() default false; }
                class Sample {
                    UserRepository repository;
                    @Transactional
                    void <warning descr="Multiple reads with default/weak isolation can allow non-repeatable reads">load</warning>() {
                        this.repository.findById(1L);
                        this.repository.findById(2L);
                    }
                }
                class User { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportReadOnlyScalarContainerReturnType() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.ReadOnlyEntityFetch());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional(readOnly = true)
                    java.util.Map<String, Object> sperreStatusRestApi(String apartmentId) {
                        boolean count = apartmentRepository.countAllByIdAndLockState(Long.valueOf(apartmentId), "frei") > 0;
                        return java.util.Collections.singletonMap("check", count);
                    }
                    Repository apartmentRepository;
                    interface Repository { long countAllByIdAndLockState(Long id, String state); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsOpenSessionInView() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.OpenSessionInView());
        this.myFixture.configureByText("application.properties", """
                <warning descr="spring.jpa.open-in-view=true keeps database connections open during view rendering">spring.jpa.open-in-view=true</warning>
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsRepositoryLevelTransactional() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.RepositoryTransaction());
        this.myFixture.configureByText("UserRepository.java", """
                @Transactional
                interface <warning descr="Repository-level @Transactional is too narrow; prefer service-layer transaction boundaries">UserRepository</warning> { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsWeakIsolation() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.WeakIsolation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    @Transactional
                    void <warning descr="Multiple reads with default/weak isolation can allow non-repeatable reads">transfer</warning>() { repository.findById(); repository.findById(); }
                    interface Repository { Object findById(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsRollbackOnlyWithoutThrow() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.RollbackOnlyWithoutThrow());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    void <warning descr="setRollbackOnly() is used without an explicit thrown exception">save</warning>() { TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportRollbackOnlyWithoutThrowInTestMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.RollbackOnlyWithoutThrow());
        this.myFixture.configureByText("SampleTest.java", """
                @interface Test { }
                class SampleTest {
                    @Test
                    void save() { TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportRollbackOnlyWithoutThrowInTestClass() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.RollbackOnlyWithoutThrow());
        this.myFixture.configureByText("SampleTest.java", """
                class SampleTest {
                    void save() { TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMultipleWritesWhenSuperclassIsTransactional() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Transactional
                abstract class ActionServiceBase { }
                abstract class ActionServiceSupport extends ActionServiceBase {
                    Repository repository;
                    void stopCancellation() { repository.save(new Object()); repository.delete(new Object()); }
                    interface Repository { void save(Object value); void delete(Object value); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsUncalledPrivateHelperWithMultipleWrites() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    private void <error descr="Method performs multiple write operations without @Transactional">persist</error>() {
                        repository.save();
                        repository.delete();
                    }
                    interface Repository { void save(); void delete(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportMultipleWritesInsideTransactionTemplateCallback() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                package org.springframework.transaction.support;
                class Sample {
                    Repository repository;
                    TransactionTemplate transactionTemplate;
                    void update() {
                        transactionTemplate.execute(() -> {
                            repository.save();
                            repository.delete();
                            return null;
                        });
                    }
                }
                class TransactionTemplate {
                    <T> T execute(Callback<T> callback) { return callback.apply(); }
                }
                interface Callback<T> { T apply(); }
                interface Repository { void save(); void delete(); }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("writes inside a transaction-template callback are not reported")
                .noneMatch(highlight -> "Method performs multiple write operations without @Transactional"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportPrivateHelperReachedThroughPrivateChainFromTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    @Transactional
                    void update() { prepare(); }
                    private void prepare() { persist(); }
                    private void persist() {
                        repository.save();
                        repository.delete();
                    }
                    interface Repository { void save(); void delete(); }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("private writes reached through a transactional caller chain are not reported")
                .noneMatch(highlight -> "Method performs multiple write operations without @Transactional"
                        .equals(highlight.getDescription()));
    }

    public void testReportsPrivateHelperReachedFromNonTransactionalMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.MultipleWritesWithoutTransaction());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Repository repository;
                    public void update() { prepare(); }
                    private void prepare() { persist(); }
                    private void <error descr="Method performs multiple write operations without @Transactional">persist</error>() {
                        repository.save();
                        repository.delete();
                    }
                    interface Repository { void save(); void delete(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAndFixesTransactionalAbstractClass() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AbstractClassTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Transactional
                public abstract class <warning descr="Avoid @Transactional on abstract classes; define transactions on concrete implementations"><caret>BaseService</warning> {
                    public abstract void process();
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Remove @Transactional annotation");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                public abstract class BaseService {
                    public abstract void process();
                }
                """);
    }

    public void testNoReportForTransactionalInterface() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AbstractClassTransaction());
        this.myFixture.configureByText("Sample.java", """
                @Transactional(readOnly = true)
                public interface Repository {
                    Optional<Object> findById(Long id);
                }
                @interface Transactional {
                    boolean readOnly() default false;
                }
                class Optional<T> {
                    static <T> Optional<T> empty() { return new Optional<>(); }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAndFixesAsyncTransactionalEventListener() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AsyncTransactionalEventListener());
        this.myFixture.configureByText("Sample.java", """
                class EventPitfallService {
                    @Async
                    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                    public void <warning descr="Potential data race: @Async with @TransactionalEventListener may read data before commit is fully visible"><caret>handleEvent</warning>(Object event) { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAsyncTransactionalEventListenerWithoutPhaseAttribute() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.AsyncTransactionalEventListener());
        this.myFixture.configureByText("Sample.java", """
                class EventPitfallService {
                    @Async
                    @TransactionalEventListener
                    public void <warning descr="Potential data race: @Async with @TransactionalEventListener may read data before commit is fully visible"><caret>handlePartnerRelChangedEvent</warning>(Object event) { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportSelfInvocationWithMatchingTransactionSemantics() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.SelfInvocation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @org.springframework.transaction.annotation.Transactional
                    void outer() { inner(); }
                    @org.springframework.transaction.annotation.Transactional
                    void inner() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportSelfInvocationFromWriteToReadOnlyMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.SelfInvocation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @org.springframework.transaction.annotation.Transactional
                    void outer() { inner(); }
                    @org.springframework.transaction.annotation.Transactional(readOnly = true)
                    void inner() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsSelfInvocationFromReadOnlyToWriteMethod() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.SelfInvocation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @org.springframework.transaction.annotation.Transactional(readOnly = true)
                    void <warning descr="Self-invocation of @Transactional method bypasses the Spring proxy">outer</warning>() { inner(); }
                    @org.springframework.transaction.annotation.Transactional
                    void inner() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsSelfInvocationWhenCalleeChangesIsolation() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.SelfInvocation());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @org.springframework.transaction.annotation.Transactional
                    void <warning descr="Self-invocation of @Transactional method bypasses the Spring proxy">outer</warning>() { inner(); }
                    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
                    void inner() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsRequiresNewCalledInLoop() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.RequiresNewInLoopOrDeepStack());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    void batch() { for (int i = 0; i < 10; i++) { item(); } }
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    void <warning descr="REQUIRES_NEW may borrow extra database connections when called in loops or deep call chains">item</warning>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsRequiresNewDeepCallStack() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.RequiresNewInLoopOrDeepStack());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    @Transactional
                    void outer() { middle(); }
                    void middle() { inner(); }
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    void <warning descr="REQUIRES_NEW may borrow extra database connections when called in loops or deep call chains">inner</warning>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsNestedWithJpaTransactionManager() {
        this.myFixture.enableInspections(new SpringTransactionalPrivateMethodInspection.NestedWithJpa());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    JpaTransactionManager transactionManager;
                    @Transactional(propagation = Propagation.NESTED)
                    void <warning descr="Propagation.NESTED is not supported by standard JpaTransactionManager unless nested transactions are explicitly enabled">save</warning>() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }
}
