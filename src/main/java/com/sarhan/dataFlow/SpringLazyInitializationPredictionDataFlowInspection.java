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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.sarhan.dataFlow.base.LazyLoadingSinkDetector;
import com.sarhan.dataFlow.base.SpringEntitySourceDetector;
import com.sarhan.springformat.inspection.SpringInspectionUtil;
import com.sarhan.springformat.inspection.SpringRemainingPitfallInspection;
import org.jetbrains.annotations.NotNull;

/**
 * Predicts {@code LazyInitializationException} by detecting lazy getter calls
 * on JPA entities that were obtained from a repository or EntityManager source
 * outside an active {@code @Transactional} context.
 *
 * <p>Uses the DataFlow taint engine ({@code com.sarhan.dataFlow}) to track
 * detached entity references through assignments, {@code Optional} unwrapping,
 * casts, and ternary expressions within a single method scope.
 *
 * <p>Architecture:
 * <pre>
 *   SOURCE: repo.findById(...), em.find(...), @Query methods, etc.
 *   ALIAS:  fixed-point propagation of tainted references
 *   SINK:   getXxx() / isXxx() / fetchXxx() on a tainted entity that triggers
 *           lazy loading (collection return, @OneToMany, entity return, etc.)
 * </pre>
 *
 * @author Akmal Sarhan
 */
