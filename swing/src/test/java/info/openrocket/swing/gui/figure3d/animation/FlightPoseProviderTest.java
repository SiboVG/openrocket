package info.openrocket.swing.gui.figure3d.animation;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.swing.gui.figure3d.constants.RenderingConstants;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlightPoseProviderTest {

	@Test
	void interpolatesPositionMidpoints() {
		FlightPoseProvider provider = FlightPoseProvider.fromFlightDataBranch(branchWithOrientation());

		Vector3f position = provider.getPosition(5.0);

		assertEquals(1.0 * RenderingConstants.WORLD_SCALE, position.x, 1e-5);
		assertEquals(2.0 * RenderingConstants.WORLD_SCALE, position.y, 1e-5);
		assertEquals(-2.0 * RenderingConstants.WORLD_SCALE, position.z, 1e-5);
	}

	@Test
	void clampsPositionAtBranchEnds() {
		FlightPoseProvider provider = FlightPoseProvider.fromFlightDataBranch(branchWithOrientation());

		Vector3f start = provider.getPosition(-1.0);
		Vector3f end = provider.getPosition(20.0);

		assertEquals(0.0, start.x, 1e-5);
		assertEquals(1.0 * RenderingConstants.WORLD_SCALE, start.y, 1e-5);
		assertEquals(0.0, start.z, 1e-5);
		assertEquals(2.0 * RenderingConstants.WORLD_SCALE, end.x, 1e-5);
		assertEquals(3.0 * RenderingConstants.WORLD_SCALE, end.y, 1e-5);
		assertEquals(-4.0 * RenderingConstants.WORLD_SCALE, end.z, 1e-5);
	}

	@Test
	void fallsBackToVelocityFacingWhenOrientationChannelsAreMissing() {
		FlightDataBranch branch = new FlightDataBranch("velocity",
				FlightDataType.TYPE_TIME,
				FlightDataType.TYPE_POSITION_X,
				FlightDataType.TYPE_POSITION_Y,
				FlightDataType.TYPE_ALTITUDE);
		addPoint(branch, 0.0, 0.0, 0.0, 0.0);
		addPoint(branch, 1.0, 1.0, 0.0, 0.0);

		Quaternionf orientation = FlightPoseProvider.fromFlightDataBranch(branch).getOrientation(0.5);
		Vector3f longAxis = orientation.transform(new Vector3f(-1.0f, 0.0f, 0.0f));

		assertEquals(1.0f, longAxis.x, 1e-5);
		assertEquals(0.0f, longAxis.y, 1e-5);
		assertEquals(0.0f, longAxis.z, 1e-5);
	}

	@Test
	void orientationThetaIsElevationFromHorizontal() {
		FlightDataBranch branch = new FlightDataBranch("vertical",
				FlightDataType.TYPE_TIME,
				FlightDataType.TYPE_POSITION_X,
				FlightDataType.TYPE_POSITION_Y,
				FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_ORIENTATION_THETA,
				FlightDataType.TYPE_ORIENTATION_PHI);
		addPoint(branch, 0.0, 0.0, 0.0, 0.0);
		addOrientation(branch, Math.PI / 2.0, 0.0);

		Quaternionf orientation = FlightPoseProvider.fromFlightDataBranch(branch).getOrientation(0.0);
		Vector3f noseAxis = orientation.transform(new Vector3f(-1.0f, 0.0f, 0.0f));
		Vector3f aftAxis = orientation.transform(new Vector3f(1.0f, 0.0f, 0.0f));

		assertEquals(0.0f, noseAxis.x, 1e-5);
		assertEquals(1.0f, noseAxis.y, 1e-5);
		assertEquals(0.0f, noseAxis.z, 1e-5);
		assertEquals(0.0f, aftAxis.x, 1e-5);
		assertEquals(-1.0f, aftAxis.y, 1e-5);
		assertEquals(0.0f, aftAxis.z, 1e-5);
	}

	@Test
	void orientationPhiZeroPointsNorth() {
		FlightDataBranch branch = new FlightDataBranch("north",
				FlightDataType.TYPE_TIME,
				FlightDataType.TYPE_POSITION_X,
				FlightDataType.TYPE_POSITION_Y,
				FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_ORIENTATION_THETA,
				FlightDataType.TYPE_ORIENTATION_PHI);
		addPoint(branch, 0.0, 0.0, 0.0, 0.0);
		addOrientation(branch, 0.0, 0.0);

		Quaternionf orientation = FlightPoseProvider.fromFlightDataBranch(branch).getOrientation(0.0);
		Vector3f noseAxis = orientation.transform(new Vector3f(-1.0f, 0.0f, 0.0f));

		assertEquals(0.0f, noseAxis.x, 1e-5);
		assertEquals(0.0f, noseAxis.y, 1e-5);
		assertEquals(-1.0f, noseAxis.z, 1e-5);
	}

	@Test
	void fallsBackToHorizontalDistanceAndDirectionWhenXYChannelsAreMissing() {
		FlightDataBranch branch = new FlightDataBranch("xy",
				FlightDataType.TYPE_TIME,
				FlightDataType.TYPE_POSITION_XY,
				FlightDataType.TYPE_POSITION_DIRECTION,
				FlightDataType.TYPE_ALTITUDE);
		branch.addPoint();
		branch.setValue(FlightDataType.TYPE_TIME, 0.0);
		branch.setValue(FlightDataType.TYPE_POSITION_XY, 2.0);
		branch.setValue(FlightDataType.TYPE_POSITION_DIRECTION, 0.0);
		branch.setValue(FlightDataType.TYPE_ALTITUDE, 0.0);
		branch.addPoint();
		branch.setValue(FlightDataType.TYPE_TIME, 1.0);
		branch.setValue(FlightDataType.TYPE_POSITION_XY, 2.0);
		branch.setValue(FlightDataType.TYPE_POSITION_DIRECTION, Math.PI / 2.0);
		branch.setValue(FlightDataType.TYPE_ALTITUDE, 0.0);

		FlightPoseProvider provider = FlightPoseProvider.fromFlightDataBranch(branch);
		Vector3f north = provider.getPosition(0.0);
		Vector3f east = provider.getPosition(1.0);

		assertEquals(0.0f, north.x, 1e-5);
		assertEquals(-2.0f * RenderingConstants.WORLD_SCALE, north.z, 1e-5);
		assertEquals(2.0f * RenderingConstants.WORLD_SCALE, east.x, 1e-5);
		assertEquals(0.0f, east.z, 1e-5);
	}

	private static FlightDataBranch branchWithOrientation() {
		FlightDataBranch branch = new FlightDataBranch("pose",
				FlightDataType.TYPE_TIME,
				FlightDataType.TYPE_POSITION_X,
				FlightDataType.TYPE_POSITION_Y,
				FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_ORIENTATION_THETA,
				FlightDataType.TYPE_ORIENTATION_PHI);
		addPoint(branch, 0.0, 0.0, 0.0, 1.0);
		addOrientation(branch, 0.0, 0.0);
		addPoint(branch, 10.0, 2.0, 4.0, 3.0);
		addOrientation(branch, 0.0, 0.0);
		return branch;
	}

	private static void addPoint(FlightDataBranch branch, double time, double east, double north, double altitude) {
		branch.addPoint();
		branch.setValue(FlightDataType.TYPE_TIME, time);
		branch.setValue(FlightDataType.TYPE_POSITION_X, east);
		branch.setValue(FlightDataType.TYPE_POSITION_Y, north);
		branch.setValue(FlightDataType.TYPE_ALTITUDE, altitude);
	}

	private static void addOrientation(FlightDataBranch branch, double theta, double phi) {
		branch.setValue(FlightDataType.TYPE_ORIENTATION_THETA, theta);
		branch.setValue(FlightDataType.TYPE_ORIENTATION_PHI, phi);
	}
}
