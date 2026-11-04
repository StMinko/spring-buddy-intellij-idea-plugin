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

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;

/**
 * SpringJpaCascadeAllOnManyToOne: flags {@code cascade = CascadeType.ALL} (or a list including ALL)
 * on {@code @ManyToOne} or {@code @ManyToMany} associations. This is a JPA anti-pattern: the owning
 * side of a many-to-one association should not cascade lifecycle operations to the parent.
 *
 * @author Akmal Sarhan
 */
public class SpringJpaCascadeAllOnManyToOneInspection extends AbstractBaseJavaLocalInspectionTool {

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
                if (!isManyAssociation(fqn)) {
                    return;
                }
                PsiAnnotationMemberValue cascade = annotation.findAttributeValue("cascade");
                if (cascade == null) {
                    return;
                }
                if (referencesCascadeAll(cascade) && !isExclusiveManyToOne(annotation, fqn)) {
                    holder.registerProblem(cascade,
                            "cascade = ALL on @ManyToOne/@ManyToMany is an anti-pattern; cascade only what the owning side controls",
                            ProblemHighlightType.ERROR);
                }
            }
        };
    }

    private static boolean isManyAssociation(String fqn) {
        return "javax.persistence.ManyToOne".equals(fqn)
                || "jakarta.persistence.ManyToOne".equals(fqn)
                || "javax.persistence.ManyToMany".equals(fqn)
                || "jakarta.persistence.ManyToMany".equals(fqn)
                || "ManyToOne".equals(fqn)
                || "ManyToMany".equals(fqn);
    }

    /**
     * A {@code @ManyToOne} whose join column is {@code unique = true} is a one-to-one in disguise:
     * the target row belongs to exactly one owner, so cascading ALL cannot reach a shared parent.
     */
    private static boolean isExclusiveManyToOne(PsiAnnotation annotation, String fqn) {
        if (!SpringInspectionUtil.shortName(fqn).equals("ManyToOne")) {
            return false;
        }
        if (!(annotation.getOwner() instanceof PsiModifierList modifiers)) {
            return false;
        }
        PsiAnnotation joinColumn = SpringInspectionUtil.findAnyAnnotation(modifiers,
                "jakarta.persistence.JoinColumn", "javax.persistence.JoinColumn");
        return (joinColumn != null) && Boolean.TRUE.equals(AnnotationUtil.getBooleanAttributeValue(joinColumn, "unique"));
    }

    private static boolean referencesCascadeAll(PsiAnnotationMemberValue value) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue child : array.getInitializers()) {
                if (referencesCascadeAll(child)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            return "ALL".equals(name);
        }
        return false;
    }
}
