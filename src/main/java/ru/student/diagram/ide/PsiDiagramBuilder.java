package ru.student.diagram.ide;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import ru.student.diagram.model.DiagramModel;
import java.util.*;

/** Called only inside a cancellable smart read action. */
public final class PsiDiagramBuilder {
    public static final int MAX_CLASSES = 120;
    public record Selection(List<String> urls, String onlyClass) {
        public Selection { urls = List.copyOf(urls); }
    }

    public static DiagramModel build(Project project, Selection selection) {
        Map<String, PsiClass> selected = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int[] count = {0};
        for (String url : selection.urls()) {
            VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
            if (file != null) collect(project, file, selection.onlyClass(), selected, visited, count);
        }
        if (selected.size() >= MAX_CLASSES || count[0] >= 2000)
            warnings.add("Достигнут лимит: 120 классов / 2000 файлов. Выберите меньшую папку.");
        Map<String, PsiClass> all = new LinkedHashMap<>(selected);
        LinkedHashMap<String, DiagramModel.Edge> edges = new LinkedHashMap<>();
        // Snapshot only direct neighbors; following neighbors recursively would explode the graph.
        for (var entry : selected.entrySet()) {
            ProgressManager.checkCanceled();
            PsiClass clazz = entry.getValue();
            for (PsiClassType type : clazz.getExtendsListTypes())
                connect(clazz, type.resolve(), DiagramModel.Relation.EXTENDS, "", selected, all, edges, project);
            for (PsiClassType type : clazz.getImplementsListTypes())
                connect(clazz, type.resolve(), DiagramModel.Relation.IMPLEMENTS, "", selected, all, edges, project);
            for (PsiField field : clazz.getFields()) {
                boolean many = field.getType() instanceof PsiArrayType;
                for (PsiClass target : types(field.getType()))
                    connect(clazz, target, DiagramModel.Relation.ASSOCIATION,
                            field.getName() + (many ? " [*]" : ""), selected, all, edges, project);
            }
            clazz.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override public void visitClass(PsiClass nested) {
                    if (nested == clazz) super.visitClass(nested);
                }
                @Override public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
                    ProgressManager.checkCanceled();
                    PsiElement resolved = reference.resolve();
                    if (resolved instanceof PsiClass target)
                        connect(clazz, target, DiagramModel.Relation.DEPENDENCY, "",
                                selected, all, edges, project);
                    super.visitReferenceElement(reference);
                }
                @Override public void visitReferenceExpression(PsiReferenceExpression reference) {
                    ProgressManager.checkCanceled();
                    PsiElement resolved = reference.resolve();
                    if (resolved instanceof PsiMember member)
                        connect(clazz, member instanceof PsiClass c ? c : member.getContainingClass(),
                                DiagramModel.Relation.DEPENDENCY, "", selected, all, edges, project);
                    super.visitReferenceExpression(reference);
                }
            });
        }
        List<DiagramModel.Node> nodes = new ArrayList<>();
        for (var entry : all.entrySet()) {
            boolean original = selected.containsKey(entry.getKey());
            nodes.add(node(entry.getValue(), original));
        }
        Set<String> strong = new HashSet<>();
        edges.values().stream().filter(e -> e.relation() != DiagramModel.Relation.DEPENDENCY)
                .forEach(e -> strong.add(e.source() + "\n" + e.target()));
        List<DiagramModel.Edge> clean = edges.values().stream()
                .filter(e -> e.relation() != DiagramModel.Relation.DEPENDENCY
                        || !strong.contains(e.source() + "\n" + e.target())).toList();
        if (all.size() >= MAX_CLASSES * 2)
            warnings.add("Показаны не все соседние классы: лимит 240 узлов.");
        return new DiagramModel(nodes, clean, warnings);
    }

    private static void collect(Project project, VirtualFile file, String onlyClass,
                                Map<String, PsiClass> result, Set<String> visited, int[] count) {
        ProgressManager.checkCanceled();
        if (result.size() >= MAX_CLASSES || count[0] >= 2000 || !visited.add(file.getUrl())) return;
        if (ProjectFileIndex.getInstance(project).isExcluded(file)) return;
        if (file.isDirectory()) {
            if (Set.of(".git", ".idea", "target", "out", "build", "node_modules").contains(file.getName())) return;
            VirtualFile[] children = file.getChildren();
            Arrays.sort(children, Comparator.comparing(VirtualFile::getName));
            for (VirtualFile child : children) collect(project, child, onlyClass, result, visited, count);
        } else {
            count[0]++;
            if (!"java".equalsIgnoreCase(file.getExtension())) return;
            PsiFile psi = PsiManager.getInstance(project).findFile(file);
            if (!(psi instanceof PsiJavaFile)) return;
            for (PsiClass clazz : PsiTreeUtil.findChildrenOfType(psi, PsiClass.class)) {
                if (clazz.getQualifiedName() == null || clazz instanceof PsiTypeParameter) continue;
                if (onlyClass != null && !onlyClass.equals(clazz.getQualifiedName())) continue;
                if (result.size() < MAX_CLASSES) result.put(clazz.getQualifiedName(), clazz);
            }
        }
    }

    private static void connect(PsiClass from, PsiClass to, DiagramModel.Relation relation,
                                String label, Map<String, PsiClass> selected,
                                Map<String, PsiClass> all, Map<String, DiagramModel.Edge> edges,
                                Project project) {
        if (to == null || to instanceof PsiTypeParameter || to.getQualifiedName() == null) return;
        String source = from.getQualifiedName(), target = to.getQualifiedName();
        if (source == null || source.equals(target) || target.equals("java.lang.Object")) return;
        PsiFile targetFile = to.getContainingFile();
        VirtualFile virtualFile = targetFile == null ? null : targetFile.getVirtualFile();
        boolean inProject = virtualFile != null && ProjectFileIndex.getInstance(project).isInContent(virtualFile);
        // External base classes/interfaces are useful; all other library dependencies are omitted.
        if (!selected.containsKey(target) && !inProject
                && relation != DiagramModel.Relation.EXTENDS && relation != DiagramModel.Relation.IMPLEMENTS) return;
        if (!all.containsKey(target) && all.size() >= MAX_CLASSES * 2) return;
        all.putIfAbsent(target, to);
        String key = source + "\n" + target + "\n" + relation;
        var old = edges.get(key);
        if (old != null && !label.isBlank() && !old.label().contains(label))
            label = old.label().isBlank() ? label : old.label() + ", " + label;
        else if (old != null) label = old.label();
        edges.put(key, new DiagramModel.Edge(source, target, relation, label));
    }

    private static List<PsiClass> types(PsiType type) {
        List<PsiClass> result = new ArrayList<>();
        if (type instanceof PsiArrayType array) result.addAll(types(array.getComponentType()));
        else if (type instanceof PsiClassType clazz) {
            PsiClass resolved = clazz.resolve();
            if (resolved != null) result.add(resolved);
            for (PsiType parameter : clazz.getParameters()) result.addAll(types(parameter));
        } else if (type instanceof PsiWildcardType wildcard && wildcard.getBound() != null)
            result.addAll(types(wildcard.getBound()));
        return result;
    }

    private static DiagramModel.Node node(PsiClass clazz, boolean selected) {
        List<DiagramModel.Member> fields = new ArrayList<>(), methods = new ArrayList<>();
        for (PsiField field : clazz.getFields())
            fields.add(member(field, field.getName() + ": " + field.getType().getPresentableText()));
        for (PsiMethod method : clazz.getMethods()) {
            List<String> parameters = new ArrayList<>();
            for (PsiParameter parameter : method.getParameterList().getParameters())
                parameters.add(parameter.getName() + ": " + parameter.getType().getPresentableText());
            String text = method.getName() + "(" + String.join(", ", parameters) + ")";
            if (!method.isConstructor() && method.getReturnType() != null)
                text += ": " + method.getReturnType().getPresentableText();
            methods.add(member(method, text));
        }
        PsiFile file = clazz.getContainingFile();
        String pkg = file instanceof PsiJavaFile javaFile ? javaFile.getPackageName() : "";
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        String kind = clazz.isAnnotationType() ? "annotation" : clazz.isInterface() ? "interface"
                : clazz.isEnum() ? "enum" : clazz.isRecord() ? "record" : "class";
        return new DiagramModel.Node(clazz.getQualifiedName(), clazz.getName(), pkg, kind,
                clazz.hasModifierProperty(PsiModifier.ABSTRACT), fields, methods,
                virtualFile == null ? "" : virtualFile.getUrl(), clazz.getTextOffset(), selected);
    }

    private static DiagramModel.Member member(PsiMember member, String text) {
        String visibility = member.hasModifierProperty(PsiModifier.PUBLIC) ? "+"
                : member.hasModifierProperty(PsiModifier.PROTECTED) ? "#"
                : member.hasModifierProperty(PsiModifier.PRIVATE) ? "-" : "~";
        return new DiagramModel.Member(text, visibility, member.hasModifierProperty(PsiModifier.STATIC),
                member.hasModifierProperty(PsiModifier.ABSTRACT), member.getTextOffset());
    }
}
