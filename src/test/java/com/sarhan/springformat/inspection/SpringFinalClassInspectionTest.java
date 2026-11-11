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

/**
 * @author Akmal Sarhan
 */
public class SpringFinalClassInspectionTest extends BasePlatformTestCase {

    public void testFlagsClassWithOnlyPrivateConstructors() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class <warning descr="Class can be declared final">Sample</warning> {
                    private Sample() {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testFlagsPrivateClassWithImplicitConstructor() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    private static class <warning descr="Class can be declared final">Nested</warning> {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagClassWithProtectedConstructorOnly() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    protected Sample() {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagGenericBaseClassWithProtectedConstructor() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("AbstractAuditedSpecificationClient.java", """
                public class AbstractAuditedSpecificationClient<E, I> {
                    protected AbstractAuditedSpecificationClient(String baseUrl) {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagClassWithPrivateAndProtectedConstructors() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    private Sample() {
                    }
                    protected Sample(String value) {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testFlagsPrivateStaticClassWithLombokAllArgsConstructor() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                import lombok.AllArgsConstructor;
                import lombok.Getter;

                class SbcEmailTests {

                    @Getter
                    @AllArgsConstructor
                    private static class <warning descr="Class can be declared final">ValidateMe</warning> {

                        private String email;

                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagPublicClassWithImplicitConstructor() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagClassWithNonPrivateConstructor() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class Sample {
                    Sample() {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testMakeClassFinalFix() {
        this.myFixture.enableInspections(new SpringFinalClassInspection());
        this.myFixture.configureByText("Sample.java", """
                class <warning descr="Class can be declared final"><caret>Sample</warning> {
                    private Sample() {
                    }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Make class final");
        this.myFixture.launchAction(fix);
        assertTrue(this.myFixture.getFile().getText().contains("final class Sample"));
    }
}
