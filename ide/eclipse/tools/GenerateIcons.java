// SPDX-License-Identifier: MIT OR Apache-2.0

// Draws the Ironwood plugin's icons.
//
// Eclipse needs a real image for a launch configuration type: without one,
// Quick Access fails to render the configuration at all rather than falling
// back to a default. Generating the icons keeps them reviewable as code and
// avoids committing opaque binaries that nobody can diff.
//
//   java ide/eclipse/tools/GenerateIcons.java <output-directory>

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GenerateIcons {

    /** Heartwood brown, the colour the language is named for. */
    private static final Color WOOD = new Color(0x5C, 0x40, 0x33);

    /** Pale sapwood, used for the mark so it reads at 16 pixels. */
    private static final Color GRAIN = new Color(0xE8, 0xD9, 0xB5);

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: GenerateIcons <output-directory>");
            System.exit(2);
        }
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);

        for (int size : new int[] {16, 32}) {
            Path file = directory.resolve("ironwood" + size + ".png");
            ImageIO.write(draw(size), "png", file.toFile());
            System.out.println("generated " + file);
        }
    }

    /**
     * Draws a rounded plank with a single grain line through it. The shape is
     * deliberately simple, because anything more detailed turns to mud at 16
     * pixels.
     */
    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);

        double inset = size / 16.0;
        double arc = size / 4.0;
        graphics.setColor(WOOD);
        graphics.fill(new RoundRectangle2D.Double(inset, inset,
                size - 2 * inset, size - 2 * inset, arc, arc));

        // A vertical bar with a serif at each end: an I for Ironwood that still
        // reads as a grain line in the plank.
        graphics.setColor(GRAIN);
        float thickness = Math.max(1.5f, size / 8.0f);
        graphics.setStroke(new BasicStroke(thickness, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER));
        double centre = size / 2.0;
        double top = size * 0.28;
        double bottom = size * 0.72;
        double serif = size * 0.18;
        graphics.draw(new java.awt.geom.Line2D.Double(centre, top, centre, bottom));
        graphics.draw(new java.awt.geom.Line2D.Double(centre - serif, top, centre + serif, top));
        graphics.draw(new java.awt.geom.Line2D.Double(
                centre - serif, bottom, centre + serif, bottom));

        graphics.dispose();
        return image;
    }
}
