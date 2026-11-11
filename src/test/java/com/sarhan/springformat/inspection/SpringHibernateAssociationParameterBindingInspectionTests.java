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

import java.util.List;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.assertj.core.api.Assertions;

/**
 * Tests for the association-typed query parameter binding inspection. The bug class:
 * a String (or other non-association value) is bound to a named parameter that the
 * query compares against an association-typed path, which Hibernate 6 rejects at
 * runtime with a {@code QueryArgumentException} (older Hibernate silently coerced the
 * value).
 *
 * @author Akmal Sarhan
 */
public class SpringHibernateAssociationParameterBindingInspectionTests extends BasePlatformTestCase {

    private void addJakartaPersistenceFixtures() {
        this.myFixture.addFileToProject("jakarta/persistence/Entity.java", """
                package jakarta.persistence;
                public @interface Entity { }
                """);
        this.myFixture.addFileToProject("jakarta/persistence/Table.java", """
                package jakarta.persistence;
                public @interface Table { String name(); }
                """);
        this.myFixture.addFileToProject("jakarta/persistence/ManyToOne.java", """
                package jakarta.persistence;
                public @interface ManyToOne { boolean optional() default true; }
                """);
        this.myFixture.addFileToProject("jakarta/persistence/Id.java", """
                package jakarta.persistence;
                public @interface Id { }
                """);
        this.myFixture.addFileToProject("jakarta/persistence/EntityManager.java", """
                package jakarta.persistence;
                public interface EntityManager {
                    Query createQuery(String qlString, Class resultType);
                }
                """);
        this.myFixture.addFileToProject("jakarta/persistence/Query.java", """
                package jakarta.persistence;
                public interface Query {
                    Query setParameter(String name, Object value);
                    Object getSingleResult();
                    java.util.List getResultList();
                }
                """);
    }

    private void addDomainModelFixtures() {
        this.myFixture.addFileToProject("com/example/Mandant.java", """
                package com.example;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "ENUM_MANDANT")
                public class Mandant {
                    @Id
                    private String mandant;
                    private String authUser;
                    public Mandant() { }
                    public Mandant(String mandant) { this.mandant = mandant; }
                }
                """);
        this.myFixture.addFileToProject("com/example/LegalEntity.java", """
                package com.example;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "LU_LEGALENTITY")
                public class LegalEntity {
                    @Id
                    private Long internalId;
                    @ManyToOne(optional = false)
                    private Mandant mandant;
                    private String value;
                }
                """);
        this.myFixture.addFileToProject("com/example/ClassMapping.java", """
                package com.example;
                import jakarta.persistence.Entity;
                import jakarta.persistence.ManyToOne;
                @Entity
                public class ClassMapping {
                    private String clazz;
                    @ManyToOne(optional = false)
                    private Mandant mandant;
                    @ManyToOne
                    private LegalEntity legalEntity;
                }
                """);
    }

    private void enableAssociationParameterBindingInspection() {
        this.myFixture.enableInspections(new SpringHibernateMigrationInspection.AssociationParameterBinding());
    }

    public void testReportsStringBoundToRootAssociationPath() {
        this.addJakartaPersistenceFixtures();
        this.addDomainModelFixtures();
        this.enableAssociationParameterBindingInspection();
        this.myFixture.configureByText("Sample.java", """
                import com.example.LegalEntity;
                import jakarta.persistence.EntityManager;
                class Service {
                    Object run(EntityManager em, String mandant) {
                        return em.createQuery(\"\"\"
                                from LegalEntity p where p.mandant=:mandant and p.value=:value
                                \"\"\", LegalEntity.class)
                            .setParameter("mandant", mandant)
                            .setParameter("value", "E9")
                            .getSingleResult();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        List<HighlightInfo> problems = highlights.stream()
                .filter((HighlightInfo info) -> info.getDescription() != null
                        && info.getDescription().contains("association path 'p.mandant'"))
                .toList();
        Assertions.assertThat(problems)
                .as("String bound to the association path p.mandant")
                .hasSize(1);
        Assertions.assertThat(problems.get(0).getSeverity().getName()).isEqualTo("ERROR");
        Assertions.assertThat(problems.get(0).getDescription())
                .contains("LegalEntity.mandant")
                .contains("Mandant")
                .contains("p.mandant.mandant = :mandant");
    }

    public void testReportsStringBoundToJoinedAssociationPathButNotStringField() {
        this.addJakartaPersistenceFixtures();
        this.addDomainModelFixtures();
        this.enableAssociationParameterBindingInspection();
        this.myFixture.configureByText("Mapping.java", """
                import com.example.ClassMapping;
                import jakarta.persistence.EntityManager;
                import java.util.List;
                class Mapper {
                    List<?> map(EntityManager em, String mandant, String legalEntity) {
                        return em.createQuery(\"\"\"
                                SELECT v from ClassMapping v left join v.legalEntity le
                                where v.clazz = :clazz and v.mandant = :mandant and le.mandant = :mandant and le.value = :legalEntity
                                \"\"\", ClassMapping.class)
                            .setParameter("mandant", mandant)
                            .setParameter("legalEntity", legalEntity)
                            .getResultList();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        List<HighlightInfo> leProblems = highlights.stream()
                .filter((HighlightInfo info) -> info.getDescription() != null
                        && info.getDescription().contains("association path 'le.mandant'"))
                .toList();
        Assertions.assertThat(leProblems)
                .as("String bound to the joined association path le.mandant")
                .hasSize(1);
        List<HighlightInfo> vProblems = highlights.stream()
                .filter((HighlightInfo info) -> info.getDescription() != null
                        && info.getDescription().contains("association path 'v.mandant'"))
                .toList();
        Assertions.assertThat(vProblems)
                .as("v.mandant is also an association (the real ClassMapping.mandant is @ManyToOne) and must be flagged")
                .hasSize(1);
        Assertions.assertThat(leProblems.get(0).getDescription()).contains("le.mandant.mandant = :mandant");
        Assertions.assertThat(vProblems.get(0).getDescription()).contains("v.mandant.mandant = :mandant");
    }

    public void testNoWarningForNavigationFixOrEntityBinding() {
        this.addJakartaPersistenceFixtures();
        this.addDomainModelFixtures();
        this.enableAssociationParameterBindingInspection();
        this.myFixture.configureByText("Fixed.java", """
                import com.example.LegalEntity;
                import com.example.Mandant;
                import jakarta.persistence.EntityManager;
                class FixedService {
                    Object run(EntityManager em, String mandant, Mandant mandantEntity) {
                        return em.createQuery(\"\"\"
                                from LegalEntity p where p.mandant.mandant=:mandant and p.value=:value
                                \"\"\", LegalEntity.class)
                            .setParameter("mandant", mandant)
                            .setParameter("value", "E9")
                            .getSingleResult();
                    }
                    Object runWithEntity(EntityManager em, Mandant mandantEntity) {
                        return em.createQuery("from LegalEntity p where p.mandant=:mandant", LegalEntity.class)
                            .setParameter("mandant", mandantEntity)
                            .getSingleResult();
                    }
                }
                """);

        List<HighlightInfo> highlights = this.myFixture.doHighlighting();

        List<HighlightInfo> associationProblems = highlights.stream()
                .filter((HighlightInfo info) -> info.getDescription() != null
                        && info.getDescription().contains("association path"))
                .toList();
        Assertions.assertThat(associationProblems)
                .as("navigated relation and entity-typed bindings are valid")
                .isEmpty();
    }

}
