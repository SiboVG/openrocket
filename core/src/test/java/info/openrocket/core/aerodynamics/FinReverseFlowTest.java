package info.openrocket.core.aerodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import info.openrocket.core.aerodynamics.barrowman.FinSetCalc;
import info.openrocket.core.aerodynamics.barrowman.TubeFinSetCalc;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.TubeFinSet;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;
import info.openrocket.core.util.Transformation;

import org.junit.jupiter.api.Test;

/**
 * Tests fin normal forces when the airflow approaches the rocket from behind.
 */
public class FinReverseFlowTest extends BaseTestCase {
	private static final double EPSILON = 0.000001;
	private static final double TEST_ANGLE = Math.toRadians(10);

	/**
	 * Verify that planar-fin normal force is symmetric between forward and reverse
	 * oblique flow and vanishes when the reverse flow is exactly axial.
	 */
	@Test
	public void testPlanarFinNormalForceInReverseFlow() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		TrapezoidFinSet fins = (TrapezoidFinSet) rocket.getChild(0).getChild(1).getChild(0);
		FlightConditions conditions = new FlightConditions(rocket.getSelectedConfiguration());

		conditions.setAOA(TEST_ANGLE);
		AerodynamicForces forwardForces = calculatePlanarFinForces(fins, conditions);
		conditions.setAOA(Math.PI - TEST_ANGLE);
		AerodynamicForces reverseForces = calculatePlanarFinForces(fins, conditions);
		conditions.setAOA(Math.PI);
		AerodynamicForces axialReverseForces = calculatePlanarFinForces(fins, conditions);

		assertTrue(forwardForces.getCN() > 0, "The forward-flow reference force should be positive");
		assertEquals(forwardForces.getCN(), reverseForces.getCN(), EPSILON,
				"Planar-fin CN should be symmetric about 90 degrees");
		assertEquals(0, axialReverseForces.getCN(), EPSILON,
				"Planar-fin CN should vanish in axial reverse flow");
		assertEquals(0, axialReverseForces.getCm(), EPSILON,
				"Planar-fin pitching moment should vanish in axial reverse flow");
	}

	/**
	 * Verify the same reverse-flow behavior for tube fins, which use a separate
	 * aerodynamic calculator.
	 */
	@Test
	public void testTubeFinNormalForceInReverseFlow() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		BodyTube body = (BodyTube) rocket.getChild(0).getChild(1);
		TubeFinSet tubeFins = new TubeFinSet();
		tubeFins.setOuterRadius(0.027);
		tubeFins.setThickness(0.002);
		tubeFins.setLength(0.05);
		body.addChild(tubeFins);

		FlightConditions conditions = new FlightConditions(rocket.getSelectedConfiguration());
		TubeFinSetCalc calculator = new TubeFinSetCalc(tubeFins);

		conditions.setAOA(TEST_ANGLE);
		AerodynamicForces forwardForces = calculateTubeFinForces(calculator, conditions);
		conditions.setAOA(Math.PI - TEST_ANGLE);
		AerodynamicForces reverseForces = calculateTubeFinForces(calculator, conditions);
		conditions.setAOA(Math.PI);
		AerodynamicForces axialReverseForces = calculateTubeFinForces(calculator, conditions);

		assertTrue(forwardForces.getCN() > 0, "The forward-flow reference force should be positive");
		assertEquals(forwardForces.getCN(), reverseForces.getCN(), EPSILON,
				"Tube-fin CN should be symmetric about 90 degrees");
		assertEquals(0, axialReverseForces.getCN(), EPSILON,
				"Tube-fin CN should vanish in axial reverse flow");
		assertEquals(0, axialReverseForces.getCm(), EPSILON,
				"Tube-fin pitching moment should vanish in axial reverse flow");
	}

	/** Calculate and sum the forces from every fin instance in a planar fin set. */
	private AerodynamicForces calculatePlanarFinForces(TrapezoidFinSet fins, FlightConditions conditions) {
		FinSetCalc calculator = new FinSetCalc(fins);
		AerodynamicForces totalForces = new AerodynamicForces().zero();
		for (int i = 0; i < fins.getFinCount(); i++) {
			AerodynamicForces instanceForces = new AerodynamicForces();
			calculator.calculateNonaxialForces(conditions,
					Transformation.rotate_x(Math.PI * i / fins.getFinCount()), instanceForces, new WarningSet());
			totalForces.merge(instanceForces);
		}
		return totalForces;
	}

	/** Calculate the force from one tube-fin instance. */
	private AerodynamicForces calculateTubeFinForces(TubeFinSetCalc calculator, FlightConditions conditions) {
		AerodynamicForces forces = new AerodynamicForces();
		calculator.calculateNonaxialForces(conditions, Transformation.IDENTITY, forces, new WarningSet());
		return forces;
	}
}
