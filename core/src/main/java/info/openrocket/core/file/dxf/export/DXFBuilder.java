package info.openrocket.core.file.dxf.export;

import info.openrocket.core.util.CoordinateIF;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DXFBuilder is a class that allows you to build DXF (Drawing Exchange Format) files.
 * The functionality is limited to the bare minimum needed to export shapes from OpenRocket.
 * 
 * @author Sibo Van Gool <sibo.vangool@hotmail.com>
 */
public class DXFBuilder {
	private static final double OR_UNIT_TO_DXF_UNIT = 1000; // OpenRocket units are in meters, DXF units are in mm
	private static final String DEFAULT_ACADVER = "AC1015"; // AutoCAD 2000 (supports LWPOLYLINE)
	
	private final List<DXFEntity> entities;
	private double minX = Double.MAX_VALUE;
	private double minY = Double.MAX_VALUE;
	private double maxX = -Double.MAX_VALUE;
	private double maxY = -Double.MAX_VALUE;
	private double originX = 0.0;
	private double originY = 0.0;
	private int nextHandle = 1;

	/**
	 * Supported horizontal text anchoring, matching SVG's naming.
	 */
	public enum TextAnchor {
		START,
		MIDDLE,
		END
	}
	
	/**
	 * Represents a DXF entity (line, polyline, circle, text, etc.)
	 */
	private static class DXFEntity {
		final String type;
		final String subclass;
		final String layer;
		final int colorIndex;
		final List<String> data; // group code/value pairs (without common entity attributes)
		
		DXFEntity(String type, String subclass, String layer, int colorIndex) {
			this.type = type;
			this.subclass = subclass;
			this.layer = layer;
			this.colorIndex = colorIndex;
			this.data = new ArrayList<>();
		}
	}
	
	/**
	 * Creates a new DXFBuilder instance.
	 */
	public DXFBuilder() {
		this.entities = new ArrayList<>();
	}
	
	/**
	 * Converts OpenRocket coordinates (meters) to DXF coordinates (millimeters).
	 */
	private double toDxfUnits(double meters) {
		return meters * OR_UNIT_TO_DXF_UNIT;
	}
	
	/**
	 * Updates the canvas bounds based on the given coordinates.
	 */
	private void updateBounds(double x, double y) {
		if (x < minX) minX = x;
		if (y < minY) minY = y;
		if (x > maxX) maxX = x;
		if (y > maxY) maxY = y;
	}
	
	/**
	 * Converts a color to a DXF color index (0-255).
	 * DXF color indices: 0=ByBlock, 7=white/black, etc.
	 * For simplicity, we map RGB colors to a reasonable DXF color index.
	 */
	private int colorToDxfIndex(Color color) {
		if (color == null) {
			return 7; // Default: white/black
		}
		
		int r = color.getRed();
		int g = color.getGreen();
		int b = color.getBlue();

		// Rough mapping onto AutoCAD ACI 1-7:
		// 1=red, 2=yellow, 3=green, 4=cyan, 5=blue, 6=magenta, 7=white/black
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		if (max - min < 10) {
			return 7;
		}
		if (max == r) {
			if (g > b) return (g > 128) ? 2 : 1;
			return (b > 128) ? 6 : 1;
		}
		if (max == g) {
			if (r > b) return (r > 128) ? 2 : 3;
			return (b > 128) ? 4 : 3;
		}
		// max == b
		if (r > g) return (r > 128) ? 6 : 5;
		return (g > 128) ? 4 : 5;
	}
	
	/**
	 * Formats a double value for DXF output.
	 */
	private String formatDouble(double value) {
		return String.format(Locale.ENGLISH, "%.6f", value);
	}
	
