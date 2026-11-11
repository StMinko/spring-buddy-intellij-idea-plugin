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

package com.sarhan.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.Test;

/**
 * Tests for the immutable {@link TransactionNode} snapshot and the way
 * {@link TransactionSvgRenderer} consumes it.
 */
public class TransactionNodeTest {

    @Test
    public void givenMutableRollbackLists_whenSourcesAndBuilderChangeAfterBuild_thenNodeKeepsItsSnapshot() {
        // given
        List<String> rollbackFor = new ArrayList<>(List.of("IOException"));
        List<String> noRollbackFor = new ArrayList<>(List.of("IllegalStateException"));
        TransactionNode.Builder builder = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .propagation(TransactionNode.Propagation.REQUIRES_NEW)
                .status(TransactionNode.TxStatus.SUSPENDS)
                .rollbackFor(rollbackFor)
                .noRollbackFor(noRollbackFor);
        TransactionNode node = builder.build();

        // when
        rollbackFor.add("SQLException");
        noRollbackFor.clear();
        builder.status(TransactionNode.TxStatus.JOINS).readOnly(true);

        // then
        Assertions.assertThat(node)
                .returns(List.of("IOException"), TransactionNode::getRollbackFor)
                .returns(List.of("IllegalStateException"), TransactionNode::getNoRollbackFor)
                .returns(TransactionNode.TxStatus.SUSPENDS, TransactionNode::getStatus)
                .returns(false, TransactionNode::isReadOnly);
    }

