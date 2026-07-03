package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.swing.gui.figure3d.animation.PoseProvider;
import info.openrocket.swing.gui.figure3d.constants.RenderingConstants;
import info.openrocket.swing.util.BaseTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FlightReplayDataTest extends BaseTestCase {

	@Test
	void mapsStagesToLargestBranchStageNotGreaterThanStageNumber() {
		Rocket rocket = new Rocket();
		AxialStage stage0 = addStage(rocket);
		AxialStage stage1 = addStage(rocket);
		AxialStage stage2 = addStage(rocket);

		FlightReplayData replay = new FlightReplayData(new FlightData(
				branch("sustainer", 0.0, 10.0, 0.0, 10.0),
				branch("booster", 2.0, 4.0, 20.0, 40.0)), rocket);

		Map<AxialStage, PoseProvider> providers = replay.getProvidersByStage();
		assertSame(replay.getPrimaryProvider(), providers.get(stage0));
		assertSame(providers.get(stage1), providers.get(stage2),
				"A later stage without its own branch should use the closest earlier branch");
	}

	@Test
	void exposesTimeRangeAcrossAllBranchesAndProvidersClampAtBranchEnds() {
		Rocket rocket = new Rocket();
		AxialStage stage0 = addStage(rocket);
		AxialStage stage1 = addStage(rocket);

		FlightReplayData replay = new FlightReplayData(new FlightData(
				branch("primary", 0.0, 10.0, 0.0, 100.0),
				branch("separated", 2.0, 4.0, 20.0, 40.0)), rocket);

		assertEquals(0.0, replay.getStartTime());
		assertEquals(10.0, replay.getEndTime());
		assertEquals(100.0 * RenderingConstants.WORLD_SCALE,
				replay.getProvidersByStage().get(stage0).getPosition(20.0).y, 1e-5);
		assertEquals(40.0 * RenderingConstants.WORLD_SCALE,
				replay.getProvidersByStage().get(stage1).getPosition(20.0).y, 1e-5);
	}

	@Test
	void collectsEventsFromAllBranchesAndDedupesById() {
		Rocket rocket = new Rocket();
		addStage(rocket);

		UUID duplicateId = UUID.randomUUID();
		FlightDataBranch primary = branch("primary", 0.0, 10.0, 0.0, 10.0);
		FlightDataBranch booster = branch("booster", 2.0, 4.0, 20.0, 40.0);
		primary.addEvent(new FlightEvent(FlightEvent.Type.LAUNCH, 0.0, null, null, duplicateId));
		booster.addEvent(new FlightEvent(FlightEvent.Type.LAUNCH, 0.0, null, null, duplicateId));
		booster.addEvent(new FlightEvent(FlightEvent.Type.APOGEE, 3.0));

		List<FlightEvent> events = new FlightReplayData(new FlightData(primary, booster), rocket).getAllEvents();

		assertEquals(2, events.size());
		assertEquals(FlightEvent.Type.LAUNCH, events.get(0).getType());
		assertEquals(FlightEvent.Type.APOGEE, events.get(1).getType());
	}

	@Test
	void extractsAndMergesBurnIntervalsByStage() {
		Rocket rocket = new Rocket();
		AxialStage stage = addStage(rocket);
		BodyTube motorMount = new BodyTube();
		stage.addChild(motorMount);

		FlightDataBranch primary = branch("primary", 0.0, 10.0, 0.0, 10.0);
		primary.addEvent(new FlightEvent(FlightEvent.Type.IGNITION, 1.0, motorMount));
		primary.addEvent(new FlightEvent(FlightEvent.Type.IGNITION, 1.5, motorMount));
		primary.addEvent(new FlightEvent(FlightEvent.Type.BURNOUT, 2.0, motorMount));
		primary.addEvent(new FlightEvent(FlightEvent.Type.BURNOUT, 3.0, motorMount));

		FlightReplayData replay = new FlightReplayData(new FlightData(primary), rocket);

		List<FlightReplayData.BurnInterval> stageIntervals = replay.getBurnIntervalsByStage().get(stage);
		assertEquals(1, stageIntervals.size());
		assertEquals(1.0, stageIntervals.get(0).start());
		assertEquals(3.0, stageIntervals.get(0).end());
		assertEquals(stageIntervals, replay.getBurnIntervals());
	}

	@Test
	void closesOpenEndedBurnAtFlightEnd() {
		Rocket rocket = new Rocket();
		AxialStage stage = addStage(rocket);
		BodyTube motorMount = new BodyTube();
		stage.addChild(motorMount);

		FlightDataBranch primary = branch("primary", 0.0, 10.0, 0.0, 10.0);
		// Ignition with no matching burnout (e.g. simulation ended mid-burn).
		primary.addEvent(new FlightEvent(FlightEvent.Type.IGNITION, 1.0, motorMount));

		FlightReplayData replay = new FlightReplayData(new FlightData(primary), rocket);

		List<FlightReplayData.BurnInterval> intervals = replay.getBurnIntervalsByStage().get(stage);
		assertEquals(1, intervals.size());
		assertEquals(1.0, intervals.get(0).start());
		assertEquals(10.0, intervals.get(0).end(), "an unclosed burn should extend to the flight end time");
	}

	@Test
	void keepsNonOverlappingBurnsSeparate() {
		Rocket rocket = new Rocket();
		AxialStage stage = addStage(rocket);
		BodyTube motorMount = new BodyTube();
		stage.addChild(motorMount);

		FlightDataBranch primary = branch("primary", 0.0, 10.0, 0.0, 10.0);
		primary.addEvent(new FlightEvent(FlightEvent.Type.IGNITION, 1.0, motorMount));
		primary.addEvent(new FlightEvent(FlightEvent.Type.BURNOUT, 2.0, motorMount));
		primary.addEvent(new FlightEvent(FlightEvent.Type.IGNITION, 5.0, motorMount));
		primary.addEvent(new FlightEvent(FlightEvent.Type.BURNOUT, 6.0, motorMount));

		FlightReplayData replay = new FlightReplayData(new FlightData(primary), rocket);

		List<FlightReplayData.BurnInterval> intervals = replay.getBurnIntervalsByStage().get(stage);
		assertEquals(2, intervals.size());
		assertEquals(1.0, intervals.get(0).start());
		assertEquals(2.0, intervals.get(0).end());
		assertEquals(5.0, intervals.get(1).start());
		assertEquals(6.0, intervals.get(1).end());
	}

	private static AxialStage addStage(Rocket rocket) {
		AxialStage stage = new AxialStage();
		rocket.addChild(stage);
		return stage;
	}

	private static FlightDataBranch branch(String name, double startTime, double endTime,
			double startAltitude, double endAltitude) {
		FlightDataBranch branch = new FlightDataBranch(name,
				FlightDataType.TYPE_TIME,
				FlightDataType.TYPE_POSITION_X,
				FlightDataType.TYPE_POSITION_Y,
				FlightDataType.TYPE_ALTITUDE);
		addPoint(branch, startTime, startAltitude);
		addPoint(branch, endTime, endAltitude);
		return branch;
	}

	private static void addPoint(FlightDataBranch branch, double time, double altitude) {
		branch.addPoint();
		branch.setValue(FlightDataType.TYPE_TIME, time);
		branch.setValue(FlightDataType.TYPE_POSITION_X, 0.0);
		branch.setValue(FlightDataType.TYPE_POSITION_Y, 0.0);
		branch.setValue(FlightDataType.TYPE_ALTITUDE, altitude);
	}
}