	/**
	 * Adds a path (polyline) to the DXF document.
	 * 
	 * @param coordinates the array of coordinates defining the path (in meters)
	 * @param xPos the offset x-axis position (in meters)
	 * @param yPos the offset y-axis position (in meters)
	 * @param stroke the color used to stroke the path, or null
	 * @param layer the layer name for this entity
	 */
	public void addPath(CoordinateIF[] coordinates, double xPos, double yPos, Color stroke, String layer) {
		if (coordinates == null || coordinates.length < 2) {
			return;
		}
		
		String resolvedLayer = normalizeLayer(layer, "PROFILES");
		DXFEntity entity = new DXFEntity("LWPOLYLINE", "AcDbPolyline", resolvedLayer, colorToDxfIndex(stroke));
		
		// LWPOLYLINE group codes
		entity.data.add("90"); // Number of vertices
		entity.data.add(String.valueOf(coordinates.length));
		entity.data.add("70"); // Polyline flag (1 = closed)
		// Check if path is closed
		boolean closed = coordinates.length > 2 &&
				Math.abs(coordinates[0].getX() - coordinates[coordinates.length-1].getX()) < 1e-10 &&
				Math.abs(coordinates[0].getY() - coordinates[coordinates.length-1].getY()) < 1e-10;
		entity.data.add(closed ? "1" : "0");
		
		// Add vertices (X, Y coordinates)
		for (CoordinateIF coord : coordinates) {
			double x = toDxfUnits(coord.getX() + xPos + originX);
			double y = toDxfUnits(coord.getY() + yPos + originY);
			updateBounds(x, y);
			
			entity.data.add("10"); // X coordinate group code
			entity.data.add(formatDouble(x));
			entity.data.add("20"); // Y coordinate group code
			entity.data.add(formatDouble(y));
		}
		
		entities.add(entity);
	}
	
	/**
	 * Adds a path with default layer.
	 */
	public void addPath(CoordinateIF[] coordinates, double xPos, double yPos, Color stroke) {
		addPath(coordinates, xPos, yPos, stroke, "PROFILES");
	}
	
	/**
	 * Adds a path without offset.
	 */
	public void addPath(CoordinateIF[] coordinates, Color stroke, String layer) {
		addPath(coordinates, 0, 0, stroke, layer);
	}
	
	/**
	 * Adds a path without offset and with default layer.
	 */
	public void addPath(CoordinateIF[] coordinates, Color stroke) {
		addPath(coordinates, 0, 0, stroke, "PROFILES");
	}
	
	/**
	 * Adds a circle to the DXF document.
	 * 
	 * @param centerX the X coordinate of the center (in meters)
	 * @param centerY the Y coordinate of the center (in meters)
	 * @param radius the radius (in meters)
	 * @param stroke the color used to stroke the circle, or null
	 * @param layer the layer name
	 */
	public void addCircle(double centerX, double centerY, double radius, Color stroke, String layer) {
		String resolvedLayer = normalizeLayer(layer, "PROFILES");
		DXFEntity entity = new DXFEntity("CIRCLE", "AcDbCircle", resolvedLayer, colorToDxfIndex(stroke));
		
		double cx = toDxfUnits(centerX + originX);
		double cy = toDxfUnits(centerY + originY);
		double r = toDxfUnits(radius);
		
		updateBounds(cx - r, cy - r);
		updateBounds(cx + r, cy + r);
		
		entity.data.add("10"); // Center X
		entity.data.add(formatDouble(cx));
		entity.data.add("20"); // Center Y
		entity.data.add(formatDouble(cy));
		entity.data.add("30"); // Center Z (0 for 2D)
		entity.data.add("0.0");
		entity.data.add("40"); // Radius
		entity.data.add(formatDouble(r));
		
		entities.add(entity);
	}
	
	/**
	 * Adds a circle with default layer.
	 */
	public void addCircle(double centerX, double centerY, double radius, Color stroke) {
		addCircle(centerX, centerY, radius, stroke, "PROFILES");
	}
	
