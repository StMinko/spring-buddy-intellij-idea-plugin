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
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.sarhan.springformat.settings.SpringBootTarget;
import com.sarhan.springformat.settings.SpringBootVersionResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Multi-purpose inspection that detects several Spring runtime pitfalls:
 * <ul>
 *   <li>@SpringBootTest broader than needed (consider focused test slices)</li>
 *   <li>@DirtiesContext invalidating test context caching</li>
 *   <li>@MockBean causing extra context loads</li>
 *   <li>@ConfigurationProperties missing @Validated or not registered for binding</li>
 *   <li>@Scheduled methods that are private/static/final (proxy failure)</li>
 *   <li>@Scheduled methods holding transactions during long-running calls</li>
 *   <li>@Scheduled methods without distributed locks in clustered deployments</li>
 *   <li>Service methods using multiple repositories without @Transactional</li>
 *   <li>Service methods swallowing exceptions without rethrowing</li>
 * </ul>
 *
 * @author Akmal Sarhan
 */
public class SpringRemainingPitfallInspection extends AbstractBaseJavaLocalInspectionTool {

    private final Kind kind;

    protected SpringRemainingPitfallInspection(Kind kind) {
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
                if (!SpringInspectionUtil.isInJavaFile(psiClass)) {
                    return;
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.SPRING_BOOT_TEST_SLICE && hasAnnotation(psiClass.getModifierList(), "SpringBootTest")
                        && hasSliceCandidateSignals(psiClass)) {
                    register(psiClass, holder, "@SpringBootTest may be broader than needed; consider a focused test slice");
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.DIRTY_CONTEXT && hasAnnotation(psiClass.getModifierList(), "DirtiesContext")) {
                    register(psiClass, holder, "@DirtiesContext invalidates Spring test context caching and slows test suites");
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.MOCK_BEAN_CONTEXT_CACHE) {
                    reportMockBeans(psiClass, holder);
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.CONFIGURATION_PROPERTIES_MISSING_VALIDATED && hasAnnotation(psiClass.getModifierList(), "ConfigurationProperties")
                        && hasConstraintFields(psiClass) && !hasAnnotation(psiClass.getModifierList(), "Validated")) {
                    register(psiClass, holder,
                            "@ConfigurationProperties uses constraints but is missing @Validated",
                            new AddValidatedFix());
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.CONFIGURATION_PROPERTIES_NOT_REGISTERED && hasAnnotation(psiClass.getModifierList(), "ConfigurationProperties")
                        && !isConfigurationPropertiesRegistered(psiClass)) {
                    register(psiClass, holder,
                            "@ConfigurationProperties class may not be registered for binding");
                }
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (!SpringInspectionUtil.isInJavaFile(method)) {
                    return;
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.SERVICE_MULTIPLE_REPOSITORIES_WITHOUT_TRANSACTION
                        && isServiceMethod(method)
                        && (method.hasModifierProperty(PsiModifier.PRIVATE)
                        ? !hasTransactionalCallerCoverage(method)
                        : !hasTransactionalBoundary(method))
                        && countDistinctRepositoryWriteFields(method) >= 2) {
                    String message = "Service method uses multiple repositories without an explicit @Transactional boundary";
                    if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
                        registerError(method, holder, message);
                    } else {
                        registerError(method, holder, message, new AddTransactionalFix());
                    }
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.SCHEDULED_PROXY && hasAnnotation(method.getModifierList(), "Scheduled")
                        && (method.hasModifierProperty(PsiModifier.PRIVATE) || method.hasModifierProperty(PsiModifier.STATIC)
                        || method.hasModifierProperty(PsiModifier.FINAL))) {
                    registerError(method, holder,
                            "@Scheduled method should not be private, static, or final");
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.SCHEDULED_TRANSACTIONAL_LONG_RUNNING && hasAnnotation(method.getModifierList(), "Scheduled")
                        && hasAnnotation(method.getModifierList(), "Transactional") && hasLongRunningCall(method)) {
                    registerError(method, holder,
                            "Long-running @Scheduled method should not hold a transaction while waiting on external work");
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.SCHEDULED_WITHOUT_LOCK && hasAnnotation(method.getModifierList(), "Scheduled")
                        && !hasAnnotation(method.getModifierList(), "SchedulerLock")) {
                    registerWarning(method, holder,
                            "@Scheduled method may run on every cluster node; consider a distributed lock such as @SchedulerLock");
                }
                if (SpringRemainingPitfallInspection.this.kind == Kind.SERVICE_SWALLOWED_EXCEPTION && isServiceMethod(method)) {
                    reportSwallowedExceptions(method, holder);
                }
            }
        };
    }

    private static boolean hasSliceCandidateSignals(PsiClass psiClass) {
        for (PsiField field : psiClass.getFields()) {
            PsiModifierList modifiers = field.getModifierList();
            if (hasMockInjection(modifiers) || hasAnnotation(modifiers, "Autowired")) {
                PsiType type = field.getType();
                String canonical = type.getCanonicalText();
                if (canonical.contains("MockMvc") || canonical.contains("WebTestClient")) {
                    return true;
                }
                PsiClass resolved = SpringInspectionUtil.resolvedClass(type);
                if (resolved != null && (SpringInspectionUtil.isSpringDataRepository(resolved)
                        || isEntityManagerType(canonical))) {
                    return true;
                }
            }
            if (hasMockInjection(modifiers)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMockInjection(PsiModifierList modifiers) {
        return hasAnnotation(modifiers, "MockBean")
                || hasAnnotation(modifiers, "SpyBean")
                || hasAnnotation(modifiers, "MockitoBean")
                || hasAnnotation(modifiers, "MockitoSpyBean");
    }

    private static boolean isEntityManagerType(String canonical) {
        return "jakarta.persistence.EntityManager".equals(canonical)
                || "javax.persistence.EntityManager".equals(canonical);
    }

    private static void reportMockBeans(PsiClass psiClass, ProblemsHolder holder) {
        if (!isTestClass(psiClass)) {
            return;
        }
        // On a Boot 4 target the removed @MockBean/@SpyBean are reported by the
        // Spring Boot 4 migration inspection; avoid double reports here.
        boolean boot4 = SpringBootVersionResolver.isAtLeast(psiClass, SpringBootTarget.BOOT_4);
        for (PsiField field : psiClass.getFields()) {
            PsiAnnotation mockBean = findAnnotation(field.getModifierList(), "MockBean");
            if (mockBean == null) {
                mockBean = findAnnotation(field.getModifierList(), "SpyBean");
            }
            if (mockBean != null && !boot4) {
                holder.registerProblem(mockBean,
                        "@MockBean changes the Spring test context cache key; centralize common mocks to avoid extra context loads",
                        ProblemHighlightType.WARNING);
            }
            PsiAnnotation mockitoBean = findAnnotation(field.getModifierList(), "MockitoBean");
            if (mockitoBean == null) {
                mockitoBean = findAnnotation(field.getModifierList(), "MockitoSpyBean");
            }
            if (mockitoBean != null) {
                holder.registerProblem(mockitoBean,
                        "@MockitoBean changes the Spring test context cache key; centralize common mocks to avoid extra context loads",
                        ProblemHighlightType.WARNING);
            }
        }
    }

    private static boolean isTestClass(PsiClass psiClass) {
        return hasAnnotation(psiClass.getModifierList(), "SpringBootTest")
                || hasAnnotation(psiClass.getModifierList(), "WebMvcTest")
                || hasAnnotation(psiClass.getModifierList(), "DataJpaTest")
                || hasAnnotation(psiClass.getModifierList(), "ContextConfiguration");
    }

    private static boolean isConfigurationPropertiesRegistered(PsiClass psiClass) {
        PsiModifierList modifiers = psiClass.getModifierList();
        String fileText = psiClass.getContainingFile().getText();
        return hasAnnotation(modifiers, "Component") || hasAnnotation(modifiers, "Configuration")
                || fileText.contains("@ConfigurationPropertiesScan") || fileText.contains("@EnableConfigurationProperties");
    }

    private static boolean hasConstraintFields(PsiClass psiClass) {
        for (PsiField field : psiClass.getFields()) {
            if (hasConstraint(field.getModifierList())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isServiceMethod(PsiMethod method) {
        PsiClass psiClass = method.getContainingClass();
        if (psiClass == null) {
            return false;
        }
        PsiModifierList modifiers = psiClass.getModifierList();
        if (modifiers == null) {
            return false;
        }
        return SpringInspectionUtil.hasAnnotation(modifiers,
                "org.springframework.stereotype.Service", "Service");
    }

    private static boolean hasTransactionalBoundary(PsiMethod method) {
        if (hasAnnotation(method.getModifierList(), "Transactional")) {
            return true;
        }
        PsiClass containingClass = method.getContainingClass();
        return containingClass != null && hasAnnotation(containingClass.getModifierList(), "Transactional");
    }

    private static int countDistinctRepositoryWriteFields(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return 0;
        }
        java.util.Set<String> repositories = new java.util.HashSet<>();
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (isInsideTransactionTemplateCallback(expression)) {
                    return;
                }
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier == null) {
                    return;
                }
                PsiType qualifierType = qualifier.getType();
                PsiClass qualifierClass = (qualifierType != null) ? SpringInspectionUtil.resolvedClass(qualifierType) : null;
                if (qualifierClass != null && SpringInspectionUtil.isSpringDataRepository(qualifierClass)) {
                    String methodName = expression.getMethodExpression().getReferenceName();
                    if (methodName != null && isWriteOperation(methodName)) {
                        repositories.add(qualifier.getText());
                    }
                }
            }
        });
        return repositories.size();
    }

    public static boolean hasTransactionalCallerCoverage(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        return containingClass != null
                && hasTransactionalCallerCoverage(method, containingClass, new java.util.HashSet<>());
    }

    private static boolean hasTransactionalCallerCoverage(PsiMethod method, PsiClass scope, java.util.Set<PsiMethod> visited) {
        if (!visited.add(method)) {
            return true;
        }
        for (PsiReference reference : ReferencesSearch.search(method, new LocalSearchScope(scope)).findAll()) {
            if (isInsideTransactionTemplateCallback(reference.getElement())) {
                continue;
            }
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(reference.getElement(), PsiMethodCallExpression.class);
            if (call == null || call.resolveMethod() != method) {
                return false;
            }
            PsiMethod caller = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
            if (caller == null) {
                return false;
            }
            if (!caller.hasModifierProperty(PsiModifier.PRIVATE) && hasTransactionalBoundary(caller)) {
                continue;
            }
            if (!caller.hasModifierProperty(PsiModifier.PRIVATE)
                    || !hasTransactionalCallerCoverage(caller, scope, visited)) {
                return false;
            }
        }
        return true;
    }

    static boolean isInsideTransactionTemplateCallback(PsiElement element) {
        for (PsiElement current = element; current != null; current = current.getParent()) {
            if (current instanceof PsiLambdaExpression || current instanceof PsiAnonymousClass) {
                PsiElement argument = current instanceof PsiAnonymousClass ? current.getParent() : current;
                while (argument != null && argument.getParent() instanceof PsiParenthesizedExpression) {
                    argument = argument.getParent();
                }
                if (argument != null && argument.getParent() instanceof PsiExpressionList arguments
                        && arguments.getParent() instanceof PsiMethodCallExpression call
                        && isTransactionTemplateCall(call)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isTransactionTemplateCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (!"execute".equals(methodName) && !"executeWithoutResult".equals(methodName)) {
            return false;
        }
        PsiMethod target = call.resolveMethod();
        PsiClass containingClass = target != null ? target.getContainingClass() : null;
        String qualifiedName = containingClass != null ? containingClass.getQualifiedName() : null;
        return "org.springframework.transaction.support.TransactionTemplate".equals(qualifiedName)
                || "org.springframework.transaction.support.TransactionOperations".equals(qualifiedName);
    }

    static boolean isWriteOperation(String methodName) {
        return methodName.startsWith("save") || methodName.equals("delete") || methodName.equals("remove")
                || methodName.startsWith("delete") || methodName.startsWith("remove")
                || methodName.equals("persist") || methodName.equals("merge") || methodName.equals("flush");
    }

    private static boolean hasLongRunningCall(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                String text = expression.getText().toLowerCase(java.util.Locale.ROOT);
                if (text.contains("resttemplate") || text.contains("webclient") || text.contains("sleep(")
                        || text.contains("client.")) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static void reportSwallowedExceptions(PsiMethod method, ProblemsHolder holder) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return;
        }
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitTryStatement(@NotNull PsiTryStatement statement) {
                super.visitTryStatement(statement);
                for (PsiCatchSection section : statement.getCatchSections()) {
                    if (section.getCatchType() != null && isBroadExceptionType(section.getCatchType().getCanonicalText())
                            && !rethrows(section.getCatchBlock()) && !logsFailure(section.getCatchBlock())) {
                        PsiTypeElement typeElem = (section.getParameter() != null)
                                ? section.getParameter().getTypeElement()
                                : null;
                        PsiElement target = (typeElem != null) ? typeElem : section;
                        holder.registerProblem(target,
                                "Service method catches a broad exception without rethrowing it",
                                ProblemHighlightType.ERROR);
                    }
                }
            }
        });
    }

    private static boolean logsFailure(PsiCodeBlock block) {
        if (block == null) {
            return false;
        }
        boolean[] found = {false};
        block.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                String methodName = expression.getMethodExpression().getReferenceName();
                if (qualifier != null && isLoggerQualifier(qualifier) && isLogMethod(methodName)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean isLoggerQualifier(PsiExpression qualifier) {
        String text = qualifier.getText();
        if ("log".equalsIgnoreCase(text) || "logger".equalsIgnoreCase(text)) {
            return true;
        }
        PsiType type = qualifier.getType();
        PsiClass resolved = type != null ? SpringInspectionUtil.resolvedClass(type) : null;
        String name = resolved != null ? resolved.getName() : null;
        return "Logger".equals(name) || "Log".equals(name);
    }

    private static boolean isLogMethod(String methodName) {
        return "info".equals(methodName) || "warn".equals(methodName) || "warning".equals(methodName)
                || "error".equals(methodName) || "severe".equals(methodName) || "fatal".equals(methodName)
                || "log".equals(methodName);
    }

    private static boolean rethrows(PsiCodeBlock block) {
        if (block == null) {
            return false;
        }
        boolean[] found = {false};
        block.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitThrowStatement(@NotNull PsiThrowStatement statement) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static boolean isBroadExceptionType(String canonicalText) {
        return "Exception".equals(canonicalText)
                || "java.lang.Exception".equals(canonicalText)
                || "RuntimeException".equals(canonicalText)
                || "java.lang.RuntimeException".equals(canonicalText)
                || "Throwable".equals(canonicalText)
                || "java.lang.Throwable".equals(canonicalText);
    }

    private static boolean hasConstraint(PsiModifierList modifiers) {
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name == null && annotation.getNameReferenceElement() != null) {
                name = annotation.getNameReferenceElement().getText();
            }
            if (name != null && (name.startsWith("javax.validation.constraints.")
                    || name.startsWith("jakarta.validation.constraints."))) {
                return true;
            }
            PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
            if (ref != null && ref.resolve() instanceof PsiClass resolved) {
                String resolvedName = resolved.getQualifiedName();
                if (resolvedName != null && (resolvedName.startsWith("javax.validation.constraints.")
                        || resolvedName.startsWith("jakarta.validation.constraints."))) {
                    return true;
                }
            }
            if (isLocalConstraintStub(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalConstraintStub(String name) {
        return name != null && !name.contains(".")
                && (name.equals("NotNull") || name.equals("NotEmpty") || name.equals("NotBlank")
                || name.equals("Size") || name.equals("Min") || name.equals("Max")
                || name.equals("Pattern") || name.equals("Email") || name.equals("DecimalMin")
                || name.equals("DecimalMax") || name.equals("AssertTrue") || name.equals("AssertFalse")
                || name.equals("Future") || name.equals("Past") || name.equals("Positive")
                || name.equals("Negative") || name.equals("PositiveOrZero") || name.equals("NegativeOrZero"));
    }

    private static boolean hasAnnotation(PsiModifierList modifiers, String shortName) {
        return findAnnotation(modifiers, shortName) != null;
    }

    private static PsiAnnotation findAnnotation(PsiModifierList modifiers, String shortName) {
        if (modifiers == null) {
            return null;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name == null && annotation.getNameReferenceElement() != null) {
                name = annotation.getNameReferenceElement().getText();
            }
            if (shortName.equals(name) || (name != null && name.endsWith("." + shortName))) {
                return annotation;
            }
        }
        return null;
    }

    private static void register(PsiClass psiClass, ProblemsHolder holder, String message) {
        PsiIdentifier id = psiClass.getNameIdentifier();
        holder.registerProblem((id != null) ? id : psiClass, message, ProblemHighlightType.WARNING);
    }

    private static void register(PsiClass psiClass, ProblemsHolder holder, String message, LocalQuickFix fix) {
        PsiIdentifier id = psiClass.getNameIdentifier();
        holder.registerProblem((id != null) ? id : psiClass, message, ProblemHighlightType.WARNING, fix);
    }

    private static void registerError(PsiMethod method, ProblemsHolder holder, String message) {
        PsiIdentifier id = method.getNameIdentifier();
        holder.registerProblem((id != null) ? id : method, message, ProblemHighlightType.ERROR);
    }

    private static void registerError(PsiMethod method, ProblemsHolder holder, String message, LocalQuickFix fix) {
        PsiIdentifier id = method.getNameIdentifier();
        holder.registerProblem((id != null) ? id : method, message, ProblemHighlightType.ERROR, fix);
    }

    private static void registerWarning(PsiMethod method, ProblemsHolder holder, String message) {
        PsiIdentifier id = method.getNameIdentifier();
        holder.registerProblem((id != null) ? id : method, message, ProblemHighlightType.WARNING);
    }

    private enum Kind {
        SERVICE_MULTIPLE_REPOSITORIES_WITHOUT_TRANSACTION,
        SPRING_BOOT_TEST_SLICE,
        DIRTY_CONTEXT,
        MOCK_BEAN_CONTEXT_CACHE,
        CONFIGURATION_PROPERTIES_MISSING_VALIDATED,
        CONFIGURATION_PROPERTIES_NOT_REGISTERED,
        SCHEDULED_PROXY,
        SCHEDULED_TRANSACTIONAL_LONG_RUNNING,
        SCHEDULED_WITHOUT_LOCK,
        SERVICE_SWALLOWED_EXCEPTION
    }

    public static class ServiceMultipleRepositoriesWithoutTransaction extends SpringRemainingPitfallInspection {
        public ServiceMultipleRepositoriesWithoutTransaction() {
            super(Kind.SERVICE_MULTIPLE_REPOSITORIES_WITHOUT_TRANSACTION);
        }
    }

    public static class SpringBootTestSlice extends SpringRemainingPitfallInspection {
        public SpringBootTestSlice() {
            super(Kind.SPRING_BOOT_TEST_SLICE);
        }
    }

    public static class DirtyContext extends SpringRemainingPitfallInspection {
        public DirtyContext() {
            super(Kind.DIRTY_CONTEXT);
        }
    }

    public static class MockBeanContextCache extends SpringRemainingPitfallInspection {
        public MockBeanContextCache() {
            super(Kind.MOCK_BEAN_CONTEXT_CACHE);
        }
    }

    public static class ConfigurationPropertiesMissingValidated extends SpringRemainingPitfallInspection {
        public ConfigurationPropertiesMissingValidated() {
            super(Kind.CONFIGURATION_PROPERTIES_MISSING_VALIDATED);
        }
    }

    public static class ConfigurationPropertiesNotRegistered extends SpringRemainingPitfallInspection {
        public ConfigurationPropertiesNotRegistered() {
            super(Kind.CONFIGURATION_PROPERTIES_NOT_REGISTERED);
        }
    }

    public static class ScheduledProxy extends SpringRemainingPitfallInspection {
        public ScheduledProxy() {
            super(Kind.SCHEDULED_PROXY);
        }
    }

    public static class ScheduledTransactionalLongRunning extends SpringRemainingPitfallInspection {
        public ScheduledTransactionalLongRunning() {
            super(Kind.SCHEDULED_TRANSACTIONAL_LONG_RUNNING);
        }
    }

    public static class ScheduledWithoutLock extends SpringRemainingPitfallInspection {
        public ScheduledWithoutLock() {
            super(Kind.SCHEDULED_WITHOUT_LOCK);
        }
    }

    public static class ServiceSwallowedException extends SpringRemainingPitfallInspection {
        public ServiceSwallowedException() {
            super(Kind.SERVICE_SWALLOWED_EXCEPTION);
        }
    }

    private static final class AddValidatedFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Add @Validated annotation";
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
                psiClass.getModifierList().addAnnotation("org.springframework.validation.annotation.Validated");
            }
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
