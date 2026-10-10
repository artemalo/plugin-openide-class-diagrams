package ru.student.diagram.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.content.ContentFactory;
import java.util.*;

public final class ShowDiagramAction extends AnAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent event) {
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        PsiFile psi = event.getData(CommonDataKeys.PSI_FILE);
        boolean applicable = psi instanceof PsiJavaFile;
        if (files != null) for (VirtualFile file : files)
            applicable |= file.isDirectory() || "java".equalsIgnoreCase(file.getExtension());
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && applicable);
    }
    @Override public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject(); if (project == null) return;
        Set<String> urls = new LinkedHashSet<>();
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null) for (VirtualFile file : files) urls.add(file.getUrl());
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        var editor = event.getData(CommonDataKeys.EDITOR);
        String onlyClass = null;
        if (editor != null && file instanceof PsiJavaFile) {
            PsiClass clazz = PsiTreeUtil.getParentOfType(file.findElementAt(editor.getCaretModel().getOffset()), PsiClass.class);
            if (clazz != null) onlyClass = clazz.getQualifiedName();
            urls.clear(); if (file.getVirtualFile() != null) urls.add(file.getVirtualFile().getUrl());
        }
        if (urls.isEmpty() && file != null && file.getVirtualFile() != null) urls.add(file.getVirtualFile().getUrl());
        if (urls.isEmpty()) return;
        var window = ToolWindowManager.getInstance(project).getToolWindow("Class Diagrams");
        if (window == null) return;
        var panel = new DiagramPanel(project, new PsiDiagramBuilder.Selection(new ArrayList<>(urls), onlyClass));
        var content = ContentFactory.getInstance().createContent(panel, onlyClass == null ? "Выбранные классы"
                : onlyClass.substring(onlyClass.lastIndexOf('.') + 1), false);
        content.setDisposer(panel);
        var manager = window.getContentManager();
        for (var existing : manager.getContents())
            if (existing.getDisplayName().equals("Начало")) manager.removeContent(existing, true);
        manager.addContent(content); manager.setSelectedContent(content);
        window.activate(() -> javax.swing.SwingUtilities.invokeLater(panel::revalidate));
    }
}
