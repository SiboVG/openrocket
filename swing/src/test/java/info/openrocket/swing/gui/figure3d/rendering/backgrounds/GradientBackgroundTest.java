package info.openrocket.swing.gui.figure3d.rendering.backgrounds;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradientBackgroundTest {
	@Test
	void alignmentIsExplicitSoDesignGradientsRemainScreenFixed() {
		Vector3f sky = new Vector3f(0.7f, 0.8f, 0.9f);
		Vector3f ground = new Vector3f(0.7f, 0.9f, 0.7f);

		assertFalse(new GradientBackground(sky, ground).isWorldAligned());
		assertTrue(GradientBackground.worldAligned(sky, ground).isWorldAligned());
	}
}
