package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.swing.gui.figure3d.scene.graph.SceneObject;
import info.openrocket.swing.gui.figure3d.scene.graph.SceneView;
import info.openrocket.swing.gui.figure3d.geometry.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class Flight3DPanelTest {
	@Test
	void parachuteGeometryIsAnOpenDomeWithSuspensionLines() {
		Flight3DPanel.ParachuteGeometry geometry = Flight3DPanel.createParachuteGeometry(10.0f);

		assertEquals(8, geometry.canopyPanels().size());
		assertEquals(8, geometry.suspensionLines().size());
		assertEquals(9.0f, geometry.lineLength(), 1e-6f);

		for (Mesh panel : geometry.canopyPanels()) {
			Vector3f min = panel.getBoundsMin(new Vector3f());
			Vector3f max = panel.getBoundsMax(new Vector3f());
			assertTrue(min.z >= -1e-5f, "The canopy must not contain a lower hemisphere");
			assertTrue(max.z > 0.0f, "Each fabric panel must rise into a dome");
		}
		for (Mesh line : geometry.suspensionLines()) {
			assertEquals(-9.0f, line.getBoundsMin(new Vector3f()).z, 0.1f);
			assertTrue(line.getBoundsMax(new Vector3f()).z > -0.1f);
		}
	}

	@Test
	void replayCameraModesExcludeOnboardView() {
		assertArrayEquals(new FlightCameraMode[] {
				FlightCameraMode.OVERVIEW, FlightCameraMode.FOLLOW, FlightCameraMode.PAD
		}, FlightCameraMode.values());
	}

	@Test
	void trajectoryDecorationsShrinkAsTheCameraMovesCloser() {
		assertEquals(1.0f, Flight3DPanel.decorationScale(1_000.0f, 1_000.0f), 1e-6f);
		assertEquals(0.5f, Flight3DPanel.decorationScale(500.0f, 1_000.0f), 1e-6f);
		assertEquals(0.1f, Flight3DPanel.decorationScale(100.0f, 1_000.0f), 1e-6f);
		assertEquals(0.04f, Flight3DPanel.decorationScale(1.0f, 1_000.0f), 1e-6f);
		assertEquals(1.0f, Flight3DPanel.decorationScale(2_000.0f, 1_000.0f), 1e-6f);
	}

	@Test
	void dynamicTrailsAreRemovedThroughTheSceneMutationApi() {
		SceneView scene = mock(SceneView.class);
		SceneObject trail = mock(SceneObject.class);
		List<SceneObject> trails = new ArrayList<>(List.of(trail));

		Flight3DPanel.removeAndCleanupObjects(scene, trails);

		verify(scene).removeObject(trail);
		verify(scene, never()).getObjects();
		verify(trail).cleanup();
		assertTrue(trails.isEmpty());
	}

	@Test
	void pausedPanelRendersOnlyWhenMarkedDirty() {
		Flight3DPanel panel = new Flight3DPanel();

		assertTrue(panel.shouldRenderOnTick());
		assertFalse(panel.shouldRenderOnTick());

		panel.requestRenderNow();
		assertTrue(panel.shouldRenderOnTick());
		assertFalse(panel.shouldRenderOnTick());
	}

	@Test
	void lowestCornerYFollowsTranslation() {
		Vector3f min = new Vector3f(-1.0f, -2.0f, -1.0f);
		Vector3f max = new Vector3f(1.0f, 2.0f, 1.0f);
		Matrix4f transform = new Matrix4f().translate(0.0f, 5.0f, 0.0f);

		float lowest = Flight3DPanel.lowestTransformedCornerY(min, max, transform);

		assertEquals(3.0f, lowest, 1e-5);
	}

	@Test
	void lowestCornerYReflectsRotationOfAnElongatedBody() {
		// A body from y=0 (nose) down to y=-10 (tail), rotated 90 deg so it lies along +x:
		// the lowest point should be 0 (the rotation maps the -y extent onto the x axis).
		Vector3f min = new Vector3f(-0.5f, -10.0f, -0.5f);
		Vector3f max = new Vector3f(0.5f, 0.0f, 0.5f);
		Matrix4f transform = new Matrix4f().rotateZ((float) Math.toRadians(90.0));

		float lowest = Flight3DPanel.lowestTransformedCornerY(min, max, transform);

		assertEquals(-0.5f, lowest, 1e-5);
	}
}