public class SpringLazyInitializationPredictionDataFlowInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        SpringEntitySourceDetector sourceDetector =
                new SpringEntitySourceDetector(holder.getProject());

        // Shared across all entry-point methods in this inspection run
        Set<PsiElement> reportedElements = new HashSet<>();

        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (method.getBody() == null) {
                    return; // abstract / native
                }
                if (isContextTransactional(method)) {
                    return; // entities are managed inside a transaction
                }
                if (isPrivateMethodCoveredByTransactionalCaller(method)) {
                    return;
                }
                if (isGetter(method)) {
                    return; // read-only, not a lazy-loading entry point
                }

                PsiClass containingClass = method.getContainingClass();
                if (containingClass != null && isTestClass(containingClass)) {
                    return;
                }

                analyse(method, sourceDetector, holder, reportedElements);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Analysis
    // -----------------------------------------------------------------------

    private static void analyse(@NotNull PsiMethod method,
                                 @NotNull SpringEntitySourceDetector sourceDetector,
                                 @NotNull ProblemsHolder holder,
                                 @NotNull Set<PsiElement> reportedElements) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return;
        }

        TaintedEntityTracker tracker = new TaintedEntityTracker();
        Map<PsiVariable, PsiMethod> sourceMethods = new HashMap<>();

        // Pass 1: seed taint from repository / EntityManager sources
        body.accept(new SourceSeedingVisitor(tracker, sourceDetector, sourceMethods));

        // Fixed-point alias propagation
        tracker.propagateAliases(body);

        // Pass 2: detect lazy-loading sinks
        body.accept(new LazyGetterSinkVisitor(tracker, sourceMethods, holder, reportedElements));
    }

    // -----------------------------------------------------------------------
    // Pass 1: seed taint from detached entity sources
    // -----------------------------------------------------------------------

    private static boolean isGetter(@NotNull PsiMethod method) {
        String name = method.getName();
        if (!name.startsWith("get") && !name.startsWith("is")) {
            return false;
        }
        if (method.getParameterList().getParametersCount() != 0) {
            return false;
        }
        PsiType ret = method.getReturnType();
        return ret != null && !ret.equals(PsiTypes.voidType());
    }

    private static boolean isContextTransactional(@NotNull PsiMethod method) {
        final String TX_FQN = "org.springframework.transaction.annotation.Transactional";
        final String TX_SIMPLE = "Transactional";

        // Method itself
        if (hasAnnotationByAnyName(method, TX_FQN, TX_SIMPLE)) {
            return true;
        }

        // Containing class and superclass hierarchy
        PsiClass cls = method.getContainingClass();
        while (cls != null) {
            if (hasAnnotationByAnyName(cls, TX_FQN, TX_SIMPLE)) {
                return true;
            }
            for (PsiMethod superMethod : method.findSuperMethods(cls)) {
                if (hasAnnotationByAnyName(superMethod, TX_FQN, TX_SIMPLE)) {
                    return true;
                }
            }
            cls = cls.getSuperClass();
        }

        // Interface default methods
        for (PsiMethod superMethod : method.findSuperMethods()) {
            if (hasAnnotationByAnyName(superMethod, TX_FQN, TX_SIMPLE)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPrivateMethodCoveredByTransactionalCaller(@NotNull PsiMethod method) {
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return false;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        boolean hasInClassCaller = !ReferencesSearch.search(method, new LocalSearchScope(containingClass)).findAll().isEmpty();
        return hasInClassCaller && SpringRemainingPitfallInspection.hasTransactionalCallerCoverage(method);
    }

    /**
     * Checks for an annotation by FQN, then by simple name from the annotation
     * reference element. Essential when annotation classes are not on the
     * classpath (demo files, stub-only projects).
     */
    private static boolean hasAnnotationByAnyName(
            @NotNull PsiModifierListOwner element,
            @NotNull String fqn,
            @NotNull String simpleName) {

        if (element.hasAnnotation(fqn)) {
            return true;
        }

        PsiModifierList modList = element.getModifierList();
        if (modList == null) {
            return false;
        }

        for (PsiAnnotation ann : modList.getAnnotations()) {
            String qualName = ann.getQualifiedName();
            if (qualName != null) {
                if (qualName.equals(fqn) || qualName.equals(simpleName)) {
                    return true;
                }
            } else {
                PsiJavaCodeReferenceElement ref = ann.getNameReferenceElement();
                if (ref != null && simpleName.equals(ref.getReferenceName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTestClass(PsiClass psiClass) {
        String name = psiClass.getName();
        if (name != null && (name.endsWith("Test") || name.endsWith("Tests")
                || name.endsWith("IT") || name.endsWith("TestIT")
                || name.endsWith("TestsIT") || name.endsWith("ITTests"))) {
            return true;
        }
        PsiModifierList modifiers = psiClass.getModifierList();
        if (modifiers == null) {
            return false;
        }
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
        return false;
    }

    private static final class SourceSeedingVisitor extends JavaRecursiveElementWalkingVisitor {

        private final TaintedEntityTracker tracker;
        private final SpringEntitySourceDetector sourceDetector;
        private final Map<PsiVariable, PsiMethod> sourceMethods;

        SourceSeedingVisitor(@NotNull TaintedEntityTracker tracker,
                             @NotNull SpringEntitySourceDetector sourceDetector,
                             @NotNull Map<PsiVariable, PsiMethod> sourceMethods) {
            this.tracker = tracker;
            this.sourceDetector = sourceDetector;
            this.sourceMethods = sourceMethods;
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
            PsiExpression lhs = expr.getLExpression();
            if (!(lhs instanceof PsiReferenceExpression ref)
                    || !(ref.resolve() instanceof PsiVariable var)) {
                return;
            }
            this.sourceMethods.remove(var);
            PsiExpression rhs = expr.getRExpression();
            if (rhs == null) {
                return;
            }
            PsiMethod sourceMethod = directSourceMethod(rhs);
            if (sourceMethod == null && !isDetachedSource(rhs)) {
                return;
            }
            this.tracker.taint(var);
            if (sourceMethod != null) {
                this.sourceMethods.put(var, sourceMethod);
            }
        }

        private void seedFromInitializer(@NotNull PsiVariable variable,
                                           PsiExpression initializer) {
            if (this.tracker.isTainted(variable)) {
                return;
            }
            if (initializer == null) {
                return;
            }
            PsiMethod sourceMethod = directSourceMethod(initializer);
            if (sourceMethod != null || isDetachedSource(initializer)) {
                this.tracker.taint(variable);
                if (sourceMethod != null) {
                    this.sourceMethods.put(variable, sourceMethod);
                }
            }
        }

        private PsiMethod directSourceMethod(@NotNull PsiExpression expression) {
            if (expression instanceof PsiMethodCallExpression call) {
                PsiMethod resolved = call.resolveMethod();
                if (resolved != null && this.sourceDetector.isDetachedEntitySource(resolved)) {
                    return resolved;
                }
                String name = call.getMethodExpression().getReferenceName();
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                if (qualifier != null && ("get".equals(name) || "orElseThrow".equals(name))) {
                    return directSourceMethod(qualifier);
                }
            }
            return null;
        }

        /**
         * Returns true if this expression produces a detached entity value.
         * Handles direct repo/EM calls and chained Optional-unwrap calls.
         */
        private boolean isDetachedSource(@NotNull PsiExpression expr) {
            if (expr instanceof PsiMethodCallExpression call) {
                PsiMethod resolved = call.resolveMethod();

                // Direct source: repo.findById(...) or em.find(...)
                if (resolved != null && this.sourceDetector.isDetachedEntitySource(resolved)) {
                    return true;
                }

                // Optional unwrap chain: opt.get() / opt.orElseThrow()
                String name = call.getMethodExpression().getReferenceName();
                if (name != null && (name.equals("get") || name.startsWith("orElse"))) {
                    PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                    if (qualifier != null) {
                        return isDetachedSource(qualifier);
                    }
                }
            }

            // Reference to an already-tainted variable
            if (expr instanceof PsiReferenceExpression ref
                    && ref.resolve() instanceof PsiVariable var) {
                return this.tracker.isTainted(var);
            }

            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Pass 2: detect lazy-loading getter sinks
    // -----------------------------------------------------------------------

    private static final class LazyGetterSinkVisitor extends JavaRecursiveElementWalkingVisitor {

        private final TaintedEntityTracker tracker;
        private final Map<PsiVariable, PsiMethod> sourceMethods;
        private final ProblemsHolder holder;
        private final Set<PsiElement> reportedElements;

        LazyGetterSinkVisitor(@NotNull TaintedEntityTracker tracker,
                              @NotNull Map<PsiVariable, PsiMethod> sourceMethods,
                              @NotNull ProblemsHolder holder,
                              @NotNull Set<PsiElement> reportedElements) {
            this.tracker = tracker;
            this.sourceMethods = sourceMethods;
            this.holder = holder;
            this.reportedElements = reportedElements;
        }

        @Override
        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
            super.visitMethodCallExpression(call);

            PsiMethod resolved = call.resolveMethod();
            if (resolved == null) {
                return;
            }
            if (!LazyLoadingSinkDetector.isLazyLoadingTrigger(resolved)) {
                return;
            }

            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null) {
                return;
            }

            if (!this.tracker.isTaintedExpression(qualifier)) {
                return;
            }
            if (isEntityIdentifierRead(call)) {
                return;
            }
            PsiMethod sourceMethod = sourceMethodFor(qualifier);
            if (sourceMethod != null && isAssociationFetchedBySource(sourceMethod, resolved)) {
                return;
            }

            registerIfNew(call,
                    "LazyInitializationException: entity loaded in a different @Transactional method "
                            + "is accessed outside a transaction boundary");
        }

        private PsiMethod sourceMethodFor(PsiExpression expression) {
            if (expression instanceof PsiParenthesizedExpression parenthesized) {
                return sourceMethodFor(parenthesized.getExpression());
            }
            if (expression instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable) {
                return this.sourceMethods.get(variable);
            }
            return null;
        }

        private static boolean isEntityIdentifierRead(PsiMethodCallExpression lazyCall) {
            PsiMethodCallExpression enclosingCall = PsiTreeUtil.getParentOfType(lazyCall, PsiMethodCallExpression.class, true);
            if (enclosingCall == null || enclosingCall.getMethodExpression().getQualifierExpression() != lazyCall) {
                return false;
            }
            PsiMethod method = enclosingCall.resolveMethod();
            return method != null && "getId".equals(method.getName())
                    && method.getParameterList().getParametersCount() == 0;
        }

        private static boolean isAssociationFetchedBySource(PsiMethod sourceMethod, PsiMethod getter) {
            String property = getterPropertyName(getter.getName());
            if (property == null) {
                return false;
            }
            PsiModifierList modifiers = sourceMethod.getModifierList();
            PsiAnnotation query = SpringInspectionUtil.findAnnotation(modifiers,
                    "org.springframework.data.jpa.repository.Query", "Query");
            PsiAnnotationMemberValue queryValue = query != null ? query.findAttributeValue("value") : null;
            if (queryValue != null && Pattern.compile("\\bjoin\\s+fetch\\s+\\w+\\." + Pattern.quote(property) + "\\b",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(queryValue.getText()).find()) {
                return true;
            }
            PsiAnnotation entityGraph = SpringInspectionUtil.findAnnotation(modifiers,
                    "org.springframework.data.jpa.repository.EntityGraph", "EntityGraph");
            return entityGraph != null && hasEntityGraphPath(entityGraph.findAttributeValue("attributePaths"), property);
        }

        private static boolean hasEntityGraphPath(PsiAnnotationMemberValue paths, String property) {
            if (paths instanceof PsiArrayInitializerMemberValue array) {
                for (PsiAnnotationMemberValue value : array.getInitializers()) {
                    if (isEntityGraphPath(value, property)) {
                        return true;
                    }
                }
                return false;
            }
            return isEntityGraphPath(paths, property);
        }

        private static boolean isEntityGraphPath(PsiAnnotationMemberValue value, String property) {
            if (!(value instanceof PsiLiteralExpression literal) || !(literal.getValue() instanceof String path)) {
                return false;
            }
            return property.equals(path) || path.startsWith(property + ".");
        }

        private static String getterPropertyName(String methodName) {
            int prefixLength = methodName.startsWith("get") ? 3
                    : methodName.startsWith("is") ? 2
                    : methodName.startsWith("fetch") ? 5
                    : 0;
            if (prefixLength == 0 || methodName.length() <= prefixLength) {
                return null;
            }
            return Character.toLowerCase(methodName.charAt(prefixLength)) + methodName.substring(prefixLength + 1);
        }

        private void registerIfNew(@NotNull PsiElement element, @NotNull String message) {
            if (this.reportedElements.add(element)) {
                this.holder.registerProblem(element, message, ProblemHighlightType.ERROR,
                        new com.sarhan.render.flow.lazyinit.ShowLazyInitFlowFix());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Entry-point filters (mirrors SpringGhostSaveDataFlowInspection)
    // -----------------------------------------------------------------------
}
