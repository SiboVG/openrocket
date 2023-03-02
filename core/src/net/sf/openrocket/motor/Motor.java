package net.sf.openrocket.motor;

import net.sf.openrocket.startup.Application;

public interface Motor {
	
	/**
	 * Enum of rocket motor types.
	 * 
	 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
	 */
    enum Type {
		SINGLE("Single-use", "Single-use solid propellant motor"), 
		RELOAD("Reloadable", "Reloadable solid propellant motor"), 
		HYBRID("Hybrid", "Hybrid rocket motor engine"), 
		UNKNOWN("Unknown", "Unknown motor type");
				
				
		private final String name;
		private final String description;
		
		Type(String name, String description) {
			this.name = name;
			this.description = description;
		}
		
		
		/**
		 * Return a short name of this motor type.
		 * @return  a short name of the motor type.
		 */
		public String getName() {
			return name;
		}
		
		/**
		 * Return a long description of this motor type.
		 * @return  a description of the motor type.
		 */
		public String getDescription() {
			return description;
		}
		
		@Override
		public String toString() {
			return name;
		}
	}
	
	double PSEUDO_TIME_EMPTY = Double.NaN;
	double PSEUDO_TIME_LAUNCH = 0.0;
	double PSEUDO_TIME_BURNOUT = Double.MAX_VALUE;
	
		
	/**
	 * Ejection charge delay value signifying a "plugged" motor with no ejection charge.
	 * The value is that of <code>Double.POSITIVE_INFINITY</code>.
	 */
    double PLUGGED_DELAY = Double.POSITIVE_INFINITY;
	
	
	/**
	 * Below what portion of maximum thrust is the motor chosen to be off when
	 * calculating average thrust and burn time.  NFPA 1125 defines the "official"
	 * burn time to be the time which the motor produces over 5% of its maximum thrust.
	 */
    double MARGINAL_THRUST = 0.05;
	
	
	
	/**
	 * Return the motor type.
	 * 
	 * @return  the motorType
	 */
    Type getMotorType();
	
	
	/**
	 * Return the motor code
	 * 
	 * @return the code
	 */
    String getCode();
	
	/**
	 * Return the common name of the motor.
	 * 
	 * @return the common name
	 */
    String getCommonName();
	
	/**
	 * Return the common name of the motor, including a delay.
	 * 
	 * @param delay  the delay of the motor.
	 * @return		 common name with delay.
	 */
    String getCommonName(double delay);
	
	/**
	 * Return the designation of the motor.
	 * 
	 * @return the designation
	 */
    String getDesignation();
	
	/**
	 * Return the designation of the motor, including a delay.
	 * 
	 * @param delay  the delay of the motor.
	 * @return		 designation with delay.
	 */
    String getDesignation(double delay);

	/**
	 * Returns the motor name, based on whether the preference is to use the designation or common name.
	 * @return the motor designation, if the preference is to use the designation, otherwise the common name.
	 */
	default String getMotorName() {
		boolean useDesignation = Application.getPreferences().getMotorNameColumn();
		return useDesignation ? getDesignation() : getCommonName();
	}

	/**
	 * Returns the motor name, including a delay, based on whether the preference is to use the designation or common name.
	 * @return the motor designation, including a delay, if the preference is to use the designation, otherwise the common name.
	 */
	default String getMotorName(double delay) {
		boolean useDesignation = Application.getPreferences().getMotorNameColumn();
		return useDesignation ? getDesignation(delay) : getCommonName(delay);
	}
	
	
	/**
	 * Return extra description for the motor.  This may include for example 
	 * comments on the source of the thrust curve.  The returned <code>String</code>
	 * may include new-lines.
	 * 
	 * @return the description
	 */
    String getDescription();
	
	
	/**
	 * Return the maximum diameter of the motor.
	 * 
	 * @return the diameter
	 */
    double getDiameter();
	
	/**
	 * Return the length of the motor.  This should be a "characteristic" length,
	 * and the exact definition may depend on the motor type.  Typically this should
	 * be the length from the bottom of the motor to the end of the maximum diameter
	 * portion, ignoring any smaller ejection charge compartments.
	 * 
	 * @return the length
	 */
    double getLength();
	
	String getDigest();
	
	double getAverageThrust( final double startTime, final double endTime );
	
	double getLaunchCGx();
	
	double getBurnoutCGx();
	
	double getLaunchMass();
	
	double getBurnoutMass();
	
	/**
	 * Return an estimate of the burn time of this motor, or NaN if an estimate is unavailable.
	 */
    double getBurnTimeEstimate();
	
	/**
	 * Return an estimate of the average thrust of this motor, or NaN if an estimate is unavailable.
	 */
    double getAverageThrustEstimate();
	
	/**
	 * Return an estimate of the maximum thrust of this motor, or NaN if an estimate is unavailable.
	 */
    double getMaxThrustEstimate();
	
	/**
	 * Return an estimate of the total impulse of this motor, or NaN if an estimate is unavailable.
	 */
    double getTotalImpulseEstimate();


	double getBurnTime();

	
	/**
	 * Return the thrust at a time offset from motor ignition
	 * 
	 * this is probably a badly-designed way to expose the thrust, but it's not worth worrying about until 
	 * there's a second (non-trivial) type of motor to support...
	 *
 	 * @param motorTime  time (in seconds) since motor ignition
 	 * @return thrust (double, in Newtons) at given time
 	 */
    double getThrust( final double motorTime);
	
	/**
	 * Return the mass at a time offset from motor ignition
	 * 
     * @param motorTime  time (in seconds) since motor ignition
	 */
    double getTotalMass( final double motorTime);

	double getPropellantMass( final Double motorTime);
	
	/** Return the mass at a given time 
	 * 
	 * @param motorTime  time (in seconds) since motor ignition
	 * @return
	 */
    double getCMx( final double motorTime);
	
	double getUnitIxx();
	
	double getUnitIyy();
	
	double getUnitIzz();

}
