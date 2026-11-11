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

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of a single method in a {@link TransactionGraph}.
 *
 * <p>Produced by {@code TransactionGraphBuilder}, which resolves the effective
 * transaction attributes of a PSI method, and consumed by
 * {@code TransactionSvgRenderer}. The node holds plain values only — no PSI
 * references — so a graph can be cached and rendered outside a read action.
 *
 * @author Akmal Sarhan
 */
public final class TransactionNode {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Stable identifier, {@code qualified.ClassName#method}; also the SVG element id. */
    private final String id;

    /** Short label, {@code ClassName#method}; the renderer splits it on {@code #}. */
    private final String displayName;

    /** Declared (or defaulted) propagation of the method. */
    private final Propagation propagation;

    /** What actually happens to the transaction when the method is entered. */
    private final TxStatus status;

    /** True when the effective annotation declares {@code readOnly = true}. */
    private final boolean readOnly;

    /** Exception type names from {@code rollbackFor} / {@code rollbackOn}. */
    private final List<String> rollbackFor;

    /** Exception type names from {@code noRollbackFor} / {@code dontRollbackOn}. */
    private final List<String> noRollbackFor;

    /** True when the method is reached by a same-class call that skips the Spring proxy. */
    private final boolean proxyBypass;

    /** True when a {@code readOnly} method performs a write operation. */
    private final boolean writeInReadOnly;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    private TransactionNode(Builder builder) {
        this.id              = Objects.requireNonNull(builder.id, "id");
        this.displayName     = Objects.requireNonNull(builder.displayName, "displayName");
        this.propagation     = Objects.requireNonNull(builder.propagation, "propagation");
        this.status          = Objects.requireNonNull(builder.status, "status");
        this.readOnly        = builder.readOnly;
        this.rollbackFor     = List.copyOf(builder.rollbackFor);
        this.noRollbackFor   = List.copyOf(builder.noRollbackFor);
        this.proxyBypass     = builder.proxyBypass;
        this.writeInReadOnly = builder.writeInReadOnly;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getId()                  { return this.id; }
    public String getDisplayName()         { return this.displayName; }
    public Propagation getPropagation()    { return this.propagation; }
    public TxStatus getStatus()            { return this.status; }
    public boolean isReadOnly()            { return this.readOnly; }
    public List<String> getRollbackFor()   { return this.rollbackFor; }
    public List<String> getNoRollbackFor() { return this.noRollbackFor; }
    public boolean isProxyBypass()         { return this.proxyBypass; }
    public boolean isWriteInReadOnly()     { return this.writeInReadOnly; }

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    /**
     * Two nodes are equal when they share the same {@link #getId() id};
     * propagation, status and all other state are deliberately ignored.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionNode)) {
            return false;
        }
        TransactionNode other = (TransactionNode) o;
        return this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return "TransactionNode{id='" + this.id + "', status=" + this.status
                + ", propagation=" + this.propagation + "}";
    }

    // -----------------------------------------------------------------------
    // Enums
    // -----------------------------------------------------------------------

    /**
     * Transaction propagation, mirroring the constant names of Spring's
     * {@code org.springframework.transaction.annotation.Propagation} so that
     * annotation attribute text can be matched by name.
     */
    public enum Propagation {
        REQUIRED,
        REQUIRES_NEW,
        NESTED,
        SUPPORTS,
        NOT_SUPPORTED,
        NEVER,
        MANDATORY
    }

    /**
     * Effective transactional behaviour of a node in the context of its caller.
     */
    public enum TxStatus {
        /** Starts a new transaction because no caller transaction is active. */
        STARTS,
        /** Participates in the caller's active transaction. */
        JOINS,
        /** Suspends the caller's transaction and runs in a new one ({@code REQUIRES_NEW}). */
        SUSPENDS,
        /** Runs as a savepoint inside the caller's transaction ({@code NESTED}). */
        NESTED,
        /** Runs without a transaction. */
        NON_TX,
        /** Transaction attributes are ignored because the call bypasses the Spring proxy. */
        IGNORED
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static final class Builder {

        private final String id;
        private final String displayName;
        private Propagation propagation = Propagation.REQUIRED;
        private TxStatus status = TxStatus.NON_TX;
        private boolean readOnly = false;
        private List<String> rollbackFor = List.of();
        private List<String> noRollbackFor = List.of();
        private boolean proxyBypass = false;
        private boolean writeInReadOnly = false;

        private Builder(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder propagation(Propagation propagation) {
            this.propagation = propagation;
            return this;
        }

        public Builder status(TxStatus status) {
            this.status = status;
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder rollbackFor(List<String> rollbackFor) {
            this.rollbackFor = rollbackFor;
            return this;
        }

        public Builder noRollbackFor(List<String> noRollbackFor) {
            this.noRollbackFor = noRollbackFor;
            return this;
        }

        public Builder proxyBypass(boolean proxyBypass) {
            this.proxyBypass = proxyBypass;
            return this;
        }

        public Builder writeInReadOnly(boolean writeInReadOnly) {
            this.writeInReadOnly = writeInReadOnly;
            return this;
        }

        public TransactionNode build() {
            return new TransactionNode(this);
        }
    }
}
