package info.openrocket.swing.gui.figure3d.flight;

/**
 * Camera behaviour for the 3D flight replay.
 */
public enum FlightCameraMode {
	/** Frame the entire flight trajectory; the user orbits/zooms around the whole path. */
	OVERVIEW("Whole flight"),
	/** Keep the camera centered on the rocket as it flies. */
	FOLLOW("Follow rocket"),
	/** Ride just behind the rocket, close enough to see it up close through the flight. */
	ONBOARD("Onboard"),
	/** A fixed camera near the pad that tracks the rocket, like real launch footage. */
	PAD("Launch pad");

	private final String label;

	FlightCameraMode(String label) {
		this.label = label;
	}

	@Override
	public String toString() {
		return label;
	}
}
