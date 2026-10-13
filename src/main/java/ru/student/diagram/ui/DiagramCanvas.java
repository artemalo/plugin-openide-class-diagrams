package ru.student.diagram.ui;

import ru.student.diagram.model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;

/** Swing renderer with no IDE dependencies; also used by rendering tests. */
public final class DiagramCanvas extends JComponent {
    public record Row(String text, boolean isStatic, boolean isAbstract, int offset) {}
    public record Card(DiagramModel.Node node, List<Row> fields, List<Row> methods,
                       double width, double height) {}
    private DiagramModel original = new DiagramModel(List.of(), List.of(), List.of());
    private DiagramModel visible = original;
    private final Map<String, Card> cards = new LinkedHashMap<>();
    private final Map<String, Rectangle2D.Double> positions = new LinkedHashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private static final class EdgeEdit {
        EdgeRouter.Anchor start, end;
        final List<Point2D.Double> points = new ArrayList<>();
    }
    private record Handle(int kind, int index) {} // 0/1: endpoints, 2: waypoint
    private final Map<DiagramModel.Edge, EdgeEdit> edits = new HashMap<>();
    private final Map<DiagramModel.Edge, EdgeRouter.Route> routes = new LinkedHashMap<>();
    private DiagramModel.Edge selectedEdge;
    private int selectedPoint = -1;
    private Handle draggingHandle;
    private boolean panning, draggingEdge;
    private boolean hasManualArrangement;
    private Point pressPoint;
    private boolean showFields = true, showMethods = true, publicOnly, related, dependencies = true;
    private boolean dark;
    private String selected, search = "";
    private double zoom = 1, panX = 0, panY = 0;
    private Point lastMouse;
    private String dragging;
    private Point2D.Double dragOrigin;
    private Double guideX, guideY;
    private BiConsumer<String, Integer> navigate = (path, offset) -> {};
    private Runnable changed = () -> {};
    private static final Font TEXT = new Font("Dialog", Font.PLAIN, 12);
    private static final Font TITLE = new Font("Dialog", Font.BOLD, 15);
    private static final Font META = new Font("Dialog", Font.PLAIN, 11);
    private static final int HEADER = 69, ROW = 21;
    private static final int SNAP_PIXELS = 8;
    private record AxisSnap(double offset, double guide) {}

