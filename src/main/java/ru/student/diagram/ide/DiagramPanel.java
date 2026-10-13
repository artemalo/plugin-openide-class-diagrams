package ru.student.diagram.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.concurrency.CancellablePromise;
import ru.student.diagram.model.*;
import ru.student.diagram.ui.DiagramCanvas;
import ru.student.diagram.ui.WrapLayout;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.CancellationException;

public final class DiagramPanel extends JPanel implements Disposable {
    private final Project project;
    private final PsiDiagramBuilder.Selection selection;
    private final DiagramCanvas canvas = new DiagramCanvas();
    private final JLabel status = new JLabel("Анализ классов...");
    private final JCheckBox fields = new JCheckBox("Поля", true), methods = new JCheckBox("Методы", true),
            publicOnly = new JCheckBox("Только public"), related = new JCheckBox("Связанные классы"),
            dependencies = new JCheckBox("Зависимости", true);
    private CancellablePromise<DiagramModel> pending;
    private boolean disposed, loading;
    private int generation;
    private String warning = "";

    public DiagramPanel(Project project, PsiDiagramBuilder.Selection selection) {
        super(new BorderLayout()); this.project = project; this.selection = selection;
        Color base = UIManager.getColor("Panel.background");
        canvas.setDark(base != null && base.getRed() + base.getGreen() + base.getBlue() < 390);
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel commands = new JPanel(new WrapLayout(5, 5));
        button(commands, "Обновить", "Перечитать выбранные исходники", this::reload);
        button(commands, "Раскладка", "Разместить узлы автоматически и вернуть скрытые", canvas::layoutGraph);
        button(commands, "Автострелки", "Сбросить ручные маршруты всех стрелок, сохранив позиции классов", canvas::resetArrows);
        button(commands, "Вписать", "Показать всю диаграмму", canvas::fit);
        button(commands, "−", "Уменьшить", canvas::zoomOut);
        button(commands, "+", "Увеличить", canvas::zoomIn);
        button(commands, "100%", "Исходный масштаб", canvas::actualSize);
        JButton export = new JButton("Экспорт ▾");
        export.addActionListener(e -> exportMenu(export)); commands.add(export);
        JCheckBox theme = new JCheckBox("Тёмная", canvas.isDark());
        theme.addActionListener(e -> canvas.setDark(theme.isSelected())); commands.add(theme);
        top.add(commands);
        JPanel filters = new JPanel(new WrapLayout(7, 2));
        for (JCheckBox box : new JCheckBox[]{fields, methods, publicOnly, related, dependencies}) {
            filters.add(box); box.addActionListener(e -> {
                canvas.setOptions(fields.isSelected(), methods.isSelected(), publicOnly.isSelected(),
                        related.isSelected(), dependencies.isSelected()); canvas.fit();
            });
        }
        top.add(filters);
        JPanel searchRow = new JPanel(new WrapLayout(8, 5));
        searchRow.add(new JLabel("Найти класс:")); JTextField search = new JTextField(23);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
            private void update() { canvas.search(search.getText()); }
        });
        searchRow.add(search); searchRow.add(new JLabel("Колесо мыши меняет масштаб. Перетащите стрелку, чтобы изменить маршрут."));
        top.add(searchRow); add(top, BorderLayout.NORTH); add(canvas, BorderLayout.CENTER);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { top.revalidate(); }
        });
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10)); add(status, BorderLayout.SOUTH);
        canvas.onChange(this::updateStatus);
        canvas.onNavigate((url, offset) -> {
            var file = VirtualFileManager.getInstance().findFileByUrl(url);
            if (file != null && !project.isDisposed()) new OpenFileDescriptor(project, file, offset).navigate(true);
        });
        reload();
    }
    private static void button(JPanel panel, String text, String tooltip, Runnable action) {
        JButton button = new JButton(text); button.setToolTipText(tooltip);
        button.addActionListener(e -> action.run()); panel.add(button);
    }
    public void reload() {
        if (disposed || project.isDisposed()) return;
        int request = ++generation;
        if (pending != null) pending.cancel();
        loading = true; status.setText("Анализ классов... Если IDE индексирует проект, построение начнётся после индексации.");
        pending = ReadAction.nonBlocking(() -> PsiDiagramBuilder.build(project, selection))
                .inSmartMode(project).expireWith(this).expireWith(project)
                .finishOnUiThread(ModalityState.any(), model -> {
                    if (disposed || request != generation) return;
                    loading = false; warning = String.join(" ", model.warnings());
                    canvas.setModel(model); canvas.fit(); updateStatus();
                    if (model.nodes().isEmpty()) status.setText("Java-классы не найдены. Выберите .java-файлы или папку исходников.");
                }).submit(AppExecutorUtil.getAppExecutorService());
        pending.onError(error -> SwingUtilities.invokeLater(() -> {
            if (disposed || project.isDisposed() || request != generation
                    || error instanceof ProcessCanceledException || error instanceof CancellationException) return;
            loading = false; status.setText("Не удалось построить диаграмму: " + error.getMessage());
        }));
    }
    private void updateStatus() {
        if (loading) return;
        var model = canvas.visibleModel();
        status.setText(model.nodes().size() + " классов, " + model.edges().size() + " связей, "
                + Math.round(canvas.getZoom() * 100) + "%. " + canvas.interactionHint()
                + (warning.isBlank() ? "" : " " + warning));
    }
    private void exportMenu(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (String format : new String[]{"SVG", "PNG", "PlantUML"}) {
            JMenuItem item = new JMenuItem(format); item.addActionListener(e -> export(format)); menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }
    private void export(String format) {
        if (canvas.visibleModel().nodes().isEmpty()) return;
        String extension = format.equals("PlantUML") ? "puml" : format.toLowerCase(Locale.ROOT);
        JFileChooser chooser = new JFileChooser(project.getBasePath());
        chooser.setDialogTitle("Сохранить диаграмму " + format);
        chooser.setFileFilter(new FileNameExtensionFilter(format, extension));
        chooser.setSelectedFile(new java.io.File("class-diagram." + extension));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension))
            path = path.resolveSibling(path.getFileName() + "." + extension);
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Заменить существующий файл?\n" + path,
                "Сохранение диаграммы", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        // Capture the Swing state on EDT; encode and write the snapshot in a worker.
        Object snapshot = format.equals("PNG") ? canvas.toImage() : format.equals("SVG") ? canvas.toSvg()
                : PlantUmlExporter.export(canvas.visibleModel(), canvas.showsFields(), canvas.showsMethods(), canvas.isPublicOnly());
        Path destination = path;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                if (snapshot instanceof java.awt.image.BufferedImage image) ImageIO.write(image, "png", destination.toFile());
                else Files.writeString(destination, (String) snapshot, StandardCharsets.UTF_8);
                return null;
            }
            @Override protected void done() {
                if (disposed) return;
                try { get(); status.setText("Сохранено: " + destination); }
                catch (Exception error) { Messages.showErrorDialog(project, error.getMessage(), "Не удалось сохранить диаграмму"); }
            }
        }.execute();
    }
    @Override public void dispose() {
        disposed = true; generation++;
        if (pending != null) pending.cancel();
        ToolTipManager.sharedInstance().unregisterComponent(canvas);
    }
}
