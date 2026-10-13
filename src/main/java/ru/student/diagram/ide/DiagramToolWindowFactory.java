package ru.student.diagram.ide;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import javax.swing.*;
import java.awt.*;

public final class DiagramToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("<html><h2>Диаграммы Java-классов</h2>Выделите классы или папку в Project.<br>"
                + "Выберите в контекстном меню пункт &quot;Построить UML-диаграмму классов&quot;.<br><br>"
                + "Диаграмму можно открыть и из редактора через меню Tools.</html>", SwingConstants.CENTER);
        panel.add(label, BorderLayout.CENTER);
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "Начало", false));
    }
}
