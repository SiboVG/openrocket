package info.openrocket.core.file.step;

import de.javagl.obj.FloatTuple;
import info.openrocket.core.file.step.STEPRevolvedSolid.Point3;
import info.openrocket.core.file.step.STEPRevolvedSolid.Profile;
import info.openrocket.core.file.step.STEPRevolvedSolid.ProfilePoint;
import info.openrocket.core.file.step.STEPRevolvedSolid.Vector3;
import info.openrocket.core.file.wavefrontobj.CoordTransform;
import info.openrocket.core.file.wavefrontobj.ObjUtils;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InstanceContext;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.util.CoordinateIF;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

/**
 * Converts axisymmetric OpenRocket components into continuous revolved-solid
 * descriptions suitable for advanced STEP B-reps.
 */
final class STEPRevolvedSolidFactory {
	private static final double GEOMETRY_EPSILON = 1.0E-9;
	private static final int ROOT_ITERATIONS = 60;

	private STEPRevolvedSolidFactory() {
		// Utility class.
	}

	static boolean supports(RocketComponent component) {
		return component instanceof BodyTube || component instanceof RingComponent
				|| component instanceof Transition;
	}

	/**
	 * Builds analytic solids, or returns an empty optional when this particular
	 * component has degenerate geometry that must retain the faceted fallback.
	 */
	static Optional<List<STEPRevolvedSolid>> create(RocketComponent component,
			FlightConfiguration configuration, CoordTransform transformer, String name,
			ObjUtils.LevelOfDetail levelOfDetail, boolean exportAllInstances) {
		if (component instanceof BodyTube bodyTube) {
			return createBodyTube(bodyTube, configuration, transformer, name, exportAllInstances);
		}
		if (component instanceof RingComponent ringComponent) {
			return createRingComponent(ringComponent, configuration, transformer, name, exportAllInstances);
		}
		if (component instanceof Transition transition) {
			return createTransition(transition, configuration, transformer, name, levelOfDetail,
					exportAllInstances);
		}
		return Optional.empty();
	}

	private static Optional<List<STEPRevolvedSolid>> createBodyTube(BodyTube bodyTube,
			FlightConfiguration configuration, CoordTransform transformer, String name,
			boolean exportAllInstances) {
		double length = bodyTube.getLength();
		double outerRadius = bodyTube.getOuterRadius();
		if (length <= GEOMETRY_EPSILON || outerRadius <= GEOMETRY_EPSILON
				|| (!bodyTube.isFilled() && bodyTube.getThickness() <= GEOMETRY_EPSILON)) {
			return Optional.empty();
		}

		Profile outerProfile = constantProfile(0.0, length, outerRadius);
		Profile innerProfile = null;
		if (!bodyTube.isFilled() && bodyTube.getInnerRadius() > GEOMETRY_EPSILON) {
			innerProfile = constantProfile(0.0, length, bodyTube.getInnerRadius());
		}
		return Optional.of(createForInstances(bodyTube, configuration, transformer, name, exportAllInstances,
				outerProfile, innerProfile, List.of()));
	}

	/**
	 * Represents internal tube and ring families as exact annular cylinders.  The
	 * active instance contexts carry the radial, clustered, and line-instance
	 * offsets used by components such as inner tubes and centering rings.
	 */
	private static Optional<List<STEPRevolvedSolid>> createRingComponent(RingComponent ringComponent,
			FlightConfiguration configuration, CoordTransform transformer, String name,
			boolean exportAllInstances) {
		double length = ringComponent.getLength();
		double outerRadius = ringComponent.getOuterRadius();
		double innerRadius = ringComponent.getInnerRadius();
		if (!Double.isFinite(length) || !Double.isFinite(outerRadius) || !Double.isFinite(innerRadius)
				|| length <= GEOMETRY_EPSILON || outerRadius <= GEOMETRY_EPSILON
				|| innerRadius < 0.0 || outerRadius - innerRadius <= GEOMETRY_EPSILON) {
			return Optional.empty();
		}

		Profile outerProfile = constantProfile(0.0, length, outerRadius);
		Profile innerProfile = innerRadius > GEOMETRY_EPSILON
				? constantProfile(0.0, length, innerRadius)
				: null;
		return Optional.of(createForInstances(ringComponent, configuration, transformer, name,
				exportAllInstances, outerProfile, innerProfile, List.of()));
	}

