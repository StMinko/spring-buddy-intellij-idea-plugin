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

package com.sarhan.dataFlow;

import java.util.HashSet;
import java.util.Set;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.sarhan.dataFlow.base.LazyLoadingSinkDetector;
import com.sarhan.springformat.inspection.SpringInspectionUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Detects when a JPA entity returned from a @Transactional method is accessed
 * (mutated or lazy-loaded) outside the transaction boundary within the same class.
 * After the proxy method returns, the entity is detached; lazy access causes
 * LazyInitializationException and mutations are silently lost or cause errors.
 *
 * <p>Uses the DataFlow taint engine to track the escaped entity through
 * assignments, Optional unwraps, casts, and ternaries — replacing brittle
 * parent-walk and string-name matching.
 *
 * @author Akmal Sarhan
 */
public class SpringEntityTransactionBoundaryEscapeDataFlowInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod callerMethod) {
                PsiClass containingClass = callerMethod.getContainingClass();
                if (containingClass == null || callerMethod.getBody() == null) {
                    return;
                }
                if (isTestClass(containingClass)) {
                    return;
                }
                boolean callerTransactional = hasTransactionalAnnotation(callerMethod);

                TaintedEntityTracker tracker = new TaintedEntityTracker();

                // Pass 1: seed taint from @Transactional method call return values
                // (only when caller itself is not @Transactional — entity stays managed otherwise)
                if (!callerTransactional) {
                    callerMethod.getBody().accept(
                            new TransactionalReturnSeedingVisitor(tracker, containingClass));
                }

                // Pass 2: propagate aliases
                tracker.propagateAliases(callerMethod.getBody());

