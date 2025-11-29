package info.openrocket.core.file.dxf.export;

import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.CenteringRing;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.util.CoordinateIF;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shapes out centering rings and bulkheads using DXFBuilder.
 */
public final class RingDxfExporter {
	private static final double MIN_CROSSHAIR_HALF_LENGTH = 0.0005; // 0.5 mm
	private static final double MAX_CROSSHAIR_HALF_LENGTH = 0.05;   // 50 mm

	private RingDxfExporter() {
	}

	public record Hole(double offsetY, double offsetZ, double radius) { }

	public static void drawCenteringRing(CenteringRing ring,
										 DXFBuilder builder,
										 DXFExportOptions options,
										 Collection<Hole> additionalHoles) {
		Objects.requireNonNull(ring, "ring");
		renderRing(builder,
				0,
				0,
				ring.getOuterRadius(),
				ring.getInnerRadius(),
				additionalHoles,
				options);
	}

	public static void drawBulkhead(Bulkhead bulkhead,
									DXFBuilder builder,
									DXFExportOptions options,
									Collection<Hole> additionalHoles) {
		Objects.requireNonNull(bulkhead, "bulkhead");
		renderRing(builder,
				0,
				0,
				bulkhead.getOuterRadius(),
				0,
				additionalHoles,
				options);
	}

	public static void renderRing(DXFBuilder builder,
								  double centerX,
								  double centerY,
								  double outerRadius,
								  double innerRadius,
								  Collection<Hole> holes,
								  DXFExportOptions options) {
		Objects.requireNonNull(builder, "builder");
		Objects.requireNonNull(options, "options");

		builder.addCircle(centerX, centerY, outerRadius, options.getStrokeColor(), "PROFILES");
		if (innerRadius > 0) {
			builder.addCircle(centerX, centerY, innerRadius, options.getStrokeColor(), "PROFILES");
		}

		if (options.isDrawCrosshair()) {
			double crosshairSizeM = options.getCrosshairSizeMm() / 1000.0;
			double halfLength = clampCrosshairHalfLength(crosshairSizeM / 2.0, outerRadius);
			builder.addCrosshair(centerX, centerY, halfLength, halfLength, options.getCrosshairColor(), "CROSSHAIRS");
		}

		if (holes != null) {
			for (Hole hole : holes) {
				builder.addCircle(centerX + hole.offsetY(), centerY + hole.offsetZ(), hole.radius(),
						options.getStrokeColor(), "PROFILES");
				if (options.isDrawCrosshair()) {
					double crosshairSizeM = options.getCrosshairSizeMm() / 1000.0;
					double halfLength = clampCrosshairHalfLength(crosshairSizeM / 2.0, hole.radius());
					builder.addCrosshair(centerX + hole.offsetY(), centerY + hole.offsetZ(),
							halfLength, halfLength, options.getCrosshairColor(), "CROSSHAIRS");
				}
			}
		}
	}

	/**
	 * Clamp crosshair half-length to ensure it's within reasonable bounds and doesn't exceed the part radius.
	 */
	private static double clampCrosshairHalfLength(double requestedHalfLength, double maxRadius) {
		double maxHalfLength = maxRadius * 0.9; // Leave 10% margin
		double clamped = Math.min(requestedHalfLength, maxHalfLength);
		return Math.max(MIN_CROSSHAIR_HALF_LENGTH, Math.min(MAX_CROSSHAIR_HALF_LENGTH, clamped));
	}

	public static List<Hole> holesFromMotorMounts(List<InnerTube> motorMounts) {
		if (motorMounts == null || motorMounts.isEmpty()) {
			return Collections.emptyList();
		}

		List<Hole> holes = new ArrayList<>();
		for (InnerTube tube : motorMounts) {
			if (tube == null) {
				continue;
			}
			List<CoordinateIF> clusterPoints = tube.getClusterPoints();
			if (clusterPoints == null || clusterPoints.isEmpty()) {
				holes.add(new Hole(tube.getRadialShiftY(), tube.getRadialShiftZ(), tube.getOuterRadius()));
				continue;
			}

			for (CoordinateIF coordinate : clusterPoints) {
				holes.add(new Hole(coordinate.getY(), coordinate.getZ(), tube.getOuterRadius()));
			}
		}

		return holes;
	}
}
