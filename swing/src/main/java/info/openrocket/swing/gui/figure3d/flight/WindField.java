package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.swing.gui.figure3d.animation.SimToWorld;
import org.joml.Vector3f;

import java.util.List;

/**
 * The wind a simulation branch recorded along its flight, as an engine-space velocity over
 * time. The simulator stores the wind speed and, like the launch conditions, the direction the
 * wind blows from, clockwise from north (a descending parachute drifts the opposite way).
 * Exhaust smoke released at a moment drifts with the wind recorded then, at the altitude the
 * rocket was flying.
 */
final class WindField {
	private static final WindField CALM = new WindField(new double[] { 0.0 }, new double[] { 0.0 },
			new double[] { 0.0 });

	private final double[] times;
	private final double[] east;
	private final double[] north;

	private WindField(double[] times, double[] east, double[] north) {
		this.times = times;
		this.east = east;
		this.north = north;
	}

	static WindField calm() {
		return CALM;
	}

	static WindField fromBranch(FlightDataBranch branch) {
		if (branch == null) {
			return CALM;
		}
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);
		List<Double> speed = branch.get(FlightDataType.TYPE_WIND_VELOCITY);
		List<Double> direction = branch.get(FlightDataType.TYPE_WIND_DIRECTION);
		if (time == null || speed == null || direction == null) {
			return CALM;
		}
		int count = Math.min(time.size(), Math.min(speed.size(), direction.size()));
		if (count == 0) {
			return CALM;
		}
		double[] times = new double[count];
		double[] east = new double[count];
		double[] north = new double[count];
		for (int i = 0; i < count; i++) {
			times[i] = finiteOr(time.get(i), i > 0 ? times[i - 1] : 0.0);
			double v = finiteOr(speed.get(i), 0.0);
			double heading = finiteOr(direction.get(i), 0.0);
			// The air moves away from the heading. Components rather than angles, so
			// interpolating across north cannot swing the wind.
			east[i] = -v * Math.sin(heading);
			north[i] = -v * Math.cos(heading);
		}
		return new WindField(times, east, north);
	}

	/** Wind velocity at the given flight time in engine units per second. */
	Vector3f velocityAt(double time) {
		// First sample strictly after the time.
		int low = 0;
		int upper = times.length;
		while (low < upper) {
			int middle = (low + upper) >>> 1;
			if (times[middle] <= time) {
				low = middle + 1;
			} else {
				upper = middle;
			}
		}
		if (upper == 0) {
			return toEngine(east[0], north[0]);
		}
		if (upper == times.length) {
			return toEngine(east[times.length - 1], north[times.length - 1]);
		}
		int lower = upper - 1;
		double span = times[upper] - times[lower];
		double fraction = span > 0.0 ? (time - times[lower]) / span : 0.0;
		return toEngine(east[lower] + (east[upper] - east[lower]) * fraction,
				north[lower] + (north[upper] - north[lower]) * fraction);
	}

	private static Vector3f toEngine(double east, double north) {
		return SimToWorld.toEngine((float) east, (float) north, 0.0f);
	}

	private static double finiteOr(Double value, double fallback) {
		return value != null && Double.isFinite(value) ? value : fallback;
	}
}
