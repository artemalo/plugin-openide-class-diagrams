package ru.student.diagram;

import ru.student.diagram.model.*;
import ru.student.diagram.ui.DiagramCanvas;
import javax.swing.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static ru.student.diagram.model.DiagramModel.Relation.*;

public final class DiagramTests {
    private static int checks;
    private static void check(boolean condition,String name) {
        if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name);
    }
    private static DiagramModel.Member field(String text){return new DiagramModel.Member(text,"-",false,false,10);}
    private static DiagramModel.Member method(String text){return new DiagramModel.Member(text,"+",false,false,25);}
    private static DiagramModel.Node node(String name,String kind,boolean selected,List<DiagramModel.Member> fields,List<DiagramModel.Member> methods){
        return new DiagramModel.Node("demo."+name,name,"demo",kind,false,fields,methods,"file:///demo/"+name+".java",0,selected);
    }
    public static DiagramModel fixture() {
        return new DiagramModel(List.of(
                node("Fraction","class",true,List.of(field("numerator: int"),field("denominator: int")),
                        List.of(method("Fraction(numerator: int, denominator: int)"),method("add(other: Fraction): Fraction"),
                                method("multiply(other: Fraction): Fraction"),method("divide(other: Fraction): Fraction"),
                                method("compareTo(other: Fraction): int"),method("reduce(): void"))),
                node("Main","class",true,List.of(),List.of(new DiagramModel.Member("main(args: String[]): void","+",true,false,12),method("readFraction(): Fraction"))),
                node("FractionTest","class",true,List.of(),List.of(method("addsFractions(): void"),method("divisionByZeroIsRejected(): void"))),
                node("Comparable","interface",false,List.of(),List.of(new DiagramModel.Member("compareTo(other: T): int","+",false,true,0)))
        ),List.of(new DiagramModel.Edge("demo.Fraction","demo.Comparable",IMPLEMENTS,""),
                new DiagramModel.Edge("demo.Main","demo.Fraction",DEPENDENCY,""),
                new DiagramModel.Edge("demo.FractionTest","demo.Fraction",DEPENDENCY,"")),List.of());
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        DiagramModel model=fixture();
        check(model.filter(false,true).nodes().size()==3,"selected classes only");
        check(model.filter(false,true).edges().size()==2,"no dangling edges after filtering");
        check(model.filter(true,false).edges().size()==1,"dependency toggle preserves inheritance");
        boolean immutable=false;try{model.nodes().clear();}catch(UnsupportedOperationException e){immutable=true;}
        check(immutable,"immutable model snapshot");
        Map<String,Rectangle2D.Double> sizes=new LinkedHashMap<>();
        model.nodes().forEach(n->sizes.put(n.id(),new Rectangle2D.Double(0,0,320,250)));
        var positions=DiagramLayout.arrange(model,sizes);
        check(positions.get("demo.Fraction").y>positions.get("demo.Comparable").y,"parent above child");
        boolean overlap=false;var rectangles=new ArrayList<>(positions.values());
        for(int i=0;i<rectangles.size();i++)for(int j=i+1;j<rectangles.size();j++)overlap|=rectangles.get(i).intersects(rectangles.get(j));
        check(!overlap,"automatic layout has no card overlaps");
        var cycle=new DiagramModel(model.nodes(),List.of(new DiagramModel.Edge("demo.Fraction","demo.Main",EXTENDS,""),new DiagramModel.Edge("demo.Main","demo.Fraction",EXTENDS,"")),List.of());
        check(DiagramLayout.arrange(cycle,sizes).size()==4,"cyclic inheritance cannot hang layout");
        String puml=PlantUmlExporter.export(model,true,true,false);
        check(puml.contains("..|>")&&puml.contains("..>")&&puml.contains("{static}"),"PlantUML relations and modifiers");
        check(!PlantUmlExporter.export(model,true,true,true).contains("- numerator:"),"public-only export filter");
        SwingUtilities.invokeAndWait(()->{
            try {
                DiagramCanvas canvas=new DiagramCanvas();canvas.setSize(1450,950);canvas.setModel(model);
                canvas.setOptions(true,true,false,true,true);canvas.fit();
                check(canvas.getZoom()>0&&canvas.getZoom()<=1.25,"fit to viewport");
                canvas.zoomIn();canvas.actualSize();check(Math.abs(canvas.getZoom()-1)<0.001,"zoom controls");
                String svg=canvas.toSvg();
                var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
                check(doc.getDocumentElement().getTagName().equals("svg"),"SVG is valid XML");
                check(svg.contains("text-decoration=\"underline\"")&&svg.contains("font-style=\"italic\""),"SVG static and abstract notation");
                var image=canvas.toImage();check(image.getWidth()>500&&image.getHeight()>250,"PNG export dimensions");
                ImageIO.write(image,"png",out.resolve("diagram-preview.png").toFile());
                Files.writeString(out.resolve("diagram-preview.svg"),svg);
                Files.writeString(out.resolve("diagram-preview.puml"),puml);
                // The canvas's own paint path is exercised separately in the dark theme.
                canvas.setDark(true);canvas.fit();
                var dark=new java.awt.image.BufferedImage(1450,950,java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D g=dark.createGraphics();canvas.paint(g);g.dispose();ImageIO.write(dark,"png",out.resolve("diagram-dark.png").toFile());
                check(dark.getRGB(0,0)!=Color.WHITE.getRGB(),"dark theme rendering");
                var escaped=node("A&B","class",true,List.of(field("items: List<String>")),List.of());
                canvas.setModel(new DiagramModel(List.of(escaped),List.of(),List.of()));
                factory.newDocumentBuilder().parse(new ByteArrayInputStream(canvas.toSvg().getBytes(StandardCharsets.UTF_8)));
                check(canvas.toSvg().contains("A&amp;B")&&canvas.toSvg().contains("List&lt;String&gt;"),"XML escaping for Java generic types");
                canvas.setModel(new DiagramModel(List.of(),List.of(),List.of()));
                check(canvas.toImage().getWidth()>0,"empty diagrams export safely");
            } catch(Exception error) {throw new RuntimeException(error);}
        });
        Files.writeString(out.resolve("test-results.txt"),checks+" checks passed\n");
        System.out.println(checks+" checks passed");
    }
}
