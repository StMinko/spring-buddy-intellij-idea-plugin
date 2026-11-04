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
import com.sarhan.dataFlow.SpringTransactionalDirtyCheckingDataFlowInspection;

/**
 * @author Akmal Sarhan
 */
public class SpringTransactionalDirtyCheckingDataFlowInspectionTest extends BasePlatformTestCase {

    public void testReportsAndFixesTransactionalReadMethodThatMutatesLoadedEntity() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional
                    String <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes"><caret>calculate</warning>(Long id) {
                        User user = userRepository.findById(id);
                        user.setName("temporary");
                        return user.getName();
                    }
                }
                class User {
                    void setName(String name) { }
                    String getName() { return ""; }
                }
                interface UserRepository {
                    User findById(Long id);
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Set readOnly = true");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional(readOnly = true)
                    String calculate(Long id) {
                        User user = userRepository.findById(id);
                        user.setName("temporary");
                        return user.getName();
                    }
                }
                class User {
                    void setName(String name) { }
                    String getName() { return ""; }
                }
                interface UserRepository {
                    User findById(Long id);
                }
                """);
    }

    public void testAddsReadOnlyToExistingTransactionalAttributes() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional(timeout = 5)
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes"><caret>calculate</warning>() {
                        User user = userRepository.getReferenceById(1L);
                        user.setName("temporary");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { User getReferenceById(Long id); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Set readOnly = true");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional(timeout = 5, readOnly = true)
                    void calculate() {
                        User user = userRepository.getReferenceById(1L);
                        user.setName("temporary");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { User getReferenceById(Long id); }
                """);
    }

    public void testFixReplacesExistingReadOnlyFalseAttribute() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional(readOnly = false, timeout = 5)
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes"><caret>calculate</warning>() {
                        User user = userRepository.getReferenceById(1L);
                        user.setName("temporary");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { User getReferenceById(Long id); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Set readOnly = true");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional(readOnly = true, timeout = 5)
                    void calculate() {
                        User user = userRepository.getReferenceById(1L);
                        user.setName("temporary");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { User getReferenceById(Long id); }
                """);
    }

    public void testReportsOptionalGetAfterRepositoryFindById() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                import java.util.Optional;
                class UserService {
                    UserRepository repo;
                
                    @Transactional
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes">update</warning>(Long id) {
                        User user = repo.findById(id).get();
                        user.setName("Oops");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { Optional<User> findById(Long id); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportReadOnlyTransactionOrWriteMethod() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional(readOnly = true)
                    void calculate(Long id) {
                        User user = userRepository.findById(id);
                        user.setName("temporary");
                    }
                
                    @Transactional
                    void update(Long id) {
                        User user = userRepository.findById(id);
                        user.setName("real");
                        userRepository.save(user);
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { User findById(Long id); User save(User user); }
                """);
        assertNoDirtyCheckingProblem();
    }

    public void testDoesNotReportSaveAndFlushWrite() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.addFileToProject("org/springframework/data/repository/Repository.java",
                "package org.springframework.data.repository; public interface Repository<T, ID> {}");
        this.myFixture.configureByText("UserService.java", """
                import org.springframework.data.repository.Repository;
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional
                    void update(Long id) {
                        User user = userRepository.findById(id);
                        user.setName("real");
                        userRepository.saveAndFlush(user);
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository extends Repository<User, Long> { User findById(Long id); User saveAndFlush(User user); }
                """);
        assertNoDirtyCheckingProblem();
    }

    public void testDoesNotReportWhenEntityIsLoadedWithWriteLock() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.addFileToProject("jakarta/persistence/LockModeType.java",
                "package jakarta.persistence; public enum LockModeType { PESSIMISTIC_READ, PESSIMISTIC_WRITE }");
        this.myFixture.addFileToProject("org/springframework/data/jpa/repository/Lock.java", """
                package org.springframework.data.jpa.repository;
                public @interface Lock { jakarta.persistence.LockModeType value(); }
                """);
        this.myFixture.configureByText("UserService.java", """
                import jakarta.persistence.LockModeType;
                import org.springframework.data.jpa.repository.Lock;
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional
                    void accept(Long id) {
                        User user = userRepository.findById(id);
                        User locked = userRepository.lockById(id);
                        user.setName("real");
                        locked.setName("real");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository {
                    User findById(Long id);
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    User lockById(Long id);
                }
                """);
        assertNoDirtyCheckingProblem();
    }

    public void testReportsWhenEntityIsLoadedWithReadLockOnly() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.addFileToProject("jakarta/persistence/LockModeType.java",
                "package jakarta.persistence; public enum LockModeType { PESSIMISTIC_READ, PESSIMISTIC_WRITE }");
        this.myFixture.addFileToProject("org/springframework/data/jpa/repository/Lock.java", """
                package org.springframework.data.jpa.repository;
                public @interface Lock { jakarta.persistence.LockModeType value(); }
                """);
        this.myFixture.configureByText("UserService.java", """
                import jakarta.persistence.LockModeType;
                import org.springframework.data.jpa.repository.Lock;
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes">calculate</warning>(Long id) {
                        User user = userRepository.lockById(id);
                        user.setName("temporary");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository {
                    @Lock(LockModeType.PESSIMISTIC_READ)
                    User lockById(Long id);
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    /**
     * DataFlow-specific: alias propagation through reassignment.
     * The old string-name tracker would not recognise that u2 refers to the
     * same loaded entity as user.
     */
    public void testReportsAliasPropagation() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional
                    String <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes"><caret>calculate</warning>(Long id) {
                        User user = userRepository.findById(id);
                        User u2 = user;
                        u2.setName("temporary");
                        return u2.getName();
                    }
                }
                class User {
                    void setName(String name) { }
                    String getName() { return ""; }
                }
                interface UserRepository {
                    User findById(Long id);
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    /**
     * DataFlow-specific: cast passthrough.
     * The old string-name tracker would break on a cast because the variable
     * name no longer matches the original loaded entity name.
     */
    public void testReportsCastPassthrough() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    UserRepository userRepository;
                
                    @Transactional
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes"><caret>calculate</warning>(Long id) {
                        Object raw = userRepository.findById(id);
                        User user = (User) raw;
                        user.setName("temporary");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { Object findById(Long id); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    /**
     * DataFlow-specific: chained Optional without intermediate variable.
     * {@code repo.findById(id).orElse(null)} must be peeled to reach the
     * repository call.
     */
    public void testReportsChainedOptionalOrElse() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                import java.util.Optional;
                class UserService {
                    UserRepository repo;
                
                    @Transactional
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes">update</warning>(Long id) {
                        User user = repo.findById(id).orElse(null);
                        user.setName("Oops");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { Optional<User> findById(Long id); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    /**
     * Multiple mutations on the same loaded entity should only produce ONE
     * warning on the method identifier (deduplication).
     */
    public void testOnlyOneWarningForMultipleMutations() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                import java.util.Optional;
                class UserService {
                    UserRepository repo;
                
                    @Transactional
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes">update</warning>(Long id) {
                        User user = repo.findById(id).orElse(null);
                        user.setName("A");
                        user.setName("B");
                    }
                }
                class User { void setName(String name) { } }
                interface UserRepository { Optional<User> findById(Long id); }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    /**
     * DataFlow-specific: EntityManager source should be tracked the same as
     * a Spring Data repository source.
     */
    public void testReportsEntityManagerSource() {
        this.myFixture.enableInspections(new SpringTransactionalDirtyCheckingDataFlowInspection());
        this.myFixture.configureByText("UserService.java", """
                class UserService {
                    EntityManager em;
                
                    @Transactional
                    void <warning descr="@Transactional method loads and mutates an entity without readOnly=true; dirty checking may persist temporary changes">update</warning>(Long id) {
                        User user = em.find(User.class, id);
                        user.setName("Oops");
                    }
                }
                class User { void setName(String name) { } }
                class EntityManager {
                    User find(Class<User> clazz, Long id) { return null; }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    private void assertNoDirtyCheckingProblem() {
        assertTrue(this.myFixture.doHighlighting().stream()
                .noneMatch(info -> info.getDescription() != null
                        && info.getDescription().startsWith("@Transactional method loads and mutates")));
    }
}
