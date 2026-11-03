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

import org.assertj.core.api.Assertions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * @author Akmal Sarhan
 */
public class SpringTransactionalEventListenerWithoutTransactionInspectionTest extends BasePlatformTestCase {

    public void testReportsTransactionalEventListenerWithoutTransactionAndAddsTransactional() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                class Listener {
                    ItemRepository repository;
                    <error descr="@TransactionalEventListener should declare a transaction boundary or be in a transactional class"><caret>@TransactionalEventListener</error>
                    void handle() { repository.save(); }
                }
                interface ItemRepository { void save(); }
                @interface TransactionalEventListener { }
                """);
        this.myFixture.checkHighlighting(true, true, true, true);
        IntentionAction fix = this.myFixture.filterAvailableIntentions("Add @Transactional").get(0);
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class Listener {
                    ItemRepository repository;
                    @org.springframework.transaction.annotation.Transactional
                    @TransactionalEventListener
                    void handle() { repository.save(); }
                }
                interface ItemRepository { void save(); }
                @interface TransactionalEventListener { }
                """);
    }

    public void testConvertsToPlainEventListener() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                class Listener {
                    ItemRepository repository;
                    <error descr="@TransactionalEventListener should declare a transaction boundary or be in a transactional class"><caret>@TransactionalEventListener</error>
                    void handle() { repository.save(); }
                }
                interface ItemRepository { void save(); }
                @interface TransactionalEventListener { }
                """);
        this.myFixture.checkHighlighting(true, true, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Use @EventListener instead");
        this.myFixture.launchAction(fix);
        assertTrue(this.myFixture.getFile().getText().contains("@org.springframework.context.event.EventListener"));
    }

    public void testAddsRequiresNewTransactional() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                class Listener {
                    ItemRepository repository;
                    <error descr="@TransactionalEventListener should declare a transaction boundary or be in a transactional class"><caret>@TransactionalEventListener</error>
                    void handle() { repository.save(); }
                }
                interface ItemRepository { void save(); }
                @interface TransactionalEventListener { }
                """);
        this.myFixture.checkHighlighting(true, true, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Add @Transactional(REQUIRES_NEW)");
        this.myFixture.launchAction(fix);
        this.myFixture.checkResult("""
                class Listener {
                    ItemRepository repository;
                    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
                    @TransactionalEventListener
                    void handle() { repository.save(); }
                }
                interface ItemRepository { void save(); }
                @interface TransactionalEventListener { }
                """);
    }

    public void testDoesNotReportMethodTransactional() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                class Listener {
                    @Transactional
                    @TransactionalEventListener
                    void handle() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportClassTransactional() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                @Transactional
                class Listener {
                    @TransactionalEventListener
                    void handle() { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportListenerThatOnlyDelegatesToAnotherBean() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                class Listener {
                    PushService push;
                    <caret>@TransactionalEventListener
                    void handle() { push.send(); }
                }
                class PushService { void send() { } }
                @interface TransactionalEventListener { }
                """);
        this.myFixture.checkHighlighting(true, true, true, true);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Add @Transactional"))
                .as("delegating listeners should not offer a transaction quick fix")
                .isEmpty();
    }

    public void testDoesNotReportWriteInsideTransactionTemplateCallback() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                package org.springframework.transaction.support;
                @interface TransactionalEventListener { }
                class Listener {
                    ItemRepository repository;
                    TransactionTemplate tx;
                    <caret>@TransactionalEventListener
                    void handle() {
                        tx.execute(() -> { repository.save(); return null; });
                    }
                }
                class TransactionTemplate {
                    <T> T execute(Callback<T> callback) { return callback.apply(); }
                }
                interface Callback<T> { T apply(); }
                interface ItemRepository { void save(); }
                """);
        this.myFixture.checkHighlighting(true, true, true, true);
        Assertions.assertThat(this.myFixture.filterAvailableIntentions("Add @Transactional"))
                .as("transaction-template callbacks should not offer a transaction quick fix")
                .isEmpty();
    }

    public void testReportsWriteInSameClassHelper() {
        this.myFixture.enableInspections(new SpringTransactionalEventListenerWithoutTransactionInspection());
        this.myFixture.configureByText("Listener.java", """
                class Listener {
                    ItemRepository repository;
                    <error descr="@TransactionalEventListener should declare a transaction boundary or be in a transactional class"><caret>@TransactionalEventListener</error>
                    void handle() { persist(); }
                    private void persist() { repository.save(); }
                }
                interface ItemRepository { void save(); }
                @interface TransactionalEventListener { }
                """);
        this.myFixture.checkHighlighting(true, true, true, true);
    }
}
