package info.openrocket.swing.gui.figure3d.scene.orchestration;

import info.openrocket.core.motor.Motor;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.swing.gui.figure3d.geometry.RocketSceneSnapshot;
import info.openrocket.swing.gui.figure3d.scene.graph.Camera;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Scene3DOrchestratorTest {
	@Test
	void followCameraPreservesPanWhenAdvancingAndScrubbingBackward() {
		Camera camera = Camera.builder().withFixedCenterOfInterest(false).build();
		Vector3f launch = new Vector3f(0.0f, 1.0f, 0.0f);
		Vector3f apogee = new Vector3f(15.0f, 100.0f, -20.0f);
		Vector3f pan = new Vector3f(3.0f, 0.0f, 0.0f);
		camera.setCenterOfInterest(new Vector3f(launch).add(pan));
		Scene3DOrchestrator.trackFlightPivot(camera, launch, apogee);
		assertEquals(new Vector3f(apogee).add(pan), camera.getCenterOfInterest());
		Scene3DOrchestrator.trackFlightPivot(camera, apogee, launch);
		assertEquals(new Vector3f(launch).add(pan), camera.getCenterOfInterest());
	}
	@Test
	void padCameraStaysAtThePadEyeWhileAimingAtTheRocket() {
		Camera camera = Camera.builder().withFixedCenterOfInterest(false).build();
		Vector3f eye = new Vector3f(20.0f, 4.0f, 20.0f);
		for (float altitude : new float[] { 1.0f, 50.0f, 2_000.0f }) {
			Vector3f rocket = new Vector3f(3.0f, altitude, -2.0f);
			float distance = Scene3DOrchestrator.lookFrom(camera, eye, rocket);
			camera.update();

			assertEquals(eye.distance(rocket), distance, distance * 1e-5f);
			assertEquals(0.0f, camera.getPosition().distance(eye), distance * 1e-4f,
					"The pad camera must not move at altitude " + altitude);
			assertEquals(rocket, camera.getEffectiveLookAt());
		}
	}

	@Test
	void telephotoLensKeepsTheRocketAtItsLaunchSizeOnScreen() {
		float baseFov = (float) Math.toRadians(45.0);
		float launchDistance = 100.0f;
		float launchTan = Scene3DOrchestrator.telephotoTanHalfFieldOfView(baseFov, launchDistance, launchDistance);
		assertEquals(Math.tan(baseFov / 2.0), launchTan, 1e-6);
		// On-screen size is proportional to 1 / (distance * tan(fov / 2)).
		for (float distance : new float[] { 400.0f, 4_000.0f, 40_000.0f }) {
			float tan = Scene3DOrchestrator.telephotoTanHalfFieldOfView(baseFov, launchDistance, distance);
			assertEquals(launchDistance * launchTan, distance * tan, 1e-2f);
		}
		// Closer than at launch (e.g. landing near the pad) keeps the normal lens.
		assertEquals(launchTan, Scene3DOrchestrator.telephotoTanHalfFieldOfView(baseFov, launchDistance, 30.0f));
	}

	@Test
	void padWheelZoomScalesTheLensInsteadOfMovingTheEye() {
		Camera camera = Camera.builder().withFixedCenterOfInterest(false).build();
		Vector3f eye = new Vector3f(20.0f, 4.0f, 20.0f);
		float applied = Scene3DOrchestrator.lookFrom(camera, eye, new Vector3f(0.0f, 50.0f, 0.0f));

		camera.dolly(1.0f);
		float zoomIn = Scene3DOrchestrator.updatedPadZoomScale(1.0f, camera.getDistance(), applied);
		assertTrue(zoomIn < 1.0f, "Scrolling in must narrow the lens");
		assertEquals(1.0f, Scene3DOrchestrator.updatedPadZoomScale(1.0f, applied, applied));
		assertEquals(1.0f, Scene3DOrchestrator.updatedPadZoomScale(1.0f, camera.getDistance(), Float.NaN),
				"A reset must not read the fitted distance as a zoom");
	}

	@Test
	void padCameraDropsAPanOffsetLeftFromAnotherView() {
		Camera camera = Camera.builder().withFixedCenterOfInterest(false).build();
		camera.pan(40.0f, 30.0f, 800, 600);
		Vector3f rocket = new Vector3f(0.0f, 50.0f, 0.0f);

		Scene3DOrchestrator.lookFrom(camera, new Vector3f(20.0f, 4.0f, 20.0f), rocket);

		assertEquals(rocket, camera.getEffectiveLookAt(), "The pad view must aim exactly at the rocket");
	}

	@Test
	void derivesNozzlePositionAndDirectionFromRenderedMotorGeometry() {
		RocketComponent mount = mock(RocketComponent.class);
		Motor motor = mock(Motor.class);
		when(motor.getLength()).thenReturn(0.2);
		RocketSceneSnapshot.ParticleEmitterPlan plan = new RocketSceneSnapshot.ParticleEmitterPlan(
				mount,
				new org.joml.Vector3f(2.0f, 3.0f, 4.0f),
				new Matrix4f().rotateZ((float) (Math.PI / 2.0)),
				motor,
				10.0f);

		Scene3DOrchestrator.MotorExhaustMount result =
				Scene3DOrchestrator.createMotorExhaustMounts(List.of(plan)).get(0);

		assertSame(mount, result.mountComponent());
		assertEquals(0.0f, result.exhaustDirection().x, 1e-5f);
		assertEquals(1.0f, result.exhaustDirection().y, 1e-5f);
		assertEquals(2.0f, result.nozzlePosition().x, 1e-5f);
		assertEquals(4.0f, result.nozzlePosition().y, 1e-5f);
		assertEquals(4.0f, result.nozzlePosition().z, 1e-5f);
	}
}
