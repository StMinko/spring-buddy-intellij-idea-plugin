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

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

/**
 * SpringJpaModifyingWithoutClearAutomatically: flags Spring Data JPA {@code @Modifying} annotations
 * that don't set {@code clearAutomatically = true}. Without it, the persistence context can hold
 * stale entities after the bulk update/delete runs. Quick-fix sets {@code clearAutomatically = true}.
 *
 * @author Akmal Sarhan
 */
public class SpringJpaModifyingWithoutClearAutomaticallyInspection extends AbstractBaseJavaLocalInspectionTool {

    private static final Pattern BULK_QUERY_ENTITY_PATTERN = Pattern.compile(
            "^\\s*(update|delete\\s+from|delete)\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {

        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                String fqn = annotation.getQualifiedName();
                if (fqn == null) {
                    return;
                }
                if (!"org.springframework.data.jpa.repository.Modifying".equals(fqn)
                        && !"Modifying".equals(fqn)) {
                    return;
                }
                PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("clearAutomatically");
                if (value instanceof PsiLiteralExpression literal && Boolean.TRUE.equals(literal.getValue())) {
                    return;
                }
                PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                if (method != null && !hasStaleEntityRead(method)) {
                    return;
                }
                holder.registerProblem(annotation,
                        "@Modifying without clearAutomatically=true may leave stale entities in the persistence context",
                        ProblemHighlightType.ERROR,
                        new AddClearAutomaticallyFix());
            }
        };
    }

    private static boolean hasStaleEntityRead(PsiMethod repositoryMethod) {
        String entityName = findTargetEntityName(repositoryMethod);
        if (entityName == null) {
            return true;
        }
        Collection<PsiReference> references = ReferencesSearch.search(repositoryMethod).findAll();
        if (references.isEmpty()) {
            return true;
        }
        for (PsiReference reference : references) {
            PsiElement referenceElement = reference.getElement();
            if (!(referenceElement instanceof PsiReferenceExpression)) {
                continue;
            }
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(referenceElement, PsiMethodCallExpression.class);
            if (call == null || call.getMethodExpression() != referenceElement) {
                continue;
            }
            PsiMethod caller = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
            PsiCodeBlock body = caller != null ? caller.getBody() : null;
            if (body == null) {
                continue;
            }
            PsiElement callback = findTransactionTemplateCallback(call);
            PsiElement scope = callback != null ? callback : body;
            boolean loadedBefore = hasEntityParameter(caller, entityName)
                    || hasEntityExpression(scope, call, entityName, true);
            if (loadedBefore && hasEntityExpression(scope, call, entityName, false)) {
                return true;
            }
        }
        return false;
    }

    private static String findTargetEntityName(PsiMethod repositoryMethod) {
        PsiAnnotation query = SpringInspectionUtil.findAnnotation(repositoryMethod.getModifierList(),
                "org.springframework.data.jpa.repository.Query", "Query");
        if (query != null) {
            PsiAnnotationMemberValue nativeQuery = query.findDeclaredAttributeValue("nativeQuery");
            boolean isNativeQuery = nativeQuery instanceof PsiLiteralExpression literal
                    && Boolean.TRUE.equals(literal.getValue());
            PsiAnnotationMemberValue queryValue = query.findDeclaredAttributeValue("value");
            if (!isNativeQuery && queryValue instanceof PsiLiteralExpression literal
                    && literal.getValue() instanceof String queryText) {
                Matcher matcher = BULK_QUERY_ENTITY_PATTERN.matcher(queryText);
                if (matcher.find()) {
                    return simpleName(matcher.group(2));
                }
            }
        }
        PsiClass repositoryClass = repositoryMethod.getContainingClass();
        if (repositoryClass == null) {
            return null;
        }
        for (PsiClassType extendedType : repositoryClass.getExtendsListTypes()) {
            PsiType[] typeParameters = extendedType.getParameters();
            if (typeParameters.length > 0 && typeParameters[0] instanceof PsiClassType entityType) {
                String className = entityType.getClassName();
                if (className != null) {
                    return simpleName(className);
                }
            }
        }
        return null;
    }

    private static String simpleName(String typeName) {
        int separator = typeName.lastIndexOf('.');
        return separator >= 0 ? typeName.substring(separator + 1) : typeName;
    }

    private static boolean hasEntityParameter(PsiMethod method, String entityName) {
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (hasEntityType(parameter.getType(), entityName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEntityExpression(PsiElement scope, PsiMethodCallExpression call,
            String entityName, boolean beforeCall) {
        boolean[] found = {false};
        int callStart = call.getTextRange().getStartOffset();
        int callEnd = call.getTextRange().getEndOffset();
        scope.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (found[0]) {
                    return;
                }
                if (element instanceof PsiExpression expression) {
                    boolean inPosition = beforeCall
                            ? expression.getTextRange().getEndOffset() <= callStart
                            : expression.getTextRange().getStartOffset() >= callEnd
                                    && (expression instanceof PsiReferenceExpression
                                    || expression instanceof PsiMethodCallExpression);
                    if (inPosition && hasEntityType(expression.getType(), entityName)) {
                        found[0] = true;
                        return;
                    }
                }
                super.visitElement(element);
            }
        });
        return found[0];
    }

    private static boolean hasEntityType(PsiType type, String entityName) {
        if (!(type instanceof PsiClassType classType)) {
            return false;
        }
        String className = classType.getClassName();
        if (className != null && entityName.equals(simpleName(className))) {
            return true;
        }
        for (PsiType typeParameter : classType.getParameters()) {
            if (hasEntityType(typeParameter, entityName)) {
                return true;
            }
        }
        return false;
    }

    private static PsiElement findTransactionTemplateCallback(PsiMethodCallExpression call) {
        for (PsiElement current = call; current != null; current = current.getParent()) {
            if (current instanceof PsiLambdaExpression || current instanceof PsiAnonymousClass) {
                PsiElement argument = current instanceof PsiAnonymousClass ? current.getParent() : current;
                while (argument != null && argument.getParent() instanceof PsiParenthesizedExpression) {
                    argument = argument.getParent();
                }
                if (argument != null && argument.getParent() instanceof PsiExpressionList arguments
                        && arguments.getParent() instanceof PsiMethodCallExpression templateCall
                        && SpringRemainingPitfallInspection.isTransactionTemplateCall(templateCall)) {
                    return current;
                }
            }
        }
        return null;
    }

    private static final class AddClearAutomaticallyFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Add clearAutomatically = true";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (!(element instanceof PsiAnnotation annotation)) {
                return;
            }
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiAnnotation replacement = factory.createAnnotationFromText(
                    "@" + annotation.getQualifiedName() + "(clearAutomatically = true)", annotation);
            // Preserve any other attributes the user may have set.
            for (com.intellij.psi.PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                if (!"clearAutomatically".equals(pair.getAttributeName()) && pair.getValue() != null) {
                    replacement.setDeclaredAttributeValue(pair.getAttributeName(), pair.getValue());
                }
            }
            replacement.setDeclaredAttributeValue("clearAutomatically",
                    factory.createExpressionFromText("true", annotation));
            annotation.replace(replacement);
        }
    }
}
