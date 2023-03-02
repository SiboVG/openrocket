package net.sf.openrocket.simulation.listeners;

import net.sf.openrocket.aerodynamics.AerodynamicForces;
import net.sf.openrocket.aerodynamics.FlightConditions;
import net.sf.openrocket.masscalc.RigidBody;
import net.sf.openrocket.models.atmosphere.AtmosphericConditions;
import net.sf.openrocket.motor.MotorConfigurationId;
import net.sf.openrocket.rocketcomponent.MotorMount;
import net.sf.openrocket.rocketcomponent.RecoveryDevice;
import net.sf.openrocket.simulation.AccelerationData;
import net.sf.openrocket.simulation.FlightEvent;
import net.sf.openrocket.simulation.MotorClusterState;
import net.sf.openrocket.simulation.SimulationStatus;
import net.sf.openrocket.simulation.exception.SimulationException;
import net.sf.openrocket.util.BugException;
import net.sf.openrocket.util.Coordinate;


/**
 * An abstract base class for implementing simulation listeners.  This class implements all
 * of the simulation listener interfaces using methods that have no effect on the simulation.
 * The recommended way of implementing simulation listeners is to extend this class.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */
public class AbstractSimulationListener implements SimulationListener, SimulationComputationListener,
		SimulationEventListener, Cloneable {
	
	////  SimulationListener  ////
	
	@Override
	public void startSimulation(SimulationStatus status) throws SimulationException {
		// No-op
	}
	
	@Override
	public void endSimulation(SimulationStatus status, SimulationException exception) {
		// No-op
	}
	
	@Override
	public boolean preStep(SimulationStatus status) {
		return true;
	}
	
	@Override
	public void postStep(SimulationStatus status) throws SimulationException {
		// No-op
	}
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * <em>This implementation of the method always returns <code>false</code>.</em>
	 */
	@Override
	public boolean isSystemListener() {
		return false;
	}
	
	
	////  SimulationEventListener  ////
	
	@Override
	public boolean addFlightEvent(SimulationStatus status, FlightEvent event) {
		return true;
	}
	
	@Override
	public boolean handleFlightEvent(SimulationStatus status, FlightEvent event) {
		return true;
	}
	
	@Override
	public boolean motorIgnition(SimulationStatus status, MotorConfigurationId motorId, MotorMount mount, MotorClusterState instance) {
		return true;
	}
	
	@Override
	public boolean recoveryDeviceDeployment(SimulationStatus status, RecoveryDevice recoveryDevice) {
		return true;
	}
	
	
	
	////  SimulationComputationListener  ////
	
	@Override
	public AccelerationData preAccelerationCalculation(SimulationStatus status) {
		return null;
	}
	
	@Override
	public AerodynamicForces preAerodynamicCalculation(SimulationStatus status) {
		return null;
	}
	
	@Override
	public AtmosphericConditions preAtmosphericModel(SimulationStatus status) {
		return null;
	}
	
	@Override
	public FlightConditions preFlightConditions(SimulationStatus status) {
		return null;
	}
	
	@Override
	public double preGravityModel(SimulationStatus status) {
		return Double.NaN;
	}
	
	@Override
	public RigidBody preMassCalculation(SimulationStatus status) {
		return null;
	}
	
	@Override
	public double preSimpleThrustCalculation(SimulationStatus status) {
		return Double.NaN;
	}
	
	@Override
	public Coordinate preWindModel(SimulationStatus status) {
		return null;
	}
	
	@Override
	public AccelerationData postAccelerationCalculation(SimulationStatus status, AccelerationData acceleration) {
		return null;
	}
	
	@Override
	public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces) {
		return null;
	}
	
	@Override
	public AtmosphericConditions postAtmosphericModel(SimulationStatus status, AtmosphericConditions atmosphericConditions) {
		return null;
	}
	
	@Override
	public FlightConditions postFlightConditions(SimulationStatus status, FlightConditions flightConditions) {
		return null;
	}
	
	@Override
	public double postGravityModel(SimulationStatus status, double gravity) {
		return Double.NaN;
	}
	
	@Override
	public RigidBody postMassCalculation(SimulationStatus status, RigidBody RigidBody) {
		return null;
	}
	
	@Override
	public double postSimpleThrustCalculation(SimulationStatus status, double thrust) {
		return Double.NaN;
	}
	
	@Override
	public Coordinate postWindModel(SimulationStatus status, Coordinate wind) {
		return null;
	}
	
	@Override
	public AbstractSimulationListener clone() {
		try {
			return (AbstractSimulationListener) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new BugException(e);
		}
	}
	
}
