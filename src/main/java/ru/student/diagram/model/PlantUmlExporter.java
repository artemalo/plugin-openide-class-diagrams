package ru.student.diagram.model;

import java.util.*;

public final class PlantUmlExporter {
    private PlantUmlExporter() {}
    public static String export(DiagramModel model, boolean fields, boolean methods, boolean publicOnly) {
        StringBuilder out = new StringBuilder("@startuml\nhide empty members\nskinparam classAttributeIconSize 0\n");
        Map<String, String> aliases = new LinkedHashMap<>();
        for (var node : model.nodes()) {
            String alias = "N" + aliases.size();
            aliases.put(node.id(), alias);
            String kind = switch (node.kind()) {
                case "interface", "annotation" -> "interface";
                case "enum" -> "enum";
                default -> node.isAbstract() ? "abstract class" : "class";
            };
            out.append(kind).append(" \"").append(quote(node.id())).append("\" as ").append(alias).append(" {\n");
            if (fields) members(out, node.fields(), publicOnly);
            if (methods) members(out, node.methods(), publicOnly);
            out.append("}\n");
        }
        for (var edge : model.edges()) {
            if (!aliases.containsKey(edge.source()) || !aliases.containsKey(edge.target())) continue;
            String arrow = switch (edge.relation()) {
                case EXTENDS -> "--|>";
                case IMPLEMENTS -> "..|>";
                case ASSOCIATION -> "-->";
                case DEPENDENCY -> "..>";
            };
            out.append(aliases.get(edge.source())).append(' ').append(arrow).append(' ')
                    .append(aliases.get(edge.target()));
            if (!edge.label().isBlank()) out.append(" : ").append(quote(edge.label()));
            out.append('\n');
        }
        return out.append("@enduml\n").toString();
    }
    private static void members(StringBuilder out, List<DiagramModel.Member> members, boolean publicOnly) {
        for (var member : members) {
            if (publicOnly && !member.visibility().equals("+")) continue;
            out.append("  ");
            if (member.isStatic()) out.append("{static} ");
            if (member.isAbstract()) out.append("{abstract} ");
            out.append(member.visibility()).append(' ').append(member.text().replace("\n", " ")).append('\n');
        }
    }
    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
    }
}
