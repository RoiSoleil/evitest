import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders the icon of EVitest (same drawing as icons/evitest.svg). Usage: java tools/MakeIcon.java <size> <file.png> */
public class MakeIcon {
  public static void main(String[] args) throws Exception {
    int size = Integer.parseInt(args[0]);
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.scale(size / 16.0, size / 16.0);
    // The frame, in the style of the icon of ERipGrep.
    g.setPaint(new GradientPaint(0, 1, new Color(0x84cc16), 16, 15, new Color(0x22d3ee)));
    g.fill(new RoundRectangle2D.Double(0.5, 1.5, 15, 13, 6, 6));
    g.setPaint(new Color(0x16161e));
    g.fill(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 4.4, 4.4));
    // The check mark of a passed test.
    Path2D check = new Path2D.Double();
    check.moveTo(3.6, 8.2);
    check.lineTo(6.2, 10.8);
    check.lineTo(10.2, 5.4);
    g.setPaint(new Color(0x4ade80));
    g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(check);
    // The lightning of Vite.
    Path2D bolt = new Path2D.Double();
    bolt.moveTo(12.2, 4.2);
    bolt.lineTo(10.0, 8.2);
    bolt.lineTo(11.5, 8.2);
    bolt.lineTo(10.7, 11.2);
    bolt.lineTo(13.1, 7.0);
    bolt.lineTo(11.6, 7.0);
    bolt.closePath();
    g.setPaint(new Color(0xfacc15));
    g.fill(bolt);
    g.dispose();
    ImageIO.write(image, "png", new File(args[1]));
  }
}
