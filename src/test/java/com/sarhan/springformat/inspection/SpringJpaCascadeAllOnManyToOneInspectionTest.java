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

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * @author Akmal Sarhan
 */
public class SpringJpaCascadeAllOnManyToOneInspectionTest extends BasePlatformTestCase {

    public void testFlagsCascadeAllOnManyToOne() {
        this.myFixture.enableInspections(new SpringJpaCascadeAllOnManyToOneInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface ManyToOne { CascadeType[] cascade() default {}; }
                enum CascadeType { ALL, PERSIST, MERGE }
                class Parent {}
                class Child {
                    @ManyToOne(cascade = <warning descr="cascade = ALL on @ManyToOne/@ManyToMany is an anti-pattern; cascade only what the owning side controls">CascadeType.ALL</warning>)
                    Parent parent;
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testFlagsCascadeAllInArrayOnManyToMany() {
        this.myFixture.enableInspections(new SpringJpaCascadeAllOnManyToOneInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface ManyToMany { CascadeType[] cascade() default {}; }
                enum CascadeType { ALL, PERSIST, MERGE }
                class Tag {}
                class Post {
                    @ManyToMany(cascade = <warning descr="cascade = ALL on @ManyToOne/@ManyToMany is an anti-pattern; cascade only what the owning side controls">{ CascadeType.PERSIST, CascadeType.ALL }</warning>)
                    java.util.List<Tag> tags;
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagPersistOnly() {
        this.myFixture.enableInspections(new SpringJpaCascadeAllOnManyToOneInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface ManyToOne { CascadeType[] cascade() default {}; }
                enum CascadeType { ALL, PERSIST, MERGE }
                class Parent {}
                class Child {
                    @ManyToOne(cascade = CascadeType.PERSIST)
                    Parent parent;
                }
                """);
        assertEquals(0, cascadeAllProblems());
    }

    public void testDoesNotFlagManyToOneWithUniqueJoinColumn() {
        this.myFixture.enableInspections(new SpringJpaCascadeAllOnManyToOneInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface ManyToOne { CascadeType[] cascade() default {}; }
                @interface JoinColumn { String name() default ""; boolean unique() default false; }
                enum CascadeType { ALL }
                class Voucher {}
                class Item {
                    @ManyToOne(cascade = CascadeType.ALL)
                    @JoinColumn(name = "VOUCHER", unique = true)
                    Voucher voucher;
                }
                """);
        assertEquals(0, cascadeAllProblems());
    }

    public void testFlagsManyToOneWithNonUniqueJoinColumn() {
        this.myFixture.enableInspections(new SpringJpaCascadeAllOnManyToOneInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface ManyToOne { CascadeType[] cascade() default {}; }
                @interface JoinColumn { String name() default ""; boolean unique() default false; }
                enum CascadeType { ALL }
                class Amount {}
                class Message {
                    @ManyToOne(cascade = <warning descr="cascade = ALL on @ManyToOne/@ManyToMany is an anti-pattern; cascade only what the owning side controls">CascadeType.ALL</warning>)
                    @JoinColumn(name = "AMOUNT", unique = false)
                    Amount amount;
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotFlagOneToMany() {
        this.myFixture.enableInspections(new SpringJpaCascadeAllOnManyToOneInspection());
        this.myFixture.configureByText("Sample.java", """
                @interface OneToMany { CascadeType[] cascade() default {}; }
                enum CascadeType { ALL }
                class Child {}
                class Parent {
                    @OneToMany(cascade = CascadeType.ALL)
                    java.util.List<Child> children;
                }
                """);
        assertEquals(0, cascadeAllProblems());
    }

    private int cascadeAllProblems() {
        return (int) this.myFixture.doHighlighting()
                .stream()
                .filter((HighlightInfo h) -> (h.getDescription() != null)
                        && h.getDescription().startsWith("cascade = ALL on @ManyToOne/@ManyToMany"))
                .count();
    }
}
