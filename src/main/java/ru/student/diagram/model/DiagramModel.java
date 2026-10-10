package ru.student.diagram.model;

import java.util.*;

/** Immutable snapshot: the drawing layer never holds live PSI objects. */
public record DiagramModel(List<Node> nodes, List<Edge> edges, List<String> warnings) {
    public DiagramModel {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        warnings = List.copyOf(warnings);
    }

    public enum Relation { EXTENDS, IMPLEMENTS, ASSOCIATION, DEPENDENCY }
    public record Member(String text, String visibility, boolean isStatic,
                         boolean isAbstract, int offset) {}
    public record Node(String id, String name, String packageName, String kind,
                       boolean isAbstract, List<Member> fields, List<Member> methods,
                       String sourcePath, int offset, boolean selected) {
        public Node {
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
    }
    public record Edge(String source, String target, Relation relation, String label) {}

    public DiagramModel filter(boolean related, boolean dependencies) {
        List<Node> visible = nodes.stream().filter(n -> related || n.selected()).toList();
        Set<String> ids = new HashSet<>();
        visible.forEach(n -> ids.add(n.id()));
        return new DiagramModel(visible, edges.stream().filter(e ->
                ids.contains(e.source()) && ids.contains(e.target())
                        && (dependencies || e.relation() != Relation.DEPENDENCY)).toList(), warnings);
    }
}
