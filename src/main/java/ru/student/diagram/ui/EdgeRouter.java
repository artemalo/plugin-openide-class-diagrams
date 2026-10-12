package ru.student.diagram.ui;

import java.awt.geom.*;
import java.util.*;
import java.util.List;

/** Orthogonal routing on a coordinate-compressed visibility grid. No external graph library. */
public final class EdgeRouter {
    private EdgeRouter() {}
    private static final double GAP = 12, LEAD = 24, BEND_COST = 24, EPS = 0.001;
    public enum Side { TOP, RIGHT, BOTTOM, LEFT }
    public record Anchor(Side side, double fraction) {
        public Anchor { fraction = Math.max(0.06, Math.min(0.94, fraction)); }
        public Point2D.Double point(Rectangle2D r) {
            return switch (side) {
                case TOP -> p(r.getX() + r.getWidth() * fraction, r.getY());
                case BOTTOM -> p(r.getX() + r.getWidth() * fraction, r.getMaxY());
                case LEFT -> p(r.getX(), r.getY() + r.getHeight() * fraction);
                case RIGHT -> p(r.getMaxX(), r.getY() + r.getHeight() * fraction);
            };
        }
        public static Anchor nearest(Rectangle2D r, Point2D point) {
            double[] distances = {Math.abs(point.getY()-r.getY()), Math.abs(point.getX()-r.getMaxX()),
                    Math.abs(point.getY()-r.getMaxY()), Math.abs(point.getX()-r.getX())};
            int best = 0; for (int i=1;i<4;i++) if (distances[i]<distances[best]) best=i;
            Side side = Side.values()[best];
            double fraction = best%2==0 ? (point.getX()-r.getX())/r.getWidth() : (point.getY()-r.getY())/r.getHeight();
            return new Anchor(side, fraction);
        }
    }
    public record Route(List<Point2D.Double> points, boolean obstructed) {}
    private record Port(Point2D.Double point, Point2D.Double stub, int direction) {} // 0:any, 1:right, 2:down, 3:left, 4:up

    public static Route route(Map<String, ? extends Rectangle2D> cards, String source, String target,
                              double sourceSlot, double targetSlot, Anchor from, Anchor to,
                              List<Point2D.Double> waypoints) {
        Rectangle2D a=cards.get(source), b=cards.get(target);
        if(a==null||b==null)return new Route(List.of(),false);
        if(a.intersects(b))return new Route(fallback(a,b,sourceSlot,targetSlot,from,to,waypoints),true);
        List<Rectangle2D> obstacles=cards.values().stream().map(r -> (Rectangle2D)new Rectangle2D.Double(
                r.getX()-GAP,r.getY()-GAP,r.getWidth()+2*GAP,r.getHeight()+2*GAP)).toList();
        List<Port> starts=ports(cards,source,from,sourceSlot), ends=ports(cards,target,to,targetSlot);
        List<Point2D.Double> extras=new ArrayList<>(waypoints);
        starts.forEach(port->extras.add(port.stub)); ends.forEach(port->extras.add(port.stub));
        Grid grid=new Grid(obstacles,extras);
        List<Point2D.Double> result=new ArrayList<>();
        List<Port> current=starts;
        for(int leg=0;leg<=waypoints.size();leg++) {
            List<Port> goals=leg==waypoints.size()?ends:List.of(new Port(waypoints.get(leg),waypoints.get(leg),0));
            List<Point2D.Double> part=grid.find(current,goals);
            if(part.isEmpty()) return new Route(fallback(a,b,sourceSlot,targetSlot,from,to,waypoints),true);
            for(var point:part) if(result.isEmpty()||result.get(result.size()-1).distance(point)>EPS) result.add(point);
            if(leg<waypoints.size()) current=goals;
        }
        return new Route(simplify(result),false);
    }

    private static List<Port> ports(Map<String,? extends Rectangle2D> cards,String id,Anchor anchor,double slot) {
        List<Port> result=new ArrayList<>(); Rectangle2D r=cards.get(id);
        for(Side side:Side.values()) {
            if(anchor!=null&&anchor.side()!=side)continue;
            Point2D.Double point=new Anchor(side,anchor==null?slot:anchor.fraction()).point(r);
            Point2D.Double stub=p(point.x,point.y);
            switch(side){case TOP->stub.y-=LEAD;case BOTTOM->stub.y+=LEAD;case LEFT->stub.x-=LEAD;case RIGHT->stub.x+=LEAD;}
            boolean blocked=cards.entrySet().stream().anyMatch(e->!e.getKey().equals(id)&&crosses(point,stub,e.getValue()));
            if(!blocked)result.add(new Port(point,stub,switch(side){case TOP->4;case RIGHT->1;case BOTTOM->2;case LEFT->3;}));
        }
        return result;
    }

