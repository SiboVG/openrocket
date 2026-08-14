package info.openrocket.core.file.step;

import info.openrocket.core.file.step.STEPRevolvedSolid.Point3;
import info.openrocket.core.file.step.STEPRevolvedSolid.Profile;
import info.openrocket.core.file.step.STEPRevolvedSolid.ProfilePoint;
import info.openrocket.core.file.step.STEPRevolvedSolid.Vector3;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes closed advanced B-reps for rotationally symmetric solids.
 *
 * <p>The resulting topology uses shared circular edges, seam curves, and
 * parameter-space curves.  These entities are more verbose than a swept-solid
 * shortcut, but are understood by the major STEP B-rep importers.</p>
 */
final class STEPAdvancedBrepWriter {
	private static final double TWO_PI = 2.0 * Math.PI;
	private static final double RADIUS_EPSILON = 1.0E-9;
	private static final double POSITION_EPSILON = 1.0E-9;

	private final STEPWriter.EntityTable entities;
	private final int parametricContext;

	STEPAdvancedBrepWriter(STEPWriter.EntityTable entities) {
		this.entities = entities;
		this.parametricContext = entities.add("(\nGEOMETRIC_REPRESENTATION_CONTEXT(2)\n"
				+ "PARAMETRIC_REPRESENTATION_CONTEXT()\n"
				+ "REPRESENTATION_CONTEXT('2D SPACE','')\n)");
	}

	/**
	 * Writes one manifold solid and returns its representation-item entity.
	 */
	int write(STEPRevolvedSolid solid) {
		SideGeometry outerSide = writeSideGeometry(solid, solid.outerProfile());
		SideGeometry innerSide = solid.innerProfile() == null
				? null
				: writeSideGeometry(solid, solid.innerProfile());

		CapGeometry foreCap = hasCircle(solid.outerProfile().start())
				? writeCapGeometry(solid, solid.outerProfile().start().axialPosition())
				: null;
		CapGeometry aftCap = hasCircle(solid.outerProfile().end())
				? writeCapGeometry(solid, solid.outerProfile().end().axialPosition())
				: null;

		CircleBoundary outerFore = foreCap == null
				? null
				: writeCircleBoundary(solid, solid.outerProfile().start(), outerSide,
						outerSide.startParameter(), foreCap);
		CircleBoundary outerAft = aftCap == null
				? null
				: writeCircleBoundary(solid, solid.outerProfile().end(), outerSide,
						outerSide.endParameter(), aftCap);

		CircleBoundary innerFore = matchingInnerCircle(solid, innerSide, foreCap, true);
		CircleBoundary innerAft = matchingInnerCircle(solid, innerSide, aftCap, false);

		List<Integer> faces = new ArrayList<>();
		faces.add(writeSideFace(solid, solid.outerProfile(), outerSide, outerFore, outerAft, true,
				solid.name() + " outer surface"));
		if (innerSide != null) {
			faces.add(writeSideFace(solid, solid.innerProfile(), innerSide, innerFore, innerAft, false,
					solid.name() + " inner surface"));
		}
		if (foreCap != null) {
			faces.add(writeCapFace(foreCap, outerFore, innerFore, false, solid.name() + " fore cap"));
		}
		if (aftCap != null) {
			faces.add(writeCapFace(aftCap, outerAft, innerAft, true, solid.name() + " aft cap"));
		}

		int shell = entities.add("CLOSED_SHELL(" + STEPWriter.stepString(solid.name()) + ",("
				+ STEPWriter.references(faces) + "))");
		return entities.add("MANIFOLD_SOLID_BREP(" + STEPWriter.stepString(solid.name()) + ",#" + shell + ")");
	}

	private SideGeometry writeSideGeometry(STEPRevolvedSolid solid, Profile profile) {
		if (profile.isCylindrical()) {
			Point3 center = solid.axisPoint(profile.start().axialPosition());
			int placement = writeAxis2Placement(center, solid.axis(), solid.referenceDirection());
			int surface = entities.add("CYLINDRICAL_SURFACE('',#" + placement + ","
					+ STEPWriter.formatMillimetres(profile.start().radius()) + ")");
			double endParameter = (profile.end().axialPosition() - profile.start().axialPosition()) * 1000.0;
			return new SideGeometry(surface, 0.0, endParameter, true);
		}

		int basisCurve = writeSplineCurve(solid, profile);
		int axisPlacement = writeAxis1Placement(solid.origin(), solid.axis());
		int surface = entities.add("SURFACE_OF_REVOLUTION('',#" + basisCurve + ",#" + axisPlacement + ")");
		return new SideGeometry(surface, 0.0, 1.0, false);
	}

