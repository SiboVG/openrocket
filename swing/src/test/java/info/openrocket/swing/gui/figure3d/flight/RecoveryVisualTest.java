package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Streamer;
import info.openrocket.swing.gui.figure3d.constants.RenderingConstants;
import info.openrocket.swing.gui.figure3d.geometry.Mesh;
import info.openrocket.swing.util.BaseTestCase;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryVisualTest extends BaseTestCase {
	private static final float SCALE = RenderingConstants.WORLD_SCALE;
	private static final float ROCKET_LENGTH = 1.0f * SCALE;

	@Test
	void parachuteIsSizedFromItsDiameterAndLines() {
		Parachute parachute = new Parachute();
		parachute.setDiameter(0.6);
		parachute.setLineLength(0.5);

		Flight3DPanel.RecoverySize size = Flight3DPanel.recoverySize(parachute, ROCKET_LENGTH);

		assertFalse(size.streamer());
		assertEquals(0.3f * SCALE, size.width(), 1e-4f, "Canopy radius is half the diameter");
		assertEquals(0.5f * SCALE, size.length(), 1e-4f);
	}

	@Test
	void streamerIsSizedFromItsStrip() {
		Streamer streamer = new Streamer();
		streamer.setStripLength(1.5);
		streamer.setStripWidth(0.05);

		Flight3DPanel.RecoverySize size = Flight3DPanel.recoverySize(streamer, ROCKET_LENGTH);

		assertTrue(size.streamer());
		assertEquals(0.05f * SCALE, size.width(), 1e-4f);
		assertEquals(1.5f * SCALE, size.length(), 1e-4f);
	}

	@Test
	void extremeOrUnknownDevicesStayInProportionToTheRocket() {
		Parachute tiny = new Parachute();
		tiny.setDiameter(0.001);
		assertEquals(0.2f * ROCKET_LENGTH, Flight3DPanel.recoverySize(tiny, ROCKET_LENGTH).width(), 1e-4f);

		Flight3DPanel.RecoverySize other = Flight3DPanel.recoverySize(new BodyTube(), ROCKET_LENGTH);
		assertFalse(other.streamer());
		assertEquals(0.6f * ROCKET_LENGTH, other.width(), 1e-4f);
	}

	@Test
	void deploymentOpensFromPackedWithAnOvershootThenSettles() {
		assertEquals(0.15f, Flight3DPanel.recoveryOpening(0.0), 1e-6f);
		float peak = 0.0f;
		for (double age = 0.0; age <= Flight3DPanel.RECOVERY_INFLATION_SECONDS; age += 0.01) {
			peak = Math.max(peak, Flight3DPanel.recoveryOpening(age));
		}
		assertTrue(peak > 1.02f && peak < 1.2f, "A brief, modest overshoot: " + peak);
		assertEquals(1.0f, Flight3DPanel.recoveryOpening(Flight3DPanel.RECOVERY_INFLATION_SECONDS), 1e-6f);
		assertEquals(1.0f, Flight3DPanel.recoveryOpening(30.0), 1e-6f);
	}

	@Test
	void streamerRibbonRisesFromItsAttachment() {
		Mesh ribbon = Flight3DPanel.createStreamerGeometry(2.0f, 30.0f);

		Vector3f min = ribbon.getBoundsMin(new Vector3f());
		Vector3f max = ribbon.getBoundsMax(new Vector3f());
		assertEquals(0.0f, min.y, 1e-5f);
		assertEquals(30.0f, max.y, 1e-4f);
		assertEquals(2.0f, max.x - min.x, 1e-4f);
	}
}
