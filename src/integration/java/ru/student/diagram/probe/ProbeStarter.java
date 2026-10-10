package ru.student.diagram.probe;

import com.intellij.openapi.application.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import ru.student.diagram.ide.PsiDiagramBuilder;
import ru.student.diagram.model.*;
import ru.student.diagram.ui.DiagramCanvas;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;

/** Test-only application starter; never packaged in the user plugin. */
public final class ProbeStarter implements ApplicationStarter {
    @Override public boolean isHeadless() { return true; }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }
    @Override public void main(List<String> args) {
        Path output = Path.of(args.get(2));
        try {
            Files.createDirectories(output);
            Project project = ProjectManagerEx.getInstanceEx().openProject(Path.of(args.get(1)), OpenProjectTask.build());
            if (project == null) throw new AssertionError("Fixture project did not open");
            var root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(args.get(1), "src"));
            if (root == null) throw new AssertionError("Fixture source folder missing");
            DiagramModel model = DumbService.getInstance(project).runReadActionInSmartMode(() ->
                    PsiDiagramBuilder.build(project, new PsiDiagramBuilder.Selection(List.of(root.getUrl()), null)));
            StringBuilder summary = new StringBuilder();
            for (var n : model.nodes()) summary.append("NODE ").append(n.id()).append(' ').append(n.kind()).append('\n');
            for (var e : model.edges()) summary.append("EDGE ").append(e.source()).append(' ').append(e.relation()).append(' ').append(e.target()).append(' ').append(e.label()).append('\n');
            Files.writeString(output.resolve("psi-model.txt"), summary);
            Set<String> ids = new HashSet<>();model.nodes().forEach(n -> ids.add(n.id()));
            require(ids.containsAll(Set.of("fixture.Base", "fixture.Repository", "fixture.Order", "fixture.Customer",
                    "fixture.OrderRepository", "fixture.Runner", "fixture.Status", "fixture.Address", "fixture.Customer.Note")), "all named/nested types");
            edge(model,"fixture.OrderRepository",DiagramModel.Relation.EXTENDS,"fixture.Base");
            edge(model,"fixture.OrderRepository",DiagramModel.Relation.IMPLEMENTS,"fixture.Repository");
            edge(model,"fixture.Order",DiagramModel.Relation.ASSOCIATION,"fixture.Customer");
            edge(model,"fixture.OrderRepository",DiagramModel.Relation.ASSOCIATION,"fixture.Order");
            edge(model,"fixture.Runner",DiagramModel.Relation.DEPENDENCY,"fixture.OrderRepository");
            require(model.nodes().stream().anyMatch(n -> n.id().equals("fixture.Status") && n.kind().equals("enum")),"enum");
            require(model.nodes().stream().anyMatch(n -> n.id().equals("fixture.Address") && n.kind().equals("record")),"record");
            require(model.nodes().stream().filter(n -> n.id().equals("fixture.Order"))
                    .flatMap(n -> n.fields().stream()).anyMatch(f -> f.text().equals("customer: Customer") && f.visibility().equals("-")),"private field signature");
            require(!ids.contains("fixture.Customer.$anonymous"),"anonymous classes omitted");
            AnAction action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("LocalClassDiagrams.Show");
            require(action != null,"production menu action registered");
            var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, new com.intellij.openapi.vfs.VirtualFile[]{root}).build();
            AnActionEvent event = AnActionEvent.createFromDataContext("ProjectViewPopup", new Presentation(), context);
            ReadAction.run(() -> action.update(event));
            require(event.getPresentation().isEnabledAndVisible(),"action available for selected source folder");
            var labRoot = root.findFileByRelativePath("ru/student/lab1");
            DiagramModel labModel = labRoot == null ? null : DumbService.getInstance(project).runReadActionInSmartMode(() ->
                    PsiDiagramBuilder.build(project, new PsiDiagramBuilder.Selection(List.of(labRoot.getUrl()), null)));
            if (labModel != null) {
                require(labModel.filter(false, true).nodes().size() == 2, "lab selection includes Fraction and Main only");
                edge(labModel,"ru.student.lab1.Main",DiagramModel.Relation.DEPENDENCY,"ru.student.lab1.Fraction");
                edge(labModel,"ru.student.lab1.Fraction",DiagramModel.Relation.IMPLEMENTS,"java.lang.Comparable");
            }
            SwingUtilities.invokeAndWait(() -> {
                try {
                    DiagramCanvas canvas = new DiagramCanvas();canvas.setSize(1600,1000);canvas.setModel(model);
                    ImageIO.write(canvas.toImage(),"png",output.resolve("actual-psi-diagram.png").toFile());
                    Files.writeString(output.resolve("actual-psi-diagram.svg"),canvas.toSvg());
                    if (labModel != null) {
                        canvas.setModel(labModel);
                        canvas.setOptions(true,true,true,true,true);
                        ImageIO.write(canvas.toImage(),"png",output.resolve("lab1-diagram.png").toFile());
                        Files.writeString(output.resolve("lab1-diagram.svg"),canvas.toSvg());
                    }
                } catch(Exception error) {throw new RuntimeException(error);}
            });
            Files.writeString(output.resolve("SUCCESS.txt"), "OpenIDE loaded the production plugin. PSI and menu registration: 12 checks passed.\n"
                    + (labModel == null ? "" : "Lab 1: 3 checks passed.\n") + summary);
            System.out.println("INTEGRATION SUCCESS");System.exit(0);
        } catch(Throwable error) {
            try {Files.writeString(output.resolve("FAILURE.txt"),error.toString()+"\n"+Arrays.toString(error.getStackTrace()));} catch(Exception ignored) {}
            error.printStackTrace();System.exit(1);
        }
    }
    private static void edge(DiagramModel model,String from,DiagramModel.Relation relation,String to) {
        require(model.edges().stream().anyMatch(e -> e.source().equals(from)&&e.target().equals(to)&&e.relation()==relation),from+" "+relation+" "+to);
    }
    private static void require(boolean value,String check) {if(!value)throw new AssertionError(check);}
}
