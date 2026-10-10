package ru.student.diagram.model;

import java.awt.geom.Rectangle2D;
import java.util.*;

/** Deterministic layered layout; cycles are collapsed before assigning ranks. */
public final class DiagramLayout {
    private DiagramLayout() {}

    public static Map<String, Rectangle2D.Double> arrange(
            DiagramModel model, Map<String, Rectangle2D.Double> sizes) {
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        model.nodes().forEach(n -> parents.put(n.id(), new LinkedHashSet<>()));
        for (var edge : model.edges()) {
            if ((edge.relation() == DiagramModel.Relation.EXTENDS
                    || edge.relation() == DiagramModel.Relation.IMPLEMENTS)
                    && parents.containsKey(edge.source()) && parents.containsKey(edge.target())) {
                parents.get(edge.source()).add(edge.target());
            }
        }
        // Tarjan components also make malformed / temporarily cyclic source safe.
        List<List<String>> components = components(parents);
        Map<String, Integer> componentOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++)
            for (String id : components.get(i)) componentOf.put(id, i);
        Map<Integer, Set<Integer>> componentParents = new HashMap<>();
        for (var entry : parents.entrySet()) {
            int from = componentOf.get(entry.getKey());
            for (String parent : entry.getValue()) {
                int to = componentOf.get(parent);
                if (from != to) componentParents.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            }
        }
        Map<Integer, Integer> ranks = new HashMap<>();
        Map<Integer, List<DiagramModel.Node>> rows = new TreeMap<>();
        for (var node : model.nodes()) {
            int rank = rank(componentOf.get(node.id()), componentParents, ranks);
            rows.computeIfAbsent(rank, k -> new ArrayList<>()).add(node);
        }
        Map<String, Rectangle2D.Double> result = new LinkedHashMap<>();
        double y = 60;
        for (var row : rows.values()) {
            row.sort(Comparator.comparing(DiagramModel.Node::packageName)
                    .thenComparing(DiagramModel.Node::name));
            // A wide unrelated selection wraps to keep the initial view readable.
            for (int start = 0; start < row.size(); start += 4) {
                double x = 60, height = 0;
                for (var node : row.subList(start, Math.min(start + 4, row.size()))) {
                    var size = sizes.get(node.id());
                    result.put(node.id(), new Rectangle2D.Double(x, y, size.width, size.height));
                    x += size.width + 90;
                    height = Math.max(height, size.height);
                }
                y += height + 110;
            }
        }
        return result;
    }

    private static int rank(int node, Map<Integer, Set<Integer>> parents,
                            Map<Integer, Integer> memo) {
        if (memo.containsKey(node)) return memo.get(node);
        int value = 0;
        for (int parent : parents.getOrDefault(node, Set.of()))
            value = Math.max(value, rank(parent, parents, memo) + 1);
        memo.put(node, value);
        return value;
    }

    private static List<List<String>> components(Map<String, Set<String>> graph) {
        List<List<String>> result = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> active = new HashSet<>();
        for (String vertex : graph.keySet())
            if (!index.containsKey(vertex)) visit(vertex, graph, index, low, stack, active, result);
        return result;
    }

    private static void visit(String v, Map<String, Set<String>> graph,
                              Map<String, Integer> index, Map<String, Integer> low,
                              Deque<String> stack, Set<String> active,
                              List<List<String>> result) {
        index.put(v, index.size()); low.put(v, index.get(v));
        stack.push(v); active.add(v);
        for (String w : graph.get(v)) {
            if (!index.containsKey(w)) {
                visit(w, graph, index, low, stack, active, result);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (active.contains(w)) low.put(v, Math.min(low.get(v), index.get(w)));
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do { w = stack.pop(); active.remove(w); component.add(w); } while (!w.equals(v));
            result.add(component);
        }
    }
}
