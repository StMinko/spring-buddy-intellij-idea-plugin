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
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sarhan.dataFlow.SpringLazyInitializationPredictionDataFlowInspection;
import org.assertj.core.api.Assertions;

/**
 * @author Akmal Sarhan
 */
public class SpringLazyInitializationPredictionDataFlowInspectionTest extends BasePlatformTestCase {

    public void testReportsLazyGetterOutsideTx() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                import java.util.List;
                @Entity
                class Order {
                    @OneToMany
                    List<Item> getItems() { return null; }
                }
                class Item { }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @interface Service { }
                @interface Repository { }
                @interface GetMapping { String value(); }
                @interface RestController { }
                @interface PathVariable { }
                @interface Autowired { }

                @Repository
                interface OrderRepo {
                    Order findById(Long id);
                }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderRepo repo;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Order order = repo.findById(id);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(1, (int) count);
    }

    public void testNoReportInsideTransactional() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                import java.util.List;
                @Entity
                class Order {
                    @OneToMany
                    List<Item> getItems() { return null; }
                }
                class Item { }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @interface Service { }
                @interface Repository { }

                @Repository
                interface OrderRepo {
                    Order findById(Long id);
                }

                @Service
                class Sample {
                    OrderRepo repo;

                    @Transactional
                    void getOrder(Long id) {
                        Order order = repo.findById(id);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportOnPlainObject() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Sample.java", """
                class Order {
                    java.util.List<String> getItems() { return null; }
                }
                class Sample {
                    void getOrder() {
                        Order order = new Order();
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportWhenClassIsTransactional() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                import java.util.List;
                @Entity
                class Order {
                    @OneToMany
                    List<Item> getItems() { return null; }
                }
                class Item { }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @interface Service { }
                @interface Repository { }

                @Repository
                interface OrderRepo {
                    Order findById(Long id);
                }

                @Service
                @Transactional
                class Sample {
                    OrderRepo repo;

                    void getOrder(Long id) {
                        Order order = repo.findById(id);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportInTestClassByName() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                import java.util.List;
                @Entity
                class Order {
                    @OneToMany
                    List<Item> getItems() { return null; }
                }
                class Item { }
                """);
        this.myFixture.configureByText("OrderServiceTest.java", """
                @interface SpringBootTest { }
                @interface Service { }
                @interface Repository { }

                @Repository
                interface OrderRepo {
                    Order findById(Long id);
                }

                @SpringBootTest
                class OrderServiceTest {
                    OrderRepo repo;

                    void shouldLoadOrder() {
                        Order order = repo.findById(1L);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportInTestClassByAnnotation() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                import java.util.List;
                @Entity
                class Order {
                    @OneToMany
                    List<Item> getItems() { return null; }
                }
                class Item { }
                """);
        this.myFixture.configureByText("OrderServiceIT.java", """
                @interface DataJpaTest { }
                @interface Service { }
                @interface Repository { }

                @Repository
                interface OrderRepo {
                    Order findById(Long id);
                }

                @DataJpaTest
                class OrderServiceIT {
                    OrderRepo repo;

                    void shouldLoadOrder() {
                        Order order = repo.findById(1L);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportForDefaultEagerManyToOneGetterOutsideTx() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Event.java", """
                @interface Entity { }
                @interface ManyToOne { }
                @Entity
                class Contract { }
                @Entity
                class Event {
                    @ManyToOne
                    Contract contract;
                    Contract getContract() { return contract; }
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Repository { }
                @Repository
                interface EventRepository {
                    java.util.List<Event> findAllByFinishedFalse();
                }
                class Sample {
                    EventRepository repo;
                    void process() {
                        java.util.List<Event> events = repo.findAllByFinishedFalse();
                        for (Event event : events) {
                            Contract contract = event.getContract();
                        }
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testReportsExplicitLazyManyToOneGetterOutsideTx() {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Event.java", """
                @interface Entity { }
                @interface ManyToOne { FetchType fetch() default FetchType.EAGER; }
                enum FetchType { EAGER, LAZY }
                @Entity
                class Contract { }
                @Entity
                class Event {
                    @ManyToOne(fetch = FetchType.LAZY)
                    Contract contract;
                    Contract getContract() { return contract; }
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Repository { }
                @Repository
                interface EventRepository {
                    java.util.List<Event> findAllByFinishedFalse();
                }
                class Sample {
                    EventRepository repo;
                    void process() {
                        java.util.List<Event> events = repo.findAllByFinishedFalse();
                        for (Event event : events) {
                            Contract contract = event.getContract();
                        }
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
        assertEquals(1, (int) count);
    }

    public void testDoesNotReportLazyGetterFollowedByGetId() {
        this.configureLazyInitializationFixture("""
                void process(Long id) {
                    Order order = repo.findById(id);
                    order.getCustomer().getId();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("reading the identifier does not initialize the lazy association")
                .isZero();
    }

    public void testReportsLazyGetterFollowedByEntityPropertyRead() {
        this.configureLazyInitializationFixture("""
                void process(Long id) {
                    Order order = repo.findById(id);
                    order.getCustomer().getName();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("reading a property of the lazy association remains reported")
                .isEqualTo(1);
    }

    public void testDoesNotReportPrivateHelperCalledFromTransactionalMethod() {
        this.configureLazyInitializationFixture("""
                @Transactional
                void process(Long id) {
                    readOrder(id);
                }

                private void readOrder(Long id) {
                    Order order = repo.findById(id);
                    order.getItems().size();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("a private helper reached only from a transactional method is covered")
                .isZero();
    }

    public void testReportsPrivateHelperCalledFromNonTransactionalMethod() {
        this.configureLazyInitializationFixture("""
                void process(Long id) {
                    readOrder(id);
                }

                private void readOrder(Long id) {
                    Order order = repo.findById(id);
                    order.getItems().size();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("a private helper reached from a non-transactional method remains reported")
                .isEqualTo(1);
    }

    public void testDoesNotReportAssociationFetchedByQuery() {
        this.configureLazyInitializationFixture("""
                void process(Long id) {
                    Order order = repo.findWithCustomerById(id);
                    order.getCustomer().getName();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("an association fetched by the source query is already initialized")
                .isZero();
    }

    public void testDoesNotReportAssociationFetchedByEntityGraph() {
        this.configureLazyInitializationFixture("""
                void process(Long id) {
                    Order order = repo.findWithCustomerGraphById(id);
                    order.getCustomer().getName();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("an association included in the source entity graph is already initialized")
                .isZero();
    }

    public void testReportsFetchAfterSourceVariableIsAliased() {
        this.configureLazyInitializationFixture("""
                void process(Long id) {
                    Order order = repo.findWithCustomerById(id);
                    Order alias = order;
                    order = alias;
                    order.getCustomer().getName();
                }
                """);

        Assertions.assertThat(this.lazyInitializationProblemCount())
                .as("fetch metadata is not propagated through aliases")
                .isEqualTo(1);
    }

    private void configureLazyInitializationFixture(String methods) {
        this.myFixture.enableInspections(new SpringLazyInitializationPredictionDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                @interface ManyToOne { FetchType fetch() default FetchType.EAGER; }
                enum FetchType { EAGER, LAZY }

                @Entity
                class Customer {
                    Long getId() { return 1L; }
                    String getName() { return "name"; }
                }

                @Entity
                class Order {
                    @ManyToOne(fetch = FetchType.LAZY)
                    Customer customer;

                    Customer getCustomer() { return customer; }

                    @OneToMany
                    java.util.List<Item> getItems() { return null; }
                }

                class Item { }
                """);
        this.myFixture.configureByText("OrderRepository.java", """
                @interface Repository { }
                @interface Query { String value(); }
                @interface EntityGraph { String[] attributePaths() default {}; }

                @Repository
                interface OrderRepository {
                    Order findById(Long id);

                    @Query("select o from Order o join fetch o.customer")
                    Order findWithCustomerById(Long id);

                    @EntityGraph(attributePaths = "customer")
                    Order findWithCustomerGraphById(Long id);
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }

                class Sample {
                    OrderRepository repo;

                %s
                }
                """.formatted(methods));
    }

    private long lazyInitializationProblemCount() {
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        return highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("LazyInitializationException"))
                .count();
    }
}
