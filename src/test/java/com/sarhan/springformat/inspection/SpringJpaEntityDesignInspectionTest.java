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
public class SpringJpaEntityDesignInspectionTest extends BasePlatformTestCase {

    public void testReportsAndFixesLombokDataOnEntity() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.LombokOnEntity());
        this.myFixture.configureByText("User.java", """
                @Entity
                <warning descr="Avoid broad Lombok-generated methods on JPA entities; they can trigger lazy loading or break equality semantics"><caret>@Data</warning>
                class User { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Remove Lombok annotation");
        this.myFixture.launchAction(fix);
        assertFalse(this.myFixture.getFile().getText().contains("@Data"));
    }

    public void testReportsAndFixesMissingNoArgConstructor() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.MissingNoArgConstructor());
        this.myFixture.configureByText("User.java", """
                @Entity
                class <warning descr="JPA entity should declare a protected no-arg constructor"><caret>User</warning> {
                    User(String name) { }
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Add protected no-arg constructor");
        this.myFixture.launchAction(fix);
        assertTrue(this.myFixture.getFile().getText().contains("protected User()"));
    }

    public void testReportsToOneWithoutLazyFetch() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.ToOneEagerDefault());
        this.myFixture.configureByText("User.java", """
                @Entity
                class User {
                    @ManyToOne
                    <warning descr="To-one JPA association uses eager loading by default; prefer fetch = FetchType.LAZY">Group group</warning>;
                }
                class Group { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testFixReplacesExistingEagerFetch() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.ToOneEagerDefault());
        this.myFixture.configureByText("User.java", """
                @Entity
                class User {
                    @ManyToOne(fetch = FetchType.EAGER)
                    <warning descr="To-one JPA association uses eager loading by default; prefer fetch = FetchType.LAZY"><caret>Group group</warning>;
                }
                class Group { }
                enum FetchType { EAGER, LAZY }
                @interface ManyToOne { FetchType fetch() default FetchType.EAGER; }
                @interface Entity { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);

        IntentionAction fix = this.myFixture.findSingleIntention("Add fetch = FetchType.LAZY");
        this.myFixture.launchAction(fix);

        this.myFixture.checkResult("""
                @Entity
                class User {
                    @ManyToOne(fetch = FetchType.LAZY)
                    Group group;
                }
                class Group { }
                enum FetchType { EAGER, LAZY }
                @interface ManyToOne { FetchType fetch() default FetchType.EAGER; }
                @interface Entity { }
                """);
    }

    public void testReportsAndFixesEnumeratedWithoutString() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.EnumeratedOrdinal());
        this.myFixture.configureByText("User.java", """
                @Entity
                class User {
                    @Enumerated
                    <warning descr="@Enumerated defaults to ORDINAL; use EnumType.STRING to avoid data corruption when enum order changes"><caret>Status status</warning>;
                }
                enum Status { ACTIVE, SUSPENDED, DELETED }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Use EnumType.STRING");
        this.myFixture.launchAction(fix);
        String fileText = this.myFixture.getFile().getText();
        assertTrue(fileText.contains("@Enumerated(EnumType.STRING)"));
        assertTrue(fileText.contains("//TODO migrate the column in the DB"));
    }

    public void testDoesNotReportEnumeratedString() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.EnumeratedOrdinal());
        this.myFixture.configureByText("User.java", """
                @Entity
                class User {
                    @Enumerated(EnumType.STRING)
                    Status status;
                }
                enum Status { ACTIVE }
        """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAndFixesMissingLobForClobColumn() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.MissingLobForClobColumn());
        this.myFixture.configureByText("Article.java", """
                @Entity
                class Article {
                    <error descr="@Column large-object definition should also declare @Lob so JPA maps the field as a large object">@Column(columnDefinition = "CLOB")
                    <caret>java.lang.String body;</error>
                }
                @interface Entity { }
                @interface Column { String columnDefinition() default ""; }
                @interface Lob { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);

        IntentionAction fix = this.myFixture.findSingleIntention("Add @Lob");
        this.myFixture.launchAction(fix);

        assertTrue(this.myFixture.getFile().getText().contains("@Lob"));
    }

    public void testReportsMissingLobForBlobColumnDefinition() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.MissingLobForClobColumn());
        this.myFixture.configureByText("Upload.java", """
                @Entity
                class Upload {
                    <error descr="@Column large-object definition should also declare @Lob so JPA maps the field as a large object">@Column(columnDefinition = "BLOB")
                    byte[] rawData;</error>
                }
                @interface Entity { }
                @interface Column { String columnDefinition() default ""; }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsMissingLobForTextColumnDefinition() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.MissingLobForClobColumn());
        this.myFixture.configureByText("Article.java", """
                @Entity
                class Article {
                    <error descr="@Column large-object definition should also declare @Lob so JPA maps the field as a large object">@Column(columnDefinition = "LONGTEXT")
                    java.lang.String body;</error>
                }
                @interface Entity { }
                @interface Column { String columnDefinition() default ""; }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportPlainTextOrArrayColumnDefinition() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.MissingLobForClobColumn());
        this.myFixture.configureByText("Article.java", """
                @Entity
                class Article {
                    @Column(columnDefinition = "text")
                    java.lang.String body;

                    @Column(columnDefinition = "text[]", nullable = false)
                    java.lang.String[] tags;

                    @Column(columnDefinition = "jsonb")
                    java.lang.String payload;
                }
                @interface Entity { }
                @interface Column { String columnDefinition() default ""; boolean nullable() default true; }
                """);
        org.assertj.core.api.Assertions.assertThat(this.myFixture.doHighlighting())
                .as("plain text, arrays and jsonb are ordinary columns, not large objects")
                .noneMatch(highlight -> highlight.getDescription() != null
                        && highlight.getDescription().startsWith("@Column large-object definition"));
    }

    public void testDoesNotReportClobColumnWhenLobPresent() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.MissingLobForClobColumn());
        this.myFixture.configureByText("Article.java", """
                @Entity
                class Article {
                    @Lob
                    @Column(columnDefinition = "CLOB")
                    java.lang.String body;
                }
                @interface Entity { }
                @interface Column { String columnDefinition() default ""; }
                @interface Lob { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsAndFixesAuditingFieldWithoutEntityListener() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.AuditingMissingListener());
        this.myFixture.configureByText("User.java", """
                @Entity
                class <warning descr="JPA auditing fields require @EntityListeners(AuditingEntityListener.class) on the entity"><caret>User</warning> {
                    @CreatedDate
                    java.time.Instant createdAt;
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
        IntentionAction fix = this.myFixture.findSingleIntention("Add @EntityListeners(AuditingEntityListener.class)");
        this.myFixture.launchAction(fix);
        assertTrue(this.myFixture.getFile().getText().contains("@EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)"));
    }

    public void testDoesNotReportAuditingFieldWithEntityListener() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.AuditingMissingListener());
        this.myFixture.configureByText("User.java", """
                @Entity
                @EntityListeners(AuditingEntityListener.class)
                class User {
                    @CreatedDate
                    java.time.Instant createdAt;
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportAuditingFieldWhenSuperclassHasEntityListener() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.AuditingMissingListener());
        this.myFixture.configureByText("User.java", """
                @MappedSuperclass
                @EntityListeners({ AuditingEntityListener.class })
                class AuditedEntity { }

                @Entity
                class User extends AuditedEntity {
                    @CreatedDate
                    java.time.Instant createdAt;
                }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testReportsOneToManyWithoutMappedBy() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.OneToManyWithoutMappedBy());
        this.myFixture.configureByText("Post.java", """
                @Entity
                class Post {
                    @OneToMany
                    <warning descr="@OneToMany without mappedBy creates an implicit join table; specify mappedBy on the inverse side">java.util.List<Comment> comments</warning>;
                }
                class Comment { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }

    public void testDoesNotReportOneToManyWithMappedBy() {
        this.myFixture.enableInspections(new SpringJpaEntityDesignInspection.OneToManyWithoutMappedBy());
        this.myFixture.configureByText("Post.java", """
                @Entity
                class Post {
                    @OneToMany(mappedBy = "post")
                    java.util.List<Comment> comments;
                }
                class Comment { }
                """);
        this.myFixture.checkHighlighting(false, false, true, true);
    }
}
