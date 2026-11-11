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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import org.jetbrains.annotations.NotNull;

/**
 * Detects classes that can be declared {@code final} because they have only
 * private constructors (or are private with an implicit constructor) and no nested or
 * anonymous subclasses, preventing accidental extension.
 *
 * @author Akmal Sarhan
 */
public class SpringFinalClassInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {

        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass psiClass) {
                if (!shouldBeFinal(psiClass)) {
                    return;
                }
                holder.registerProblem((psiClass.getNameIdentifier() != null) ? psiClass.getNameIdentifier() : psiClass,
                        "Class can be declared final",
                        ProblemHighlightType.WARNING,
                        new MakeClassFinalFix());
            }
        };
    }

    private static boolean shouldBeFinal(PsiClass psiClass) {
        if (!isCandidateKind(psiClass)) {
            return false;
        }
        if (psiClass.hasModifierProperty(PsiModifier.FINAL) || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
            return false;
        }
        if (hasNestedSubclass(psiClass) || isSuperClassOfAnonymousClass(psiClass)) {
            return false;
        }
        PsiMethod[] constructors = Arrays.stream(psiClass.getConstructors())
                .filter(PsiMethod::isPhysical)
                .toArray(PsiMethod[]::new);
        if (constructors.length == 0) {
            return psiClass.hasModifierProperty(PsiModifier.PRIVATE);
        }
        // Mirrors Checkstyle FinalClass: only private constructors qualify. A protected
        // constructor explicitly invites subclassing (possibly from other files/modules).
        for (PsiMethod constructor : constructors) {
            if (!constructor.hasModifierProperty(PsiModifier.PRIVATE)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCandidateKind(PsiClass psiClass) {
        if (psiClass instanceof PsiAnonymousClass) {
            return false;
        }
        if (psiClass.isInterface() || psiClass.isEnum()) {
            return false;
        }
        return !psiClass.isAnnotationType() && !psiClass.isRecord();
    }

    private static boolean hasNestedSubclass(PsiClass psiClass) {
        Set<String> names = classNames(psiClass);
        for (PsiClass innerClass : psiClass.getAllInnerClasses()) {
            PsiClass superClass = innerClass.getSuperClass();
            if ((superClass != null) && names.contains(superClass.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuperClassOfAnonymousClass(PsiClass psiClass) {
        Set<String> names = classNames(psiClass);
        final boolean[] found = {false};
        psiClass.accept(new JavaElementVisitor() {
            @Override
            public void visitAnonymousClass(@NotNull PsiAnonymousClass anonymousClass) {
                PsiClass superClass = anonymousClass.getSuperClass();
                if ((superClass != null) && names.contains(superClass.getName())) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static Set<String> classNames(PsiClass psiClass) {
        Set<String> result = new HashSet<>();
        String name = psiClass.getName();
        if (name != null) {
            result.add(name);
        }
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName != null) {
            result.add(qualifiedName);
        }
        return result;
    }

    private static final class MakeClassFinalFix implements LocalQuickFix {

        @Override
        @NotNull
        public String getName() {
            return "Make class final";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!(descriptor.getPsiElement().getParent() instanceof PsiClass psiClass)) {
                return;
            }
            if (psiClass.hasModifierProperty(PsiModifier.FINAL)) {
                return;
            }
            PsiModifierList modifierList = psiClass.getModifierList();
            if (modifierList == null) {
                return;
            }
            modifierList.add(JavaPsiFacade.getElementFactory(project).createKeyword(PsiModifier.FINAL));
        }
    }
}
