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
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.sarhan.lombok.LombokAnnotationUtil;
import com.sarhan.render.flow.bag.ShowBagProblemFlowFix;
import org.jetbrains.annotations.NotNull;

/**
 * Detects {@code @OneToMany} and {@code @ManyToMany} fields typed as {@code List}
 * instead of {@code Set}. Hibernate "bag" semantics cause delete-all + re-insert
 * on every change, leading to severe performance issues and lost ordering.
 *
 * @author Akmal Sarhan
 */
public class SpringBagProblemInspection extends AbstractBaseJavaLocalInspectionTool {

    private static final String[] MANY_ANNOTATIONS = {
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToMany",
            "javax.persistence.OneToMany",
            "javax.persistence.ManyToMany",
            "OneToMany",
            "ManyToMany"
    };

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                PsiAnnotation annotation = findManyAnnotation(field);
                if (annotation == null) {
                    return;
                }
                PsiType type = field.getType();
                String typeText = type.getCanonicalText();
                if (!isList(typeText) || isInverseSide(annotation) || hasOrderColumn(field)) {
                    return;
                }
                String annotationName = annotation.getQualifiedName();
                if (annotationName == null) {
                    annotationName = (annotation.getNameReferenceElement() != null)
                            ? annotation.getNameReferenceElement().getText()
                            : "ManyToOne/ManyToMany";
                }
                int lastDot = annotationName.lastIndexOf('.');
                String shortName = (lastDot >= 0) ? annotationName.substring(lastDot + 1) : annotationName;
                PsiTypeElement typeElement = field.getTypeElement();
                holder.registerProblem((typeElement != null) ? typeElement : field,
                        "@" + shortName + " with List causes 'bag' semantics; use Set instead to avoid delete-all + re-insert performance issues",
                        ProblemHighlightType.WARNING,
                        new ReplaceListWithSetFix(),
                        new ShowBagProblemFlowFix());
            }
        };
    }

    private static PsiAnnotation findManyAnnotation(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) {
            return null;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn == null && annotation.getNameReferenceElement() != null) {
                fqn = annotation.getNameReferenceElement().getText();
            }
            for (String many : MANY_ANNOTATIONS) {
                if (many.equals(fqn)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    /** Hibernate never recreates an inverse ({@code mappedBy}) collection; the owning side writes the rows. */
    private static boolean isInverseSide(PsiAnnotation annotation) {
        PsiAnnotationMemberValue mappedBy = annotation.findDeclaredAttributeValue("mappedBy");
        if (mappedBy == null) {
            return false;
        }
        if (mappedBy instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String value && !value.isEmpty();
        }
        return true;
    }

    /** {@code @OrderColumn} maps the List as an indexed list, not a bag. */
    private static boolean hasOrderColumn(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn == null && annotation.getNameReferenceElement() != null) {
                fqn = annotation.getNameReferenceElement().getText();
            }
            if ("jakarta.persistence.OrderColumn".equals(fqn)
                    || "javax.persistence.OrderColumn".equals(fqn)
                    || "OrderColumn".equals(fqn)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isList(String typeText) {
        if (typeText == null) {
            return false;
        }
        return typeText.equals("java.util.List")
                || typeText.startsWith("java.util.List<")
                || typeText.equals("List")
                || typeText.startsWith("List<");
    }

    private static final class ReplaceListWithSetFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Replace List with Set";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiField field = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                    descriptor.getPsiElement(), PsiField.class);
            if (field == null) {
                return;
            }
            PsiTypeElement typeElement = field.getTypeElement();
            if (typeElement == null) {
                return;
            }
            String currentType = typeElement.getText();
            String newType = currentType.replaceFirst("List", "Set")
                    .replaceFirst("java\\.util\\.List", "java.util.Set");
            PsiTypeElement newTypeElement = JavaPsiFacade.getElementFactory(project)
                    .createTypeElementFromText(newType, field);
            typeElement.replace(newTypeElement);
            PsiClass containingClass = field.getContainingClass();
            if (containingClass != null && containingClass.getContainingFile() instanceof PsiJavaFile) {
                LombokAnnotationUtil.ensureImport(project, containingClass, "java.util.Set");
            }
        }
    }
}
