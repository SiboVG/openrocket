package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.MathUtil;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

/**
 * A compact telemetry strip for the flight replay: shows the simulation, its flight configuration,
 * and the altitude/velocity/acceleration/position interpolated at the current playback time. Lives
 * beside the 3D view (a Swing component cannot overlay the heavyweight GL canvas).
 */
@SuppressWarnings("serial")
class FlightMetricsPanel extends JPanel {
	private static final int POLL_INTERVAL_MS = 100;
	private static final String EMPTY = "—";

	private final JLabel simulationLabel = valueLabel();
	private final JLabel configLabel = valueLabel();
	private final JLabel timeLabel = valueLabel();
	private final JLabel altitudeLabel = valueLabel();
	private final JLabel velocityLabel = valueLabel();
	private final JLabel accelerationLabel = valueLabel();
	private final JLabel positionLabel = valueLabel();
	private final Timer pollTimer = new Timer(POLL_INTERVAL_MS, e -> refresh());

	private PlaybackClock clock;
	private List<Double> times;
	private List<Double> altitude;
	private List<Double> velocity;
	private List<Double> acceleration;
	private List<Double> east;
	private List<Double> north;

	FlightMetricsPanel() {
		super(new FlowLayout(FlowLayout.LEFT, 14, 4));
		setBackground(new Color(0x1E1E1E));
		addField("Sim", simulationLabel);
		addField("Config", configLabel);
		addField("T", timeLabel);
		addField("Alt", altitudeLabel);
		addField("Vel", velocityLabel);
		addField("Accel", accelerationLabel);
		addField("Pos", positionLabel);
	}

	void setReplay(Simulation simulation, PlaybackClock clock) {
		this.clock = clock;
		if (simulation == null || clock == null || !simulation.hasSimulationData()) {
			this.times = null;
			pollTimer.stop();
			clearValues();
			return;
		}

		simulationLabel.setText(orEmpty(simulation.getName()));
		configLabel.setText(orEmpty(simulation.getSimulatedConfigurationDescription()));

		FlightData data = simulation.getSimulatedData();
		FlightDataBranch branch = data.getBranch(0);
		this.times = branch.get(FlightDataType.TYPE_TIME);
		this.altitude = branch.get(FlightDataType.TYPE_ALTITUDE);
		this.velocity = branch.get(FlightDataType.TYPE_VELOCITY_TOTAL);
		this.acceleration = branch.get(FlightDataType.TYPE_ACCELERATION_TOTAL);
		this.east = branch.get(FlightDataType.TYPE_POSITION_X);
		this.north = branch.get(FlightDataType.TYPE_POSITION_Y);

		refresh();
		pollTimer.start();
	}

	void dispose() {
		pollTimer.stop();
	}

	private void refresh() {
		if (clock == null || times == null || times.isEmpty()) {
			return;
		}
		double t = clock.getTime();
		timeLabel.setText(String.format("%.2f s", t));
		altitudeLabel.setText(formatMetric(altitude, t, FlightDataType.TYPE_ALTITUDE));
		velocityLabel.setText(formatMetric(velocity, t, FlightDataType.TYPE_VELOCITY_TOTAL));
		accelerationLabel.setText(formatMetric(acceleration, t, FlightDataType.TYPE_ACCELERATION_TOTAL));
		positionLabel.setText(formatPosition(t));
	}

	private String formatMetric(List<Double> values, double t, FlightDataType type) {
		if (values == null) {
			return EMPTY;
		}
		double value = MathUtil.interpolate(times, values, t);
		if (Double.isNaN(value)) {
			return EMPTY;
		}
		return type.getUnitGroup().toStringUnit(value);
	}

	private String formatPosition(double t) {
		if (east == null || north == null) {
			return EMPTY;
		}
		double x = MathUtil.interpolate(times, east, t);
		double y = MathUtil.interpolate(times, north, t);
		if (Double.isNaN(x) || Double.isNaN(y)) {
			return EMPTY;
		}
		UnitGroup distance = FlightDataType.TYPE_POSITION_X.getUnitGroup();
		return "E " + distance.toStringUnit(x) + ", N " + distance.toStringUnit(y);
	}

	private void clearValues() {
		for (JLabel label : new JLabel[] { simulationLabel, configLabel, timeLabel, altitudeLabel,
				velocityLabel, accelerationLabel, positionLabel }) {
			label.setText(EMPTY);
		}
	}

	private void addField(String name, JLabel valueLabel) {
		JPanel field = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		field.setOpaque(false);
		JLabel nameLabel = new JLabel(name + ":");
		nameLabel.setForeground(new Color(0x9AA0A6));
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 11f));
		field.add(nameLabel);
		field.add(valueLabel);
		add(field);
	}

	private static JLabel valueLabel() {
		JLabel label = new JLabel(EMPTY);
		label.setForeground(new Color(0xE8EAED));
		label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
		return label;
	}

	private static String orEmpty(String value) {
		return value == null || value.isBlank() ? EMPTY : value;
	}
}
