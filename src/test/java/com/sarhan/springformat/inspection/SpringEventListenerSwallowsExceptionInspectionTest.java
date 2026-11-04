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
public class SpringEventListenerSwallowsExceptionInspectionTest extends BasePlatformTestCase {

    public void testFlagsBroadCatchOnlyLogs() {
        this.myFixture.enableInspections(new SpringEventListenerSwallowsExceptionInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface EventListener {}
                class Logger { void error(String s, Throwable t) {} }
                class Sample {
                    Logger log = new Logger();
                    @EventListener
                    void on(Object event) {
                        try {
                            doWork();
                        } catch (<warning descr="@EventListener swallows Exception; Spring will not see the failure — log and rethrow, or handle specifically">Exception</warning> ex) {
                            log.error("oops", ex);
                        }
                    }
                    void doWork() {}
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagWhenRethrows() {
        this.myFixture.enableInspections(new SpringEventListenerSwallowsExceptionInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface EventListener {}
                class Logger { void error(String s, Throwable t) {} }
                class Sample {
                    Logger log = new Logger();
                    @EventListener
                    void on(Object event) {
                        try {
                            doWork();
                        } catch (Exception ex) {
                            log.error("oops", ex);
                            throw new RuntimeException(ex);
                        }
                    }
                    void doWork() {}
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagSpecificCatch() {
        this.myFixture.enableInspections(new SpringEventListenerSwallowsExceptionInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface EventListener {}
                class Logger { void error(String s, Throwable t) {} }
                class Sample {
                    Logger log = new Logger();
                    @EventListener
                    void on(Object event) {
                        try {
                            doWork();
                        } catch (IllegalStateException ex) {
                            log.error("oops", ex);
                        }
                    }
                    void doWork() {}
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagAfterCommitTransactionalListener() {
        this.myFixture.enableInspections(new SpringEventListenerSwallowsExceptionInspection());
        this.myFixture.configureByText("Sample.java", """
                enum TransactionPhase { BEFORE_COMMIT, AFTER_COMMIT, AFTER_ROLLBACK, AFTER_COMPLETION }
                @interface TransactionalEventListener { TransactionPhase phase() default TransactionPhase.AFTER_COMMIT; }
                class Logger { void error(String s, Throwable t) {} }
                class Sample {
                    Logger log = new Logger();
                    @TransactionalEventListener
                    void onDefault(Object event) {
                        try {
                            doWork();
                        } catch (RuntimeException ex) {
                            log.error("oops", ex);
                        }
                    }
                    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                    void onCommit(Object event) {
                        try {
                            doWork();
                        } catch (RuntimeException ex) {
                            log.error("oops", ex);
                        }
                    }
                    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
                    void onCompletion(Object event) {
                        try {
                            doWork();
                        } catch (Exception ex) {
                            log.error("oops", ex);
                        }
                    }
                    void doWork() {}
                }
                """);
        this.myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null && info.getDescription().startsWith("@EventListener swallows"))
                .forEach(info -> fail("Unexpected: " + info.getText() + " -> " + info.getDescription()));
    }

    public void testFlagsBeforeCommitTransactionalListener() {
        this.myFixture.enableInspections(new SpringEventListenerSwallowsExceptionInspection());
        this.myFixture.configureByText("Sample.java", """
                enum TransactionPhase { BEFORE_COMMIT, AFTER_COMMIT, AFTER_ROLLBACK, AFTER_COMPLETION }
                @interface TransactionalEventListener { TransactionPhase phase() default TransactionPhase.AFTER_COMMIT; }
                class Logger { void error(String s, Throwable t) {} }
                class Sample {
                    Logger log = new Logger();
                    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
                    void on(Object event) {
                        try {
                            doWork();
                        } catch (<warning descr="@EventListener swallows RuntimeException; Spring will not see the failure — log and rethrow, or handle specifically">RuntimeException</warning> ex) {
                            log.error("oops", ex);
                        }
                    }
                    void doWork() {}
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagNonEventListenerMethod() {
        this.myFixture.enableInspections(new SpringEventListenerSwallowsExceptionInspection());
        this.myFixture.configureByText("Sample.java", """
                class Logger { void error(String s, Throwable t) {} }
                class Sample {
                    Logger log = new Logger();
                    void on(Object event) {
                        try {
                            doWork();
                        } catch (Exception ex) {
                            log.error("oops", ex);
                        }
                    }
                    void doWork() {}
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }
}