                // Pass 3: detect lazy or mutating access on tainted escaped entities
                Set<PsiElement> reported = new HashSet<>();
                callerMethod.getBody().accept(
                        new EscapeSinkVisitor(tracker, holder, reported));
            }
        };
    }

    // -----------------------------------------------------------------------
    // Pass 1: seed from @Transactional method returns
    // -----------------------------------------------------------------------

    private static boolean isLikelyMutation(String name, PsiMethodCallExpression call) {
        if (name.startsWith("set") && call.getArgumentList().getExpressions().length > 0) {
            return true;
        }
        return name.equals("add") || name.equals("addAll")
                || name.equals("remove") || name.equals("removeAll")
                || name.equals("clear") || name.equals("put") || name.equals("putAll");
    }

    private static boolean isEntityType(@NotNull PsiType type) {
        if (type instanceof PsiArrayType) {
            return isEntityType(((PsiArrayType) type).getComponentType());
        }
        if (type instanceof PsiClassType classType) {
            // Generic container: List<Order>, Optional<Order>, Page<Order>
            for (PsiType param : classType.getParameters()) {
                if (isEntityType(param)) {
                    return true;
                }
            }
            PsiClass resolved = classType.resolve();
            return resolved != null && SpringInspectionUtil.isJpaEntity(resolved);
        }
        return false;
    }

    private static boolean hasTransactionalAnnotation(PsiMethod method) {
        PsiModifierList modifiers = method.getModifierList();
        if (modifiers != null && hasTransactionalOnModifiers(modifiers)) {
            return true;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        PsiModifierList classModifiers = containingClass.getModifierList();
        return classModifiers != null && hasTransactionalOnModifiers(classModifiers);
    }

    private static boolean hasTransactionalOnModifiers(PsiModifierList modifiers) {
        return SpringInspectionUtil.findAnyAnnotation(modifiers,
                "org.springframework.transaction.annotation.Transactional",
                "jakarta.transaction.Transactional",
                "javax.transaction.Transactional") != null;
    }

    private static boolean isTestClass(PsiClass psiClass) {
        String name = psiClass.getName();
        if (name != null && (name.endsWith("Test") || name.endsWith("Tests")
                || name.endsWith("IT") || name.endsWith("TestIT")
                || name.endsWith("TestsIT") || name.endsWith("ITTests"))) {
            return true;
        }
        // Check class and superclass hierarchy for Spring test annotations
        PsiClass cls = psiClass;
        while (cls != null) {
            PsiModifierList modifiers = cls.getModifierList();
            if (modifiers != null) {
                for (PsiAnnotation a : modifiers.getAnnotations()) {
                    String annName = a.getQualifiedName();
                    if (annName == null && a.getNameReferenceElement() != null) {
                        annName = a.getNameReferenceElement().getText();
                    }
                    if (annName != null && ("SpringBootTest".equals(annName) || annName.endsWith(".SpringBootTest")
                            || "WebMvcTest".equals(annName) || annName.endsWith(".WebMvcTest")
                            || "DataJpaTest".equals(annName) || annName.endsWith(".DataJpaTest")
                            || "ContextConfiguration".equals(annName) || annName.endsWith(".ContextConfiguration")
                            || "Test".equals(annName) || annName.endsWith(".Test"))) {
                        return true;
                    }
                }
            }
            cls = cls.getSuperClass();
        }
        return false;
    }

    private static final class TransactionalReturnSeedingVisitor
            extends JavaRecursiveElementWalkingVisitor {

        private final TaintedEntityTracker tracker;
        private final PsiClass containingClass;

        TransactionalReturnSeedingVisitor(@NotNull TaintedEntityTracker tracker,
                                           @NotNull PsiClass containingClass) {
            this.tracker = tracker;
            this.containingClass = containingClass;
        }

        @Override
        public void visitDeclarationStatement(@NotNull PsiDeclarationStatement statement) {
            super.visitDeclarationStatement(statement);
            for (PsiElement declared : statement.getDeclaredElements()) {
                if (declared instanceof PsiLocalVariable var) {
                    seedFromInitializer(var, var.getInitializer());
                }
            }
        }

        @Override
        public void visitLocalVariable(@NotNull PsiLocalVariable variable) {
            super.visitLocalVariable(variable);
            seedFromInitializer(variable, variable.getInitializer());
        }

        @Override
        public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expr) {
            super.visitAssignmentExpression(expr);
            PsiExpression rhs = expr.getRExpression();
            if (rhs != null && isTransactionalEntityReturn(rhs)) {
                PsiExpression lhs = expr.getLExpression();
                if (lhs instanceof PsiReferenceExpression ref
                        && ref.resolve() instanceof PsiVariable var) {
                    this.tracker.taint(var);
                }
            }
        }

        private void seedFromInitializer(@NotNull PsiVariable variable,
                                          PsiExpression initializer) {
            if (this.tracker.isTainted(variable)) {
                return;
            }
            if (initializer != null && isTransactionalEntityReturn(initializer)) {
                this.tracker.taint(variable);
            }
        }

        private boolean isTransactionalEntityReturn(@NotNull PsiExpression expr) {
            if (expr instanceof PsiMethodCallExpression call) {
                PsiMethod target = call.resolveMethod();
                if (target != null) {
                    if (hasTransactionalAnnotation(target)) {
                        PsiType returnType = target.getReturnType();
                        if (returnType != null && isEntityType(returnType)) {
                            return true;
                        }
                    }
                }
                // Optional unwrap chain: service.findOrderOpt(id).get() / .orElse(null)
                String name = call.getMethodExpression().getReferenceName();
                if (name != null && (name.equals("get") || name.startsWith("orElse"))) {
                    PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                    if (qualifier != null) {
                        return isTransactionalEntityReturn(qualifier);
                    }
                }
            }
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Pass 3: detect lazy / mutation sinks
    // -----------------------------------------------------------------------

    private static final class EscapeSinkVisitor extends JavaRecursiveElementWalkingVisitor {

        private final TaintedEntityTracker tracker;
        private final ProblemsHolder holder;
        private final Set<PsiElement> reported;

        EscapeSinkVisitor(@NotNull TaintedEntityTracker tracker,
                          @NotNull ProblemsHolder holder,
                          @NotNull Set<PsiElement> reported) {
            this.tracker = tracker;
            this.holder = holder;
            this.reported = reported;
        }

        @Override
        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
            super.visitMethodCallExpression(call);
            PsiMethod resolved = call.resolveMethod();
            if (resolved == null) {
                return;
            }
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null) {
                return;
            }
            if (!this.tracker.isTaintedExpression(qualifier)) {
                return;
            }

            String name = resolved.getName();
            if (name == null) {
                return;
            }

            // Lazy-loading trigger
            if (LazyLoadingSinkDetector.isLazyLoadingTrigger(resolved)) {
                register(call, true);
                return;
            }

            // Mutation
            if (isLikelyMutation(name, call) && !isPersistedAfter(call, qualifier)) {
                register(call, false);
            }
        }

        /** A later {@code repository.save(entity)} or {@code em.merge(entity)} writes the detached changes back. */
        private static boolean isPersistedAfter(@NotNull PsiMethodCallExpression mutation, @NotNull PsiExpression qualifier) {
            if (!(PsiUtil.skipParenthesizedExprDown(qualifier) instanceof PsiReferenceExpression reference)
                    || !(reference.resolve() instanceof PsiVariable variable)) {
                return false;
            }
            PsiMethod method = PsiTreeUtil.getParentOfType(mutation, PsiMethod.class);
            PsiCodeBlock body = method != null ? method.getBody() : null;
            if (body == null) {
                return false;
            }
            int mutationEnd = mutation.getTextRange().getEndOffset();
            for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
                if (call.getTextRange().getStartOffset() >= mutationEnd && isPersistCallOf(call, variable)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isPersistCallOf(@NotNull PsiMethodCallExpression call, @NotNull PsiVariable variable) {
            String name = call.getMethodExpression().getReferenceName();
            if (!"save".equals(name) && !"saveAndFlush".equals(name) && !"merge".equals(name)) {
                return false;
            }
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if (arguments.length != 1
                    || !(PsiUtil.skipParenthesizedExprDown(arguments[0]) instanceof PsiReferenceExpression argument)
                    || argument.resolve() != variable) {
                return false;
            }
            PsiExpression target = call.getMethodExpression().getQualifierExpression();
            PsiType targetType = target != null ? target.getType() : null;
            PsiClass targetClass = targetType != null ? SpringInspectionUtil.resolvedClass(targetType) : null;
            if (targetClass == null) {
                return false;
            }
            String qualifiedName = targetClass.getQualifiedName();
            return SpringInspectionUtil.isSpringDataRepository(targetClass)
                    || "jakarta.persistence.EntityManager".equals(qualifiedName)
                    || "javax.persistence.EntityManager".equals(qualifiedName);
        }

        private void register(@NotNull PsiMethodCallExpression call, boolean isLazy) {
            if (!this.reported.add(call)) {
                return;
            }
            String detail = isLazy
                    ? "lazy loading may throw LazyInitializationException"
                    : "mutations are silently lost";
            this.holder.registerProblem(call,
                    "Entity accessed outside @Transactional boundary: " + detail,
                    ProblemHighlightType.ERROR,
                    new com.sarhan.render.flow.boundary.ShowBoundaryEscapeFlowFix());
        }
    }// -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
}
