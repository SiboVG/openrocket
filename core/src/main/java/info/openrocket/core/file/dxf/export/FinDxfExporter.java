package info.openrocket.core.file.dxf.export;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.util.CoordinateIF;

/**
 * Utility to render fin sets into a shared DXFBuilder and calculate their bounds.
 */
public final class FinDxfExporter {
	private FinDxfExporter() {
	}

	public static Bounds calculateBounds(FinSet finSet) {
		Bounds bounds = new Bounds();
		addPoints(bounds, finSet.generateContinuousFinAndTabShape(), 0, 0);

		if (finSet.isTabBeyondFin()) {
			CoordinateIF[] tabPoints = finSet.getTabPointsWithRoot();
			CoordinateIF finFront = finSet.getFinFront();
			addPoints(bounds, tabPoints, finFront.getX(), finFront.getY());
		}

		return bounds;
	}

	public static void drawFinSet(FinSet finSet, DXFBuilder builder, double offsetX, double offsetY,
								  DXFExportOptions options) {
		CoordinateIF[] points = finSet.generateContinuousFinAndTabShape();
		builder.addPath(points, offsetX, offsetY, options.getStrokeColor(), "PROFILES");

		if (finSet.isTabBeyondFin()) {
			CoordinateIF[] tabPoints = finSet.getTabPointsWithRoot();
			CoordinateIF finFront = finSet.getFinFront();
			builder.addPath(tabPoints, offsetX + finFront.getX(), offsetY + finFront.getY(),
					options.getStrokeColor(), "PROFILES");
		}
	}

	private static void addPoints(Bounds bounds, CoordinateIF[] points, double offsetX, double offsetY) {
		for (CoordinateIF coord : points) {
			double x = coord.getX() + offsetX;
			double y = coord.getY() + offsetY;
			bounds.include(x, y);
		}
	}

	public static final class Bounds {
		private double minX = Double.MAX_VALUE;
		private double minY = Double.MAX_VALUE;
		private double maxX = -Double.MAX_VALUE;
		private double maxY = -Double.MAX_VALUE;

		public Bounds() { }

		void include(double x, double y) {
			if (x < minX) minX = x;
			if (y < minY) minY = y;
			if (x > maxX) maxX = x;
			if (y > maxY) maxY = y;
		}

		public double getWidth() {
			return (maxX == -Double.MAX_VALUE) ? 0 : (maxX - minX);
		}

		public double getHeight() {
			return (maxY == -Double.MAX_VALUE) ? 0 : (maxY - minY);
		}

		public double getMinX() {
			return minX;
		}

		public double getMinY() {
			return minY;
		}
	}
}
