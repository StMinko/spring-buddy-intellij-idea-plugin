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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDoWhileStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiWhileStatement;
import org.jetbrains.annotations.NotNull;

/**
 * Detects proxy-breaking usage of {@code @Transactional} and related pitfalls.
 * <p>
 * Covers: private/static/final methods, final classes, self-invocations,
 * checked exceptions without rollback, conflicting rollback rules,
 * read-only transactions performing writes, class-level read-only inheritance
 * violations, {@code @Transactional} on tests, abstract classes, repositories,
 * and dangerous pairings such as {@code @Async} with
 * {@code @TransactionalEventListener}.
 *
 * @author Akmal Sarhan
 */
public class SpringTransactionalPrivateMethodInspection extends AbstractBaseJavaLocalInspectionTool {

    private final Kind kind;

    public SpringTransactionalPrivateMethodInspection() {
        this(Kind.PRIVATE_METHOD);
    }

    protected SpringTransactionalPrivateMethodInspection(Kind kind) {
        this.kind = kind;
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {

        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass psiClass) {
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.TRANSACTIONAL_TEST)
                        && hasTransactionalAnnotation(psiClass) && isTestClass(psiClass)) {
                    PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : psiClass,
                            "Avoid @Transactional on tests because rollback can hide SQL/flush failures",
                            ProblemHighlightType.ERROR, new RemoveClassTransactionalAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.REPOSITORY_TRANSACTION)
                        && hasTransactionalAnnotation(psiClass) && isRepositoryClass(psiClass)) {
                    PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : psiClass,
                            "Repository-level @Transactional is too narrow; prefer service-layer transaction boundaries",
                            ProblemHighlightType.ERROR, new RemoveClassTransactionalAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.ABSTRACT_CLASS_TRANSACTION)
                        && hasTransactionalAnnotation(psiClass) && psiClass.hasModifierProperty(PsiModifier.ABSTRACT)
                        && !psiClass.isInterface()) {
                    PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : psiClass,
                            "Avoid @Transactional on abstract classes; define transactions on concrete implementations",
                            ProblemHighlightType.ERROR, new RemoveClassTransactionalAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.FINAL_CLASS) && psiClass.hasModifierProperty(PsiModifier.FINAL)
                        && hasTransactionalAnnotation(psiClass)) {
                    PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : psiClass,
                            "@Transactional final classes cannot be subclass-proxied by Spring",
                            ProblemHighlightType.ERROR, new RemoveFinalFromClassFix());
                }
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                PsiModifierList modifiers = method.getModifierList();
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.ASYNC_TRANSACTIONAL_EVENT_LISTENER)
                        && hasAnnotation(modifiers, "Async") && hasAnnotation(modifiers, "TransactionalEventListener")) {
                    holder.registerProblem((method.getNameIdentifier() != null) ? method.getNameIdentifier() : method,
                            "Potential data race: @Async with @TransactionalEventListener may read data before commit is fully visible",
                            ProblemHighlightType.ERROR, new RemoveAsyncAnnotationFix());
                    return;
                }
                PsiAnnotation transactional = findTransactionalAnnotation(method);
                if (transactional == null) {
                    if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.ROLLBACK_ONLY_WITHOUT_THROW)
                            && !isTestMethod(method) && callsSetRollbackOnly(method)) {
                        holder.registerProblem((method.getNameIdentifier() != null) ? method.getNameIdentifier() : method,
                                "setRollbackOnly() is used without an explicit thrown exception",
                                ProblemHighlightType.ERROR);
                    }
                    if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.READ_ONLY_WRITE)
                            && isEffectiveReadOnly(method) && countWriteOperations(method) > 0) {
                        holder.registerProblem((method.getNameIdentifier() != null) ? method.getNameIdentifier() : method,
                                "@Transactional(readOnly = true) method appears to perform write operations",
                                ProblemHighlightType.ERROR, new DisableReadOnlyFix(), new RemoveMethodTransactionalAnnotationFix());
                    }
                    if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.READ_ONLY_ENTITY_FETCH)
                            && isEffectiveReadOnly(method) && returnsLikelyEntity(method)) {
                        holder.registerProblem((method.getNameIdentifier() != null) ? method.getNameIdentifier() : method,
                                "Read-only transaction appears to fetch entities; prefer DTO projections",
                                ProblemHighlightType.ERROR, new RemoveMethodTransactionalAnnotationFix());
                    }
                    if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.MULTIPLE_WRITES_WITHOUT_TRANSACTION)
                            && shouldReportMultipleWritesWithoutTransaction(method)) {
                        holder.registerProblem((method.getNameIdentifier() != null) ? method.getNameIdentifier() : method,
                                "Method performs multiple write operations without @Transactional",
                                ProblemHighlightType.ERROR, new AddTransactionalFix());
                    }
                    if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.CONSTRUCTOR_TRAP) && isConstructorTrap(method)) {
                        holder.registerProblem((method.getNameIdentifier() != null) ? method.getNameIdentifier() : method,
                                "Constructor calls a @Transactional method before the Spring proxy exists",
                                ProblemHighlightType.ERROR);
                    }
                    return;
                }
                PsiIdentifier nameIdentifier = method.getNameIdentifier();
                if (isTransactionalTestMethod(method) && !SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.TRANSACTIONAL_TEST)) {
                    return;
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.TRANSACTIONAL_TEST) && isTransactionalTestMethod(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Avoid @Transactional on tests because rollback can hide SQL/flush failures",
                            ProblemHighlightType.ERROR, new RemoveMethodTransactionalAnnotationFix());
                    return;
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.PRIVATE_METHOD) && modifiers.hasModifierProperty(PsiModifier.PRIVATE)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Transactional private methods are not proxied by Spring; make the method public",
                            ProblemHighlightType.ERROR, new MakeMethodPublicFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.STATIC_METHOD) && modifiers.hasModifierProperty(PsiModifier.STATIC)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Transactional static methods are not proxied by Spring",
                            ProblemHighlightType.ERROR, new RemoveMethodModifierFix(PsiModifier.STATIC, "Remove static modifier"));
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.FINAL_METHOD) && modifiers.hasModifierProperty(PsiModifier.FINAL)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Transactional final methods cannot be subclass-proxied by Spring",
                            ProblemHighlightType.ERROR, new RemoveMethodModifierFix(PsiModifier.FINAL, "Remove final modifier"));
                }
                PsiClass containingClass = method.getContainingClass();
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.METHOD_IN_FINAL_CLASS) && containingClass != null
                        && containingClass.hasModifierProperty(PsiModifier.FINAL)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Transactional methods in final classes cannot be subclass-proxied by Spring",
                            ProblemHighlightType.ERROR);
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.CHECKED_EXCEPTION) && throwsCheckedExceptionWithoutRollback(method, transactional)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Checked exception may not trigger transaction rollback; add rollbackFor/rollbackOn",
                            ProblemHighlightType.ERROR, new AddRollbackForExceptionFix(), new AddRuntimeExceptionCatchFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.CONFLICTING_ROLLBACK_RULES) && hasConflictingRollbackRules(transactional)) {
                    holder.registerProblem(transactional,
                            "Conflicting rollbackFor/rollbackOn and noRollbackFor/noRollbackOn rules make rollback behavior non-obvious",
                            ProblemHighlightType.ERROR);
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.READ_ONLY_WRITE) && isEffectiveReadOnly(method) && countWriteOperations(method) > 0) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Transactional(readOnly = true) method appears to perform write operations",
                            ProblemHighlightType.ERROR, new DisableReadOnlyFix(), new RemoveMethodTransactionalAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.SELF_INVOCATION)
                        && hasTransactionalSelfInvocation(method, transactional)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Self-invocation of @Transactional method bypasses the Spring proxy",
                            ProblemHighlightType.ERROR);
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.REQUIRES_NEW_SELF_INVOCATION) && hasIgnoredRequiresNewSelfInvocation(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Self-invocation of @Transactional(REQUIRES_NEW) method ignores the REQUIRES_NEW boundary",
                            ProblemHighlightType.ERROR);
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.ASYNC_BOUNDARY) && hasAsyncBoundaryProblem(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Async crosses a transaction boundary; transaction context is not propagated to the async thread",
                            ProblemHighlightType.ERROR);
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.LONG_RUNNING_OPERATION) && hasLongRunningOperation(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Long-running work inside @Transactional may hold a database connection too long",
                            ProblemHighlightType.ERROR, new RemoveMethodTransactionalAnnotationFix(), new AddRequiresNewTransactionalFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.UNSUPPORTED_TIMEOUT) && hasTimeout(transactional)
                        && hasLikelyUnsupportedTimeoutDriver(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "@Transactional timeout may not be supported by the configured JDBC driver",
                            ProblemHighlightType.ERROR, new RemoveTimeoutAttributeFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.SWALLOWED_EXCEPTION) && swallowsException(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Exception is swallowed inside @Transactional method; transaction may commit unexpectedly",
                            ProblemHighlightType.ERROR, new AddRethrowToCatchBlocksFix(), new AddRollbackOnlyToCatchBlocksFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.READ_ONLY_ENTITY_FETCH)
                        && isEffectiveReadOnly(method) && returnsLikelyEntity(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Read-only transaction appears to fetch entities; prefer DTO projections",
                            ProblemHighlightType.ERROR, new RemoveMethodTransactionalAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.WEAK_ISOLATION)
                        && hasMultipleReads(method) && !hasExplicitStrongIsolation(transactional)
                        && !isEffectiveReadOnly(method) && !takesPessimisticLock(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Multiple reads with default/weak isolation can allow non-repeatable reads",
                            ProblemHighlightType.ERROR, new AddRepeatableReadIsolationFix(), new AddSerializableIsolationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.REQUIRES_NEW_IN_LOOP_OR_DEEP_STACK)
                        && hasRequiresNewTransactionalAnnotation(method) && (isCalledInLoop(method) || isDeepRequiresNewCallStack(method))) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "REQUIRES_NEW may borrow extra database connections when called in loops or deep call chains",
                            ProblemHighlightType.ERROR, new UseRequiredPropagationFix(), new RemoveMethodTransactionalAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.NESTED_WITH_JPA)
                        && hasNestedPropagation(transactional) && usesJpaWithoutNestedTransactions(method)) {
                    holder.registerProblem((nameIdentifier != null) ? nameIdentifier : method,
                            "Propagation.NESTED is not supported by standard JpaTransactionManager unless nested transactions are explicitly enabled",
                            ProblemHighlightType.ERROR, new UseRequiredPropagationFix(), new EnableNestedTransactionsCommentFix());
                }
            }

            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                if (!isTransactionalAnnotation(annotation)) {
                    return;
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.TRANSACTIONAL_BEAN_METHOD) && isBeanMethodAnnotation(annotation)) {
                    holder.registerProblem(annotation,
                            "@Transactional on @Bean methods in @Configuration classes is usually ineffective or misleading",
                            ProblemHighlightType.ERROR, new RemoveAnnotationFix());
                }
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.INTERFACE_METHOD_WITH_CGLIB) && isInterfaceMethodAnnotationWithCglib(annotation)) {
                    holder.registerProblem(annotation,
                            "@Transactional on interface methods is ignored when CGLIB proxyTargetClass=true is used",
                            ProblemHighlightType.ERROR);
                }
            }

            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (SpringTransactionalPrivateMethodInspection.this.thisKind(Kind.OPEN_SESSION_IN_VIEW)
                        && isOpenSessionInViewEnabled(file)) {
                    holder.registerProblem(file,
                            "spring.jpa.open-in-view=true keeps database connections open during view rendering",
                            ProblemHighlightType.ERROR, new DisableOpenSessionInViewFix());
                }
            }
        };
    }

    private boolean thisKind(Kind kind) {
        return this.kind == kind;
    }

    private static boolean isTestClass(PsiClass psiClass) {
        String name = psiClass.getName();
        return name != null && (name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")
                || name.endsWith("TestIT") || name.endsWith("TestsIT") || name.endsWith("ITTests"));
    }

    private static boolean isTestMethod(PsiMethod method) {
        if (hasAnnotation(method.getModifierList(), "Test") || hasAnnotation(method.getModifierList(), "ParameterizedTest")
                || hasAnnotation(method.getModifierList(), "RepeatedTest") || hasAnnotation(method.getModifierList(), "TestFactory")) {
            return true;
        }
        PsiClass containingClass = method.getContainingClass();
        return containingClass != null && isTestClass(containingClass);
    }

    private static boolean isTransactionalTestMethod(PsiMethod method) {
        if (findTransactionalAnnotation(method) == null) {
            return false;
        }
        PsiClass containingClass = method.getContainingClass();
        return hasTestAnnotation(method) || (containingClass != null && isTestClass(containingClass));
    }

    private static boolean hasTestAnnotation(PsiMethod method) {
        return hasAnnotation(method.getModifierList(), "Test") || hasAnnotation(method.getModifierList(), "ParameterizedTest")
                || hasAnnotation(method.getModifierList(), "RepeatedTest") || hasAnnotation(method.getModifierList(), "TestFactory");
    }

    private static boolean shouldReportMultipleWritesWithoutTransaction(PsiMethod method) {
        if (countWriteOperationsOutsideTransactionTemplate(method) < 2 || isTestMethod(method)
                || isWithinTransactionalClass(method) || isPrivateWithTransactionalCallerCoverage(method)) {
            return false;
        }
        return true;
    }

    private static boolean isPrivateWithTransactionalCallerCoverage(PsiMethod method) {
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return false;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        boolean hasInClassCaller = false;
        for (PsiMethod candidate : containingClass.getMethods()) {
            if (candidate != method && candidate.getBody() != null && callsMethod(candidate, method)) {
                hasInClassCaller = true;
                break;
            }
        }
        return hasInClassCaller && SpringRemainingPitfallInspection.hasTransactionalCallerCoverage(method);
    }

    private static boolean isWithinTransactionalClass(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        while (containingClass != null) {
            if (hasTransactionalAnnotation(containingClass)) {
                return true;
            }
            PsiClass superClass = containingClass.getSuperClass();
            if (superClass == containingClass || (superClass != null && "java.lang.Object".equals(superClass.getQualifiedName()))) {
                return false;
            }
            containingClass = superClass;
        }
        return false;
    }

    private static boolean isPrivateCalledOnlyFromWriteTransactionalContext(PsiMethod method) {
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return false;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        boolean hasCall = false;
        for (PsiMethod candidate : containingClass.getMethods()) {
            if (candidate == method || candidate.getBody() == null) {
                continue;
            }
            if (callsMethod(candidate, method)) {
                hasCall = true;
                if (isEffectiveReadOnly(candidate)) {
                    return false;
                }
            }
        }
        return hasCall;
    }

    private static boolean callsMethod(PsiMethod caller, PsiMethod target) {
        boolean[] found = {false};
        caller.getBody().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (expression.resolveMethod() == target) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean isRepositoryClass(PsiClass psiClass) {
        return SpringInspectionUtil.isSpringDataRepository(psiClass);
    }

    private static boolean isOpenSessionInViewEnabled(PsiFile file) {
        String name = file.getName();
        if (!(name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return false;
        }
        String text = file.getText().toLowerCase(Locale.ROOT);
        return text.contains("spring.jpa.open-in-view=true") || text.contains("open-in-view: true");
    }

    private static boolean hasTransactionalAnnotation(PsiClass psiClass) {
        return findTransactionalAnnotation(psiClass.getModifierList()) != null;
    }

    private static PsiAnnotation findTransactionalAnnotation(PsiMethod method) {
        return findTransactionalAnnotation(method.getModifierList());
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

    private static boolean isTransactionalAnnotation(PsiAnnotation annotation) {
        String name = annotation.getQualifiedName();
        if (name == null && annotation.getNameReferenceElement() != null) {
            name = annotation.getNameReferenceElement().getText();
        }
        return "org.springframework.transaction.annotation.Transactional".equals(name)
                || "jakarta.transaction.Transactional".equals(name)
                || "javax.transaction.Transactional".equals(name)
                || "Transactional".equals(name);
    }

    private static boolean isBeanMethodAnnotation(PsiAnnotation annotation) {
        PsiMethod method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
        PsiClass containingClass = (method != null) ? method.getContainingClass() : null;
        return method != null && containingClass != null && hasAnnotation(method.getModifierList(), "Bean")
                && hasAnnotation(containingClass.getModifierList(), "Configuration");
    }

    private static boolean isInterfaceMethodAnnotationWithCglib(PsiAnnotation annotation) {
        PsiMethod method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
        PsiClass containingClass = (method != null) ? method.getContainingClass() : null;
        if (containingClass == null || !containingClass.isInterface()) {
            return false;
        }
        com.intellij.psi.PsiFile file = annotation.getContainingFile();
        if (!(file instanceof com.intellij.psi.PsiJavaFile javaFile)) {
            return false;
        }
        for (PsiClass cls : javaFile.getClasses()) {
            PsiModifierList clsModifiers = cls.getModifierList();
            if (clsModifiers == null) {
                continue;
            }
            for (PsiAnnotation clsAnnotation : clsModifiers.getAnnotations()) {
                String name = clsAnnotation.getQualifiedName();
                if (name == null && clsAnnotation.getNameReferenceElement() != null) {
                    name = clsAnnotation.getNameReferenceElement().getText();
                }
                if (!("EnableTransactionManagement".equals(name) || (name != null && name.endsWith(".EnableTransactionManagement")))) {
                    continue;
                }
                PsiAnnotationMemberValue proxyVal = clsAnnotation.findDeclaredAttributeValue("proxyTargetClass");
                if (proxyVal != null && "true".equals(proxyVal.getText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnnotation(PsiModifierList modifiers, String shortName) {
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name == null && annotation.getNameReferenceElement() != null) {
                name = annotation.getNameReferenceElement().getText();
            }
            if (shortName.equals(name) || (name != null && name.endsWith("." + shortName))) {
                return true;
            }
        }
        return false;
    }

    private static boolean throwsCheckedExceptionWithoutRollback(PsiMethod method, PsiAnnotation annotation) {
        if (isEffectiveReadOnly(method) && countWriteOperations(method) == 0) {
            return false;
        }
        Set<String> rollbackTypes = annotationTypes(annotation, "rollbackFor", "rollbackOn");
        Set<String> noRollbackTypes = annotationTypes(annotation, "noRollbackFor", "noRollbackOn");
        PsiCodeBlock body = method.getBody();
        for (PsiClassType thrownType : method.getThrowsList().getReferencedTypes()) {
            String type = thrownType.getCanonicalText();
            if (body != null) {
                boolean thrownByBody = false;
                for (PsiClassType bodyThrownType : ExceptionUtil.getThrownCheckedExceptions(body)) {
                    if (thrownType.isAssignableFrom(bodyThrownType)) {
                        thrownByBody = true;
                        break;
                    }
                }
                if (!thrownByBody) {
                    continue;
                }
            }
            if (isCheckedException(type) && !covers(rollbackTypes, type) && !covers(noRollbackTypes, type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConflictingRollbackRules(PsiAnnotation annotation) {
        Set<String> rollbackTypes = annotationTypes(annotation, "rollbackFor", "rollbackOn");
        Set<String> noRollbackTypes = annotationTypes(annotation, "noRollbackFor", "noRollbackOn");
        for (String rollbackType : rollbackTypes) {
            for (String noRollbackType : noRollbackTypes) {
                if (covers(rollbackType, noRollbackType) || covers(noRollbackType, rollbackType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEffectiveReadOnly(PsiMethod method) {
        PsiAnnotation methodTx = findTransactionalAnnotation(method);
        if (methodTx != null && isReadOnly(methodTx)) {
            return true;
        }
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            PsiAnnotation classTx = findTransactionalAnnotation(containingClass.getModifierList());
            if (classTx != null && isReadOnly(classTx)) {
                if (methodTx == null) {
                    if (isPrivateCalledOnlyFromWriteTransactionalContext(method)) {
                        return false;
                    }
                    return true;
                }
                Boolean methodExplicitReadOnly = explicitReadOnlyValue(methodTx);
                if (!Boolean.FALSE.equals(methodExplicitReadOnly)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Boolean explicitReadOnlyValue(PsiAnnotation annotation) {
        for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
            if ("readOnly".equals(pair.getAttributeName()) && pair.getValue() != null) {
                return "true".equals(pair.getValue().getText());
            }
        }
        return null;
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

    private static boolean returnsLikelyEntity(PsiMethod method) {
        PsiType type = method.getReturnType();
        if (!(type instanceof PsiClassType classType)) {
            return false;
        }
        PsiClass resolved = classType.resolve();
        return resolved != null && (hasAnnotation(resolved.getModifierList(), "Entity")
                || hasAnnotation(resolved.getModifierList(), "MappedSuperclass"));
    }

    private static boolean hasMultipleReads(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        int[] count = {0};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (isReadOperation(expression)) {
                    count[0]++;
                }
            }
        });
        return count[0] >= 2;
    }

    private static boolean takesPessimisticLock(PsiMethod method) {
        return takesPessimisticLock(method, new HashSet<>());
    }

    private static boolean takesPessimisticLock(PsiMethod method, Set<PsiMethod> visited) {
        if (!visited.add(method)) {
            return false;
        }
        PsiCodeBlock body = method.getBody();
        PsiClass containingClass = method.getContainingClass();
        if (body == null || containingClass == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod called = expression.resolveMethod();
                if (hasPessimisticLock(called)
                        || (called != null && called.getContainingClass() == containingClass
                        && takesPessimisticLock(called, visited))) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean hasPessimisticLock(PsiMethod method) {
        if (method == null) {
            return false;
        }
        PsiModifierList modifiers = method.getModifierList();
        if (SpringInspectionUtil.hasAnnotation(modifiers, "org.springframework.data.jpa.repository.Lock", "Lock")) {
            return true;
        }
        PsiAnnotation query = SpringInspectionUtil.findAnnotation(modifiers,
                "org.springframework.data.jpa.repository.Query", "Query");
        if (query == null) {
            return false;
        }
        String queryText = query.getText().toLowerCase(Locale.ROOT);
        return queryText.contains("for update") || queryText.contains("for no key update");
    }

    private static boolean isReadOperation(PsiMethodCallExpression expression) {
        String name = referenceName(expression.getMethodExpression());
        if (name == null) {
            return false;
        }
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) {
            return false;
        }
        if (!isPersistenceMethod(resolved)) {
            return false;
        }
        return name.startsWith("find") || name.startsWith("get") || name.startsWith("read")
                || name.startsWith("query") || name.startsWith("search") || name.startsWith("stream")
                || name.startsWith("exists") || name.startsWith("load") || name.equals("count");
    }

    private static boolean hasExplicitStrongIsolation(PsiAnnotation annotation) {
        String text = annotation.getText();
        return text.contains("REPEATABLE_READ") || text.contains("SERIALIZABLE");
    }

    private static boolean callsSetRollbackOnly(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if ("setRollbackOnly".equals(referenceName(expression.getMethodExpression()))) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static int countWriteOperations(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return 0;
        }
        int[] count = {0};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (isWriteOperation(expression)) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    private static int countWriteOperationsOutsideTransactionTemplate(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return 0;
        }
        int[] count = {0};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (!SpringRemainingPitfallInspection.isInsideTransactionTemplateCallback(expression)
                        && isWriteOperation(expression)) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    private static boolean isWriteOperation(PsiMethodCallExpression expression) {
        String name = referenceName(expression.getMethodExpression());
        if (name == null) {
            return false;
        }
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) {
            return false;
        }
        if (!isPersistenceMethod(resolved)) {
            return false;
        }
        return name.equals("save") || name.equals("saveAll") || name.equals("delete") || name.equals("deleteAll")
                || name.equals("deleteById") || name.equals("persist") || name.equals("merge") || name.equals("flush")
                || name.equals("remove");
    }

    private static boolean isPersistenceMethod(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        return isSpringDataRepositoryOrSubclass(containingClass)
                || isJpaEntityManagerOrQueryClass(containingClass);
    }

    private static boolean isSpringDataRepositoryOrSubclass(PsiClass psiClass) {
        return SpringInspectionUtil.isSpringDataRepository(psiClass)
                || SpringInspectionUtil.isSpringDataRepositoryType(psiClass);
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

    private static boolean isConstructorTrap(PsiMethod constructor) {
        if (!constructor.isConstructor()) {
            return false;
        }
        PsiCodeBlock body = constructor.getBody();
        PsiClass containingClass = constructor.getContainingClass();
        if (body == null || containingClass == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod target = expression.resolveMethod();
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (target != null && target.getContainingClass() == containingClass
                        && (qualifier == null || qualifier instanceof PsiThisExpression)
                        && findTransactionalAnnotation(target) != null) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean hasTransactionalSelfInvocation(PsiMethod method, PsiAnnotation callerTransactional) {
        PsiCodeBlock body = method.getBody();
        PsiClass containingClass = method.getContainingClass();
        if (body == null || containingClass == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod target = expression.resolveMethod();
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                PsiAnnotation targetTransactional = target != null ? findTransactionalAnnotation(target) : null;
                if (target != null && target.getContainingClass() == containingClass
                        && (qualifier == null || qualifier instanceof PsiThisExpression)
                        && targetTransactional != null
                        && changesTransactionSemantics(callerTransactional, targetTransactional)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean changesTransactionSemantics(PsiAnnotation caller, PsiAnnotation callee) {
        for (PsiNameValuePair attribute : callee.getParameterList().getAttributes()) {
            if (!"readOnly".equals(attribute.getName())) {
                return true;
            }
        }
        return isReadOnly(caller) && !isReadOnly(callee);
    }


    private static boolean hasIgnoredRequiresNewSelfInvocation(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        PsiClass containingClass = method.getContainingClass();
        if (body == null || containingClass == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod target = expression.resolveMethod();
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (target != null && target.getContainingClass() == containingClass
                        && (qualifier == null || qualifier instanceof PsiThisExpression)
                        && hasRequiresNewTransactionalAnnotation(target)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean hasRequiresNewTransactionalAnnotation(PsiMethod method) {
        PsiAnnotation annotation = findTransactionalAnnotation(method);
        return annotation != null && annotation.getText().contains("REQUIRES_NEW");
    }

    private static boolean hasNestedPropagation(PsiAnnotation annotation) {
        return annotation.getText().contains("NESTED");
    }

    private static boolean isCalledInLoop(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        boolean[] found = {false};
        for (PsiMethod candidate : containingClass.getMethods()) {
            PsiCodeBlock body = candidate.getBody();
            if (body == null) {
                continue;
            }
            body.accept(new JavaRecursiveElementVisitor() {
                private int loopDepth;

                @Override
                public void visitForStatement(@NotNull PsiForStatement statement) {
                    this.loopDepth++;
                    super.visitForStatement(statement);
                    this.loopDepth--;
                }

                @Override
                public void visitForeachStatement(@NotNull PsiForeachStatement statement) {
                    this.loopDepth++;
                    super.visitForeachStatement(statement);
                    this.loopDepth--;
                }

                @Override
                public void visitWhileStatement(@NotNull PsiWhileStatement statement) {
                    this.loopDepth++;
                    super.visitWhileStatement(statement);
                    this.loopDepth--;
                }

                @Override
                public void visitDoWhileStatement(@NotNull PsiDoWhileStatement statement) {
                    this.loopDepth++;
                    super.visitDoWhileStatement(statement);
                    this.loopDepth--;
                }

                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    if (this.loopDepth > 0 && expression.resolveMethod() == method) {
                        found[0] = true;
                    }
                }
            });
        }
        return found[0];
    }

    private static boolean isDeepRequiresNewCallStack(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return false;
        }
        for (PsiMethod candidate : containingClass.getMethods()) {
            if (candidate != method && findTransactionalAnnotation(candidate) != null
                    && callsThroughIntermediate(candidate, method, containingClass, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean callsThroughIntermediate(PsiMethod caller, PsiMethod target, PsiClass containingClass,
                                                    Set<PsiMethod> visited) {
        if (!visited.add(caller) || caller.getBody() == null) {
            return false;
        }
        boolean[] found = {false};
        caller.getBody().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod called = expression.resolveMethod();
                if (called == null || called.getContainingClass() != containingClass) {
                    return;
                }
                if (called == target) {
                    found[0] = true;
                } else if (callsThroughIntermediate(called, target, containingClass, visited)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean usesJpaWithoutNestedTransactions(PsiMethod method) {
        com.intellij.psi.PsiFile file = method.getContainingFile();
        if (!(file instanceof com.intellij.psi.PsiJavaFile javaFile)) {
            return true;
        }
        for (com.intellij.psi.PsiImportStatement imp : (javaFile.getImportList() != null) ? javaFile.getImportList().getImportStatements() : new com.intellij.psi.PsiImportStatement[0]) {
            String qName = imp.getQualifiedName();
            if (qName == null) {
                continue;
            }
            if (qName.startsWith("jakarta.persistence.") || qName.startsWith("javax.persistence.")
                    || qName.startsWith("org.springframework.data.")
                    || qName.equals("org.springframework.orm.jpa.JpaTransactionManager")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAsyncBoundaryProblem(PsiMethod method) {
        if (hasAnnotation(method.getModifierList(), "Async")) {
            return true;
        }
        PsiCodeBlock body = method.getBody();
        PsiClass containingClass = method.getContainingClass();
        if (body == null || containingClass == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod target = expression.resolveMethod();
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (target != null && target.getContainingClass() == containingClass
                        && (qualifier == null || qualifier instanceof PsiThisExpression)
                        && hasAnnotation(target.getModifierList(), "Async")) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean hasLongRunningOperation(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (isLongRunningOperation(expression)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean isLongRunningOperation(PsiMethodCallExpression expression) {
        String methodName = referenceName(expression.getMethodExpression());
        if ("sleep".equals(methodName)) {
            return true;
        }
        PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return false;
        }
        PsiType qualifierType = qualifier.getType();
        if (qualifierType instanceof PsiClassType classType) {
            String className = classType.getClassName();
            if (className != null) {
                String lowerClassName = className.toLowerCase(Locale.ROOT);
                return className.endsWith("Client") || lowerClassName.contains("resttemplate")
                        || lowerClassName.contains("restoperations") || lowerClassName.contains("webclient");
            }
        } else if (qualifierType != null) {
            return false;
        }
        String lowerQualifier = qualifier.getText().toLowerCase(Locale.ROOT);
        return lowerQualifier.contains("client") || lowerQualifier.contains("resttemplate")
                || lowerQualifier.contains("restoperations") || lowerQualifier.contains("webclient");
    }

    private static boolean hasTimeout(PsiAnnotation annotation) {
        return hasAttribute(annotation, "timeout") || hasAttribute(annotation, "timeoutString");
    }

    private static boolean hasAttribute(PsiAnnotation annotation, String name) {
        for (PsiNameValuePair attribute : annotation.getParameterList().getAttributes()) {
            if (name.equals(attribute.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLikelyUnsupportedTimeoutDriver(PsiMethod method) {
        String fileText = method.getContainingFile().getText().toLowerCase(Locale.ROOT);
        return containsUnsupportedDriverHint(fileText) || directoryContainsUnsupportedDriverHint(method.getContainingFile().getContainingDirectory());
    }

    private static boolean directoryContainsUnsupportedDriverHint(PsiDirectory directory) {
        if (directory == null) {
            return false;
        }
        for (PsiFile file : directory.getFiles()) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if ((name.equals("application.properties") || name.equals("application.yml") || name.equals("application.yaml"))
                    && containsUnsupportedDriverHint(file.getText().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsupportedDriverHint(String text) {
        return text.contains("sqlite") || text.contains("h2") || text.contains("r2dbc");
    }

    private static boolean swallowsException(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        boolean[] swallowed = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitTryStatement(@NotNull PsiTryStatement statement) {
                super.visitTryStatement(statement);
                for (PsiCatchSection catchSection : statement.getCatchSections()) {
                    if (catchSection.getCatchType() != null && isRollbackExceptionType(catchSection.getCatchType())
                            && !catchBlockRethrows(catchSection.getCatchBlock())) {
                        swallowed[0] = true;
                    }
                }
            }
        });
        return swallowed[0];
    }

    private static boolean isRollbackExceptionType(PsiType type) {
        String text = type.getCanonicalText();
        return "Exception".equals(text) || "java.lang.Exception".equals(text)
                || "RuntimeException".equals(text) || "java.lang.RuntimeException".equals(text)
                || "Throwable".equals(text) || "java.lang.Throwable".equals(text)
                || "Error".equals(text) || "java.lang.Error".equals(text);
    }

    private static boolean catchBlockRethrows(PsiCodeBlock block) {
        if (block == null) {
            return false;
        }
        boolean[] rethrows = {false};
        block.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitThrowStatement(@NotNull PsiThrowStatement statement) {
                rethrows[0] = true;
            }

            @Override
            public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
            }
        });
        return rethrows[0];
    }

    private static String referenceName(PsiReferenceExpression expression) {
        PsiElement nameElement = expression.getReferenceNameElement();
        return (nameElement != null) ? nameElement.getText() : null;
    }

    private static Set<String> annotationTypes(PsiAnnotation annotation, String... names) {
        Set<String> result = new HashSet<>();
        for (PsiNameValuePair attribute : annotation.getParameterList().getAttributes()) {
            if (!matches(attribute.getName(), names)) {
                continue;
            }
            collectTypes(attribute.getValue(), result);
        }
        return result;
    }

    private static boolean matches(String name, String... names) {
        for (String candidate : names) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void collectTypes(PsiAnnotationMemberValue value, Set<String> result) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                collectTypes(initializer, result);
            }
        } else if (value instanceof PsiClassObjectAccessExpression classObject) {
            PsiTypeElement operand = classObject.getOperand();
            result.add(operand.getType().getCanonicalText());
        } else if (value != null && value.getText().endsWith(".class")) {
            result.add(value.getText().substring(0, value.getText().length() - ".class".length()));
        }
    }

    private static boolean isCheckedException(String type) {
        return !covers("java.lang.RuntimeException", type) && !covers("RuntimeException", type)
                && !covers("java.lang.Error", type) && !covers("Error", type);
    }

    private static boolean covers(Set<String> configuredTypes, String thrownType) {
        for (String configuredType : configuredTypes) {
            if (covers(configuredType, thrownType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean covers(String configuredType, String thrownType) {
        if (configuredType.equals(thrownType)) {
            return true;
        }
        String configuredSimple = configuredType.substring(configuredType.lastIndexOf('.') + 1);
        String thrownSimple = thrownType.substring(thrownType.lastIndexOf('.') + 1);
        return "Exception".equals(configuredSimple) || "Throwable".equals(configuredSimple)
                || configuredSimple.equals(thrownSimple);
    }

    private static void addIsolation(Project project, ProblemDescriptor descriptor, String isolation) {
        PsiAnnotation annotation = new MethodTransactionalAnnotationFix() {
            @Override
            @NotNull
            public String getName() {
                return "";
            }

            @Override
            @NotNull
            public String getFamilyName() {
                return "";
            }

            @Override
            public void applyFix(@NotNull Project p, @NotNull ProblemDescriptor d) {
            }
        }.annotation(descriptor);
        if (annotation != null) {
            replaceAnnotation(project, annotation, addAttribute(annotation.getText(), "isolation = " + isolation));
        }
    }

    private static void addToCatchBlocks(Project project, PsiMethod method, String statementText) {
        if (method == null || method.getBody() == null) {
            return;
        }
        method.getBody().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitTryStatement(@NotNull PsiTryStatement statement) {
                for (PsiCatchSection catchSection : statement.getCatchSections()) {
                    if (catchSection.getCatchBlock() != null && !catchBlockRethrows(catchSection.getCatchBlock())) {
                        catchSection.getCatchBlock().addBefore(JavaPsiFacade.getElementFactory(project).createStatementFromText(statementText, method), catchSection.getCatchBlock().getRBrace());
                    }
                }
            }
        });
    }

    private static void replaceAnnotation(Project project, PsiAnnotation annotation, String text) {
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText("A.java", JavaLanguage.INSTANCE, "@" + text.substring(1) + " class A {}");
        PsiAnnotation replacement = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, PsiAnnotation.class);
        if (replacement != null) {
            annotation.replace(replacement);
        }
    }

    private static String addAttribute(String annotationText, String attribute) {
        if (!annotationText.contains("(")) {
            return annotationText + "(" + attribute + ")";
        }
        return annotationText.replaceFirst("\\)$", ", " + attribute + ")");
    }

    private static String removeAttribute(String annotationText, String attributeName) {
        return annotationText.replaceAll(",?\\s*" + attributeName + "(?:String)?\\s*=\\s*[^,)]+", "").replace("(, ", "(").replace("()", "");
    }

    private enum Kind {
        PRIVATE_METHOD,
        STATIC_METHOD,
        FINAL_METHOD,
        FINAL_CLASS,
        METHOD_IN_FINAL_CLASS,
        CHECKED_EXCEPTION,
        CONFLICTING_ROLLBACK_RULES,
        READ_ONLY_WRITE,
        SELF_INVOCATION,
        REQUIRES_NEW_SELF_INVOCATION,
        ASYNC_BOUNDARY,
        LONG_RUNNING_OPERATION,
        UNSUPPORTED_TIMEOUT,
        SWALLOWED_EXCEPTION,
        TRANSACTIONAL_BEAN_METHOD,
        INTERFACE_METHOD_WITH_CGLIB,
        MULTIPLE_WRITES_WITHOUT_TRANSACTION,
        CONSTRUCTOR_TRAP,
        TRANSACTIONAL_TEST,
        READ_ONLY_ENTITY_FETCH,
        OPEN_SESSION_IN_VIEW,
        REPOSITORY_TRANSACTION,
        WEAK_ISOLATION,
        ROLLBACK_ONLY_WITHOUT_THROW,
        ABSTRACT_CLASS_TRANSACTION,
        ASYNC_TRANSACTIONAL_EVENT_LISTENER,
        REQUIRES_NEW_IN_LOOP_OR_DEEP_STACK,
        NESTED_WITH_JPA
    }

    public static class StaticMethod extends SpringTransactionalPrivateMethodInspection {
        public StaticMethod() {
            super(Kind.STATIC_METHOD);
        }
    }

    public static class FinalMethod extends SpringTransactionalPrivateMethodInspection {
        public FinalMethod() {
            super(Kind.FINAL_METHOD);
        }
    }

    public static class FinalClass extends SpringTransactionalPrivateMethodInspection {
        public FinalClass() {
            super(Kind.FINAL_CLASS);
        }
    }

    public static class MethodInFinalClass extends SpringTransactionalPrivateMethodInspection {
        public MethodInFinalClass() {
            super(Kind.METHOD_IN_FINAL_CLASS);
        }
    }

    public static class CheckedException extends SpringTransactionalPrivateMethodInspection {
        public CheckedException() {
            super(Kind.CHECKED_EXCEPTION);
        }
    }

    public static class ConflictingRollbackRules extends SpringTransactionalPrivateMethodInspection {
        public ConflictingRollbackRules() {
            super(Kind.CONFLICTING_ROLLBACK_RULES);
        }
    }

    public static class ReadOnlyWrite extends SpringTransactionalPrivateMethodInspection {
        public ReadOnlyWrite() {
            super(Kind.READ_ONLY_WRITE);
        }
    }

    public static class SelfInvocation extends SpringTransactionalPrivateMethodInspection {
        public SelfInvocation() {
            super(Kind.SELF_INVOCATION);
        }
    }

    public static class RequiresNewSelfInvocation extends SpringTransactionalPrivateMethodInspection {
        public RequiresNewSelfInvocation() {
            super(Kind.REQUIRES_NEW_SELF_INVOCATION);
        }
    }

    public static class AsyncBoundary extends SpringTransactionalPrivateMethodInspection {
        public AsyncBoundary() {
            super(Kind.ASYNC_BOUNDARY);
        }
    }

    public static class LongRunningOperation extends SpringTransactionalPrivateMethodInspection {
        public LongRunningOperation() {
            super(Kind.LONG_RUNNING_OPERATION);
        }
    }

    public static class UnsupportedTimeout extends SpringTransactionalPrivateMethodInspection {
        public UnsupportedTimeout() {
            super(Kind.UNSUPPORTED_TIMEOUT);
        }
    }

    public static class SwallowedException extends SpringTransactionalPrivateMethodInspection {
        public SwallowedException() {
            super(Kind.SWALLOWED_EXCEPTION);
        }
    }

    public static class TransactionalBeanMethod extends SpringTransactionalPrivateMethodInspection {
        public TransactionalBeanMethod() {
            super(Kind.TRANSACTIONAL_BEAN_METHOD);
        }
    }

    public static class InterfaceMethodWithCglib extends SpringTransactionalPrivateMethodInspection {
        public InterfaceMethodWithCglib() {
            super(Kind.INTERFACE_METHOD_WITH_CGLIB);
        }
    }

    public static class MultipleWritesWithoutTransaction extends SpringTransactionalPrivateMethodInspection {
        public MultipleWritesWithoutTransaction() {
            super(Kind.MULTIPLE_WRITES_WITHOUT_TRANSACTION);
        }
    }

    public static class ConstructorTrap extends SpringTransactionalPrivateMethodInspection {
        public ConstructorTrap() {
            super(Kind.CONSTRUCTOR_TRAP);
        }
    }

    public static class TransactionalTest extends SpringTransactionalPrivateMethodInspection {
        public TransactionalTest() {
            super(Kind.TRANSACTIONAL_TEST);
        }
    }

    public static class ReadOnlyEntityFetch extends SpringTransactionalPrivateMethodInspection {
        public ReadOnlyEntityFetch() {
            super(Kind.READ_ONLY_ENTITY_FETCH);
        }
    }

    public static class OpenSessionInView extends SpringTransactionalPrivateMethodInspection {
        public OpenSessionInView() {
            super(Kind.OPEN_SESSION_IN_VIEW);
        }
    }

    public static class RepositoryTransaction extends SpringTransactionalPrivateMethodInspection {
        public RepositoryTransaction() {
            super(Kind.REPOSITORY_TRANSACTION);
        }
    }

    public static class WeakIsolation extends SpringTransactionalPrivateMethodInspection {
        public WeakIsolation() {
            super(Kind.WEAK_ISOLATION);
        }
    }

    public static class RollbackOnlyWithoutThrow extends SpringTransactionalPrivateMethodInspection {
        public RollbackOnlyWithoutThrow() {
            super(Kind.ROLLBACK_ONLY_WITHOUT_THROW);
        }
    }

    public static class AbstractClassTransaction extends SpringTransactionalPrivateMethodInspection {
        public AbstractClassTransaction() {
            super(Kind.ABSTRACT_CLASS_TRANSACTION);
        }
    }

    public static class AsyncTransactionalEventListener extends SpringTransactionalPrivateMethodInspection {
        public AsyncTransactionalEventListener() {
            super(Kind.ASYNC_TRANSACTIONAL_EVENT_LISTENER);
        }
    }

    public static class RequiresNewInLoopOrDeepStack extends SpringTransactionalPrivateMethodInspection {
        public RequiresNewInLoopOrDeepStack() {
            super(Kind.REQUIRES_NEW_IN_LOOP_OR_DEEP_STACK);
        }
    }

    public static class NestedWithJpa extends SpringTransactionalPrivateMethodInspection {
        public NestedWithJpa() {
            super(Kind.NESTED_WITH_JPA);
        }
    }

    private static final class MakeMethodPublicFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Make method public";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod method = descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
            if (method == null) {
                return;
            }
            PsiModifierList modifiers = method.getModifierList();
            modifiers.setModifierProperty(PsiModifier.PRIVATE, false);
            modifiers.setModifierProperty(PsiModifier.PROTECTED, false);
            modifiers.setModifierProperty(PsiModifier.PUBLIC, true);
        }
    }

    private static class RemoveMethodModifierFix implements LocalQuickFix {

        private final String modifier;

        private final String name;

        RemoveMethodModifierFix(String modifier, String name) {
            this.modifier = modifier;
            this.name = name;
        }

        @Override
        @NotNull
        public String getName() {
            return this.name;
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod method = descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
            if (method != null) {
                method.getModifierList().setModifierProperty(this.modifier, false);
            }
        }
    }

    private static final class RemoveFinalFromClassFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Remove final modifier";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass psiClass = descriptor.getPsiElement() instanceof PsiClass c ? c
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class);
            if (psiClass != null && psiClass.getModifierList() != null) {
                psiClass.getModifierList().setModifierProperty(PsiModifier.FINAL, false);
            }
        }
    }

    private static final class RemoveAnnotationFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Remove @Transactional annotation";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (descriptor.getPsiElement() instanceof PsiAnnotation annotation) {
                annotation.delete();
            }
        }
    }

    private static final class RemoveClassTransactionalAnnotationFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Remove @Transactional annotation";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass psiClass = descriptor.getPsiElement() instanceof PsiClass c ? c
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class);
            PsiAnnotation annotation = (psiClass != null) ? findTransactionalAnnotation(psiClass.getModifierList()) : null;
            if (annotation != null) {
                annotation.delete();
            }
        }
    }

    private static final class RemoveAsyncAnnotationFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Remove @Async annotation";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod method = descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
            PsiModifierList modifiers = (method != null) ? method.getModifierList() : null;
            if (modifiers == null) {
                return;
            }
            for (PsiAnnotation annotation : modifiers.getAnnotations()) {
                if (SpringInspectionUtil.isAnnotation(annotation, "org.springframework.scheduling.annotation.Async", "Async")) {
                    annotation.delete();
                    return;
                }
            }
        }
    }

    private static final class DisableOpenSessionInViewFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Set spring.jpa.open-in-view=false";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement() instanceof PsiFile f ? f : descriptor.getPsiElement().getContainingFile();
            if (file == null) {
                return;
            }
            String text = file.getText()
                    .replace("spring.jpa.open-in-view=true", "spring.jpa.open-in-view=false")
                    .replace("spring.jpa.open-in-view: true", "spring.jpa.open-in-view: false")
                    .replace("open-in-view: true", "open-in-view: false");
            PsiFile replacement = PsiFileFactory.getInstance(project)
                    .createFileFromText(file.getName(), JavaLanguage.INSTANCE, text);
            file.replace(replacement);
        }
    }

    private abstract static class MethodTransactionalAnnotationFix implements LocalQuickFix {

        protected PsiAnnotation annotation(ProblemDescriptor descriptor) {
            PsiMethod method = descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
            return (method != null) ? findTransactionalAnnotation(method) : null;
        }

        protected PsiMethod method(ProblemDescriptor descriptor) {
            return descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
        }
    }

    private static final class RemoveMethodTransactionalAnnotationFix extends MethodTransactionalAnnotationFix {

        @Override
        @NotNull
        public String getName() {
            return "Remove @Transactional annotation";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiAnnotation annotation = this.annotation(descriptor);
            if (annotation != null) {
                annotation.delete();
            }
        }
    }

    private static final class DisableReadOnlyFix extends MethodTransactionalAnnotationFix {

        @Override
        @NotNull
        public String getName() {
            return "Set readOnly to false";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiAnnotation annotation = this.annotation(descriptor);
            if (annotation != null) {
                replaceAnnotation(project, annotation, annotation.getText().replace("readOnly = true", "readOnly = false"));
            }
        }
    }

    private static final class RemoveTimeoutAttributeFix extends MethodTransactionalAnnotationFix {

        @Override
        @NotNull
        public String getName() {
            return "Remove timeout attribute";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiAnnotation annotation = this.annotation(descriptor);
            if (annotation != null) {
                replaceAnnotation(project, annotation, removeAttribute(annotation.getText(), "timeout"));
            }
        }
    }

    private static final class AddRollbackForExceptionFix extends MethodTransactionalAnnotationFix {

        @Override
        @NotNull
        public String getName() {
            return "Add rollbackFor = Exception.class";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiAnnotation annotation = this.annotation(descriptor);
            if (annotation != null) {
                replaceAnnotation(project, annotation, addAttribute(annotation.getText(), "rollbackFor = Exception.class"));
            }
        }
    }

    private static final class AddRepeatableReadIsolationFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Use Isolation.REPEATABLE_READ";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            addIsolation(project, descriptor, "Isolation.REPEATABLE_READ");
        }
    }

    private static final class AddSerializableIsolationFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Use Isolation.SERIALIZABLE";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            addIsolation(project, descriptor, "Isolation.SERIALIZABLE");
        }
    }

    private static final class AddRequiresNewTransactionalFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Use Propagation.REQUIRES_NEW";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiAnnotation annotation = this.annotation(descriptor);
            if (annotation != null) {
                replaceAnnotation(project, annotation, addAttribute(annotation.getText(), "propagation = Propagation.REQUIRES_NEW"));
            }
        }
    }

    private static final class UseRequiredPropagationFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Use Propagation.REQUIRED";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiAnnotation annotation = this.annotation(descriptor);
            if (annotation != null) {
                replaceAnnotation(project, annotation, annotation.getText().replace("Propagation.REQUIRES_NEW", "Propagation.REQUIRED").replace("Propagation.NESTED", "Propagation.REQUIRED"));
            }
        }
    }

    private static final class EnableNestedTransactionsCommentFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Add nestedTransactionAllowed reminder comment";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        }
    }

    private static final class AddRuntimeExceptionCatchFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Throw RuntimeException for rollback";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod method = this.method(descriptor);
            if (method != null && method.getBody() != null) {
                method.getBody().addBefore(JavaPsiFacade.getElementFactory(project).createStatementFromText("throw new RuntimeException();", method), method.getBody().getRBrace());
            }
        }
    }

    private static final class AddRethrowToCatchBlocksFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Rethrow caught exception";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            addToCatchBlocks(project, this.method(descriptor), "throw ex;");
        }
    }

    private static final class AddRollbackOnlyToCatchBlocksFix extends MethodTransactionalAnnotationFix {
        @Override
        @NotNull
        public String getName() {
            return "Mark transaction rollback-only";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            addToCatchBlocks(project, this.method(descriptor), "org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();");
        }
    }

    private static final class AddTransactionalFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Add @Transactional annotation";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod method = descriptor.getPsiElement() instanceof PsiMethod m ? m
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class);
            if (method != null) {
                method.getModifierList().addAnnotation("org.springframework.transaction.annotation.Transactional");
            }
        }
    }
}
