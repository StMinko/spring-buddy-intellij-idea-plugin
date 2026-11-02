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

package com.sarhan.springformat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.GlobalInspectionContextBase;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.ex.ScopeToolState;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.sarhan.springformat.settings.SpringBuddySettingsSynchronizer;
import com.sarhan.springformat.settings.SpringBuddySettingsUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Akmal Sarhan
 */
public class RunSpringFormatInspectionsAction extends AnAction {

    private static final String SCOPE_DISPLAY_NAME = "Spring Buddy";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        runInspections(project, files);
    }

    public static void runInspections(Project project, @Nullable VirtualFile[] files) {
        AnalysisScope scope;
        if ((files != null) && (files.length > 0)) {
            scope = new NamedAnalysisScope(project, Arrays.asList(files), SCOPE_DISPLAY_NAME);
        } else {
            scope = new NamedAnalysisScope(project, SCOPE_DISPLAY_NAME);
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Spring Java Format: Running Inspections", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    FileDocumentManager.getInstance().saveAllDocuments();
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                });

                InspectionManagerEx managerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
                GlobalInspectionContextBase context = (GlobalInspectionContextBase) managerEx.createNewGlobalContext(false);

                InspectionProfileImpl profile = createProfile(project);
                SpringBuddySettingsSynchronizer.syncProfile(project, profile);
                context.setExternalProfile(profile);
                context.doInspections(scope);
            }
        });
    }

    public static Map<String, List<String>> collectErrorInspections(Project project, @Nullable VirtualFile[] files) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            FileDocumentManager.getInstance().saveAllDocuments();
            PsiDocumentManager.getInstance(project).commitAllDocuments();
        });

        return ApplicationManager.getApplication().runReadAction((Computable<Map<String, List<String>>>) () -> {
            Map<String, List<String>> errorsByFile = new LinkedHashMap<>();
            if (files == null || files.length == 0) {
                return errorsByFile;
            }

            InspectionProfileImpl profile = createProfile(project);
            SpringBuddySettingsSynchronizer.syncProfile(project, profile);

            InspectionManagerEx managerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
            GlobalInspectionContextBase context = (GlobalInspectionContextBase) managerEx.createNewGlobalContext(false);
            PsiManager psiManager = PsiManager.getInstance(project);
            List<ScopeToolState> allTools = profile.getAllTools();

            return collectErrorInspections(project, files, allTools, context, psiManager);
        });
    }

    static Map<String, List<String>> collectErrorInspections(Project project, @Nullable VirtualFile[] files,
                                                            List<ScopeToolState> allTools) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            FileDocumentManager.getInstance().saveAllDocuments();
            PsiDocumentManager.getInstance(project).commitAllDocuments();
        });

        return ApplicationManager.getApplication().runReadAction((Computable<Map<String, List<String>>>) () -> {
            InspectionManagerEx managerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
            GlobalInspectionContextBase context = (GlobalInspectionContextBase) managerEx.createNewGlobalContext(false);
            PsiManager psiManager = PsiManager.getInstance(project);

            return collectErrorInspections(project, files, allTools, context, psiManager);
        });
    }

    private static Map<String, List<String>> collectErrorInspections(Project project, @Nullable VirtualFile[] files,
                                                                    List<ScopeToolState> allTools,
                                                                    GlobalInspectionContextBase context,
                                                                    PsiManager psiManager) {
        Map<String, List<String>> errorsByFile = new LinkedHashMap<>();
        if (files == null || files.length == 0) {
            return errorsByFile;
        }

        for (VirtualFile vf : files) {
            if (vf == null || vf.isDirectory()) {
                continue;
            }
            PsiFile psiFile = psiManager.findFile(vf);
            if (psiFile == null) {
                continue;
            }

            List<String> fileErrors = new ArrayList<>();
            for (ScopeToolState state : allTools) {
                if (!state.isEnabled()) {
                    continue;
                }
                InspectionToolWrapper<?, ?> wrapper = state.getTool();
                if (!isConfiguredErrorTool(wrapper)) {
                    continue;
                }
                if (!(wrapper instanceof LocalInspectionToolWrapper localWrapper)) {
                    continue;
                }

                for (ProblemDescriptor problem : inspectFile(psiFile, localWrapper, context)) {
                    fileErrors.add(problem.getDescriptionTemplate());
                }
            }
            if (!fileErrors.isEmpty()) {
                errorsByFile.put(vf.getName(), fileErrors);
            }
        }
        return errorsByFile;
    }

    private static boolean isConfiguredErrorTool(InspectionToolWrapper<?, ?> wrapper) {
        for (Element tool : SpringBuddySettingsUtil.loadBundledProfileTools()) {
            if (wrapper.getShortName().equals(tool.getAttributeValue("class"))
                    && SpringBuddySettingsUtil.toolLevel(tool) == HighlightDisplayLevel.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static List<ProblemDescriptor> inspectFile(PsiFile psiFile, LocalInspectionToolWrapper wrapper,
                                                       GlobalInspectionContextBase context) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator == null) {
            return ProgressManager.getInstance().runProcess(
                    () -> InspectionEngine.runInspectionOnFile(psiFile, wrapper, context),
                    new EmptyProgressIndicator());
        }
        return InspectionEngine.runInspectionOnFile(psiFile, wrapper, context);
    }

    private static InspectionProfileImpl createProfile(Project project) {
        InspectionProfileImpl profile = new InspectionProfileImpl("Spring Buddy");
        profile.initInspectionTools(project);
        profile.disableAllTools(project);
        loadProfileTools(profile, project);
        return profile;
    }


    private static void loadProfileTools(InspectionProfileImpl profile, Project project) {
        for (Element tool : SpringBuddySettingsUtil.loadBundledProfileTools()) {
            if (Boolean.parseBoolean(tool.getAttributeValue("enabled"))) {
                try {
                    enableTool(profile, project, tool);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void enableTool(InspectionProfileImpl profile, Project project, Element tool) {
        String toolName = tool.getAttributeValue("class");
        if (toolName == null) {
            return;
        }
        profile.enableTool(toolName, project);
        ScopeToolState state = profile.getToolDefaultState(toolName, project);
        if (state == null) {
            return;
        }
        state.setEnabled(true);

        try {
            ScopeToolState.tryReadSettings(state.getTool().getTool(), tool);
        } catch (Exception ignored) {
        }
    }
}
