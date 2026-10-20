package ru.student.diagram.ui;

import java.util.ArrayList;
import java.util.List;

/** Values needed to restore an editable diagram without retaining Swing or PSI objects. */
public final class DiagramViewState {
    public List<CardPosition> positions = new ArrayList<>();
    public List<String> hidden = new ArrayList<>();
    public List<EditedEdge> edits = new ArrayList<>();
    public boolean showFields = true;
    public boolean showMethods = true;
    public boolean publicOnly;
    public boolean related;
    public boolean dependencies = true;
    public boolean dark;
    public boolean hasViewport;
    public double zoom = 1;
    public double centerX;
    public double centerY;

    public static final class CardPosition {
        public String id = "";
        public double x;
        public double y;
    }

    public static final class EditedEdge {
        public String source = "";
        public String target = "";
        public String relation = "";
        public String label = "";
        public AnchorPosition start;
        public AnchorPosition end;
        public List<Waypoint> points = new ArrayList<>();
    }

    public static final class AnchorPosition {
        public String side = "";
        public double fraction;
    }

    public static final class Waypoint {
        public double x;
        public double y;
    }
}
