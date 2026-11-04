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

import com.intellij.lang.java.JavaLanguage;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.sarhan.dataFlow.base.SpringEntitySourceDetector;
import com.sarhan.render.flow.mutation.MutationFlowMode;
import com.sarhan.render.flow.mutation.ShowJpaEntityMutationFlowFix;
import com.sarhan.springformat.inspection.SpringInspectionUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Detects @Transactional methods that load entities from a repository or
 * EntityManager and then mutate them <strong>without</strong> readOnly=true
 * and <strong>without</strong> an explicit write operation (save, delete, etc.).
 * Hibernate dirty checking may persist those temporary changes unexpectedly.
 *
 * <p>Uses the DataFlow taint engine to track loaded entities through
 * assignments, Optional unwraps, casts, and ternaries — replacing brittle
 * string-name matching.
 *
 * @author Akmal Sarhan
 */
public class SpringTransactionalDirtyCheckingDataFlowInspection extends AbstractBaseJavaLocalInspectionTool {

    private static final Set<String> WRITE_METHODS = Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch", "deleteAllInBatch",
            "deleteAllByIdInBatch", "update", "persist", "merge", "flush", "remove");

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        SpringEntitySourceDetector sourceDetector =
                new SpringEntitySourceDetector(holder.getProject());

        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                PsiAnnotation transactional = findEffectiveTransactionalAnnotation(method);
                PsiCodeBlock body = method.getBody();
                if (transactional == null || body == null || isReadOnly(transactional) || hasWriteOperation(body)) {
                    return;
                }

                TaintedEntityTracker tracker = new TaintedEntityTracker();

                // Pass 1: seed taint from repository / EM read sources
                body.accept(new EntitySourceSeedingVisitor(tracker, sourceDetector));

                // Pass 2: propagate aliases
                tracker.propagateAliases(body);

                // Pass 3: detect mutations on tainted entities
                Set<PsiElement> reported = new HashSet<>();
                body.accept(new MutationSinkVisitor(tracker, holder, reported, method));
            }
        };
    }

    // -----------------------------------------------------------------------
    // Pass 1: seed entity sources
    // -----------------------------------------------------------------------

    private static boolean isLikelyMutator(String name, PsiMethodCallExpression call) {
        if (name.startsWith("set") && call.getArgumentList().getExpressions().length > 0) {
            return true;
        }
        return name.equals("add") || name.equals("addAll")
                || name.equals("remove") || name.equals("removeAll")
                || name.equals("clear") || name.equals("put") || name.equals("putAll");
    }

    private static boolean hasWriteOperation(PsiCodeBlock body) {
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                if (isKnownWriteOperation(call)) {
                    found[0] = true;
                }
                super.visitMethodCallExpression(call);
            }
        });
        return found[0];
    }

    private static boolean isKnownWriteOperation(PsiMethodCallExpression call) {
        PsiMethod resolved = call.resolveMethod();
        if (resolved != null) {
            return (WRITE_METHODS.contains(resolved.getName()) && isPersistenceMethod(resolved))
                    || hasWriteLock(resolved);
        }
        String name = call.getMethodExpression().getReferenceName();
        if (name == null || !WRITE_METHODS.contains(name)) {
            return false;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return false;
        }
        PsiType qualifierType = qualifier.getType();
        PsiClass qualifierClass = (qualifierType != null) ? SpringInspectionUtil.resolvedClass(qualifierType) : null;
        return qualifierClass != null && (SpringInspectionUtil.isSpringDataRepository(qualifierClass)
                || isJpaEntityManagerOrQueryClass(qualifierClass));
    }

    // A write lock (PESSIMISTIC_WRITE, *_FORCE_INCREMENT) declares intent to modify the loaded row.
    private static boolean hasWriteLock(PsiMethod method) {
        PsiAnnotation lock = SpringInspectionUtil.findAnnotation(method.getModifierList(),
                "org.springframework.data.jpa.repository.Lock", "Lock");
        PsiAnnotationMemberValue value = (lock != null) ? lock.findAttributeValue("value") : null;
        if (value == null) {
            return false;
        }
        String mode = value.getText();
        return mode.endsWith("WRITE") || mode.endsWith("FORCE_INCREMENT");
    }

    private static boolean isPersistenceMethod(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        return SpringInspectionUtil.isSpringDataRepository(containingClass)
                || SpringInspectionUtil.isSpringDataRepositoryType(containingClass)
                || isJpaEntityManagerOrQueryClass(containingClass);
    }

    private static boolean isJpaEntityManagerOrQueryClass(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        return qualifiedName != null && (qualifiedName.equals("jakarta.persistence.EntityManager")
                || qualifiedName.equals("javax.persistence.EntityManager")
                || qualifiedName.equals("jakarta.persistence.Query")
                || qualifiedName.equals("javax.persistence.Query")
                || qualifiedName.equals("jakarta.persistence.TypedQuery")
                || qualifiedName.equals("javax.persistence.TypedQuery"));
    }

    private static PsiAnnotation findTransactionalAnnotation(PsiModifierList modifiers) {
        if (modifiers == null) {
            return null;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (isTransactionalAnnotation(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    private static PsiAnnotation findEffectiveTransactionalAnnotation(PsiMethod method) {
        PsiAnnotation methodTx = findTransactionalAnnotation(method.getModifierList());
        if (methodTx != null) {
            return methodTx;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return null;
        }
        return findTransactionalAnnotation(containingClass.getModifierList());
    }

    private static boolean isTransactionalAnnotation(PsiAnnotation annotation) {
        return SpringInspectionUtil.isAnyAnnotation(annotation,
                "org.springframework.transaction.annotation.Transactional",
                "jakarta.transaction.Transactional",
                "javax.transaction.Transactional");
    }

    private static boolean isReadOnly(PsiAnnotation annotation) {
        for (PsiNameValuePair attribute : annotation.getParameterList().getAttributes()) {
            if ("readOnly".equals(attribute.getName()) && attribute.getValue() != null
                    && "true".equals(attribute.getValue().getText())) {
                return true;
            }
        }
        return false;
    }

    private static final class EntitySourceSeedingVisitor
            extends JavaRecursiveElementWalkingVisitor {

        private final TaintedEntityTracker tracker;
        private final SpringEntitySourceDetector sourceDetector;

        EntitySourceSeedingVisitor(@NotNull TaintedEntityTracker tracker,
                                    @NotNull SpringEntitySourceDetector sourceDetector) {
            this.tracker = tracker;
            this.sourceDetector = sourceDetector;
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
            if (rhs != null && isEntitySource(rhs)) {
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
            if (initializer != null && isEntitySource(initializer)) {
                this.tracker.taint(variable);
            }
        }

        private boolean isEntitySource(@NotNull PsiExpression expr) {
            if (expr instanceof PsiMethodCallExpression call) {
                PsiMethod method = call.resolveMethod();
                if (method != null && this.sourceDetector.isDetachedEntitySource(method)) {
                    return true;
                }
                // Optional unwrap chain: repo.findById(id).get() / .orElse(null) / .orElseThrow()
                String name = call.getMethodExpression().getReferenceName();
                if (name != null && (name.equals("get") || name.startsWith("orElse"))) {
                    PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                    if (qualifier != null) {
                        return isEntitySource(qualifier);
                    }
                }
            }
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Pass 3: detect mutation sinks
    // -----------------------------------------------------------------------

    private static final class MutationSinkVisitor extends JavaRecursiveElementWalkingVisitor {

        private final TaintedEntityTracker tracker;
        private final ProblemsHolder holder;
        private final Set<PsiElement> reported;
        private final PsiMethod method;

        MutationSinkVisitor(@NotNull TaintedEntityTracker tracker,
                            @NotNull ProblemsHolder holder,
                            @NotNull Set<PsiElement> reported,
                            @NotNull PsiMethod method) {
            this.tracker = tracker;
            this.holder = holder;
            this.reported = reported;
            this.method = method;
        }

        @Override
        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
            super.visitMethodCallExpression(call);
            String name = call.getMethodExpression().getReferenceName();
            if (name == null || !isLikelyMutator(name, call)) {
                return;
            }
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null) {
                return;
            }
            if (!this.tracker.isTaintedExpression(qualifier)) {
                return;
            }
            registerIfNew(this.method,
                    "@Transactional method loads and mutates an entity without readOnly=true; "
                            + "dirty checking may persist temporary changes");
        }

        private void registerIfNew(@NotNull PsiElement element, @NotNull String message) {
            if (this.reported.add(element)) {
                PsiIdentifier nameIdentifier = (this.method instanceof PsiMethod m) ? m.getNameIdentifier() : null;
                this.holder.registerProblem((nameIdentifier != null) ? nameIdentifier : element,
                        message, ProblemHighlightType.ERROR,
                        new ShowJpaEntityMutationFlowFix(MutationFlowMode.DIRTY_CHECKING),
                        new SetReadOnlyTrueFix());
            }
        }
    }

    private static final class SetReadOnlyTrueFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Set readOnly = true";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod method = descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
            PsiAnnotation annotation = (method != null) ? findTransactionalAnnotation(method.getModifierList()) : null;
            if (annotation != null) {
                replaceAnnotation(project, annotation, addOrSetReadOnly(annotation.getText()));
            }
        }

        private static String addOrSetReadOnly(String text) {
            if (text.contains("readOnly")) {
                return text.replaceAll("readOnly\\s*=\\s*false", "readOnly = true");
            }
            if (!text.contains("(")) {
                return text + "(readOnly = true)";
            }
            return text.replaceFirst("\\)$", ", readOnly = true)");
        }

        private static void replaceAnnotation(Project project, PsiAnnotation annotation, String text) {
            PsiFile file = PsiFileFactory.getInstance(project)
                    .createFileFromText("A.java", JavaLanguage.INSTANCE, text + " class A {}");
            PsiAnnotation replacement = PsiTreeUtil.findChildOfType(file, PsiAnnotation.class);
            if (replacement != null) {
                annotation.replace(replacement);
            }
        }
    }
}
