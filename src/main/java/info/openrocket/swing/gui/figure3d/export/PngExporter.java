package info.openrocket.swing.gui.figure3d.export;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * A utility to export the current view to a PNG file.
 */
public class PngExporter implements ImageExporter {

	@Override
	public void export(int width, int height, String filePath) throws IOException {
		glReadBuffer(GL_FRONT);
		ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

		// The pixels are read from bottom-to-top, so we need to flip the image vertically
		// while copying them from the ByteBuffer to the BufferedImage.
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (x + (width * y)) * 4;
				int r = buffer.get(i) & 0xFF;
				int g = buffer.get(i + 1) & 0xFF;
				int b = buffer.get(i + 2) & 0xFF;
				int a = buffer.get(i + 3) & 0xFF;
				image.setRGB(x, height - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}

		try {
			File outputFile = new File(filePath);
			ImageIO.write(image, "png", outputFile);
			System.out.println("Successfully exported view to " + filePath);
		} catch (IOException e) {
			System.err.println("Failed to write PNG file: " + e.getMessage());
			throw e;
		}
	}

	@Override
	public String getFileExtension() {
		return "png";
	}

	@Override
	public String getDescription() {
		return "PNG Image";
	}

	@Override
	public boolean supportsTransparency() {
		return true;
	}
}