    private static List<Point2D.Double> fallback(Rectangle2D a,Rectangle2D b,double sa,double sb,
                                                Anchor from,Anchor to,List<Point2D.Double> waypoints) {
        Point2D next=waypoints.isEmpty()?p(b.getCenterX(),b.getCenterY()):waypoints.get(0);
        Point2D previous=waypoints.isEmpty()?p(a.getCenterX(),a.getCenterY()):waypoints.get(waypoints.size()-1);
        Anchor start=from==null?new Anchor(Anchor.nearest(a,next).side(),sa):from;
        Anchor end=to==null?new Anchor(Anchor.nearest(b,previous).side(),sb):to;
        var first=start.point(a); var last=end.point(b);
        List<Point2D.Double> points=new ArrayList<>(); points.add(first);
        Point2D.Double firstStub=stub(first,start.side()), lastStub=stub(last,end.side());
        points.add(firstStub);
        for(var goal:concat(waypoints,List.of(lastStub))) {
            var current=points.get(points.size()-1);points.add(p(goal.x,current.y));points.add(goal);
        }
        points.add(last);return simplify(points);
    }
    private static Point2D.Double stub(Point2D.Double point,Side side) {
        return p(point.x+(side==Side.RIGHT?LEAD:side==Side.LEFT?-LEAD:0),
                point.y+(side==Side.BOTTOM?LEAD:side==Side.TOP?-LEAD:0));
    }
    private static List<Point2D.Double> concat(List<Point2D.Double> a,List<Point2D.Double> b) {
        List<Point2D.Double> result=new ArrayList<>(a);result.addAll(b);return result;
    }
    public static List<Point2D.Double> simplify(List<Point2D.Double> points) {
        List<Point2D.Double> result=new ArrayList<>();
        for(var point:points) {
            if(!result.isEmpty()&&point.distance(result.get(result.size()-1))<EPS)continue;
            while(result.size()>1) {
                var a=result.get(result.size()-2);var b=result.get(result.size()-1);
                // Only remove a point lying BETWEEN its neighbors, never a deliberate U-turn.
                if(Math.abs((b.x-a.x)*(point.y-b.y)-(b.y-a.y)*(point.x-b.x))>EPS
                        || (b.x-a.x)*(point.x-b.x)+(b.y-a.y)*(point.y-b.y)<0)break;
                result.remove(result.size()-1);
            }
            result.add(p(point.x,point.y));
        }
        return List.copyOf(result);
    }
    public static boolean crosses(Point2D a,Point2D b,Rectangle2D r) {
        if(Math.abs(a.getY()-b.getY())<EPS)
            return a.getY()>r.getY()+EPS&&a.getY()<r.getMaxY()-EPS
                    &&Math.max(a.getX(),b.getX())>r.getX()+EPS&&Math.min(a.getX(),b.getX())<r.getMaxX()-EPS;
        if(Math.abs(a.getX()-b.getX())<EPS)
            return a.getX()>r.getX()+EPS&&a.getX()<r.getMaxX()-EPS
                    &&Math.max(a.getY(),b.getY())>r.getY()+EPS&&Math.min(a.getY(),b.getY())<r.getMaxY()-EPS;
        return r.intersectsLine(a.getX(),a.getY(),b.getX(),b.getY());
    }
    private static Point2D.Double p(double x,double y){return new Point2D.Double(x,y);}

