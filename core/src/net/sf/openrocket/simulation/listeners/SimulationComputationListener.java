package net.sf.openrocket.simulation.listeners;

import net.sf.openrocket.aerodynamics.AerodynamicForces;
import net.sf.openrocket.aerodynamics.FlightConditions;
import net.sf.openrocket.masscalc.RigidBody;
import net.sf.openrocket.models.atmosphere.AtmosphericConditions;
import net.sf.openrocket.simulation.AccelerationData;
import net.sf.openrocket.simulation.SimulationStatus;
import net.sf.openrocket.simulation.exception.SimulationException;
import net.sf.openrocket.util.Coordinate;

/**
 * An interface containing listener callbacks relating to different computational aspects performed
 * during flight.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */
public interface SimulationComputationListener extends SimulationListener {
	
	
	////////  Computation/modeling related callbacks  ////////
	
	AccelerationData preAccelerationCalculation(SimulationStatus status) throws SimulationException;
	
	AccelerationData postAccelerationCalculation(SimulationStatus status, AccelerationData acceleration)
			throws SimulationException;
	
	AtmosphericConditions preAtmosphericModel(SimulationStatus status)
			throws SimulationException;
	
	AtmosphericConditions postAtmosphericModel(SimulationStatus status, AtmosphericConditions atmosphericConditions)
			throws SimulationException;
	
	
	Coordinate preWindModel(SimulationStatus status) throws SimulationException;
	
	Coordinate postWindModel(SimulationStatus status, Coordinate wind) throws SimulationException;
	
	
	double preGravityModel(SimulationStatus status) throws SimulationException;
	
	double postGravityModel(SimulationStatus status, double gravity) throws SimulationException;
	
	
	FlightConditions preFlightConditions(SimulationStatus status)
			throws SimulationException;
	
	FlightConditions postFlightConditions(SimulationStatus status, FlightConditions flightConditions)
			throws SimulationException;
	
	
	AerodynamicForces preAerodynamicCalculation(SimulationStatus status)
			throws SimulationException;
	
	AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces)
			throws SimulationException;
	
	RigidBody preMassCalculation(SimulationStatus status) throws SimulationException;
	
	RigidBody postMassCalculation(SimulationStatus status, RigidBody massData) throws SimulationException;
	
	
	double preSimpleThrustCalculation(SimulationStatus status) throws SimulationException;
	
	double postSimpleThrustCalculation(SimulationStatus status, double thrust) throws SimulationException;
	
}