	/**
	 * Adds a line to the DXF document.
	 * 
	 * @param startX the X coordinate of the start point (in meters)
	 * @param startY the Y coordinate of the start point (in meters)
	 * @param endX the X coordinate of the end point (in meters)
	 * @param endY the Y coordinate of the end point (in meters)
	 * @param stroke the color used to stroke the line, or null
	 * @param layer the layer name
	 */
	public void addLine(double startX, double startY, double endX, double endY, Color stroke, String layer) {
		String resolvedLayer = normalizeLayer(layer, "PROFILES");
		DXFEntity entity = new DXFEntity("LINE", "AcDbLine", resolvedLayer, colorToDxfIndex(stroke));
		
		double x1 = toDxfUnits(startX + originX);
		double y1 = toDxfUnits(startY + originY);
		double x2 = toDxfUnits(endX + originX);
		double y2 = toDxfUnits(endY + originY);
		
		updateBounds(x1, y1);
		updateBounds(x2, y2);
		
		entity.data.add("10"); // Start X
		entity.data.add(formatDouble(x1));
		entity.data.add("20"); // Start Y
		entity.data.add(formatDouble(y1));
		entity.data.add("30"); // Start Z
		entity.data.add("0.0");
		entity.data.add("11"); // End X
		entity.data.add(formatDouble(x2));
		entity.data.add("21"); // End Y
		entity.data.add(formatDouble(y2));
		entity.data.add("31"); // End Z
		entity.data.add("0.0");
		
		entities.add(entity);
	}
	
	/**
	 * Adds a line with default layer.
	 */
	public void addLine(double startX, double startY, double endX, double endY, Color stroke) {
		addLine(startX, startY, endX, endY, stroke, "PROFILES");
	}
	
	/**
	 * Convenience helper to draw a crosshair centered at (centerX, centerY).
	 */
	public void addCrosshair(double centerX, double centerY, double armHalfWidth, double armHalfHeight,
			Color stroke, String layer) {
		addLine(centerX - armHalfWidth, centerY, centerX + armHalfWidth, centerY, stroke, layer);
		addLine(centerX, centerY - armHalfHeight, centerX, centerY + armHalfHeight, stroke, layer);
	}
	
	/**
	 * Adds a crosshair with default layer.
	 */
	public void addCrosshair(double centerX, double centerY, double armHalfWidth, double armHalfHeight, Color stroke) {
		addCrosshair(centerX, centerY, armHalfWidth, armHalfHeight, stroke, "CROSSHAIRS");
	}
	
	/**
	 * Adds a text element to the DXF document.
	 * 
	 * @param x the X coordinate of the text insertion point (in meters)
	 * @param y the Y coordinate of the text insertion point (in meters)
	 * @param text the text content
	 * @param height the text height in millimeters
	 * @param color the text color, or null for default
	 * @param layer the layer name
	 */
	public void addText(double x, double y, String text, double height, Color color, String layer, TextAnchor anchor) {
		if (text == null || text.trim().isEmpty()) {
			return;
		}
		text = text.replace('\n', ' ').replace('\r', ' ').trim();
		
		String resolvedLayer = normalizeLayer(layer, "LABELS");
		TextAnchor resolvedAnchor = (anchor != null) ? anchor : TextAnchor.MIDDLE;
		DXFEntity entity = new DXFEntity("TEXT", "AcDbText", resolvedLayer, colorToDxfIndex(color));
		
		double dxfX = toDxfUnits(x + originX);
		double dxfY = toDxfUnits(y + originY);
		
		// Estimate text bounds for updating canvas size
		double estimatedWidth = text.length() * height * 0.6;

		if (resolvedAnchor == TextAnchor.START) {
			updateBounds(dxfX, dxfY - height);
			updateBounds(dxfX + estimatedWidth, dxfY + height * 0.3);
		} else if (resolvedAnchor == TextAnchor.END) {
			updateBounds(dxfX - estimatedWidth, dxfY - height);
			updateBounds(dxfX, dxfY + height * 0.3);
		} else {
			double halfWidth = estimatedWidth / 2.0;
			updateBounds(dxfX - halfWidth, dxfY - height);
			updateBounds(dxfX + halfWidth, dxfY + height * 0.3);
		}
		
		entity.data.add("10"); // Insertion point X
		entity.data.add(formatDouble(dxfX));
		entity.data.add("20"); // Insertion point Y
		entity.data.add(formatDouble(dxfY));
		entity.data.add("30"); // Insertion point Z
		entity.data.add("0.0");
		entity.data.add("40"); // Text height
		entity.data.add(formatDouble(height));
		entity.data.add("1"); // Text value
		entity.data.add(text);
		entity.data.add("50"); // Rotation angle (0 = horizontal)
		entity.data.add("0.0");
		entity.data.add("7"); // Text style
		entity.data.add("STANDARD");

		// Alignment: for center/right anchoring, TEXT uses 72 + second alignment point 11/21/31.
		entity.data.add("72");
		entity.data.add(String.valueOf(textAnchorToDxfHAlign(resolvedAnchor)));
		entity.data.add("73");
		entity.data.add("0"); // baseline
		entity.data.add("11");
		entity.data.add(formatDouble(dxfX));
		entity.data.add("21");
		entity.data.add(formatDouble(dxfY));
		entity.data.add("31");
		entity.data.add("0.0");
		
		entities.add(entity);
	}