	private static Optional<List<STEPRevolvedSolid>> createTransition(Transition transition,
			FlightConfiguration configuration, CoordTransform transformer, String name,
			ObjUtils.LevelOfDetail levelOfDetail, boolean exportAllInstances) {
		double length = transition.getLength();
		double maximumRadius = Math.max(transition.getForeRadius(), transition.getAftRadius());
		if (length <= GEOMETRY_EPSILON || maximumRadius <= GEOMETRY_EPSILON
				|| (!transition.isFilled() && transition.getThickness() <= GEOMETRY_EPSILON)
				|| hasUnsupportedShoulder(transition)) {
			return Optional.empty();
		}

		int segmentCount = Math.max(8, levelOfDetail.getValue() / 3);
		DoubleUnaryOperator outerRadius = transition::getRadius;
		boolean foreTip = transition.getRadius(0.0) <= GEOMETRY_EPSILON;
		boolean aftTip = transition.getRadius(length) <= GEOMETRY_EPSILON;
		Profile outerProfile = sampleProfile(outerRadius, 0.0, length, segmentCount, foreTip, aftTip);
		Profile innerProfile = transition.isFilled()
				? null
				: createInnerProfile(transition, segmentCount);

		List<ShoulderProfile> shoulders = createShoulderProfiles(transition);
		return Optional.of(createForInstances(transition, configuration, transformer, name, exportAllInstances,
				outerProfile, innerProfile, shoulders));
	}

	private static boolean hasUnsupportedShoulder(Transition transition) {
		boolean hasForeShoulder = transition.getForeShoulderLength() > GEOMETRY_EPSILON
				&& transition.getForeRadius() > GEOMETRY_EPSILON
				&& transition.getForeShoulderRadius() > GEOMETRY_EPSILON;
		boolean hasAftShoulder = transition.getAftShoulderLength() > GEOMETRY_EPSILON
				&& transition.getAftRadius() > GEOMETRY_EPSILON
				&& transition.getAftShoulderRadius() > GEOMETRY_EPSILON;
		return (hasForeShoulder && transition.getForeShoulderThickness() <= GEOMETRY_EPSILON)
				|| (hasAftShoulder && transition.getAftShoulderThickness() <= GEOMETRY_EPSILON);
	}

	private static Profile createInnerProfile(Transition transition, int segmentCount) {
		double length = transition.getLength();
		double thickness = transition.getThickness();
		double foreValue = transition.getRadius(0.0) - thickness;
		double aftValue = transition.getRadius(length) - thickness;
		if (foreValue <= GEOMETRY_EPSILON && aftValue <= GEOMETRY_EPSILON) {
			return null;
		}

		double start = 0.0;
		double end = length;
		boolean forceStartZero = false;
		boolean forceEndZero = false;
		if (foreValue <= GEOMETRY_EPSILON) {
			start = findIncreasingZero(transition, thickness, length);
			forceStartZero = true;
		}
		if (aftValue <= GEOMETRY_EPSILON) {
			end = findDecreasingZero(transition, thickness, length);
			forceEndZero = true;
		}
		if (end - start <= GEOMETRY_EPSILON) {
			return null;
		}

		DoubleUnaryOperator innerRadius = x -> Math.max(transition.getRadius(x) - thickness, 0.0);
		return sampleProfile(innerRadius, start, end, segmentCount, forceStartZero, forceEndZero);
	}

	private static double findIncreasingZero(Transition transition, double thickness, double length) {
		double low = 0.0;
		double high = length;
		for (int i = 0; i < ROOT_ITERATIONS; i++) {
			double middle = (low + high) / 2.0;
			if (transition.getRadius(middle) - thickness > 0.0) {
				high = middle;
			} else {
				low = middle;
			}
		}
		return (low + high) / 2.0;
	}