    @Test
    public void givenBuiltNode_whenReturnedRollbackListIsModified_thenModificationIsRejected() {
        // given
        TransactionNode node = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .rollbackFor(new ArrayList<>(List.of("IOException")))
                .build();

        // when / then
        Assertions.assertThatThrownBy(() -> node.getRollbackFor().add("SQLException"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void givenNullPropagation_whenNodeIsBuilt_thenBuildIsRejected() {
        // given
        TransactionNode.Builder builder = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .propagation(null);

        // when / then
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("propagation");
    }

    @Test
    public void givenNodesWithSameIdAndDifferentState_whenCompared_thenTheyAreEqualInBothDirectionsWithIdHashCode() {
        // given
        String id = "com.acme.OrderService#place";
        TransactionNode first = TransactionNode.builder(id, "OrderService#place")
                .propagation(TransactionNode.Propagation.REQUIRES_NEW)
                .status(TransactionNode.TxStatus.SUSPENDS)
                .readOnly(true)
                .build();
        TransactionNode second = TransactionNode.builder(id, "place")
                .propagation(TransactionNode.Propagation.NESTED)
                .status(TransactionNode.TxStatus.NESTED)
                .proxyBypass(true)
                .build();

        // when
        boolean firstEqualsSecond = first.equals(second);
        boolean secondEqualsFirst = second.equals(first);
        boolean firstEqualsItself = first.equals(first);

        // then
        Assertions.assertThat(firstEqualsSecond).isTrue();
        Assertions.assertThat(secondEqualsFirst).isTrue();
        Assertions.assertThat(firstEqualsItself).isTrue();
        Assertions.assertThat(first)
                .returns(id.hashCode(), TransactionNode::hashCode)
                .hasSameHashCodeAs(second);
    }

    @Test
    public void givenNodesWithSameIdAndDifferentState_whenAddedToHashSet_thenOnlyFirstNodeIsKept() {
        // given
        TransactionNode first = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .status(TransactionNode.TxStatus.STARTS)
                .build();
        TransactionNode second = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .propagation(TransactionNode.Propagation.REQUIRES_NEW)
                .status(TransactionNode.TxStatus.SUSPENDS)
                .build();
        Set<TransactionNode> nodes = new HashSet<>();

        // when
        boolean firstAdded = nodes.add(first);
        boolean secondAdded = nodes.add(second);

        // then
        Assertions.assertThat(firstAdded).isTrue();
        Assertions.assertThat(secondAdded).isFalse();
        Assertions.assertThat(nodes)
                .hasSize(1)
                .first()
                .isSameAs(first);
    }

    @Test
    public void givenNodeWithOtherwiseIdenticalState_whenComparedWithDifferentIdNullOrOtherType_thenItIsNotEqual() {
        // given
        TransactionNode node = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .status(TransactionNode.TxStatus.STARTS)
                .build();
        TransactionNode differentId = TransactionNode.builder("com.acme.OrderService#cancel", "OrderService#place")
                .status(TransactionNode.TxStatus.STARTS)
                .build();
        Object otherType = "com.acme.OrderService#place";

        // when
        boolean equalsDifferentId = node.equals(differentId);
        boolean differentIdEqualsNode = differentId.equals(node);
        boolean equalsNull = node.equals(null);
        boolean equalsOtherType = node.equals(otherType);

        // then
        Assertions.assertThat(equalsDifferentId).isFalse();
        Assertions.assertThat(differentIdEqualsNode).isFalse();
        Assertions.assertThat(equalsNull).isFalse();
        Assertions.assertThat(equalsOtherType).isFalse();
    }

    @Test
    public void givenBuiltNode_whenConvertedToString_thenIdStatusAndPropagationAreShownInDiagnosticFormat() {
        // given
        TransactionNode node = TransactionNode.builder("com.acme.OrderService#place", "OrderService#place")
                .propagation(TransactionNode.Propagation.REQUIRES_NEW)
                .status(TransactionNode.TxStatus.SUSPENDS)
                .readOnly(true)
                .build();

        // when
        String text = node.toString();

        // then
        Assertions.assertThat(text)
                .isEqualTo("TransactionNode{id='com.acme.OrderService#place', status=SUSPENDS, propagation=REQUIRES_NEW}");
    }

    @Test
    public void givenNodeWithBuilderDefaults_whenGraphIsRendered_thenNodeIsShownAsNonTransactional() {
        // given
        TransactionNode node = TransactionNode.builder("com.acme.PlainService#run", "PlainService#run").build();
        TransactionGraph graph = TransactionGraph.builder(node)
                .nodes(List.of(node))
                .build();

        // when
        String html = new TransactionSvgRenderer().renderHtml(graph, false);

        // then
        Assertions.assertThat(html)
                .contains(">NO TX<")
                .contains("No transaction boundaries found")
                .doesNotContain("showTip(evt,'readOnly = true")
                .doesNotContain("write in readOnly tx");
    }

    @Test
    public void givenReadOnlyRootWritingAndSuspendingCallee_whenGraphIsRendered_thenBadgesFollowNodeState() {
        // given
        TransactionNode root = TransactionNode.builder("com.acme.ReportService#generate", "ReportService#generate")
                .status(TransactionNode.TxStatus.STARTS)
                .readOnly(true)
                .writeInReadOnly(true)
                .build();
        TransactionNode callee = TransactionNode.builder("com.acme.AuditService#audit", "AuditService#audit")
                .propagation(TransactionNode.Propagation.REQUIRES_NEW)
                .status(TransactionNode.TxStatus.SUSPENDS)
                .build();
        TransactionGraph graph = TransactionGraph.builder(root)
                .nodes(List.of(root, callee))
                .edges(List.of(new TransactionGraph.Edge(root.getId(), callee.getId(), false, true, false, "suspends")))
                .build();

        // when
        String html = new TransactionSvgRenderer().renderHtml(graph, true);

        // then
        Assertions.assertThat(html)
                .contains(">REQUIRED<")
                .contains("showTip(evt,'readOnly = true")
                .contains("write in readOnly tx")
                .contains(">NEW TX<")
                .contains("REQUIRES_NEW [SUSPENDS]")
                .contains(">suspends<")
                .doesNotContain("No transaction boundaries found");
    }
}
