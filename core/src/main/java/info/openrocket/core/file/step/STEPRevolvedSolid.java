package info.openrocket.core.file.step;

import info.openrocket.core.file.wavefrontobj.DefaultObj;

import java.util.List;

/**
 * Describes a closed axisymmetric solid before it is serialized as an advanced
 * STEP B-rep.
 *
 * <p>Profile coordinates are expressed in metres in a local axial/radial plane.
 * The origin and directions place that plane in the exported coordinate system.
 * Both profiles run from the fore end towards the aft end.</p>
 */
final class STEPRevolvedSolid {
	private static final double DIRECTION_TOLERANCE = 1.0E-10;

	private final String name;
	private final Point3 origin;
	private final Vector3 axis;
	private final Vector3 referenceDirection;
	private final Profile outerProfile;
	private final Profile innerProfile;

	STEPRevolvedSolid(String name, Point3 origin, Vector3 axis, Vector3 referenceDirection,
			Profile outerProfile, Profile innerProfile) {
		this.name = name;
		this.origin = origin;
		this.axis = axis.normalized();
		this.referenceDirection = referenceDirection.normalized();
		this.outerProfile = outerProfile;
		this.innerProfile = innerProfile;

		if (Math.abs(this.axis.dot(this.referenceDirection)) > DIRECTION_TOLERANCE) {
			throw new IllegalArgumentException("The axial and radial directions must be perpendicular");
		}
		if (innerProfile != null
				&& (innerProfile.start().axialPosition() < outerProfile.start().axialPosition()
				|| innerProfile.end().axialPosition() > outerProfile.end().axialPosition())) {
			throw new IllegalArgumentException("The inner profile must lie within the outer profile");
		}
	}

	String name() {
		return name;
	}

	Point3 origin() {
		return origin;
	}

	Vector3 axis() {
		return axis;
	}

	Vector3 referenceDirection() {
		return referenceDirection;
	}

	Profile outerProfile() {
		return outerProfile;
	}

	Profile innerProfile() {
		return innerProfile;
	}

	/**
	 * Returns a copy translated in exported coordinates.
	 */
	STEPRevolvedSolid translated(double x, double y, double z) {
		return new STEPRevolvedSolid(name, origin.add(new Vector3(x, y, z)), axis, referenceDirection,
				outerProfile, innerProfile);
	}

	/**
	 * Adds exact axis-aligned bounds for this rotationally symmetric solid.
	 */
	void addBoundsTo(DefaultObj bounds) {
		Vector3 secondRadialDirection = axis.cross(referenceDirection).normalized();
		for (ProfilePoint point : outerProfile.points()) {
			Point3 center = pointAt(point.axialPosition(), 0.0);
			double radius = point.radius();
			addBound(bounds, center);
			addBound(bounds, center.add(referenceDirection.scale(radius)));
			addBound(bounds, center.add(referenceDirection.scale(-radius)));
			addBound(bounds, center.add(secondRadialDirection.scale(radius)));
			addBound(bounds, center.add(secondRadialDirection.scale(-radius)));
		}
	}

	Point3 pointAt(double axialPosition, double radius) {
		return origin.add(axis.scale(axialPosition)).add(referenceDirection.scale(radius));
	}

	Point3 axisPoint(double axialPosition) {
		return origin.add(axis.scale(axialPosition));
	}

	private static void addBound(DefaultObj bounds, Point3 point) {
		bounds.addVertex((float) point.x(), (float) point.y(), (float) point.z());
	}

	/**
	 * Samples of one monotonic axial profile.
	 */
	static final class Profile {
		private final List<ProfilePoint> points;

		Profile(List<ProfilePoint> points) {
			if (points.size() < 2) {
				throw new IllegalArgumentException("A revolved profile needs at least two points");
			}
			for (int i = 0; i < points.size(); i++) {
				ProfilePoint point = points.get(i);
				if (!Double.isFinite(point.axialPosition()) || !Double.isFinite(point.radius())
						|| point.radius() < 0.0) {
					throw new IllegalArgumentException("Profile coordinates must be finite and non-negative");
				}
				if (i > 0 && point.axialPosition() <= points.get(i - 1).axialPosition()) {
					throw new IllegalArgumentException("Profile points must have increasing axial positions");
				}
			}
			this.points = List.copyOf(points);
		}

		List<ProfilePoint> points() {
			return points;
		}

		ProfilePoint start() {
			return points.get(0);
		}

		ProfilePoint end() {
			return points.get(points.size() - 1);
		}

		boolean isCylindrical() {
			double radius = start().radius();
			double tolerance = Math.max(1.0E-12, radius * 1.0E-10);
			return points.stream().allMatch(point -> Math.abs(point.radius() - radius) <= tolerance);
		}
	}

	record ProfilePoint(double axialPosition, double radius) {
	}

	record Point3(double x, double y, double z) {
		Point3 add(Vector3 vector) {
			return new Point3(x + vector.x(), y + vector.y(), z + vector.z());
		}
	}

	record Vector3(double x, double y, double z) {
		double dot(Vector3 other) {
			return x * other.x + y * other.y + z * other.z;
		}

		Vector3 cross(Vector3 other) {
			return new Vector3(y * other.z - z * other.y, z * other.x - x * other.z,
					x * other.y - y * other.x);
		}

		Vector3 scale(double factor) {
			return new Vector3(x * factor, y * factor, z * factor);
		}

		Vector3 normalized() {
			double magnitude = Math.sqrt(dot(this));
			if (!Double.isFinite(magnitude) || magnitude == 0.0) {
				throw new IllegalArgumentException("Direction vectors must be finite and non-zero");
			}
			return scale(1.0 / magnitude);
		}
	}
}