	private static double findDecreasingZero(Transition transition, double thickness, double length) {
		double low = 0.0;
		double high = length;
		for (int i = 0; i < ROOT_ITERATIONS; i++) {
			double middle = (low + high) / 2.0;
			if (transition.getRadius(middle) - thickness > 0.0) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return (low + high) / 2.0;
	}

	private static List<ShoulderProfile> createShoulderProfiles(Transition transition) {
		List<ShoulderProfile> shoulders = new ArrayList<>(2);
		if (transition.getForeShoulderLength() > GEOMETRY_EPSILON
				&& transition.getForeRadius() > GEOMETRY_EPSILON
				&& transition.getForeShoulderRadius() > GEOMETRY_EPSILON) {
			shoulders.add(new ShoulderProfile("fore shoulder", -transition.getForeShoulderLength(), 0.0,
					transition.getForeShoulderRadius(), shoulderInnerRadius(transition.isFilled(),
							transition.isForeShoulderCapped(), transition.getForeShoulderRadius(),
							transition.getForeShoulderThickness())));
		}
		if (transition.getAftShoulderLength() > GEOMETRY_EPSILON
				&& transition.getAftRadius() > GEOMETRY_EPSILON
				&& transition.getAftShoulderRadius() > GEOMETRY_EPSILON) {
			shoulders.add(new ShoulderProfile("aft shoulder", transition.getLength(),
					transition.getLength() + transition.getAftShoulderLength(),
					transition.getAftShoulderRadius(), shoulderInnerRadius(transition.isFilled(),
							transition.isAftShoulderCapped(), transition.getAftShoulderRadius(),
							transition.getAftShoulderThickness())));
		}
		return shoulders;
	}

	private static double shoulderInnerRadius(boolean filled, boolean capped, double outerRadius,
			double thickness) {
		return filled || capped ? 0.0 : Math.max(outerRadius - thickness, 0.0);
	}

	private static List<STEPRevolvedSolid> createForInstances(RocketComponent component,
			FlightConfiguration configuration, CoordTransform transformer, String name, boolean exportAllInstances,
			Profile outerProfile, Profile innerProfile, List<ShoulderProfile> shoulders) {
		List<InstanceContext> contexts = configuration.getActiveInstances().getInstanceContexts(component);
		if (!exportAllInstances && !contexts.isEmpty()) {
			contexts = contexts.subList(0, 1);
		}

		Vector3 axis = vector(transformer.convertLocWithoutOriginOffs(1.0, 0.0, 0.0));
		Vector3 referenceDirection = vector(transformer.convertLocWithoutOriginOffs(0.0, 1.0, 0.0));
		List<STEPRevolvedSolid> solids = new ArrayList<>();
		for (int i = 0; i < contexts.size(); i++) {
			InstanceContext context = contexts.get(i);
			String instanceName = contexts.size() == 1 ? name : name + " " + (i + 1);
			Point3 origin = componentOrigin(transformer, context.getLocation());
			solids.add(new STEPRevolvedSolid(instanceName, origin, axis, referenceDirection,
					outerProfile, innerProfile));
			for (ShoulderProfile shoulder : shoulders) {
				Profile shoulderOuter = constantProfile(shoulder.start(), shoulder.end(), shoulder.outerRadius());
				Profile shoulderInner = shoulder.innerRadius() > GEOMETRY_EPSILON
						? constantProfile(shoulder.start(), shoulder.end(), shoulder.innerRadius())
						: null;
				solids.add(new STEPRevolvedSolid(instanceName + " " + shoulder.name(), origin, axis,
						referenceDirection, shoulderOuter, shoulderInner));
			}
		}
		return solids;
	}

	private static Point3 componentOrigin(CoordTransform transformer, CoordinateIF location) {
		FloatTuple base = transformer.convertLoc(0.0, 0.0, 0.0);
		FloatTuple instanceOffset = transformer.convertLocWithoutOriginOffs(location);
		return new Point3(base.getX() + instanceOffset.getX(), base.getY() + instanceOffset.getY(),
				base.getZ() + instanceOffset.getZ());
	}

	private static Vector3 vector(FloatTuple tuple) {
		return new Vector3(tuple.getX(), tuple.getY(), tuple.getZ());
	}

	private static Profile constantProfile(double start, double end, double radius) {
		return new Profile(List.of(new ProfilePoint(start, radius), new ProfilePoint(end, radius)));
	}

	private static Profile sampleProfile(DoubleUnaryOperator radiusFunction, double start, double end,
			int segmentCount, boolean forceStartZero, boolean forceEndZero) {
		if (isLinear(radiusFunction, start, end)) {
			return new Profile(List.of(
					new ProfilePoint(start, forceStartZero ? 0.0 : radiusFunction.applyAsDouble(start)),
					new ProfilePoint(end, forceEndZero ? 0.0 : radiusFunction.applyAsDouble(end))));
		}

		List<ProfilePoint> points = new ArrayList<>(segmentCount + 1);
		for (int i = 0; i <= segmentCount; i++) {
			double fraction = (double) i / segmentCount;
			double axialPosition = start + (end - start) * fraction;
			double radius = radiusFunction.applyAsDouble(axialPosition);
			if ((i == 0 && forceStartZero) || (i == segmentCount && forceEndZero)) {
				radius = 0.0;
			}
			points.add(new ProfilePoint(axialPosition, Math.max(radius, 0.0)));
		}
		return new Profile(points);
	}

	private static boolean isLinear(DoubleUnaryOperator radiusFunction, double start, double end) {
		double startRadius = radiusFunction.applyAsDouble(start);
		double endRadius = radiusFunction.applyAsDouble(end);
		double tolerance = Math.max(1.0E-11, Math.max(startRadius, endRadius) * 1.0E-9);
		for (int i = 1; i < 4; i++) {
			double fraction = i / 4.0;
			double position = start + (end - start) * fraction;
			double expectedRadius = startRadius + (endRadius - startRadius) * fraction;
			if (Math.abs(radiusFunction.applyAsDouble(position) - expectedRadius) > tolerance) {
				return false;
			}
		}
		return true;
	}

	private record ShoulderProfile(String name, double start, double end, double outerRadius, double innerRadius) {
	}
}