	public void addText(double x, double y, String text, double height, Color color, String layer) {
		addText(x, y, text, height, color, layer, TextAnchor.MIDDLE);
	}
	
	/**
	 * Adds text with default layer.
	 */
	public void addText(double x, double y, String text, double height, Color color) {
		addText(x, y, text, height, color, "LABELS", TextAnchor.MIDDLE);
	}
	
	/**
	 * Repositions the drawing origin so subsequent calls are offset.
	 */
	public void setOrigin(double originX, double originY) {
		this.originX = originX;
		this.originY = originY;
	}
	
	/**
	 * Translates the origin by the given delta.
	 */
	public void translate(double deltaX, double deltaY) {
		this.originX += deltaX;
		this.originY += deltaY;
	}
	
	public double getOriginX() {
		return originX;
	}
	
	public double getOriginY() {
		return originY;
	}
	
	/**
	 * Writes the DXF document to a file.
	 * 
	 * @param file the file to write to
	 * @throws IOException if an error occurs while writing the file
	 */
	public void writeToFile(File file) throws IOException {
		// Calculate extents
		if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE) {
			minX = 0;
			minY = 0;
			maxX = 0;
			maxY = 0;
		}
		
		try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
			// Write DXF header
			writeSection(writer, "HEADER");
			writeHeaderVariables(writer);
			writer.println("0");
			writer.println("ENDSEC");
			
			// Write TABLES section
			writeSection(writer, "TABLES");
			writeLineTypeTable(writer);
			writeStyleTable(writer);
			writeLayerTable(writer);
			writer.println("0");
			writer.println("ENDSEC");
			
			// Write ENTITIES section
			writeSection(writer, "ENTITIES");
			for (DXFEntity entity : entities) {
				writeEntity(writer, entity);
			}
			writer.println("0");
			writer.println("ENDSEC");
			
