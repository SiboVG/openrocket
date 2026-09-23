package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;

import java.util.List;

/**
 * A branch's recorded thrust relative to each burn's average, so the replay flame grows with
 * the ignition spike and dies down through the tail-off while looking as it always did at
 * average thrust. Without thrust data every burn reads as average.
 */
final class ThrustProfile {
	private static final int AVERAGE_SAMPLES = 64;
	static final ThrustProfile STEADY = new ThrustProfile(new double[0], new double[0], List.of());

	private final double[] times;
	private final double[] thrust;
	private final List<double[]> windows;
	private final double[] windowAverages;

	private ThrustProfile(double[] times, double[] thrust, List<double[]> windows) {
		this.times = times;
		this.thrust = thrust;
		this.windows = windows.stream().map(double[]::clone).toList();
		this.windowAverages = new double[this.windows.size()];
		for (int i = 0; i < windowAverages.length; i++) {
			double[] window = this.windows.get(i);
			double sum = 0.0;
			for (int s = 0; s < AVERAGE_SAMPLES; s++) {
				sum += thrustAt(window[0] + (window[1] - window[0]) * (s + 0.5) / AVERAGE_SAMPLES);
			}
			windowAverages[i] = sum / AVERAGE_SAMPLES;
		}
	}

	/** Uses the branch's thrust over the given burn windows ({start, end} pairs). */
	static ThrustProfile fromBranch(FlightDataBranch branch, List<double[]> burnWindows) {
		if (branch == null) {
			return STEADY;
		}
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);
		List<Double> force = branch.get(FlightDataType.TYPE_THRUST_FORCE);
		if (time == null || force == null || time.isEmpty() || force.isEmpty()) {
			return STEADY;
		}
		int count = Math.min(time.size(), force.size());
		double[] times = new double[count];
		double[] thrust = new double[count];
		for (int i = 0; i < count; i++) {
			Double t = time.get(i);
			Double f = force.get(i);
			times[i] = t != null && Double.isFinite(t) ? t : (i > 0 ? times[i - 1] : 0.0);
			thrust[i] = f != null && Double.isFinite(f) ? Math.max(0.0, f) : 0.0;
		}
		return new ThrustProfile(times, thrust, burnWindows);
	}

	/** Thrust at the time relative to the average of the burn it falls in; 1 outside burns. */
	double relativeThrustAt(double time) {
		for (int i = 0; i < windows.size(); i++) {
			double[] window = windows.get(i);
			if (time >= window[0] && time <= window[1]) {
				return windowAverages[i] > 0.0 ? thrustAt(time) / windowAverages[i] : 1.0;
			}
		}
		return 1.0;
	}

	private double thrustAt(double time) {
		if (times.length == 0) {
			return 0.0;
		}
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
			return thrust[0];
		}
		if (upper == times.length) {
			return thrust[times.length - 1];
		}
		double span = times[upper] - times[upper - 1];
		double fraction = span > 0.0 ? (time - times[upper - 1]) / span : 0.0;
		return thrust[upper - 1] + (thrust[upper] - thrust[upper - 1]) * fraction;
	}
}