	private CapGeometry writeCapGeometry(STEPRevolvedSolid solid, double axialPosition) {
		int placement = writeAxis2Placement(solid.axisPoint(axialPosition), solid.axis(),
				solid.referenceDirection());
		return new CapGeometry(entities.add("PLANE('',#" + placement + ")"));
	}

	private CircleBoundary matchingInnerCircle(STEPRevolvedSolid solid, SideGeometry innerSide,
			CapGeometry cap, boolean fore) {
		if (innerSide == null || cap == null) {
			return null;
		}
		ProfilePoint outerEndpoint = fore ? solid.outerProfile().start() : solid.outerProfile().end();
		ProfilePoint innerEndpoint = fore ? solid.innerProfile().start() : solid.innerProfile().end();
		if (!hasCircle(innerEndpoint)
				|| Math.abs(innerEndpoint.axialPosition() - outerEndpoint.axialPosition()) > POSITION_EPSILON) {
			return null;
		}
		double parameter = fore ? innerSide.startParameter() : innerSide.endParameter();
		return writeCircleBoundary(solid, innerEndpoint, innerSide, parameter, cap);
	}

	private CircleBoundary writeCircleBoundary(STEPRevolvedSolid solid, ProfilePoint point,
			SideGeometry side, double sideParameter, CapGeometry cap) {
		int placement = writeAxis2Placement(solid.axisPoint(point.axialPosition()), solid.axis(),
				solid.referenceDirection());
		int circle = entities.add("CIRCLE('',#" + placement + ","
				+ STEPWriter.formatMillimetres(point.radius()) + ")");
		int sidePcurve = writeParametricLine(side.surface(), 0.0, sideParameter, 1.0, 0.0);
		int capPcurve = writeParametricCircle(cap.surface(), point.radius() * 1000.0);
		int surfaceCurve = entities.add("SURFACE_CURVE('',#" + circle + ",(#" + sidePcurve + ",#"
				+ capPcurve + "),.PCURVE_S1.)");
		int pointEntity = writePoint(solid.pointAt(point.axialPosition(), point.radius()));
		int vertex = entities.add("VERTEX_POINT('',#" + pointEntity + ")");
		int edge = entities.add("EDGE_CURVE('',#" + vertex + ",#" + vertex + ",#" + surfaceCurve + ",.T.)");
		return new CircleBoundary(edge, vertex);
	}

	private int writeSideFace(STEPRevolvedSolid solid, Profile profile, SideGeometry side,
			CircleBoundary startCircle, CircleBoundary endCircle, boolean sameSense, String name) {
		int startVertex = startCircle == null
				? writeVertex(solid.pointAt(profile.start().axialPosition(), profile.start().radius()))
				: startCircle.vertex();
		int endVertex = endCircle == null
				? writeVertex(solid.pointAt(profile.end().axialPosition(), profile.end().radius()))
				: endCircle.vertex();

		int seamCurve;
		if (side.cylindrical()) {
			int linePoint = writePoint(solid.pointAt(profile.start().axialPosition(), profile.start().radius()));
			int lineVector = writeVector(solid.axis(), 1.0);
			seamCurve = entities.add("LINE('',#" + linePoint + ",#" + lineVector + ")");
		} else {
			seamCurve = writeSplineCurve(solid, profile);
		}
		int firstPcurve = writeParametricLine(side.surface(), 0.0, side.startParameter(), 0.0, 1.0);
		int secondPcurve = writeParametricLine(side.surface(), TWO_PI, side.startParameter(), 0.0, 1.0);
		int seam = entities.add("SEAM_CURVE('',#" + seamCurve + ",(#" + firstPcurve + ",#"
				+ secondPcurve + "),.PCURVE_S1.)");
		int seamEdge = entities.add("EDGE_CURVE('',#" + startVertex + ",#" + endVertex + ",#"
				+ seam + ",.T.)");

		List<Integer> orientedEdges = new ArrayList<>(4);
		orientedEdges.add(writeOrientedEdge(seamEdge, true));
		if (endCircle != null) {
			orientedEdges.add(writeOrientedEdge(endCircle.edge(), false));
		}
		orientedEdges.add(writeOrientedEdge(seamEdge, false));
		if (startCircle != null) {
			orientedEdges.add(writeOrientedEdge(startCircle.edge(), true));
		}

		int loop = entities.add("EDGE_LOOP('',(" + STEPWriter.references(orientedEdges) + "))");
		int bound = entities.add("FACE_BOUND('',#" + loop + "," + STEPWriter.stepBoolean(sameSense) + ")");
		return entities.add("ADVANCED_FACE(" + STEPWriter.stepString(name) + ",(#" + bound + "),#"
				+ side.surface() + "," + STEPWriter.stepBoolean(sameSense) + ")");
	}

