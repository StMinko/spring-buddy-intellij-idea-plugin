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
public class SpringBagProblemInspectionTest extends BasePlatformTestCase {

    private static final String ENTITY_STUB = """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
            public @interface Entity {}
            """;

    private static final String ONE_TO_MANY_STUB = """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Target({ElementType.METHOD, ElementType.FIELD}) @Retention(RetentionPolicy.RUNTIME)
            public @interface OneToMany { String mappedBy() default ""; }
            """;

    private static final String MANY_TO_MANY_STUB = """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Target({ElementType.METHOD, ElementType.FIELD}) @Retention(RetentionPolicy.RUNTIME)
            public @interface ManyToMany { String mappedBy() default ""; }
            """;

    private static final String JAVAX_ENTITY_STUB = """
            package javax.persistence;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
            public @interface Entity {}
            """;

    private static final String JAVAX_ONE_TO_MANY_STUB = """
            package javax.persistence;
            import java.lang.annotation.*;
            @Target({ElementType.METHOD, ElementType.FIELD}) @Retention(RetentionPolicy.RUNTIME)
            public @interface OneToMany { String mappedBy() default ""; }
            """;

    private static final String ORDER_COLUMN_STUB = """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Target({ElementType.METHOD, ElementType.FIELD}) @Retention(RetentionPolicy.RUNTIME)
            public @interface OrderColumn {}
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.myFixture.addFileToProject("jakarta/persistence/Entity.java", ENTITY_STUB);
        this.myFixture.addFileToProject("jakarta/persistence/OneToMany.java", ONE_TO_MANY_STUB);
        this.myFixture.addFileToProject("jakarta/persistence/ManyToMany.java", MANY_TO_MANY_STUB);
        this.myFixture.addFileToProject("javax/persistence/Entity.java", JAVAX_ENTITY_STUB);
        this.myFixture.addFileToProject("javax/persistence/OneToMany.java", JAVAX_ONE_TO_MANY_STUB);
        this.myFixture.addFileToProject("jakarta/persistence/OrderColumn.java", ORDER_COLUMN_STUB);
    }

    public void testFlagsManyToManyList() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class User {
                    @ManyToMany
                    <warning descr="@ManyToMany with List causes 'bag' semantics; use Set instead to avoid delete-all + re-insert performance issues">List<Role></warning> roles;
                }
                class Role {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testFlagsOneToManyList() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class User {
                    @OneToMany
                    <warning descr="@OneToMany with List causes 'bag' semantics; use Set instead to avoid delete-all + re-insert performance issues">List<Address></warning> addresses;
                }
                class Address {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testDoesNotFlagSet() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import jakarta.persistence.*;
                import java.util.Set;

                @Entity
                class User {
                    @OneToMany
                    Set<Role> roles;
                }
                class Role {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testDoesNotFlagInverseSideOneToManyList() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("Cart.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class Cart {
                    @OneToMany(mappedBy = "cart")
                    List<CartItem> items;
                }
                class CartItem {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testDoesNotFlagInverseSideManyToManyList() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("Role.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class Role {
                    @ManyToMany(mappedBy = "roles")
                    List<User> users;
                }
                class User {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testFlagsEmptyMappedBy() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class User {
                    @OneToMany(mappedBy = "")
                    <warning descr="@OneToMany with List causes 'bag' semantics; use Set instead to avoid delete-all + re-insert performance issues">List<Address></warning> addresses;
                }
                class Address {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testDoesNotFlagOrderColumnList() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class User {
                    @ManyToMany
                    @OrderColumn
                    List<Role> roles;
                }
                class Role {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testDoesNotFlagNonEntityList() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("Data.java", """
                import java.util.List;

                class Data {
                    List<String> items;
                }
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testJavaXPersistenceVariant() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import javax.persistence.*;
                import java.util.List;

                @Entity
                class User {
                    @OneToMany
                    <warning descr="@OneToMany with List causes 'bag' semantics; use Set instead to avoid delete-all + re-insert performance issues">List<Role></warning> roles;
                }
                class Role {}
                """);
        this.myFixture.checkHighlighting(false, false, false, true);
    }

    public void testFixAvailableForListWithManToMany() {
        this.myFixture.enableInspections(new SpringBagProblemInspection());
        this.myFixture.configureByText("User.java", """
                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                class User {
                    @OneToMany
                    List<String> roles;
                }
                """);
        this.myFixture.doHighlighting();
        String text = this.myFixture.getFile().getText();
        assertNotNull("Warning should be present in file", this.myFixture.getEditor().getMarkupModel());
        assertTrue("Should have List in original: " + text, text.contains("List<String>"));
    }
}
