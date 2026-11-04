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
import org.assertj.core.api.Assertions;

/**
 * @author Akmal Sarhan
 */
public class SpringJpaModifyingWithoutClearAutomaticallyInspectionTest extends BasePlatformTestCase {

    public void testFlagsModifyingWithoutClearAutomatically() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Repo.java", """
                @interface Modifying { boolean clearAutomatically() default false; boolean flushAutomatically() default false; }
                interface Repo {
                    <warning descr="@Modifying without clearAutomatically=true may leave stale entities in the persistence context">@Modifying</warning>
                    int updateAll();
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testQuickFixAddsClearAutomatically() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Repo.java", """
                @interface Modifying { boolean clearAutomatically() default false; boolean flushAutomatically() default false; }
                interface Repo {
                    @Mod<caret>ifying
                    int updateAll();
                }
                """);
        com.intellij.codeInsight.intention.IntentionAction fix =
                this.myFixture.findSingleIntention("Add clearAutomatically = true");
        this.myFixture.launchAction(fix);
        String text = this.myFixture.getEditor().getDocument().getText();
        assertTrue("Expected clearAutomatically = true to be inserted, was:\n" + text,
                text.contains("clearAutomatically = true"));
    }

    public void testDoesNotFlagWhenAlreadyTrue() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Repo.java", """
                @interface Modifying { boolean clearAutomatically() default false; boolean flushAutomatically() default false; }
                interface Repo {
                    @Modifying(clearAutomatically = true)
                    int updateAll();
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsWhenEntityIsReadBeforeAndAfterBulkUpdate() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Sample.java", """
                import java.util.Optional;
                @interface Modifying { boolean clearAutomatically() default false; }
                @interface Query { String value(); }
                class Device { }
                interface Repository {
                    @Modifying
                    @Query("update Device d set d.active = false")
                    int updateDevices();
                    Optional<Device> findDevice();
                }
                class Service {
                    Repository repository;
                    void updateThenRead() {
                        Device before = this.repository.findDevice().orElseThrow();
                        this.repository.updateDevices();
                        this.repository.findDevice().orElseThrow();
                    }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("bulk updates followed by a read of a previously loaded entity are reported")
                .anyMatch(highlight -> "@Modifying without clearAutomatically=true may leave stale entities in the persistence context"
                        .equals(highlight.getDescription()));
    }

    public void testReportsWhenLoadedEntityIsMutatedAfterBulkUpdate() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface Modifying { boolean clearAutomatically() default false; }
                @interface Query { String value(); }
                class Device {
                    void setFcmToken(String token) { }
                }
                interface JpaRepository<E, ID> { }
                interface Repository extends JpaRepository<Device, Long> {
                    @Modifying
                    @Query("update Device d set d.fcmToken = null where d.id <> :id")
                    int clearFcmTokenOfOthers();
                    Device loadDevice();
                }
                class Service {
                    Repository repository;
                    void update() {
                        Device device = this.repository.loadDevice();
                        this.repository.clearFcmTokenOfOthers();
                        device.setFcmToken("new-token");
                    }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("a loaded entity that is mutated after the bulk update remains reported")
                .anyMatch(highlight -> "@Modifying without clearAutomatically=true may leave stale entities in the persistence context"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportWhenEntityIsLoadedOnlyAfterBulkUpdate() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Sample.java", """
                import java.util.Optional;
                @interface Modifying { boolean clearAutomatically() default false; }
                @interface Query { String value(); }
                class Device { }
                interface Repository {
                    @Modifying
                    @Query("update Device d set d.active = false")
                    int updateDevices();
                    Optional<Device> findDevice();
                }
                class Service {
                    Repository repository;
                    void updateThenLoadFresh() {
                        this.repository.updateDevices();
                        Device fresh = this.repository.findDevice().orElseThrow();
                    }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("a fresh entity load after the bulk update is not reported")
                .noneMatch(highlight -> "@Modifying without clearAutomatically=true may leave stale entities in the persistence context"
                        .equals(highlight.getDescription()));
    }

    public void testDoesNotReportWhenBulkUpdateExcludesTheLoadedEntity() {
        this.configureExcludedEntityFixture("""
                void update(Long id) {
                    Device device = this.repository.loadDevice(id).orElseThrow();
                    this.repository.clearFcmTokenOfOthers("token", device.getId());
                    device.setFcmToken("token");
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("an entity the bulk query excludes by id <> :param cannot go stale")
                .noneMatch(highlight -> "@Modifying without clearAutomatically=true may leave stale entities in the persistence context"
                        .equals(highlight.getDescription()));
    }

    public void testReportsWhenAnotherEntityIsLoadedBeforeExcludingBulkUpdate() {
        this.configureExcludedEntityFixture("""
                void update(Long id, Long otherId) {
                    Device device = this.repository.loadDevice(id).orElseThrow();
                    Device other = this.repository.loadDevice(otherId).orElseThrow();
                    this.repository.clearFcmTokenOfOthers("token", device.getId());
                    other.setFcmToken("token");
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("an entity the bulk query does not exclude remains reported")
                .anyMatch(highlight -> "@Modifying without clearAutomatically=true may leave stale entities in the persistence context"
                        .equals(highlight.getDescription()));
    }

    private void configureExcludedEntityFixture(String serviceMethod) {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.configureByText("Sample.java", """
                import java.util.Optional;
                @interface Modifying { boolean clearAutomatically() default false; boolean flushAutomatically() default false; }
                @interface Query { String value(); }
                @interface Param { String value(); }
                class Device {
                    Long getId() { return 1L; }
                    void setFcmToken(String token) { }
                }
                interface JpaRepository<E, ID> { }
                interface Repository extends JpaRepository<Device, Long> {
                    @Modifying(flushAutomatically = true)
                    @Query("update Device d set d.fcmToken = null where d.fcmToken = :token and d.id <> :id")
                    int clearFcmTokenOfOthers(@Param("token") String token, @Param("id") Long id);
                    Optional<Device> loadDevice(Long id);
                }
                class Service {
                    Repository repository;
                %s
                }
                """.formatted(serviceMethod));
    }

    public void testDoesNotReportWhenEntityIsLoadedOutsideTransactionTemplateCallback() {
        this.myFixture.enableInspections(new SpringJpaModifyingWithoutClearAutomaticallyInspection());
        this.myFixture.addFileToProject("org/springframework/data/jpa/repository/Modifying.java", """
                package org.springframework.data.jpa.repository;
                public @interface Modifying { boolean clearAutomatically() default false; }
                """);
        this.myFixture.configureByText("Sample.java", """
                package org.springframework.transaction.support;
                import java.util.Optional;
                import org.springframework.data.jpa.repository.Modifying;
                class Device { }
                interface JpaRepository<E, ID> { }
                interface Repository extends JpaRepository<Device, Long> {
                    @Modifying
                    int updateDevices();
                    Optional<Device> findDevice();
                }
                class Service {
                    Repository repository;
                    TransactionTemplate transactionTemplate;
                    void updateInCallback() {
                        Device before = this.repository.findDevice().orElseThrow();
                        this.transactionTemplate.execute(status -> {
                            this.repository.updateDevices();
                            return null;
                        });
                    }
                }
                class TransactionTemplate {
                    <T> T execute(java.util.function.Function<Object, T> callback) { return null; }
                }
                """);
        Assertions.assertThat(this.myFixture.doHighlighting())
                .as("an entity read outside the transaction-template callback does not make its update stale")
                .noneMatch(highlight -> "@Modifying without clearAutomatically=true may leave stale entities in the persistence context"
                        .equals(highlight.getDescription()));
    }
}
