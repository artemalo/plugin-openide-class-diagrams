package ru.student.diagram;

import ru.student.diagram.model.DiagramModel;
import ru.student.diagram.ui.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Geometry and actual Swing mouse gestures: no IDE or desktop automation needed. */
public final class ArrowTests {
    private static int checks;
    private static void check(boolean value,String description) {
        if(!value)throw new AssertionError(description);checks++;System.out.println("PASS "+description);
    }
    private static Point2D.Double p(double x,double y){return new Point2D.Double(x,y);}
    private static Rectangle2D.Double r(double x,double y,double w,double h){return new Rectangle2D.Double(x,y,w,h);}
    private static EdgeRouter.Route route(Map<String,Rectangle2D.Double> boxes) {
        return EdgeRouter.route(boxes,"A","B",0.5,0.5,null,null,List.of());
    }
    private static boolean orthogonal(List<Point2D.Double> path) {
        for(int i=1;i<path.size();i++)if(path.get(i).distance(path.get(i-1))<0.001
                || (Math.abs(path.get(i).x-path.get(i-1).x)>0.001&&Math.abs(path.get(i).y-path.get(i-1).y)>0.001))return false;
        return path.size()>=2;
    }
    private static boolean clear(List<Point2D.Double> path,Collection<Rectangle2D.Double> boxes) {
        for(var box:boxes)for(int i=1;i<path.size();i++)if(EdgeRouter.crosses(path.get(i-1),path.get(i),box))return false;
        return true;
    }
    private static double distanceTo(List<Point2D.Double> path,Point2D p) {
        double distance=Double.POSITIVE_INFINITY;
        for(int i=1;i<path.size();i++)distance=Math.min(distance,new Line2D.Double(path.get(i-1),path.get(i)).ptSegDist(p));
        return distance;
    }
    private static Point2D.Double middle(List<Point2D.Double> path) {
        int best=1;double longest=-1;
        for(int i=1;i<path.size();i++)if(path.get(i-1).distance(path.get(i))>longest){longest=path.get(i-1).distance(path.get(i));best=i;}
        var a=path.get(best-1);var b=path.get(best);return p((a.x+b.x)/2,(a.y+b.y)/2);
    }
    private static void mouse(DiagramCanvas c,int id,Point point,int count,int button,int modifiers) {
        c.dispatchEvent(new MouseEvent(c,id,System.currentTimeMillis(),modifiers,point.x,point.y,count,false,button));
    }
    private static void click(DiagramCanvas c,Point2D point,int count) {
        Point view=c.toView(point);
        mouse(c,MouseEvent.MOUSE_PRESSED,view,count,MouseEvent.BUTTON1,InputEvent.BUTTON1_DOWN_MASK);
        mouse(c,MouseEvent.MOUSE_RELEASED,view,count,MouseEvent.BUTTON1,0);
        mouse(c,MouseEvent.MOUSE_CLICKED,view,count,MouseEvent.BUTTON1,0);
    }
    private static void drag(DiagramCanvas c,Point2D from,Point2D to) {
        mouse(c,MouseEvent.MOUSE_PRESSED,c.toView(from),1,MouseEvent.BUTTON1,InputEvent.BUTTON1_DOWN_MASK);
        mouse(c,MouseEvent.MOUSE_DRAGGED,c.toView(to),1,MouseEvent.NOBUTTON,InputEvent.BUTTON1_DOWN_MASK);
        mouse(c,MouseEvent.MOUSE_RELEASED,c.toView(to),1,MouseEvent.BUTTON1,0);
    }
    private static DiagramModel.Node node(String id) {
        return new DiagramModel.Node(id,id,"example", "class",false,List.of(),List.of(),"file:///"+id+".java",0,true);
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        Map<String,Rectangle2D.Double> boxes=new LinkedHashMap<>();
        boxes.put("A",r(0,0,120,90));boxes.put("B",r(600,0,120,90));
        var path=route(boxes);
        check(!path.obstructed()&&path.points().size()==2&&path.points().get(0).x==120&&path.points().get(1).x==600,"horizontal cards connect by facing sides");
        boxes.put("block",r(290,-60,140,210));path=route(boxes);
        check(!path.obstructed()&&orthogonal(path.points())&&clear(path.points(),boxes.values()),"automatic arrow avoids blocking card");
        var before=path.points();boxes.put("block",r(290,300,140,210));path=route(boxes);
        check(path.points().size()==2&&!path.points().equals(before),"route shortens after obstacle moves away");
        boxes.put("B",r(0,420,120,90));path=route(boxes);
        check(path.points().get(0).y==90&&path.points().get(path.points().size()-1).y==420,"moving target below changes connection sides");
        boxes.put("B",r(-600,0,120,90));path=route(boxes);
        check(path.points().get(0).x==0&&path.points().get(path.points().size()-1).x==-480,"moving target left reverses routing direction");
        boxes.put("B",r(600,0,120,90));
        List<Point2D.Double> pins=List.of(p(170,240),p(500,240));
        path=EdgeRouter.route(boxes,"A","B",0.5,0.5,new EdgeRouter.Anchor(EdgeRouter.Side.BOTTOM,0.3),null,pins);
        check(!path.obstructed()&&orthogonal(path.points())&&pins.stream().allMatch(pin->distanceTo(
                EdgeRouter.route(boxes,"A","B",0.5,0.5,new EdgeRouter.Anchor(EdgeRouter.Side.BOTTOM,0.3),null,pins).points(),pin)<0.01),"manual waypoints are honored by orthogonal routing");
        check(path.points().get(0).distance(p(36,90))<0.01&&clear(path.points(),boxes.values()),"manual anchor stays on requested side and avoids cards");
        var impossible=EdgeRouter.route(boxes,"A","B",0.5,0.5,null,null,List.of(p(30,30)));
        check(impossible.obstructed()&&orthogonal(impossible.points()),"waypoint inside card yields explicit recoverable obstruction");
        boxes.put("B",r(0,0,120,90));
        check(route(boxes).obstructed(),"overlapping endpoints fail safely");

        Random random=new Random(29);boolean allClear=true;long started=System.nanoTime();
        for(int iteration=0;iteration<30;iteration++) {
            Map<String,Rectangle2D.Double> scene=new LinkedHashMap<>();scene.put("A",r(-180,120,110,80));scene.put("B",r(920,650,110,80));
            for(int row=0;row<4;row++)for(int column=0;column<5;column++)
                scene.put(row+"-"+column,r(column*170+random.nextInt(35),row*200+random.nextInt(35),100,110));
            var result=route(scene);allClear&=!result.obstructed()&&orthogonal(result.points())&&clear(result.points(),scene.values());
        }
        check(allClear,"30 obstacle arrangements have no card crossings or diagonal segments");
        Files.writeString(out.resolve("routing-performance.txt"),"30 scenes / 22 cards: "+(System.nanoTime()-started)/1_000_000+" ms\n");
        SwingUtilities.invokeAndWait(()->{
            try {
                var edge=new DiagramModel.Edge("A","B",DiagramModel.Relation.ASSOCIATION,"items");
                var model=new DiagramModel(List.of(node("A"),node("B"),node("C")),List.of(edge),List.of());
                DiagramCanvas canvas=new DiagramCanvas();canvas.setSize(1500,1000);canvas.setModel(model);
                // Label rendering must also work for a route containing exactly two endpoints.
                check(canvas.edgePoints(edge).size()==2&&canvas.toSvg().contains("items")&&canvas.toImage().getWidth()>0,"straight arrow label exports without exception");
                Rectangle2D obstacle=canvas.nodeBounds("C");
                drag(canvas,p(obstacle.getCenterX(),obstacle.getY()+22),p(390,130));
                check(clear(canvas.edgePoints(edge),List.of((Rectangle2D.Double)canvas.nodeBounds("C"))),"dragging unrelated card reroutes cached arrow");
                ImageIO.write(canvas.toImage(),"png",out.resolve("arrows-auto.png").toFile());
                Files.writeString(out.resolve("arrows-auto.svg"),canvas.toSvg());
                var autoPath=canvas.edgePoints(edge);
                Point2D.Double pin=p(430,410);
                drag(canvas,middle(autoPath),pin);
                check(canvas.waypoints(edge).size()==1&&canvas.waypoints(edge).get(0).distance(pin)<1,"dragging line creates a pinned waypoint");
                check(distanceTo(canvas.edgePoints(edge),pin)<1,"drawn arrow passes through dragged point");
                String manualSvg=canvas.toSvg();
                var xmlFactory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
                xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                var document=xmlFactory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(manualSvg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                String exportedPoints=((org.w3c.dom.Element)document.getElementsByTagName("polyline").item(0)).getAttribute("points");
                List<Point2D.Double> exportedPath=new ArrayList<>();
                for(String pair:exportedPoints.strip().split("\\s+")) {String[] xy=pair.split(",");exportedPath.add(p(Double.parseDouble(xy[0]),Double.parseDouble(xy[1])));}
                check(distanceTo(exportedPath,pin)<0.01&&!exportedPath.equals(autoPath),"SVG export contains manually changed route");
                Point2D.Double nextPin=p(500,470);drag(canvas,pin,nextPin);
                check(canvas.waypoints(edge).size()==1&&canvas.waypoints(edge).get(0).distance(nextPin)<1,"dragging existing handle moves it without adding another");
                var source=canvas.nodeBounds("A");var first=canvas.edgePoints(edge).get(0);
                drag(canvas,first,p(source.getCenterX(),source.getY()));
                check(Math.abs(canvas.edgePoints(edge).get(0).y-source.getY())<0.01,"endpoint handle changes attachment side");
                var storedPin=canvas.waypoints(edge).get(0);
                drag(canvas,p(source.getCenterX(),source.getY()+30),p(source.getCenterX()+80,source.getY()+120));
                var moved=canvas.nodeBounds("A");
                check(Math.abs(canvas.edgePoints(edge).get(0).y-moved.getY())<0.01&&canvas.waypoints(edge).get(0).equals(storedPin),"moving class keeps endpoint attached and manual point fixed");
                canvas.setOptions(false,false,false,false,false);canvas.setOptions(true,true,false,false,true);
                check(canvas.nodeBounds("A").equals(moved)&&canvas.waypoints(edge).size()==1,"filters preserve manual position and arrow controls");
                canvas.setModel(model);
                check(canvas.nodeBounds("A").equals(moved)&&canvas.waypoints(edge).size()==1,"refresh preserves manual routes for unchanged relations");
                canvas.fit();canvas.zoomOut();
                Point2D.Double zoomPin=p(570,500);drag(canvas,storedPin,zoomPin);
                check(canvas.waypoints(edge).size()==1&&canvas.waypoints(edge).get(0).distance(zoomPin)<2/canvas.getZoom(),"unselected handle dragging works after refresh, fit and zoom without duplicating point");
                ImageIO.write(canvas.toImage(),"png",out.resolve("arrows-manual.png").toFile());
                Files.writeString(out.resolve("arrows-manual.svg"),canvas.toSvg());
                var image=new java.awt.image.BufferedImage(1500,1000,java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=image.createGraphics();canvas.paint(graphics);graphics.dispose();
                ImageIO.write(image,"png",out.resolve("arrows-handles.png").toFile());
                canvas.getActionMap().get("remove").actionPerformed(null);
                check(canvas.waypoints(edge).isEmpty()&&canvas.visibleModel().edges().size()==1,"Delete removes point without deleting UML relation");
                click(canvas,middle(canvas.edgePoints(edge)),2);
                check(canvas.waypoints(edge).size()==1,"double click inserts a waypoint");
                canvas.getActionMap().get("autoArrow").actionPerformed(null);
                check(canvas.waypoints(edge).isEmpty()&&!canvas.interactionHint().contains("ручной"),"R restores automatic attachment and routing");
                click(canvas,middle(canvas.edgePoints(edge)),2);canvas.resetArrows();
                check(canvas.waypoints(edge).isEmpty()&&canvas.nodeBounds("A").equals(moved),"reset all arrows keeps class positions");
                click(canvas,middle(canvas.edgePoints(edge)),2);canvas.layoutGraph();
                check(canvas.waypoints(edge).isEmpty()&&!canvas.nodeBounds("A").equals(moved),"automatic layout resets manual routes together with cards");
            }catch(Exception error){throw new RuntimeException(error);}
        });
        Files.writeString(out.resolve("arrow-test-results.txt"),checks+" arrow checks passed\n");
        System.out.println(checks+" arrow checks passed");
    }
}
