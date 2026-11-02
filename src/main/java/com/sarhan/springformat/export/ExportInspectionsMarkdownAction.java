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

package com.sarhan.springformat.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ui.InspectionResultsView;
import com.intellij.codeInspection.ui.InspectionTreeNode;
import com.intellij.codeInspection.ui.ProblemDescriptionNode;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTree;

/**
 * Right-click action for the inspection results view. Exports the selected
 * subtree (or the whole results tree when nothing is selected) as a Markdown
 * report: per inspection tool the problem description, then all affected files
 * with one-based line numbers and the problem message.
 *
 * @author Akmal Sarhan
 */
public class ExportInspectionsMarkdownAction extends AnAction {

    private static final char GROUP_PATH_SEPARATOR = '>';
    private static final int UNKNOWN_LINE = -1;

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        InspectionResultsView view = resultsView(e);
        if (project == null || view == null) {
            return;
        }

        List<ProblemDescriptionNode> problemNodes = collectProblemNodes(view);
        if (problemNodes.isEmpty()) {
            Messages.showInfoMessage(project, "No inspection problems to export.", "Spring Buddy");
            return;
        }

        List<InspectionMarkdownReport.InspectionSection> sections = buildSections(view, problemNodes);
        String markdown = InspectionMarkdownReport.build(scopeTitle(view), projectRoot(project),
                InspectionMarkdownReport.defaultTimestamp(), sections);
        save(project, markdown, defaultFileName(selectedNodeLabel(inspectionTree(view))));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        InspectionResultsView view = resultsView(e);
        e.getPresentation().setEnabledAndVisible(view != null && view.hasProblems());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /**
     * Resolves the inspection results view from the action data context. The
     * platform provides the view through {@link InspectionResultsView#DATA_KEY};
     * the internal helper {@code InspectionViewActionBase#getView} does the same.
     */
    private static InspectionResultsView resultsView(AnActionEvent e) {
        InspectionResultsView view = e.getData(InspectionResultsView.DATA_KEY);
        return view == null || view.isDisposed() ? null : view;
    }

    /**
     * Locates the results tree without referencing internal classes. The view
     * nests exactly one {@link JTree} whose model root is an
     * {@link InspectionTreeNode}; other child components never match.
     */
    private static JTree inspectionTree(InspectionResultsView view) {
        for (JTree tree : UIUtil.findComponentsOfType(view, JTree.class)) {
            if (tree.getModel().getRoot() instanceof InspectionTreeNode) {
                return tree;
            }
        }
        return null;
    }

    private static List<ProblemDescriptionNode> collectProblemNodes(InspectionResultsView view) {
        JTree tree = inspectionTree(view);
        if (tree == null) {
            return List.of();
        }
        List<InspectionTreeNode> roots = selectedRoots(tree);
        Set<InspectionTreeNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ProblemDescriptionNode> result = new ArrayList<>();
        for (InspectionTreeNode root : roots) {
            collect(root, visited, result);
        }
        return result;
    }

