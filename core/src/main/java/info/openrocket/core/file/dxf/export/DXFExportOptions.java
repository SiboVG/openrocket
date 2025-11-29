package info.openrocket.core.file.dxf.export;

import java.awt.Color;
import java.util.Objects;

/**
 * Immutable container describing how DXF outlines should be rendered.
 * Similar to SVGExportOptions but for DXF format.
 */
public class DXFExportOptions {
	private final Color strokeColor;
	private final double strokeWidthMm;
	private final boolean drawCrosshair;
	private final Color crosshairColor;
	private final double crosshairSizeMm;
	private final boolean showLabels;
	private final Color labelColor;
	private final double partSpacingM;

	public DXFExportOptions(Color strokeColor, double strokeWidthMm) {
		this(strokeColor, strokeWidthMm, true, strokeColor, 2.0, true, strokeColor, 0.01);
	}

	public DXFExportOptions(Color strokeColor, double strokeWidthMm, boolean drawCrosshair) {
		this(strokeColor, strokeWidthMm, drawCrosshair, strokeColor, 2.0, true, strokeColor, 0.01);
	}

	public DXFExportOptions(Color strokeColor, double strokeWidthMm, boolean drawCrosshair, Color crosshairColor) {
		this(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, 2.0, true, strokeColor, 0.01);
	}

	public DXFExportOptions(Color strokeColor, double strokeWidthMm, boolean drawCrosshair, Color crosshairColor, double crosshairSizeMm, boolean showLabels) {
		this(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, strokeColor, 0.01);
	}

	public DXFExportOptions(Color strokeColor, double strokeWidthMm, boolean drawCrosshair, Color crosshairColor, double crosshairSizeMm, boolean showLabels, Color labelColor) {
		this(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, 0.01);
	}

	public DXFExportOptions(Color strokeColor, double strokeWidthMm, boolean drawCrosshair, Color crosshairColor, double crosshairSizeMm, boolean showLabels, Color labelColor, double partSpacingM) {
		this.strokeColor = Objects.requireNonNull(strokeColor, "strokeColor");
		this.strokeWidthMm = strokeWidthMm;
		this.drawCrosshair = drawCrosshair;
		this.crosshairColor = Objects.requireNonNull(crosshairColor, "crosshairColor");
		this.crosshairSizeMm = crosshairSizeMm;
		this.showLabels = showLabels;
		this.labelColor = Objects.requireNonNull(labelColor, "labelColor");
		this.partSpacingM = partSpacingM;
	}

	public Color getStrokeColor() {
		return strokeColor;
	}

	public double getStrokeWidthMm() {
		return strokeWidthMm;
	}

	public boolean isDrawCrosshair() {
		return drawCrosshair;
	}

	public Color getCrosshairColor() {
		return crosshairColor;
	}

	public double getCrosshairSizeMm() {
		return crosshairSizeMm;
	}

	public boolean isShowLabels() {
		return showLabels;
	}

	public Color getLabelColor() {
		return labelColor;
	}

	public double getPartSpacingM() {
		return partSpacingM;
	}

	public DXFExportOptions withStrokeColor(Color color) {
		return new DXFExportOptions(color, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}

	public DXFExportOptions withStrokeWidth(double strokeWidth) {
		return new DXFExportOptions(strokeColor, strokeWidth, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}

	public DXFExportOptions withDrawCrosshair(boolean drawCrosshair) {
		return new DXFExportOptions(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}

	public DXFExportOptions withCrosshairColor(Color color) {
		return new DXFExportOptions(strokeColor, strokeWidthMm, drawCrosshair, color, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}

	public DXFExportOptions withCrosshairSize(double crosshairSizeMm) {
		return new DXFExportOptions(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}

	public DXFExportOptions withShowLabels(boolean showLabels) {
		return new DXFExportOptions(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}

	public DXFExportOptions withLabelColor(Color color) {
		return new DXFExportOptions(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, color, partSpacingM);
	}

	public DXFExportOptions withPartSpacing(double partSpacingM) {
		return new DXFExportOptions(strokeColor, strokeWidthMm, drawCrosshair, crosshairColor, crosshairSizeMm, showLabels, labelColor, partSpacingM);
	}
}
