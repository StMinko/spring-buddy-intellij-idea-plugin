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
import com.sarhan.dataFlow.SpringEntityTransactionBoundaryEscapeDataFlowInspection;

/**
 * @author Akmal Sarhan
 */
public class SpringEntityTransactionBoundaryEscapeDataFlowInspectionTest extends BasePlatformTestCase {

    public void testReportsLazyAccessOutsideTx() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
                @interface RestController { }
                @interface GetMapping { String value(); }
                @interface Autowired { }
                @interface PathVariable { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderService service;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Order order = service.findOrder(id);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(1, (int) count);
    }

    public void testNoReportInsideTransactionalCaller() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }

                    @Transactional
                    void processOrder(Long id) {
                        Order order = findOrder(id);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testReportsMutationOutsideTx() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @Entity
                class Order {
                    void setStatus(String s) { }
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @interface Service { }
                @interface RestController { }
                @interface GetMapping { String value(); }
                @interface Autowired { }
                @interface PathVariable { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderService service;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Order order = service.findOrder(id);
                        order.setStatus("SHIPPED");
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(1, (int) count);
    }

    public void testNoReportWhenMutatedEntityIsSavedAfterwards() {
        assertEquals(0, this.mutationThenPersistProblemCount("this.orders.save(order);"));
    }

    public void testReportsMutationWhenAnotherEntityIsSaved() {
        assertEquals(1, this.mutationThenPersistProblemCount("this.orders.save(new Order());"));
    }

    public void testReportsMutationSavedBeforeTheChange() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
        this.configureMutationThenPersist("""
                Order order = service.findOrder(id);
                this.orders.save(order);
                order.setStatus("SHIPPED");
                """);
        assertEquals(1, (int) this.boundaryEscapeProblemCount());
    }

    private long mutationThenPersistProblemCount(String persistStatement) {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
        this.configureMutationThenPersist("""
                Order order = service.findOrder(id);
                order.setStatus("SHIPPED");
                %s
                """.formatted(persistStatement));
        return this.boundaryEscapeProblemCount();
    }

    private void configureMutationThenPersist(String body) {
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @Entity
                class Order {
                    void setStatus(String s) { }
                }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @interface Service { }
                @interface Repository { }

                @Repository
                interface OrderRepository {
                    Order save(Order order);
                }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @Service
                class Sample {
                    OrderService service;
                    OrderRepository orders;

                    void ship(Long id) {
                %s
                    }
                }
                """.formatted(body));
    }

    private long boundaryEscapeProblemCount() {
        return this.myFixture.doHighlighting().stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
    }

    public void testNoReportWhenCallerClassIsTransactional() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @Service
                @Transactional
                class Sample {
                    OrderService service;

                    void processOrder(Long id) {
                        Order order = service.findOrder(id);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportInTestClassByName() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
                @interface Transactional { }
                @interface Service { }
                @interface SpringBootTest { }
                @interface Autowired { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @SpringBootTest
                class OrderServiceTest {
                    @Autowired
                    OrderService service;

                    void shouldLoadOrder() {
                        Order order = service.findOrder(1L);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportInTestClassBySuperClassAnnotation() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
        this.myFixture.configureByText("BaseIntegrationTest.java", """
                @interface SpringBootTest { }

                @SpringBootTest
                class BaseIntegrationTest { }
                """);
        this.myFixture.configureByText("OrderServiceTest.java", """
                @interface Transactional { }
                @interface Service { }
                @interface Autowired { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                class OrderServiceTest extends BaseIntegrationTest {
                    @Autowired
                    OrderService service;

                    void shouldLoadOrder() {
                        Order order = service.findOrder(1L);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(0, (int) count);
    }

    public void testNoReportInTestClassByNameSuffix() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
                @interface Transactional { }
                @interface Service { }
                @interface Autowired { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                class OrderServiceIT {
                    @Autowired
                    OrderService service;

                    void shouldLoadOrder() {
                        Order order = service.findOrder(1L);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(0, (int) count);
    }

    /**
     * DataFlow-specific: alias propagation.
     * The old PSI-only implementation tracked only the direct variable name;
     * reassignment to a new variable broke the chain.
     */
    public void testReportsAliasPropagation() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
                @interface RestController { }
                @interface GetMapping { String value(); }
                @interface Autowired { }
                @interface PathVariable { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderService service;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Order order = service.findOrder(id);
                        Order alias = order;
                        alias.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(1, (int) count);
    }

    /**
     * DataFlow-specific: Optional unwrap chain from @Transactional return.
     * The old PSI-only implementation only tracked direct assignment.
     */
    public void testReportsOptionalUnwrap() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
                import java.util.Optional;
                @interface Transactional { }
                @interface Service { }
                @interface RestController { }
                @interface GetMapping { String value(); }
                @interface Autowired { }
                @interface PathVariable { }

                @Service
                class OrderService {
                    @Transactional
                    Optional<Order> findOrderOptional(Long id) { return Optional.of(new Order()); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderService service;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Optional<Order> opt = service.findOrderOptional(id);
                        Order order = opt.get();
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(1, (int) count);
    }

    /**
     * Multiple lazy accesses on the same escaped entity should each get their own
     * highlight on the call site.
     */
    public void testReportsMultipleLazyAccesses() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
        this.myFixture.configureByText("Order.java", """
                @interface Entity { }
                @interface OneToMany { }
                import java.util.List;
                @Entity
                class Order {
                    @OneToMany
                    List<Item> getItems() { return null; }
                    @OneToMany
                    List<Tag> getTags() { return null; }
                }
                class Item { }
                class Tag { }
                """);
        this.myFixture.configureByText("Sample.java", """
                @interface Transactional { }
                @interface Service { }
                @interface RestController { }
                @interface GetMapping { String value(); }
                @interface Autowired { }
                @interface PathVariable { }

                @Service
                class OrderService {
                    @Transactional
                    Order findOrder(Long id) { return new Order(); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderService service;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Order order = service.findOrder(id);
                        order.getItems().size();
                        order.getTags().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(2, (int) count);
    }

    /**
     * DataFlow-specific: chained Optional without intermediate variable.
     * {@code service.findOrderOpt(id).orElse(null)} must be peeled to reach
     * the transactional method call.
     */
    public void testReportsChainedOptionalOrElse() {
        this.myFixture.enableInspections(new SpringEntityTransactionBoundaryEscapeDataFlowInspection());
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
                import java.util.Optional;
                @interface Transactional { }
                @interface Service { }
                @interface RestController { }
                @interface GetMapping { String value(); }
                @interface Autowired { }
                @interface PathVariable { }

                @Service
                class OrderService {
                    @Transactional
                    Optional<Order> findOrderOpt(Long id) { return Optional.of(new Order()); }
                }

                @RestController
                class Sample {
                    @Autowired
                    OrderService service;

                    @GetMapping("/order/{id}")
                    void getOrder(@PathVariable Long id) {
                        Order order = service.findOrderOpt(id).orElse(null);
                        order.getItems().size();
                    }
                }
                """);
        List<HighlightInfo> highlights = this.myFixture.doHighlighting();
        long count = highlights.stream()
                .filter((HighlightInfo h) -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter((HighlightInfo h) -> h.getDescription() != null && h.getDescription().contains("Entity accessed outside @Transactional boundary"))
                .count();
        assertEquals(1, (int) count);
    }
}
