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

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import org.jetbrains.annotations.NotNull;

/**
 * SpringEventListenerSwallowsException: flags an {@code @EventListener} (or
 * {@code @TransactionalEventListener}) method whose body ends with a {@code try/catch} that
 * catches a broad exception type ({@code Exception}/{@code Throwable}/{@code RuntimeException})
 * and only logs without rethrowing. Spring loses the failure signal — publishers can't react
 * and transactional rollbacks won't trigger.
 *
 * @author Akmal Sarhan
 */
public class SpringEventListenerSwallowsExceptionInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {

        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                if (!isEventListener(method)) {
                    return;
                }
                PsiCodeBlock body = method.getBody();
                if (body == null) {
                    return;
                }
                PsiStatement[] statements = body.getStatements();
                for (PsiStatement statement : statements) {
                    if (!(statement instanceof PsiTryStatement tryStmt)) {
                        continue;
                    }
                    for (PsiCatchSection section : tryStmt.getCatchSections()) {
                        if (isBroadCatch(section) && onlyLogs(section.getCatchBlock())) {
                            PsiTypeElement typeElem = (section.getParameter() != null)
                                    ? section.getParameter().getTypeElement()
                                    : null;
                            PsiElement target = (typeElem != null) ? typeElem : section;
                            holder.registerProblem(target,
                                    "@EventListener swallows " + ((typeElem != null) ? typeElem.getText() : "exception")
                                            + "; Spring will not see the failure — log and rethrow, or handle specifically",
                                    ProblemHighlightType.ERROR);
                        }
                    }
                }
            }
        };
    }

    private static boolean isEventListener(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn == null) {
                continue;
            }
            if ("org.springframework.context.event.EventListener".equals(fqn)
                    || "EventListener".equals(fqn)) {
                return true;
            }
            if ("org.springframework.transaction.event.TransactionalEventListener".equals(fqn)
                    || "TransactionalEventListener".equals(fqn)) {
                return isBeforeCommit(annotation);
            }
        }
        return false;
    }

    /*
     * AFTER_COMMIT (the default), AFTER_ROLLBACK and AFTER_COMPLETION listeners run in
     * TransactionSynchronization.afterCompletion, which Spring wraps in a catch-and-log: a rethrow
     * never reaches the publisher and the transaction is already finished. Only BEFORE_COMMIT
     * propagates, so only there is a swallowed exception a lost signal.
     */
    private static boolean isBeforeCommit(PsiAnnotation annotation) {
        PsiAnnotationMemberValue phase = annotation.findDeclaredAttributeValue("phase");
        return phase instanceof PsiReferenceExpression ref && "BEFORE_COMMIT".equals(ref.getReferenceName());
    }

    private static boolean isBroadCatch(PsiCatchSection section) {
        PsiType type = section.getCatchType();
        if (type == null) {
            return false;
        }
        String canonical = type.getCanonicalText();
        return "java.lang.Exception".equals(canonical)
                || "java.lang.Throwable".equals(canonical)
                || "java.lang.RuntimeException".equals(canonical)
                || "Exception".equals(canonical)
                || "Throwable".equals(canonical)
                || "RuntimeException".equals(canonical);
    }

    private static boolean onlyLogs(PsiCodeBlock catchBlock) {
        if (catchBlock == null) {
            return false;
        }
        PsiStatement[] stmts = catchBlock.getStatements();
        if (stmts.length == 0) {
            return true; // empty catch — even worse, also "swallows"
        }
        boolean hasAnyLog = false;
        for (PsiStatement stmt : stmts) {
            if (stmt instanceof PsiThrowStatement) {
                return false;
            }
            if (stmt instanceof PsiExpressionStatement exprStmt
                    && exprStmt.getExpression() instanceof PsiMethodCallExpression call) {
                String name = call.getMethodExpression().getReferenceName();
                if (name != null && isLogMethodName(name)) {
                    hasAnyLog = true;
                    continue;
                }
                // some other call — not pure logging
                return false;
            }
            // any other statement type -> not pure logging
            return false;
        }
        return hasAnyLog;
    }

    private static boolean isLogMethodName(String name) {
        return "trace".equals(name)
                || "debug".equals(name)
                || "info".equals(name)
                || "warn".equals(name)
                || "error".equals(name);
    }
}