			// Write EOF
			writer.println("0");
			writer.println("EOF");
		}
	}
	
	/**
	 * Writes a DXF section header.
	 */
	private void writeSection(PrintWriter writer, String sectionName) {
		writer.println("0");
		writer.println("SECTION");
		writer.println("2");
		writer.println(sectionName);
	}
	
	/**
	 * Writes header variables (minimal set).
	 */
	private void writeHeaderVariables(PrintWriter writer) {
		// $ACADVER - target DXF version
		writer.println("9");
		writer.println("$ACADVER");
		writer.println("1");
		writer.println(DEFAULT_ACADVER);

		// $INSUNITS - insertion units (4 = millimeters)
		writer.println("9");
		writer.println("$INSUNITS");
		writer.println("70");
		writer.println("4");

		// $MEASUREMENT - 1 = metric
		writer.println("9");
		writer.println("$MEASUREMENT");
		writer.println("70");
		writer.println("1");

		// $EXTMIN - minimum extents
		writer.println("9");
		writer.println("$EXTMIN");
		writer.println("10");
		writer.println(formatDouble(minX));
		writer.println("20");
		writer.println(formatDouble(minY));
		writer.println("30");
		writer.println("0.0");
		
		// $EXTMAX - maximum extents
		writer.println("9");
		writer.println("$EXTMAX");
		writer.println("10");
		writer.println(formatDouble(maxX));
		writer.println("20");
		writer.println(formatDouble(maxY));
		writer.println("30");
		writer.println("0.0");
	}

	/**
	 * Writes the linetype table (minimal: CONTINUOUS).
	 */
	private void writeLineTypeTable(PrintWriter writer) {
		writer.println("0");
		writer.println("TABLE");
		writer.println("2");
		writer.println("LTYPE");
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbSymbolTable");
		writer.println("70");
		writer.println("1");

		writer.println("0");
		writer.println("LTYPE");
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbSymbolTableRecord");
		writer.println("100");
		writer.println("AcDbLinetypeTableRecord");
		writer.println("2");
		writer.println("CONTINUOUS");
		writer.println("70");
		writer.println("0");
		writer.println("3");
		writer.println("Solid line");
		writer.println("72");
		writer.println("65");
		writer.println("73");
		writer.println("0");
		writer.println("40");
		writer.println("0.0");

		writer.println("0");
		writer.println("ENDTAB");
	}

	/**
	 * Writes the text style table (minimal: STANDARD).
	 */
	private void writeStyleTable(PrintWriter writer) {
		writer.println("0");
		writer.println("TABLE");
		writer.println("2");
		writer.println("STYLE");
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbSymbolTable");
		writer.println("70");
		writer.println("1");

		writer.println("0");
		writer.println("STYLE");
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbSymbolTableRecord");
		writer.println("100");
		writer.println("AcDbTextStyleTableRecord");
		writer.println("2");
		writer.println("STANDARD");
		writer.println("70");
		writer.println("0");
		writer.println("40");
		writer.println("0.0");
		writer.println("41");
		writer.println("1.0");
		writer.println("50");
		writer.println("0.0");
		writer.println("71");
		writer.println("0");
		writer.println("42");
		writer.println("2.5");
		writer.println("3");
		writer.println("txt");
		writer.println("4");
		writer.println("");

		writer.println("0");
		writer.println("ENDTAB");
	}
	
	/**
	 * Writes the layer table.
	 */
	private void writeLayerTable(PrintWriter writer) {
		Set<String> layers = collectLayers();

		writer.println("0");
		writer.println("TABLE");
		writer.println("2");
		writer.println("LAYER");
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbSymbolTable");
		writer.println("70");
		writer.println(String.valueOf(layers.size())); // Number of layers

		for (String layer : layers) {
			writeLayer(writer, layer, 7);
		}
		
		writer.println("0");
		writer.println("ENDTAB");
	}
	
	/**
	 * Writes a single layer definition.
	 */
	private void writeLayer(PrintWriter writer, String name, int color) {
		writer.println("0");
		writer.println("LAYER");
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbSymbolTableRecord");
		writer.println("100");
		writer.println("AcDbLayerTableRecord");
		writer.println("2");
		writer.println(name);
		writer.println("70");
		writer.println("0");
		writer.println("62");
		writer.println(String.valueOf(color));
		writer.println("6");
		writer.println("CONTINUOUS"); // Linetype
	}
	
	/**
	 * Writes a DXF entity.
	 */
	private void writeEntity(PrintWriter writer, DXFEntity entity) {
		writer.println("0");
		writer.println(entity.type);
		writer.println("5");
		writer.println(nextHandle());
		writer.println("100");
		writer.println("AcDbEntity");
		writer.println("8");
		writer.println(entity.layer);
		writer.println("62");
		writer.println(String.valueOf(entity.colorIndex));
		if (entity.subclass != null && !entity.subclass.isEmpty()) {
			writer.println("100");
			writer.println(entity.subclass);
		}
		
		// Write entity-specific data
		for (int i = 0; i < entity.data.size(); i += 2) {
			if (i + 1 < entity.data.size()) {
				writer.println(entity.data.get(i));
				writer.println(entity.data.get(i + 1));
			}
		}
	}

	private Set<String> collectLayers() {
		Set<String> layers = new LinkedHashSet<>();
		layers.add("0");
		for (DXFEntity entity : entities) {
			if (entity.layer != null && !entity.layer.isEmpty()) {
				layers.add(entity.layer);
			}
		}
		return layers;
	}

	private String nextHandle() {
		return Integer.toHexString(nextHandle++).toUpperCase(Locale.ENGLISH);
	}

	private String normalizeLayer(String layer, String fallback) {
		if (layer == null || layer.trim().isEmpty()) {
			return fallback;
		}
		return layer.trim();
	}

	private int textAnchorToDxfHAlign(TextAnchor anchor) {
		return switch (anchor) {
			case START -> 0;
			case MIDDLE -> 1;
			case END -> 2;
		};
	}
}
