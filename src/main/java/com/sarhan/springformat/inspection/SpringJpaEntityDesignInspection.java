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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;

/**
 * Base inspection that detects common JPA entity design flaws.
 * Covers Lombok-generated methods on entities, missing protected no-arg constructors,
 * eager to-one associations, ordinal enum mappings, missing auditing listeners,
 * and unmapped one-to-many relationships that create implicit join tables.
 *
 * @author Akmal Sarhan
 */
public abstract class SpringJpaEntityDesignInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {

        if (!SpringInspectionUtil.isInJavaFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass psiClass) {
                if (!isJpaEntityType(psiClass)) {
                    return;
                }
                inspectClass(psiClass, holder);
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                inspectField(field, holder);
            }
        };
    }

    protected void inspectClass(PsiClass psiClass, ProblemsHolder holder) {
    }

    protected void inspectField(PsiField field, ProblemsHolder holder) {
    }

    private static void reportLombokEntityAnnotations(PsiClass psiClass, ProblemsHolder holder) {
        PsiModifierList modifiers = psiClass.getModifierList();
        if (modifiers == null) {
            return;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (isAnnotation(annotation, "Data") || isAnnotation(annotation, "ToString")
                    || isAnnotation(annotation, "EqualsAndHashCode")) {
                holder.registerProblem(annotation,
                        "Avoid broad Lombok-generated methods on JPA entities; they can trigger lazy loading or break equality semantics",
                        ProblemHighlightType.WARNING, new RemoveAnnotationFix());
            }
        }
    }

    private static boolean missingNoArgConstructor(PsiClass psiClass) {
        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length == 0) {
            return false;
        }
        for (PsiMethod constructor : constructors) {
            if (constructor.getParameterList().getParametersCount() == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJpaEntityType(PsiClass psiClass) {
        return SpringInspectionUtil.isJpaEntity(psiClass);
    }

    private static boolean isToOneRelationship(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        return hasAnnotation(modifiers, "ManyToOne") || hasAnnotation(modifiers, "OneToOne");
    }

    private static boolean hasLazyFetch(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if ((isAnnotation(annotation, "ManyToOne") || isAnnotation(annotation, "OneToOne"))
                    && annotation.getText().contains("LAZY")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnumTypeString(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (isAnnotation(annotation, "Enumerated") && annotation.getText().contains("STRING")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMappedBy(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (isAnnotation(annotation, "OneToMany") && annotation.getText().contains("mappedBy")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLargeObjectColumnDefinition(PsiField field) {
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (!isAnnotation(annotation, "Column")) {
                continue;
            }
            PsiAnnotationMemberValue columnDefinition = annotation.findDeclaredAttributeValue("columnDefinition");
            if (columnDefinition != null && isLargeObjectColumnDefinition(columnDefinition.getText())) {
                return true;
            }
        }
        return false;
    }

    // Plain TEXT is excluded: PostgreSQL and SQL Server map it to an ordinary string column, and
    // @Lob there would switch Hibernate to large-object (OID) storage.
    private static final Set<String> LARGE_OBJECT_TYPES = Set.of("clob", "nclob", "blob", "tinyblob", "mediumblob",
            "longblob", "tinytext", "mediumtext", "longtext");

    private static final Pattern COLUMN_TYPE = Pattern.compile("^\\s*([a-z]+)\\s*(\\[)?");

    private static boolean isLargeObjectColumnDefinition(String text) {
        String normalized = text.replace("\"", "").replace("'", "").toLowerCase(Locale.ROOT);
        Matcher type = COLUMN_TYPE.matcher(normalized);
        return type.find() && type.group(2) == null && LARGE_OBJECT_TYPES.contains(type.group(1));
    }

    private static boolean hasAuditingField(PsiClass psiClass) {
        for (PsiField field : psiClass.getFields()) {
            PsiModifierList modifiers = field.getModifierList();
            if (hasAnnotation(modifiers, "CreatedDate") || hasAnnotation(modifiers, "LastModifiedDate")
                    || hasAnnotation(modifiers, "CreatedBy") || hasAnnotation(modifiers, "LastModifiedBy")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEntityListeners(PsiClass psiClass) {
        for (PsiClass current = psiClass; current != null; current = current.getSuperClass()) {
            if (declaresAuditingEntityListener(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresAuditingEntityListener(PsiClass psiClass) {
        PsiModifierList modifiers = psiClass.getModifierList();
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (!isAnnotation(annotation, "EntityListeners")) {
                continue;
            }
            PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(null);
            if (value == null) {
                value = annotation.findDeclaredAttributeValue("value");
            }
            if (isAuditingEntityListener(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAuditingEntityListener(PsiAnnotationMemberValue value) {
        if (value instanceof PsiReferenceExpression reference && reference.resolve() instanceof PsiClass psiClass) {
            return "org.springframework.data.jpa.domain.support.AuditingEntityListener".equals(psiClass.getQualifiedName())
                    || "AuditingEntityListener".equals(psiClass.getName());
        }
        if (value instanceof com.intellij.psi.PsiClassObjectAccessExpression classObject
                && classObject.getOperand().getType() instanceof com.intellij.psi.PsiClassType classType) {
            PsiClass psiClass = classType.resolve();
            return psiClass != null && ("org.springframework.data.jpa.domain.support.AuditingEntityListener".equals(psiClass.getQualifiedName())
                    || "AuditingEntityListener".equals(psiClass.getName()));
        }
        if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                if (isAuditingEntityListener(initializer)) {
                    return true;
                }
            }
        }
        String text = (value != null) ? value.getText() : null;
        return "AuditingEntityListener.class".equals(text)
                || "org.springframework.data.jpa.domain.support.AuditingEntityListener.class".equals(text);
    }

    private static boolean hasAnnotation(PsiModifierList modifiers, String shortName) {
        if (modifiers == null) {
            return false;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (isAnnotation(annotation, shortName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnnotation(PsiAnnotation annotation, String shortName) {
        String name = annotation.getQualifiedName();
        if (name == null && annotation.getNameReferenceElement() != null) {
            name = annotation.getNameReferenceElement().getText();
        }
        return shortName.equals(name) || (name != null && name.endsWith("." + shortName));
    }

    public static class LombokOnEntity extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectClass(PsiClass psiClass, ProblemsHolder holder) {
            reportLombokEntityAnnotations(psiClass, holder);
        }
    }

    public static class MissingNoArgConstructor extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectClass(PsiClass psiClass, ProblemsHolder holder) {
            if (missingNoArgConstructor(psiClass)) {
                PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                holder.registerProblem((nameIdentifier != null) ? nameIdentifier : psiClass,
                        "JPA entity should declare a protected no-arg constructor",
                        ProblemHighlightType.ERROR, new AddProtectedNoArgConstructorFix());
            }
        }
    }

    public static class ToOneEagerDefault extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectField(PsiField field, ProblemsHolder holder) {
            if (isToOneRelationship(field) && !hasLazyFetch(field)) {
                holder.registerProblem(field,
                        "To-one JPA association uses eager loading by default; prefer fetch = FetchType.LAZY",
                        ProblemHighlightType.WARNING, new AddLazyFetchFix());
            }
        }
    }

    public static class EnumeratedOrdinal extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectField(PsiField field, ProblemsHolder holder) {
            if (hasAnnotation(field.getModifierList(), "Enumerated") && !hasEnumTypeString(field)) {
                holder.registerProblem(field,
                        "@Enumerated defaults to ORDINAL; use EnumType.STRING to avoid data corruption when enum order changes",
                        ProblemHighlightType.ERROR, new AddEnumTypeStringFix());
            }
        }
    }

    public static class AuditingMissingListener extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectClass(PsiClass psiClass, ProblemsHolder holder) {
            if (hasAuditingField(psiClass) && !hasEntityListeners(psiClass)) {
                PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                holder.registerProblem((nameIdentifier != null) ? nameIdentifier : psiClass,
                        "JPA auditing fields require @EntityListeners(AuditingEntityListener.class) on the entity",
                        ProblemHighlightType.ERROR, new AddAuditingEntityListenerFix());
            }
        }
    }

    public static class OneToManyWithoutMappedBy extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectField(PsiField field, ProblemsHolder holder) {
            if (hasAnnotation(field.getModifierList(), "OneToMany") && !hasMappedBy(field)) {
                holder.registerProblem(field,
                        "@OneToMany without mappedBy creates an implicit join table; specify mappedBy on the inverse side",
                        ProblemHighlightType.WARNING);
            }
        }
    }

    public static class MissingLobForClobColumn extends SpringJpaEntityDesignInspection {
        @Override
        protected void inspectField(PsiField field, ProblemsHolder holder) {
            PsiModifierList modifiers = field.getModifierList();
            if (hasLargeObjectColumnDefinition(field) && !hasAnnotation(modifiers, "Lob")) {
                holder.registerProblem(field,
                        "@Column large-object definition should also declare @Lob so JPA maps the field as a large object",
                        ProblemHighlightType.ERROR, new AddLobAnnotationFix());
            }
        }
    }

    private static final class RemoveAnnotationFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Remove Lombok annotation";
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

    private static final class AddProtectedNoArgConstructorFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Add protected no-arg constructor";
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
            if (psiClass != null && psiClass.getName() != null) {
                PsiMethod constructor = JavaPsiFacade.getElementFactory(project)
                        .createConstructor(psiClass.getName());
                constructor.getModifierList().setModifierProperty(PsiModifier.PROTECTED, true);
                psiClass.add(constructor);
            }
        }
    }

    private static final class AddLazyFetchFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Add fetch = FetchType.LAZY";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiField field = descriptor.getPsiElement() instanceof PsiField f ? f
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiField.class);
            if (field == null || field.getModifierList() == null) {
                return;
            }
            for (PsiAnnotation annotation : field.getModifierList().getAnnotations()) {
                if (isAnnotation(annotation, "ManyToOne") || isAnnotation(annotation, "OneToOne")) {
                    PsiAnnotationMemberValue existingFetch = annotation.findDeclaredAttributeValue("fetch");
                    String replacementText = (existingFetch != null) ? existingFetch.getText().replaceFirst("\\bEAGER\\b", "LAZY") : "FetchType.LAZY";
                    PsiAnnotationMemberValue replacement = JavaPsiFacade.getElementFactory(project)
                            .createExpressionFromText(replacementText, annotation);
                    annotation.setDeclaredAttributeValue("fetch", replacement);
                    return;
                }
            }
        }
    }

    private static final class AddEnumTypeStringFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Use EnumType.STRING";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiField field = descriptor.getPsiElement() instanceof PsiField f ? f
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiField.class);
            if (field == null || field.getModifierList() == null) {
                return;
            }
            for (PsiAnnotation annotation : field.getModifierList().getAnnotations()) {
                if (isAnnotation(annotation, "Enumerated")) {
                    String enumeratedFqn = annotation.getQualifiedName();
                    if (enumeratedFqn == null) {
                        PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
                        if (ref != null) {
                            PsiElement resolved = ref.resolve();
                            if (resolved instanceof PsiClass resolvedClass) {
                                enumeratedFqn = resolvedClass.getQualifiedName();
                            }
                        }
                    }
                    String replacementText;
                    if (enumeratedFqn != null && enumeratedFqn.contains(".")) {
                        String pkg = enumeratedFqn.substring(0, enumeratedFqn.lastIndexOf('.'));
                        replacementText = "@" + pkg + ".Enumerated(" + pkg + ".EnumType.STRING)";
                    }
                    else {
                        replacementText = "@Enumerated(EnumType.STRING)";
                    }
                    PsiAnnotation newAnnotation = JavaPsiFacade.getElementFactory(project)
                            .createAnnotationFromText(replacementText, annotation);
                    annotation.replace(newAnnotation);

                    if (field.getContainingFile() instanceof PsiJavaFile javaFile) {
                        JavaCodeStyleManager.getInstance(project).shortenClassReferences(javaFile);
                    }

                    PsiJavaFile todoDummy = (PsiJavaFile) PsiFileFactory.getInstance(project).createFileFromText(
                            "Dummy.java", JavaLanguage.INSTANCE,
                            "//TODO migrate the column in the DB\nclass Dummy {}");
                    PsiComment todoComment = null;
                    PsiWhiteSpace afterCommentWs = null;
                    for (PsiElement child = todoDummy.getClasses()[0].getFirstChild(); child != null; child = child.getNextSibling()) {
                        if (todoComment == null && child instanceof PsiComment) {
                            todoComment = (PsiComment) child.copy();
                        }
                        else if (todoComment != null && child instanceof PsiWhiteSpace && afterCommentWs == null) {
                            afterCommentWs = (PsiWhiteSpace) child.copy();
                            break;
                        }
                    }
                    if (todoComment != null) {
                        PsiElement parent = field.getParent();
                        parent.addBefore(todoComment, field);
                        if (afterCommentWs != null) {
                            parent.addBefore(afterCommentWs, field);
                        }
                    }
                    return;
                }
            }
        }
    }

    private static final class AddLobAnnotationFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Add @Lob";
        }

        @Override
        @NotNull
        public String getFamilyName() {
            return this.getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiField field = descriptor.getPsiElement() instanceof PsiField f ? f
                    : com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiField.class);
            if (field == null || field.getModifierList() == null) {
                return;
            }
            field.getModifierList().addAnnotation("Lob");
        }
    }

    private static final class AddAuditingEntityListenerFix implements LocalQuickFix {
        @Override
        @NotNull
        public String getName() {
            return "Add @EntityListeners(AuditingEntityListener.class)";
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
                psiClass.getModifierList().addAnnotation(
                        "EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)");
            }
        }
    }
}