    private static List<InspectionTreeNode> selectedRoots(JTree tree) {
        Object modelRoot = tree.getModel().getRoot();
        if (!(modelRoot instanceof InspectionTreeNode rootNode)) {
            return List.of();
        }
        javax.swing.tree.TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) {
            return List.of(rootNode);
        }
        List<InspectionTreeNode> roots = new ArrayList<>();
        for (javax.swing.tree.TreePath path : paths) {
            Object last = path.getLastPathComponent();
            if (last instanceof InspectionTreeNode node) {
                roots.add(node);
            }
        }
        if (roots.isEmpty()) {
            return List.of(rootNode);
        }
        return roots;
    }

    private static void collect(InspectionTreeNode node, Set<InspectionTreeNode> visited, List<ProblemDescriptionNode> result) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.isExcluded()) {
            return;
        }
        if (node instanceof ProblemDescriptionNode problemNode) {
            result.add(problemNode);
            return;
        }
        for (InspectionTreeNode child : node.getChildren()) {
            collect(child, visited, result);
        }
    }

    private static List<InspectionMarkdownReport.InspectionSection> buildSections(InspectionResultsView view,
                                                                                   List<ProblemDescriptionNode> nodes) {
        Project project = view.getProject();
        Map<String, InspectionToolWrapper<?, ?>> wrappers = new LinkedHashMap<>();
        Map<String, String> levelsBySection = new LinkedHashMap<>();
        Map<String, Map<String, InspectionMarkdownReport.Problem>> problemsBySection = new LinkedHashMap<>();

        for (ProblemDescriptionNode node : nodes) {
            InspectionToolWrapper<?, ?> wrapper = node.getToolWrapper();
            if (wrapper == null) {
                continue;
            }
            String sectionKey = wrapper.getShortName();
            wrappers.putIfAbsent(sectionKey, wrapper);
            levelsBySection.putIfAbsent(sectionKey, levelOf(view, node, wrapper));
            InspectionMarkdownReport.Problem problem = toProblem(project, node);
            if (problem == null) {
                continue;
            }
            problemsBySection.computeIfAbsent(sectionKey, key -> new LinkedHashMap<>())
                    .putIfAbsent(identityKey(problem), problem);
        }

        List<InspectionMarkdownReport.InspectionSection> sections = new ArrayList<>();
        for (Map.Entry<String, InspectionToolWrapper<?, ?>> entry : wrappers.entrySet()) {
            InspectionToolWrapper<?, ?> wrapper = entry.getValue();
            String description = descriptionOf(wrapper);
            String level = levelsBySection.getOrDefault(entry.getKey(), "");
            String groupPath = groupPathOf(wrapper);
            Map<String, InspectionMarkdownReport.Problem> problems = problemsBySection.getOrDefault(entry.getKey(),
                    Collections.emptyMap());
            sections.add(new InspectionMarkdownReport.InspectionSection(wrapper.getShortName(), wrapper.getDisplayName(),
                    level, groupPath, description, new ArrayList<>(problems.values())));
        }
        return sections;
    }

    private static String identityKey(InspectionMarkdownReport.Problem problem) {
        return problem.filePath() + '|' + problem.line() + '|' + problem.message();
    }

    /**
     * Resolves the severity the tool is configured with in the profile of the
     * run that produced the results view, matching the severity group the
     * problems are presented under, and falls back to the tool's default
     * level when the profile has no entry for the tool.
     */
    private static String levelOf(InspectionResultsView view, ProblemDescriptionNode node,
                                  InspectionToolWrapper<?, ?> wrapper) {
        HighlightDisplayKey key = HighlightDisplayKey.find(wrapper.getShortName());
        InspectionProfile profile = view.getCurrentProfile();
        if (key != null && profile != null) {
            HighlightDisplayLevel level = profile.getErrorLevel(key, null);
            if (level != null) {
                return level.getName();
            }
        }
        HighlightDisplayLevel fallback = wrapper.getDefaultLevel();
        return fallback == null ? "" : fallback.getName();
    }

    private static String displayPath(Project project, VirtualFile virtualFile) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        String relative = baseDir != null ? VfsUtil.getRelativePath(virtualFile, baseDir, '/') : null;
        return relative != null && !relative.isBlank() ? relative : virtualFile.getPath();
    }

    private static String projectRoot(Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        return baseDir != null ? baseDir.getPath() : null;
    }

    private static InspectionMarkdownReport.Problem toProblem(Project project, ProblemDescriptionNode node) {
        CommonProblemDescriptor descriptor = node.getDescriptor();
        if (descriptor == null) {
            return null;
        }
        Location location = resolveLocation(project, node);
        String message = messageText(node, descriptor);
        return new InspectionMarkdownReport.Problem(location.filePath(), location.line(), message);
    }

    private static Location resolveLocation(Project project, ProblemDescriptionNode node) {
        PsiElement element = problemElement(node);
        if (element != null) {
            PsiFile psiFile = element.getContainingFile();
            VirtualFile virtualFile = psiFile != null ? psiFile.getVirtualFile() : null;
            if (virtualFile == null) {
                virtualFile = PsiUtilCore.getVirtualFile(element);
            }
            int line = UNKNOWN_LINE;
            if (psiFile != null && virtualFile != null && element.isValid()) {
                line = lineNumber(element, psiFile);
            }
            if (virtualFile != null) {
                return new Location(displayPath(project, virtualFile), line);
            }
        }
        if (node.getElement() != null) {
            String externalName = node.getElement().getExternalName();
            if (externalName != null && !externalName.isBlank()) {
                return new Location(externalName, UNKNOWN_LINE);
            }
        }
        return new Location("", UNKNOWN_LINE);
    }

    private static int lineNumber(PsiElement element, PsiFile psiFile) {
        if (psiFile.getVirtualFile() == null) {
            return UNKNOWN_LINE;
        }
        com.intellij.openapi.editor.Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(psiFile);
        if (document == null) {
            return UNKNOWN_LINE;
        }
        int offset = element.getTextRange() != null ? element.getTextRange().getStartOffset() : element.getTextOffset();
        if (offset < 0 || offset > document.getTextLength()) {
            return UNKNOWN_LINE;
        }
        return document.getLineNumber(offset) + 1;
    }

    private static String messageText(ProblemDescriptionNode node, CommonProblemDescriptor descriptor) {
        String text = descriptor.getDescriptionTemplate();
        if (text == null) {
            text = node.getPresentableText();
        }
        if (text == null) {
            return "";
        }
        if (text.contains("#ref")) {
            PsiElement element = problemElement(node);
            String ref = element != null && element.getText() != null ? element.getText().trim() : "";
            if (ref.isEmpty()) {
                ref = "the problem location";
            }
            text = text.replace("#ref", ref);
        }
        return InspectionMarkdownReport.toSingleLine(text);
    }

    private static PsiElement problemElement(ProblemDescriptionNode node) {
        CommonProblemDescriptor descriptor = node.getDescriptor();
        if (descriptor instanceof ProblemDescriptor problemDescriptor) {
            PsiElement start = problemDescriptor.getStartElement();
            if (start != null) {
                return start;
            }
            return problemDescriptor.getPsiElement();
        }
        return null;
    }

    private static String groupPathOf(InspectionToolWrapper<?, ?> wrapper) {
        String[] groupPath = wrapper.getGroupPath();
        if (groupPath == null || groupPath.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groupPath.length; i++) {
            if (i > 0) {
                sb.append(' ').append(GROUP_PATH_SEPARATOR).append(' ');
            }
            sb.append(groupPath[i]);
        }
        return sb.toString();
    }

    private static String descriptionOf(InspectionToolWrapper<?, ?> wrapper) {
        String html = null;
        try {
            html = wrapper.loadDescription();
        } catch (Exception ignored) {
        }
        if (html == null || html.isBlank()) {
            html = wrapper.getStaticDescription();
        }
        return InspectionMarkdownReport.toText(html);
    }

    private static String scopeTitle(InspectionResultsView view) {
        try {
            com.intellij.analysis.AnalysisScope scope = view.getScope();
            if (scope != null) {
                String name = scope.getDisplayName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return view.getViewTitle();
    }

    private static void save(Project project, String markdown, String fileName) {
        FileSaverDescriptor descriptor = new FileSaverDescriptor("Export Inspection Results as Markdown",
                "Select the file to save the Markdown report", "md");
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save(ProjectUtil.guessProjectDir(project), fileName);
        if (wrapper == null) {
            return;
        }
        try {
            File file = wrapper.getFile();
            FileUtil.writeToFile(file, markdown.getBytes(StandardCharsets.UTF_8));
            VirtualFile virtualFile = VfsUtil.findFileByIoFile(file, true);
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openEditor(new OpenFileDescriptor(project, virtualFile), true);
            }
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "Could not save report: " + ex.getMessage(), "Spring Buddy");
        }
    }

    /**
     * Builds the suggested export file name. When a single results-tree node is
     * selected the node's label is included in the name so the file identifies
     * the exported subtree (severity group, inspection tool, package, file,
     * or individual problem); otherwise the generic name is used.
     */
    private static String defaultFileName(String nodeLabel) {
        StringBuilder sb = new StringBuilder("spring-buddy-inspections");
        if (nodeLabel != null && !nodeLabel.isBlank()) {
            sb.append('-').append(nodeLabel);
        }
        sb.append('-')
                .append(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .append(".md");
        return sb.toString();
    }

    /**
     * Returns a file-name-safe label for the single selected results-tree node,
     * or {@code null} when nothing is selected, several nodes are selected, or
     * the whole tree (root) is selected.
     */
    private static String selectedNodeLabel(JTree tree) {
        if (tree == null) {
            return null;
        }
        javax.swing.tree.TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length != 1) {
            return null;
        }
        Object last = paths[0].getLastPathComponent();
        if (!(last instanceof InspectionTreeNode node) || node == tree.getModel().getRoot()) {
            return null;
        }
        String label;
        if (node instanceof ProblemDescriptionNode problemNode) {
            InspectionToolWrapper<?, ?> wrapper = problemNode.getToolWrapper();
            label = wrapper != null ? wrapper.getShortName() : node.toString();
        } else {
            label = node.toString();
        }
        return sanitizeFileName(label);
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^[-.]+|[-.]+$", "");
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private record Location(String filePath, int line) {
    }
}