    public DiagramCanvas() {
        setOpaque(true); setFocusable(true); setPreferredSize(new Dimension(1050, 720));
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                Point2D world = world(e.getPoint());
                lastMouse = e.getPoint(); pressPoint = e.getPoint();
                panning = SwingUtilities.isMiddleMouseButton(e);
                dragging = null; draggingHandle = null; draggingEdge = false;
                dragOrigin = null; guideX = null; guideY = null;
                if (panning) return;
                Handle handle = hitHandle(world);
                if (handle != null) {
                    selected = null; selectedPoint = handle.kind == 2 ? handle.index : -1;
                    if (SwingUtilities.isLeftMouseButton(e)) draggingHandle = handle;
                } else {
                    selected = hit(world); selectedPoint = -1;
                    selectedEdge = selected == null ? hitEdge(world) : null;
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        dragging = selected;
                        if (dragging != null) {
                            Rectangle2D.Double r = positions.get(dragging);
                            dragOrigin = new Point2D.Double(r.x, r.y);
                        }
                        draggingEdge = selectedEdge != null;
                        panning = selected == null && selectedEdge == null;
                        Handle newlySelectedHandle = hitHandle(world);
                        if (newlySelectedHandle != null) {
                            draggingHandle = newlySelectedHandle; draggingEdge = false;
                            selectedPoint = newlySelectedHandle.kind == 2 ? newlySelectedHandle.index : -1;
                        }
                    }
                }
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) popup(e);
                repaint(); changed.run();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger() && !SwingUtilities.isRightMouseButton(e)) popup(e);
                lastMouse = null; dragging = null; dragOrigin = null; draggingHandle = null;
                draggingEdge = false; panning = false; guideX = null; guideY = null;
                repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (lastMouse == null) return;
                double dx = e.getX() - lastMouse.x, dy = e.getY() - lastMouse.y;
                if (draggingEdge && pressPoint.distance(e.getPoint()) >= 3) {
                    selectedPoint = addWaypoint(selectedEdge, world(pressPoint));
                    draggingHandle = new Handle(2, selectedPoint); draggingEdge = false;
                }
                if (draggingHandle != null && selectedEdge != null) {
                    hasManualArrangement = true;
                    EdgeEdit edit = edits.computeIfAbsent(selectedEdge, key -> new EdgeEdit());
                    Point2D point = world(e.getPoint());
                    switch (draggingHandle.kind) {
                        case 0 -> edit.start = EdgeRouter.Anchor.nearest(positions.get(selectedEdge.source()), point);
                        case 1 -> edit.end = EdgeRouter.Anchor.nearest(positions.get(selectedEdge.target()), point);
                        case 2 -> edit.points.set(draggingHandle.index, new Point2D.Double(point.getX(), point.getY()));
                    }
                    invalidateRoutes();
                } else if (dragging != null) {
                    hasManualArrangement = true;
                    Rectangle2D.Double r = positions.get(dragging);
                    r.x = dragOrigin.x + (e.getX() - pressPoint.x) / zoom;
                    r.y = dragOrigin.y + (e.getY() - pressPoint.y) / zoom;
                    guideX = null; guideY = null;
                    if (e.isShiftDown()) {
                        AxisSnap x = snapAxis(r, true);
                        AxisSnap y = snapAxis(r, false);
                        if (x != null) { r.x += x.offset(); guideX = x.guide(); }
                        if (y != null) { r.y += y.offset(); guideY = y.guide(); }
                    }
                    invalidateRoutes();
                } else if (panning) { panX += dx; panY += dy; }
                lastMouse = e.getPoint(); repaint(); changed.run();
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    if (selected != null) navigateAt(world(e.getPoint()));
                    else if (selectedEdge != null && hitHandle(world(e.getPoint())) == null) {
                        selectedPoint = addWaypoint(selectedEdge, world(e.getPoint())); repaint(); changed.run();
                    }
                }
            }
            @Override public void mouseMoved(MouseEvent e) {
                Point2D point = world(e.getPoint());
                setCursor(Cursor.getPredefinedCursor(hitHandle(point) != null ? Cursor.CROSSHAIR_CURSOR
                        : hit(point) != null || hitEdge(point) != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                scale(Math.pow(1.12, -e.getPreciseWheelRotation()), e.getPoint());
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        getActionMap().put("remove", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (selectedEdge != null) {
                    if (selectedPoint >= 0) removeWaypoint(); else resetSelectedArrow();
                } else hideSelected();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "deselect");
        getActionMap().put("deselect", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                selected = null; selectedEdge = null; selectedPoint = -1; repaint(); changed.run();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "autoArrow");
        getActionMap().put("autoArrow", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { resetSelectedArrow(); }
        });
    }

    public void setModel(DiagramModel model) {
        original = model; edits.keySet().retainAll(model.edges()); rebuild(!hasManualArrangement);
    }
    public DiagramModel visibleModel() { return visible; }
    public void onNavigate(BiConsumer<String, Integer> callback) { navigate = callback; }
    public void onChange(Runnable callback) { changed = callback; }
    public void setDark(boolean value) { dark = value; repaint(); }
    public boolean isDark() { return dark; }
    public double getZoom() { return zoom; }
    public boolean showsFields() { return showFields; }
    public boolean showsMethods() { return showMethods; }
    public boolean isPublicOnly() { return publicOnly; }
    public void setOptions(boolean fields, boolean methods, boolean onlyPublic,
                           boolean includeRelated, boolean includeDependencies) {
        showFields = fields; showMethods = methods; publicOnly = onlyPublic;
        related = includeRelated; dependencies = includeDependencies; rebuild(!hasManualArrangement);
    }
    public void search(String text) { search = text.strip().toLowerCase(Locale.ROOT); repaint(); }
    public void layoutGraph() { hidden.clear(); edits.clear(); hasManualArrangement = false; rebuild(true); fit(); }
    public void resetArrows() { edits.clear(); selectedPoint = -1; invalidateRoutes(); repaint(); changed.run(); }
    public void resetSelectedArrow() {
        if (selectedEdge == null) return;
        edits.remove(selectedEdge); selectedPoint = -1; invalidateRoutes(); repaint(); changed.run();
    }
    public String interactionHint() {
        if (selectedEdge == null) return "Перетащите линию стрелки, чтобы изменить маршрут.";
        String mode = edits.containsKey(selectedEdge) ? "ручной маршрут" : "автоматический маршрут";
        String obstruction = edgeRoute(selectedEdge).obstructed() ? " Уберите точку из карточки или раздвиньте классы." : "";
        return "Стрелка: " + mode + ". R: сброс; Delete: удалить точку или сбросить маршрут." + obstruction;
    }
    public void zoomIn() { scale(1.2, new Point(getWidth() / 2, getHeight() / 2)); }
    public void zoomOut() { scale(1 / 1.2, new Point(getWidth() / 2, getHeight() / 2)); }
    public void actualSize() { scale(1 / zoom, new Point(getWidth() / 2, getHeight() / 2)); }

    private void rebuild(boolean layout) {
        DiagramModel filtered = original.filter(related, dependencies);
        List<DiagramModel.Node> nodes = filtered.nodes().stream().filter(n -> !hidden.contains(n.id())).toList();
        visible = new DiagramModel(nodes, filtered.edges().stream().filter(e ->
                !hidden.contains(e.source()) && !hidden.contains(e.target())).toList(), original.warnings());
        cards.clear();
        Map<String, Rectangle2D.Double> sizes = new LinkedHashMap<>();
        FontMetrics metrics = getFontMetrics(TEXT);
        for (var node : visible.nodes()) {
            List<Row> fields = rows(showFields ? node.fields() : List.of());
            List<Row> methods = rows(showMethods ? node.methods() : List.of());
            int width = Math.max(295, getFontMetrics(TITLE).stringWidth(node.name()) + 62);
            width = Math.max(width, Math.min(530, getFontMetrics(META).stringWidth(node.packageName()) + 32));
            for (Row row : concat(fields, methods)) width = Math.max(width, Math.min(530, metrics.stringWidth(row.text()) + 32));
            int height = HEADER + 12;
            if (!fields.isEmpty()) height += fields.size() * ROW + 10;
            if (!methods.isEmpty()) height += methods.size() * ROW + 10;
            cards.put(node.id(), new Card(node, fields, methods, width, height));
            sizes.put(node.id(), new Rectangle2D.Double(0, 0, width, height));
        }
        Map<String, Rectangle2D.Double> arranged = DiagramLayout.arrange(visible, sizes);
        if (layout) positions.clear();
        for (var entry : sizes.entrySet()) {
            var previous = positions.get(entry.getKey());
            var size = entry.getValue();
            var candidate = previous == null ? arranged.get(entry.getKey())
                    : new Rectangle2D.Double(previous.x, previous.y, size.width, size.height);
            if (previous == null && !layout) {
                // A newly revealed neighbor must not land on a card that the user moved.
                while (positions.values().stream().anyMatch(candidate::intersects)) candidate.x += candidate.width + 90;
            }
            positions.put(entry.getKey(), candidate);
        }
        // Keep hidden cards' coordinates so toggling a filter does not discard a manual arrangement.
        Set<String> originalIds = new HashSet<>(); original.nodes().forEach(node -> originalIds.add(node.id()));
        positions.keySet().retainAll(originalIds);
        selected = null; selectedEdge = null; selectedPoint = -1;
        invalidateRoutes(); repaint(); changed.run();
    }
    private List<Row> rows(List<DiagramModel.Member> members) {
        return members.stream().filter(m -> !publicOnly || m.visibility().equals("+"))
                .map(m -> new Row(m.visibility() + " " + m.text(), m.isStatic(), m.isAbstract(), m.offset())).toList();
    }
    private static List<Row> concat(List<Row> a, List<Row> b) {
        List<Row> result = new ArrayList<>(a); result.addAll(b); return result;
    }

    public void fit() {
        Rectangle2D b = diagramBounds();
        if (getWidth() < 50 || getHeight() < 50) return;
        zoom = Math.max(0.12, Math.min(1.25, Math.min((getWidth() - 50) / b.getWidth(),
                (getHeight() - 50) / b.getHeight())));
        panX = (getWidth() - b.getWidth() * zoom) / 2 - b.getX() * zoom;
        panY = (getHeight() - b.getHeight() * zoom) / 2 - b.getY() * zoom;
        repaint(); changed.run();
    }
    private void scale(double factor, Point anchor) {
        Point2D world = world(anchor);
        zoom = Math.max(0.12, Math.min(3.0, zoom * factor));
        panX = anchor.x - world.getX() * zoom; panY = anchor.y - world.getY() * zoom;
        repaint(); changed.run();
    }
    private Point2D world(Point p) { return new Point2D.Double((p.x - panX) / zoom, (p.y - panY) / zoom); }
    private AxisSnap snapAxis(Rectangle2D.Double moving, boolean horizontal) {
        double start = horizontal ? moving.x : moving.y;
        double size = horizontal ? moving.width : moving.height;
        double[] movingAnchors = {start, start + size / 2, start + size};
        double best = SNAP_PIXELS / zoom;
        AxisSnap result = null;
        for (String id : cards.keySet()) {
            if (id.equals(dragging)) continue;
            Rectangle2D.Double other = positions.get(id);
            double otherStart = horizontal ? other.x : other.y;
            double otherSize = horizontal ? other.width : other.height;
            double[] otherAnchors = {otherStart, otherStart + otherSize / 2, otherStart + otherSize};
            for (double movingAnchor : movingAnchors) for (double otherAnchor : otherAnchors) {
                double offset = otherAnchor - movingAnchor;
                if (Math.abs(offset) <= best) {
                    best = Math.abs(offset);
                    result = new AxisSnap(offset, otherAnchor);
                }
            }
        }
        return result;
    }
    private String hit(Point2D point) {
        List<String> ids = new ArrayList<>(cards.keySet()); Collections.reverse(ids);
        for (String id : ids) if (positions.get(id).contains(point)) return id;
        return null;
    }
    private void hideSelected() {
        if (selected == null) return;
        hidden.add(selected); rebuild(false); fit();
    }
    private void popup(MouseEvent e) {
        if (selectedEdge != null) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem add = new JMenuItem("Добавить точку изгиба");
            add.addActionListener(a -> { selectedPoint = addWaypoint(selectedEdge, world(e.getPoint())); repaint(); changed.run(); }); menu.add(add);
            if (selectedPoint >= 0) {
                JMenuItem remove = new JMenuItem("Удалить точку изгиба");
                remove.addActionListener(a -> removeWaypoint()); menu.add(remove);
            }
            JMenuItem reset = new JMenuItem("Автоматический маршрут (R)");
            reset.addActionListener(a -> resetSelectedArrow()); menu.add(reset);
            menu.show(this, e.getX(), e.getY()); return;
        }
        if (selected == null) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem go = new JMenuItem("Перейти к исходнику");
        go.addActionListener(a -> navigateAt(world(e.getPoint()))); menu.add(go);
        JMenuItem hide = new JMenuItem("Убрать с диаграммы");
        hide.addActionListener(a -> hideSelected()); menu.add(hide);
        menu.show(this, e.getX(), e.getY());
    }
    private void navigateAt(Point2D point) {
        if (selected == null) return;
        Card card = cards.get(selected); int offset = card.node().offset();
        double y = point.getY() - positions.get(selected).y;
        int cursor = HEADER + 10;
        for (List<Row> group : List.of(card.fields(), card.methods())) {
            for (Row row : group) {
                if (y >= cursor - 3 && y <= cursor + ROW - 3) offset = row.offset();
                cursor += ROW;
            }
            if (!group.isEmpty()) cursor += 10;
        }
        navigate.accept(card.node().sourcePath(), offset);
    }
    @Override public String getToolTipText(MouseEvent e) {
        String id = hit(world(e.getPoint()));
        if (id == null) {
            var edge = hitEdge(world(e.getPoint()));
            return edge == null ? "Колесо мыши: масштаб; перетаскивание фона: перемещение; двойной щелчок по классу: исходник."
                    : "Перетащите линию стрелки, чтобы изменить маршрут. Двойной щелчок добавляет точку. Концы крепятся к границам классов. R сбрасывает маршрут.";
        }
        Card card = cards.get(id);
        return "<html><b>" + xml(card.node().id()) + "</b><br>"
                + concat(card.fields(), card.methods()).stream().map(r -> xml(r.text()))
                .reduce((a, b) -> a + "<br>" + b).orElse("") + "</html>";
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); hints(g);
        g.setColor(background(dark)); g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(dark ? new Color(0x303644) : new Color(0xe2e7ef));
        double step = 24 * zoom;
        if (step >= 8) for (double x = panX % step; x < getWidth(); x += step)
            for (double y = panY % step; y < getHeight(); y += step) g.fill(new Ellipse2D.Double(x, y, 1.5, 1.5));
        g.translate(panX, panY); g.scale(zoom, zoom); paintGraph(g, dark, true); g.dispose();
    }
    private void paintGraph(Graphics2D g, boolean darkMode, boolean interactive) {
        for (var edge : visible.edges()) paintEdge(g, edge, darkMode, interactive);
        if (interactive && dragging != null) paintGuides(g, darkMode);
        for (var entry : cards.entrySet()) paintCard(g, entry.getValue(), positions.get(entry.getKey()), darkMode, interactive);
        if (interactive) paintHandles(g, darkMode);
    }
    private void paintGuides(Graphics2D g, boolean darkMode) {
        if (guideX == null && guideY == null) return;
        g.setColor(darkMode ? new Color(0x81c7ff) : new Color(0x2274c9));
        g.setStroke(new BasicStroke((float) (1 / zoom), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10, new float[] {(float) (5 / zoom), (float) (4 / zoom)}, 0));
        double left = -panX / zoom, right = (getWidth() - panX) / zoom;
        double top = -panY / zoom, bottom = (getHeight() - panY) / zoom;
        if (guideX != null) g.draw(new Line2D.Double(guideX, top, guideX, bottom));
        if (guideY != null) g.draw(new Line2D.Double(left, guideY, right, guideY));
    }
    private void paintCard(Graphics2D g, Card card, Rectangle2D.Double r, boolean darkMode, boolean interactive) {
        Color accent = accent(card.node().kind());
        g.setColor(new Color(0, 0, 0, darkMode ? 45 : 16));
        g.fill(new RoundRectangle2D.Double(r.x + 2, r.y + 4, r.width, r.height, 12, 12));
        g.setColor(darkMode ? new Color(0x252b38) : Color.WHITE);
        g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 12, 12));
        boolean marked = interactive && (card.node().id().equals(selected)
                || !search.isEmpty() && card.node().id().toLowerCase(Locale.ROOT).contains(search));
        g.setColor(marked ? accent : darkMode ? new Color(0x495367) : new Color(0xcbd5e1));
        g.setStroke(new BasicStroke(marked ? 2.5f : 1));
        g.draw(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 12, 12));
        g.setColor(accent); g.fill(new RoundRectangle2D.Double(r.x + 13, r.y + 13, 27, 27, 7, 7));
        g.setFont(TITLE); g.setColor(Color.WHITE); g.drawString(card.node().kind().substring(0, 1).toUpperCase(Locale.ROOT), (float) r.x + 21, (float) r.y + 32);
        g.setColor(foreground(darkMode));
        g.setFont(card.node().isAbstract() ? TITLE.deriveFont(Font.BOLD | Font.ITALIC) : TITLE);
        g.drawString(elide(card.node().name(), g.getFontMetrics(), r.width - 62), (float) r.x + 49, (float) r.y + 28);
        g.setFont(META); g.setColor(muted(darkMode));
        String tag = card.node().kind() + (card.node().selected() ? "" : " (связанный)");
        g.drawString(tag, (float) r.x + 49, (float) r.y + 44);
        g.drawString(elide(card.node().packageName(), g.getFontMetrics(), r.width - 28), (float) r.x + 14, (float) r.y + 60);
        double y = r.y + HEADER;
        for (List<Row> group : List.of(card.fields(), card.methods())) {
            if (group.isEmpty()) continue;
            g.setColor(darkMode ? new Color(0x424a5b) : new Color(0xe2e8f0));
            g.setStroke(new BasicStroke(1)); g.draw(new Line2D.Double(r.x, y, r.getMaxX(), y)); y += 10;
            for (Row row : group) {
                g.setFont(row.isAbstract() ? TEXT.deriveFont(Font.ITALIC) : TEXT);
                g.setColor(foreground(darkMode)); String text = elide(row.text(), g.getFontMetrics(), r.width - 28);
                g.drawString(text, (float) r.x + 14, (float) y + 14);
                if (row.isStatic()) g.draw(new Line2D.Double(r.x + 14, y + 16, r.x + 14 + g.getFontMetrics().stringWidth(text), y + 16));
                y += ROW;
            }
        }
    }
    private void invalidateRoutes() { routes.clear(); }
    private EdgeRouter.Route edgeRoute(DiagramModel.Edge edge) {
        return routes.computeIfAbsent(edge, key -> {
            Map<String, Rectangle2D.Double> obstacles = new LinkedHashMap<>();
            for (String id : cards.keySet()) obstacles.put(id, positions.get(id));
            EdgeEdit edit = edits.get(edge);
            return EdgeRouter.route(obstacles, edge.source(), edge.target(), portSlot(edge.source(), edge),
                    portSlot(edge.target(), edge), edit == null ? null : edit.start, edit == null ? null : edit.end,
                    edit == null ? List.of() : edit.points);
        });
    }
    /** Geometry snapshots are independent from the editable Swing state. */
    public List<Point2D.Double> edgePoints(DiagramModel.Edge edge) {
        return edgeRoute(edge).points().stream().map(p -> point(p.x,p.y)).toList();
    }
    public Rectangle2D nodeBounds(String id) { return (Rectangle2D) positions.get(id).clone(); }
    public Point toView(Point2D point) { return new Point((int)Math.round(point.getX()*zoom+panX),(int)Math.round(point.getY()*zoom+panY)); }
    public List<Point2D.Double> waypoints(DiagramModel.Edge edge) {
        EdgeEdit edit = edits.get(edge);
        return edit == null ? List.of() : edit.points.stream().map(p -> point(p.x,p.y)).toList();
    }
    private double portSlot(String id, DiagramModel.Edge edge) {
        List<DiagramModel.Edge> incident = visible.edges().stream()
                .filter(e -> e.source().equals(id) || e.target().equals(id)).toList();
        int order = incident.indexOf(edge);
        return (order + 1.0) / (incident.size() + 1.0);
    }
    private DiagramModel.Edge hitEdge(Point2D point) {
        DiagramModel.Edge best = null; double distance = 7 / zoom;
        // Prefer the selected edge if two routes happen to share a short segment.
        List<DiagramModel.Edge> edges = new ArrayList<>(visible.edges());
        if (selectedEdge != null && edges.remove(selectedEdge)) edges.add(0,selectedEdge);
        for (var edge : edges) {
            List<Point2D.Double> points = edgeRoute(edge).points();
            for (int i=1;i<points.size();i++) {
                double d = new Line2D.Double(points.get(i-1),points.get(i)).ptSegDist(point);
                if (d < distance) { distance = d; best = edge; }
            }
        }
        return best;
    }
    private Handle hitHandle(Point2D point) {
        if (selectedEdge == null) return null;
        double radius = 9 / zoom;
        EdgeEdit edit = edits.get(selectedEdge);
        if (edit != null) for (int i=0;i<edit.points.size();i++)
            if (edit.points.get(i).distance(point) <= radius) return new Handle(2,i);
        var path = edgeRoute(selectedEdge).points();
        if (path.size()<2) return null;
        if (path.get(0).distance(point) <= radius) return new Handle(0,-1);
        if (path.get(path.size()-1).distance(point) <= radius) return new Handle(1,-1);
        return null;
    }
    private int addWaypoint(DiagramModel.Edge edge, Point2D point) {
        hasManualArrangement = true;
        List<Point2D.Double> path = edgeRoute(edge).points();
        EdgeEdit edit = edits.computeIfAbsent(edge, key -> new EdgeEdit());
        double distance = distanceAlong(path,point); int index = 0;
        while (index < edit.points.size() && distanceAlong(path,edit.points.get(index)) <= distance) index++;
        edit.points.add(index, new Point2D.Double(point.getX(),point.getY()));
        invalidateRoutes(); return index;
    }
    private static double distanceAlong(List<Point2D.Double> path,Point2D point) {
        double best = Double.POSITIVE_INFINITY, along = 0, total = 0;
        for (int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);double length=a.distance(b);
            if(length<0.001)continue;
            double fraction=Math.max(0,Math.min(1,((point.getX()-a.x)*(b.x-a.x)+(point.getY()-a.y)*(b.y-a.y))/(length*length)));
            double distance=point.distance(a.x+fraction*(b.x-a.x),a.y+fraction*(b.y-a.y));
            if(distance<best){best=distance;along=total+fraction*length;}total+=length;
        }
        return along;
    }
    private void removeWaypoint() {
        EdgeEdit edit=edits.get(selectedEdge);
        if(edit!=null&&selectedPoint>=0&&selectedPoint<edit.points.size())edit.points.remove(selectedPoint);
        selectedPoint=-1;
        if(edit!=null&&edit.points.isEmpty()&&edit.start==null&&edit.end==null)edits.remove(selectedEdge);
        invalidateRoutes(); repaint(); changed.run();
    }
    private void paintHandles(Graphics2D g,boolean darkMode) {
        if(selectedEdge==null)return;
        var points=edgeRoute(selectedEdge).points();if(points.size()<2)return;
        g.setStroke(new BasicStroke((float)(1.6/zoom)));
        for(var p:List.of(points.get(0),points.get(points.size()-1))) {
            double r=5/zoom;var handle=new Ellipse2D.Double(p.x-r,p.y-r,r*2,r*2);
            g.setColor(darkMode?new Color(0x252b38):Color.WHITE);g.fill(handle);g.setColor(new Color(0x4f7de8));g.draw(handle);
        }
        EdgeEdit edit=edits.get(selectedEdge);
        if(edit!=null)for(int i=0;i<edit.points.size();i++) {
            var p=edit.points.get(i);double r=5/zoom;var handle=new Rectangle2D.Double(p.x-r,p.y-r,r*2,r*2);
            g.setColor(i==selectedPoint?new Color(0x4f7de8):darkMode?new Color(0x252b38):Color.WHITE);g.fill(handle);
            g.setColor(new Color(0x4f7de8));g.draw(handle);
        }
    }
    private static Point2D.Double point(double x, double y) { return new Point2D.Double(x,y); }
    private void paintEdge(Graphics2D g, DiagramModel.Edge edge, boolean darkMode, boolean interactive) {
        EdgeRouter.Route route=edgeRoute(edge);
        List<Point2D.Double> points = route.points(); if (points.size()<2) return;
        boolean dashed = edge.relation() == DiagramModel.Relation.DEPENDENCY || edge.relation() == DiagramModel.Relation.IMPLEMENTS;
        boolean marked=interactive&&edge.equals(selectedEdge);
        g.setColor(route.obstructed()?new Color(0xd38a26):marked?new Color(0x4f7de8):darkMode ? new Color(0x9cabc5) : new Color(0x65758e));
        float weight=marked?2.3f:1.5f;
        g.setStroke(dashed ? new BasicStroke(weight, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{6,4},0) : new BasicStroke(weight));
        Path2D path = new Path2D.Double(); path.moveTo(points.get(0).x, points.get(0).y);
        for (int i=1;i<points.size();i++) path.lineTo(points.get(i).x,points.get(i).y); g.draw(path);
        g.setStroke(new BasicStroke(1.5f));
        List<Point2D.Double> arrow = arrow(points);
        Path2D head = new Path2D.Double(); head.moveTo(arrow.get(0).x,arrow.get(0).y);
        head.lineTo(arrow.get(1).x,arrow.get(1).y); head.lineTo(arrow.get(2).x,arrow.get(2).y);
        if (edge.relation() == DiagramModel.Relation.EXTENDS || edge.relation() == DiagramModel.Relation.IMPLEMENTS) {
            head.closePath(); Color stroke = g.getColor(); g.setColor(background(darkMode)); g.fill(head); g.setColor(stroke);
        }
        g.draw(head);
        if (!edge.label().isBlank()) {
            Point2D.Double p = labelPosition(points);
            g.setFont(META); String text = elide(edge.label(),g.getFontMetrics(),220);
            float x = (float)(p.x - g.getFontMetrics().stringWidth(text)/2.0);
            float y = (float)p.y-6;
            g.setColor(background(darkMode)); g.fillRect((int)x-4,(int)y-12,g.getFontMetrics().stringWidth(text)+8,16);
            g.setColor(muted(darkMode)); g.drawString(text,x,y);
        }
    }
    private static Point2D.Double labelPosition(List<Point2D.Double> points) {
        int best=1;double longest=-1;
        for(int i=1;i<points.size();i++) {
            double score=points.get(i).distance(points.get(i-1));
            if(Math.abs(points.get(i).y-points.get(i-1).y)<0.001)score*=1.3;
            if(score>longest){longest=score;best=i;}
        }
        var a=points.get(best-1);var b=points.get(best);return point((a.x+b.x)/2,(a.y+b.y)/2);
    }
    private static List<Point2D.Double> arrow(List<Point2D.Double> points) {
        Point2D.Double tip=points.get(points.size()-1), previous=points.get(points.size()-2);
        double angle=Math.atan2(tip.y-previous.y,tip.x-previous.x), length=12, width=5;
        double x=tip.x-length*Math.cos(angle), y=tip.y-length*Math.sin(angle);
        return List.of(point(x-width*Math.sin(angle),y+width*Math.cos(angle)),tip,
                point(x+width*Math.sin(angle),y-width*Math.cos(angle)));
    }
    public Rectangle2D diagramBounds() {
        Rectangle2D result = null;
        for (String id : cards.keySet()) { Rectangle2D r=positions.get(id); result = result == null ? (Rectangle2D) r.clone() : result.createUnion(r); }
        for (var edge: visible.edges()) for (Point2D p:edgeRoute(edge).points())
            if (result != null) result.add(p);
        if (result == null) return new Rectangle2D.Double(0,0,400,250);
        return new Rectangle2D.Double(result.getX()-30,result.getY()-35,result.getWidth()+60,result.getHeight()+65);
    }
    public BufferedImage toImage() {
        Rectangle2D b=diagramBounds();
        double scale=Math.min(2,Math.sqrt(24_000_000.0/(b.getWidth()*b.getHeight())));
        scale=Math.min(scale, Math.min(12000/b.getWidth(), 12000/b.getHeight()));
        int w=Math.max(1,(int)Math.ceil(b.getWidth()*scale)),h=Math.max(1,(int)Math.ceil(b.getHeight()*scale));
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); hints(g); g.setColor(Color.WHITE);g.fillRect(0,0,w,h);
        g.scale(scale,scale);g.translate(-b.getX(),-b.getY());paintGraph(g,false,false);g.dispose();return image;
    }
    public String toSvg() {
        Rectangle2D b=diagramBounds();
        StringBuilder out=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(Math.ceil(b.getWidth())).append("\" height=\"").append(Math.ceil(b.getHeight()))
                .append("\" viewBox=\"").append(b.getX()).append(' ').append(b.getY()).append(' ')
                .append(b.getWidth()).append(' ').append(b.getHeight()).append("\">\n")
                .append("<rect x=\"").append(b.getX()).append("\" y=\"").append(b.getY())
                .append("\" width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        for (var edge:visible.edges()) {
            EdgeRouter.Route route=edgeRoute(edge);
            List<Point2D.Double> points=route.points(); if(points.size()<2)continue;
            boolean dashed=edge.relation()==DiagramModel.Relation.DEPENDENCY||edge.relation()==DiagramModel.Relation.IMPLEMENTS;
            out.append("<polyline fill=\"none\" stroke=\"").append(route.obstructed()?"#d38a26":"#65758e").append("\" stroke-width=\"1.5\"")
                    .append(dashed?" stroke-dasharray=\"6 4\"":"").append(" points=\"").append(points(points)).append("\"/>\n");
            boolean triangle=edge.relation()==DiagramModel.Relation.EXTENDS||edge.relation()==DiagramModel.Relation.IMPLEMENTS;
            out.append(triangle?"<polygon":"<polyline").append(" fill=\"").append(triangle?"white":"none")
                    .append("\" stroke=\"#65758e\" stroke-width=\"1.5\" points=\"").append(points(arrow(points))).append("\"/>\n");
            if(!edge.label().isBlank()) {
                Point2D.Double p=labelPosition(points);
                svgText(out,p.x,p.y-7,elide(edge.label(),getFontMetrics(META),220),11,"#65758e"," text-anchor=\"middle\"");
            }
        }
        for(var entry:cards.entrySet()) {
            Card card=entry.getValue();var r=positions.get(entry.getKey());
            out.append("<g><title>").append(xml(card.node().id())).append("</title><rect x=\"").append(r.x)
                    .append("\" y=\"").append(r.y).append("\" width=\"").append(r.width).append("\" height=\"")
                    .append(r.height).append("\" rx=\"10\" fill=\"white\" stroke=\"#cbd5e1\"/>\n");
            svgText(out,r.x+14,r.y+24,card.node().name(),15,"#172338"," font-weight=\"bold\""+(card.node().isAbstract()?" font-style=\"italic\"":""));
            svgText(out,r.x+14,r.y+43,card.node().kind(),11,hex(accent(card.node().kind())),"");
            svgText(out,r.x+14,r.y+60,elide(card.node().packageName(),getFontMetrics(META),r.width-28),11,"#65758e","");
            double y=r.y+HEADER;
            for(List<Row> group:List.of(card.fields(),card.methods())) {
                if(group.isEmpty())continue;
                out.append("<path d=\"M ").append(r.x).append(' ').append(y).append(" H ").append(r.getMaxX()).append("\" stroke=\"#e2e8f0\"/>\n");y+=10;
                for(Row row:group) {
                    svgText(out,r.x+14,y+14,elide(row.text(),getFontMetrics(TEXT),r.width-28),12,"#172338",
                            (row.isStatic()?" text-decoration=\"underline\"":"")+(row.isAbstract()?" font-style=\"italic\"":""));y+=ROW;
                }
            }
            out.append("</g>\n");
        }
        return out.append("</svg>\n").toString();
    }
    private static void svgText(StringBuilder out,double x,double y,String text,int size,String color,String attributes) {
        out.append("<text x=\"").append(x).append("\" y=\"").append(y).append("\" font-family=\"Arial, sans-serif\" font-size=\"")
                .append(size).append("\" fill=\"").append(color).append('"').append(attributes).append('>').append(xml(text)).append("</text>\n");
    }
    private static String points(List<Point2D.Double> points) {StringBuilder b=new StringBuilder();for(var p:points)b.append(p.x).append(',').append(p.y).append(' ');return b.toString();}
    private static String elide(String text,FontMetrics metrics,double width) {
        if(metrics.stringWidth(text)<=width)return text;
        int length=text.length();while(length>0&&metrics.stringWidth(text.substring(0,length)+"...")>width)length--;
        return text.substring(0,length)+"...";
    }
    public static String xml(String s) {return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static void hints(Graphics2D g) {g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);}
    private static Color accent(String kind) {return switch(kind){case "interface","annotation"->new Color(0x8b5cf6);case "enum"->new Color(0xd38a26);case "record"->new Color(0x159a86);default->new Color(0x4f7de8);};}
    private static Color background(boolean d){return new Color(d?0x1c222e:0xf7f9fc);}
    private static Color foreground(boolean d){return new Color(d?0xe2e8f3:0x172338);}
    private static Color muted(boolean d){return new Color(d?0xa5b2c8:0x65758e);}
    private static String hex(Color c){return String.format(Locale.ROOT,"#%06x",c.getRGB()&0xffffff);}
}