	private int writeCapFace(CapGeometry cap, CircleBoundary outerCircle, CircleBoundary innerCircle,
			boolean aft, String name) {
		List<Integer> bounds = new ArrayList<>(2);
		int outerEdge = writeOrientedEdge(outerCircle.edge(), true);
		int outerLoop = entities.add("EDGE_LOOP('',(#" + outerEdge + "))");
		bounds.add(entities.add("FACE_BOUND('',#" + outerLoop + "," + STEPWriter.stepBoolean(aft) + ")"));
		if (innerCircle != null) {
			int innerEdge = writeOrientedEdge(innerCircle.edge(), false);
			int innerLoop = entities.add("EDGE_LOOP('',(#" + innerEdge + "))");
			bounds.add(entities.add("FACE_BOUND('',#" + innerLoop + "," + STEPWriter.stepBoolean(aft) + ")"));
		}
		return entities.add("ADVANCED_FACE(" + STEPWriter.stepString(name) + ",("
				+ STEPWriter.references(bounds) + "),#" + cap.surface() + ","
				+ STEPWriter.stepBoolean(aft) + ")");
	}

	private int writeOrientedEdge(int edge, boolean orientation) {
		return entities.add("ORIENTED_EDGE('',*,*,#" + edge + "," + STEPWriter.stepBoolean(orientation) + ")");
	}

	private int writeSplineCurve(STEPRevolvedSolid solid, Profile profile) {
		SplineDefinition spline = createSplineDefinition(profile);
		List<Integer> controlPoints = new ArrayList<>(spline.controlPoints().size());
		for (ProfilePoint point : spline.controlPoints()) {
			controlPoints.add(writePoint(solid.pointAt(point.axialPosition(), point.radius())));
		}
		return entities.add("B_SPLINE_CURVE_WITH_KNOTS(''," + spline.degree() + ",("
				+ STEPWriter.references(controlPoints) + "),.UNSPECIFIED.,.F.,.F.,("
				+ integerValues(spline.multiplicities()) + "),(" + realValues(spline.knots())
				+ "),.PIECEWISE_BEZIER_KNOTS.)");
	}

	private static SplineDefinition createSplineDefinition(Profile profile) {
		List<ProfilePoint> samples = profile.points();
		if (samples.size() == 2) {
			return new SplineDefinition(1, samples, List.of(2, 2), List.of(0.0, 1.0));
		}

		double[] derivatives = monotoneDerivatives(samples);
		List<ProfilePoint> controls = new ArrayList<>(3 * (samples.size() - 1) + 1);
		controls.add(samples.get(0));
		for (int i = 0; i < samples.size() - 1; i++) {
			ProfilePoint start = samples.get(i);
			ProfilePoint end = samples.get(i + 1);
			double interval = end.axialPosition() - start.axialPosition();
			controls.add(new ProfilePoint(start.axialPosition() + interval / 3.0,
					Math.max(0.0, start.radius() + derivatives[i] * interval / 3.0)));
			controls.add(new ProfilePoint(end.axialPosition() - interval / 3.0,
					Math.max(0.0, end.radius() - derivatives[i + 1] * interval / 3.0)));
			controls.add(end);
		}

		List<Integer> multiplicities = new ArrayList<>(samples.size());
		List<Double> knots = new ArrayList<>(samples.size());
		for (int i = 0; i < samples.size(); i++) {
			multiplicities.add(i == 0 || i == samples.size() - 1 ? 4 : 3);
			knots.add((double) i / (samples.size() - 1));
		}
		return new SplineDefinition(3, controls, multiplicities, knots);
	}

	/**
	 * Computes shape-preserving PCHIP derivatives so a monotonic rocket profile
	 * remains monotonic between samples and never develops a negative radius.
	 */
	private static double[] monotoneDerivatives(List<ProfilePoint> points) {
		int count = points.size();
		double[] intervals = new double[count - 1];
		double[] slopes = new double[count - 1];
		for (int i = 0; i < count - 1; i++) {
			intervals[i] = points.get(i + 1).axialPosition() - points.get(i).axialPosition();
			slopes[i] = (points.get(i + 1).radius() - points.get(i).radius()) / intervals[i];
		}

		double[] derivatives = new double[count];
		derivatives[0] = endpointDerivative(intervals[0], intervals[1], slopes[0], slopes[1]);
		for (int i = 1; i < count - 1; i++) {
			if (slopes[i - 1] == 0.0 || slopes[i] == 0.0
					|| Math.signum(slopes[i - 1]) != Math.signum(slopes[i])) {
				derivatives[i] = 0.0;
			} else {
				double firstWeight = 2.0 * intervals[i] + intervals[i - 1];
				double secondWeight = intervals[i] + 2.0 * intervals[i - 1];
				derivatives[i] = (firstWeight + secondWeight)
						/ (firstWeight / slopes[i - 1] + secondWeight / slopes[i]);
			}
		}
		derivatives[count - 1] = endpointDerivative(intervals[count - 2], intervals[count - 3],
				slopes[count - 2], slopes[count - 3]);
		return derivatives;
	}