    private static final class Grid {
        final double[] xs,ys; final int width,height; final boolean[] horizontal,vertical,blocked;
        Grid(List<Rectangle2D> obstacles,List<Point2D.Double> extras) {
            TreeSet<Double> x=new TreeSet<>(),y=new TreeSet<>();
            for(var r:obstacles){x.add(r.getMinX());x.add(r.getMaxX());y.add(r.getMinY());y.add(r.getMaxY());}
            for(var p:extras){x.add(p.x);y.add(p.y);}
            x.add(x.first()-LEAD);x.add(x.last()+LEAD);y.add(y.first()-LEAD);y.add(y.last()+LEAD);
            xs=x.stream().mapToDouble(Double::doubleValue).toArray();ys=y.stream().mapToDouble(Double::doubleValue).toArray();
            width=xs.length;height=ys.length;horizontal=new boolean[width*height];vertical=new boolean[width*height];blocked=new boolean[width*height];
            for(var r:obstacles) {
                int left=Arrays.binarySearch(xs,r.getMinX()),right=Arrays.binarySearch(xs,r.getMaxX());
                int top=Arrays.binarySearch(ys,r.getMinY()),bottom=Arrays.binarySearch(ys,r.getMaxY());
                for(int row=top+1;row<bottom;row++) {
                    Arrays.fill(horizontal,row*width+left,row*width+right,true);
                    Arrays.fill(blocked,row*width+left+1,row*width+right,true);
                }
                for(int row=top;row<bottom;row++)Arrays.fill(vertical,row*width+left+1,row*width+right,true);
            }
        }
        record Visit(int state,double distance,double score) {}
        List<Point2D.Double> find(List<Port> starts,List<Port> ends) {
            List<Port> goals=ends.stream().filter(port->!blocked[node(port.stub)]).toList();
            if(goals.isEmpty()||starts.isEmpty())return List.of();
            int size=width*height*5;
            double[] distances=new double[size];Arrays.fill(distances,Double.POSITIVE_INFINITY);
            int[] previous=new int[size],origin=new int[size];Arrays.fill(previous,-1);Arrays.fill(origin,-1);
            PriorityQueue<Visit> queue=new PriorityQueue<>(Comparator.comparingDouble(Visit::score).thenComparingInt(Visit::state));
            for(int i=0;i<starts.size();i++) {
                Port port=starts.get(i);int node=node(port.stub),state=node*5+port.direction;
                if(blocked[node])continue;
                double cost=port.point.distance(port.stub);
                if(cost<distances[state]){distances[state]=cost;origin[state]=i;queue.add(new Visit(state,cost,cost+heuristic(port.stub,goals)));}
            }
            double best=Double.POSITIVE_INFINITY;int bestState=-1;Port bestEnd=null;
            int visited=0;
            while(!queue.isEmpty()&&visited++<180_000) {
                Visit visit=queue.remove();int state=visit.state,node=state/5,direction=state%5;
                if(visit.distance>distances[state]+EPS)continue;
                if(visit.score>=best)break;
                Point2D.Double point=point(node);
                for(Port goal:goals) if(point.distance(goal.stub)<EPS) {
                    // An arrow must enter the target, never double back over its final lead.
                    if(direction!=0&&direction==goal.direction)continue;
                    double score=visit.distance+goal.stub.distance(goal.point)
                            +(direction!=0&&goal.direction!=0&&direction!=opposite(goal.direction)?BEND_COST:0);
                    if(score<best){best=score;bestState=state;bestEnd=goal;}
                }
                int x=node%width,y=node/width;
                int[] neighbors={x>0&&!horizontal[node-1]?node-1:-1,x+1<width&&!horizontal[node]?node+1:-1,
                        y>0&&!vertical[node-width]?node-width:-1,y+1<height&&!vertical[node]?node+width:-1};
                for(int i=0;i<neighbors.length;i++) {
                    int neighbor=neighbors[i];if(neighbor<0||blocked[neighbor])continue;
                    int nextDirection=switch(i){case 0->3;case 1->1;case 2->4;default->2;};
                    if(previous[state]<0&&direction!=0&&nextDirection==opposite(direction))continue;
                    int next=neighbor*5+nextDirection;
                    Point2D.Double nextPoint=point(neighbor);
                    double distance=visit.distance+point.distance(nextPoint)+(direction!=0&&direction!=nextDirection?BEND_COST:0);
                    if(distance+EPS<distances[next]) {
                        distances[next]=distance;previous[next]=state;origin[next]=origin[state];
                        queue.add(new Visit(next,distance,distance+heuristic(nextPoint,goals)));
                    }
                }
            }
            if(bestState<0)return List.of();
            List<Point2D.Double> path=new ArrayList<>();path.add(bestEnd.point);
            int state=bestState;while(state>=0){path.add(point(state/5));state=previous[state];}
            path.add(starts.get(origin[bestState]).point);Collections.reverse(path);return simplify(path);
        }
        private int node(Point2D.Double point){return Arrays.binarySearch(ys,point.y)*width+Arrays.binarySearch(xs,point.x);}
        private static int opposite(int direction){return direction==0?0:(direction+1)%4+1;}
        private Point2D.Double point(int node){return p(xs[node%width],ys[node/width]);}
        private static double heuristic(Point2D.Double point,List<Port> goals) {
            double value=Double.POSITIVE_INFINITY;
            for(Port goal:goals)value=Math.min(value,Math.abs(point.x-goal.stub.x)+Math.abs(point.y-goal.stub.y)+goal.stub.distance(goal.point));
            return value;
        }
    }
}