	private static double endpointDerivative(double firstInterval, double secondInterval,
			double firstSlope, double secondSlope) {
		double derivative = ((2.0 * firstInterval + secondInterval) * firstSlope
				- firstInterval * secondSlope) / (firstInterval + secondInterval);
		if (Math.signum(derivative) != Math.signum(firstSlope)) {
			return 0.0;
		}
		if (Math.signum(firstSlope) != Math.signum(secondSlope)
				&& Math.abs(derivative) > Math.abs(3.0 * firstSlope)) {
			return 3.0 * firstSlope;
		}
		return derivative;
	}

	private int writeParametricLine(int surface, double x, double y, double directionX, double directionY) {
		int point = entities.add("CARTESIAN_POINT('',(" + STEPWriter.formatReal(x) + ","
				+ STEPWriter.formatReal(y) + "))");
		int direction = entities.add("DIRECTION('',(" + STEPWriter.formatReal(directionX) + ","
				+ STEPWriter.formatReal(directionY) + "))");
		int vector = entities.add("VECTOR('',#" + direction + ",1.)");
		int line = entities.add("LINE('',#" + point + ",#" + vector + ")");
		int representation = entities.add("DEFINITIONAL_REPRESENTATION('',(#" + line + "),#"
				+ parametricContext + ")");
		return entities.add("PCURVE('',#" + surface + ",#" + representation + ")");
	}

	private int writeParametricCircle(int surface, double radius) {
		int origin = entities.add("CARTESIAN_POINT('',(0.,0.))");
		int direction = entities.add("DIRECTION('',(1.,0.))");
		int placement = entities.add("AXIS2_PLACEMENT_2D('',#" + origin + ",#" + direction + ")");
		int circle = entities.add("CIRCLE('',#" + placement + "," + STEPWriter.formatReal(radius) + ")");
		int representation = entities.add("DEFINITIONAL_REPRESENTATION('',(#" + circle + "),#"
				+ parametricContext + ")");
		return entities.add("PCURVE('',#" + surface + ",#" + representation + ")");
	}

	private int writeAxis1Placement(Point3 location, Vector3 axis) {
		int point = writePoint(location);
		int direction = writeDirection(axis);
		return entities.add("AXIS1_PLACEMENT('',#" + point + ",#" + direction + ")");
	}

	private int writeAxis2Placement(Point3 location, Vector3 axis, Vector3 referenceDirection) {
		int point = writePoint(location);
		int axisDirection = writeDirection(axis);
		int radialDirection = writeDirection(referenceDirection);
		return entities.add("AXIS2_PLACEMENT_3D('',#" + point + ",#" + axisDirection + ",#"
				+ radialDirection + ")");
	}

	private int writeVertex(Point3 point) {
		return entities.add("VERTEX_POINT('',#" + writePoint(point) + ")");
	}

	private int writePoint(Point3 point) {
		return entities.add("CARTESIAN_POINT('',(" + STEPWriter.formatMillimetres(point.x()) + ","
				+ STEPWriter.formatMillimetres(point.y()) + "," + STEPWriter.formatMillimetres(point.z()) + "))");
	}

	private int writeDirection(Vector3 direction) {
		return entities.add("DIRECTION('',(" + STEPWriter.formatReal(direction.x()) + ","
				+ STEPWriter.formatReal(direction.y()) + "," + STEPWriter.formatReal(direction.z()) + "))");
	}

	private int writeVector(Vector3 direction, double magnitude) {
		return entities.add("VECTOR('',#" + writeDirection(direction) + ","
				+ STEPWriter.formatReal(magnitude) + ")");
	}

	private static boolean hasCircle(ProfilePoint point) {
		return point.radius() > RADIUS_EPSILON;
	}

	private static String integerValues(List<Integer> values) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			builder.append(values.get(i));
		}
		return builder.toString();
	}

	private static String realValues(List<Double> values) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			builder.append(STEPWriter.formatReal(values.get(i)));
		}
		return builder.toString();
	}

	private record SideGeometry(int surface, double startParameter, double endParameter, boolean cylindrical) {
	}

	private record CapGeometry(int surface) {
	}

	private record CircleBoundary(int edge, int vertex) {
	}

	private record SplineDefinition(int degree, List<ProfilePoint> controlPoints,
			List<Integer> multiplicities, List<Double> knots) {
	}
}
