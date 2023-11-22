package net.sf.openrocket.gui.util;

import net.sf.openrocket.gui.dialogs.preset.XTableColumnModel;
import net.sf.openrocket.util.ORPreferences;

import javax.swing.JTable;
import javax.swing.table.TableColumn;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility class for storing and loading the following table UI preferences:
 * - column width
 * - column order
 * - column visibility
 */
public class TableUIPreferences {

	private static final String TABLE_COLUMN_WIDTH_PREFIX = ".cw.";
	private static final String TABLE_COLUMN_ORDER_PREFIX = ".co.";
	private static final String TABLE_COLUMN_VISIBILITY_PREFIX = ".cv.";

	/**
	 * Stores the table UI preferences (column width, order and visibility) in the given preferences object.
	 * @param table The table to store the preferences for
	 * @param tableName The name of the table (used as a prefix for the preference keys)
	 * @param preferences The preferences object to store the preferences in
	 * @param storeColumnWidth whether to store the column widths
	 * @param storeColumnOrder whether to store the column order
	 * @param storeColumnVisibility whether to store the column visibility
	 */
	public static void storeTableUIPreferences(JTable table, String tableName, ORPreferences preferences,
											   boolean storeColumnWidth, boolean storeColumnOrder, boolean storeColumnVisibility) {
		// Store column widths
		if (storeColumnWidth) {
			for (int i = 0; i < table.getColumnCount(); i++) {
				TableColumn column = table.getColumnModel().getColumn(i);
				int width = column.getWidth();
				preferences.putInt(tableName + TABLE_COLUMN_WIDTH_PREFIX + column.getIdentifier(), width);
			}
		}

		// Store column order
		if (storeColumnOrder) {
			for (int i = 0; i < table.getColumnCount(); i++) {
				TableColumn column = table.getColumnModel().getColumn(i);
				preferences.putInt(tableName + TABLE_COLUMN_ORDER_PREFIX + column.getIdentifier(), i);
			}
		}

		// Store column visibility
		if (storeColumnVisibility) {
			if (table.getColumnModel() instanceof XTableColumnModel customModel) {
				Enumeration<TableColumn> columns = customModel.getAllColumns();
				while (columns.hasMoreElements()) {
					TableColumn column = columns.nextElement();
					boolean isVisible = customModel.isColumnVisible(column);
					preferences.putBoolean(tableName + TABLE_COLUMN_VISIBILITY_PREFIX + column.getIdentifier(), isVisible);
				}
			}
		}
	}

	/**
	 * Stores the table UI preferences (column width, order and visibility) in the given preferences object.
	 * @param table The table to store the preferences for
	 * @param tableName The name of the table (used as a prefix for the preference keys)
	 * @param preferences The preferences object to store the preferences in
	 */
	public static void storeTableUIPreferences(JTable table, String tableName, ORPreferences preferences) {
		storeTableUIPreferences(table, tableName, preferences, true, true, true);

	}

	/**
	 * Loads the table UI preferences (column width, order and visibility) from the given preferences object.
	 * @param table The table to load the preferences for
	 * @param tableName The name of the table (used as a prefix for the preference keys)
	 * @param preferences The preferences object to load the preferences from
	 * @param loadColumnWidth whether to load the column widths
	 * @param loadColumnOrder whether to load the column order
	 * @param loadColumnVisibility whether to load the column visibility
	 */
	public static void loadTableUIPreferences(JTable table, String tableName, ORPreferences preferences,
											  boolean loadColumnWidth, boolean loadColumnOrder, boolean loadColumnVisibility) {
		// Ensure all columns are visible
		Enumeration<TableColumn> allColumns = table.getColumnModel().getColumns();
		List<TableColumn> removedColumns = new ArrayList<>();
		while (allColumns.hasMoreElements()) {
			TableColumn column = allColumns.nextElement();
			if (table.convertColumnIndexToView(column.getModelIndex()) == -1) {
				removedColumns.add(column);
			}
		}
		for (TableColumn col : removedColumns) {
			table.addColumn(col);
		}

		// Get all columns from the table's column model and restore visibility
		if (loadColumnVisibility) {
			if (table.getColumnModel() instanceof XTableColumnModel customModel) {
				Enumeration<TableColumn> columns = customModel.getAllColumns();  // Use getAllColumns to get all columns, including invisible ones
				while (columns.hasMoreElements()) {
					TableColumn column = columns.nextElement();
					String identifier = column.getIdentifier().toString();
					// Default to true if the preference is not found
					boolean isVisible = preferences.getBoolean(tableName + TABLE_COLUMN_VISIBILITY_PREFIX + identifier, true);
					customModel.setColumnVisible(column, isVisible);
				}
			}
		}

		// Now, restore column order
		if (loadColumnOrder) {
			for (int i = 0; i < table.getColumnCount(); i++) {
				TableColumn column = table.getColumnModel().getColumn(i);
				int storedOrder = preferences.getInt(tableName + TABLE_COLUMN_ORDER_PREFIX + column.getIdentifier(), i);
				if (storedOrder != i && storedOrder < table.getColumnCount()) {
					table.moveColumn(table.convertColumnIndexToView(column.getModelIndex()), storedOrder);
				}
			}
		}

		// Lastly, restore column widths
		if (loadColumnWidth) {
			// Check if any column width is missing from preferences
			boolean computeOptimalWidths = false;
			for (int i = 0; i < table.getColumnCount() && !computeOptimalWidths; i++) {
				TableColumn column = table.getColumnModel().getColumn(i);
				if (preferences.getString(tableName + TABLE_COLUMN_WIDTH_PREFIX + column.getIdentifier(), null) == null) {
					computeOptimalWidths = true;
				}
			}

			// If any column width is missing, compute optimal widths for all columns
			int[] optimalWidths = null;
			if (computeOptimalWidths) {
				optimalWidths = GUIUtil.computeOptimalColumnWidths(table, 20, true);
			}

			// Restore column widths
			for (int i = 0; i < table.getColumnCount(); i++) {
				TableColumn column = table.getColumnModel().getColumn(i);
				int defaultWidth = (optimalWidths != null) ? optimalWidths[i] : column.getWidth();
				int width = preferences.getInt(tableName + TABLE_COLUMN_WIDTH_PREFIX + column.getIdentifier(), defaultWidth);
				column.setPreferredWidth(width);
			}
		}
	}

	/**
	 * Loads the table UI preferences (column width, order and visibility) from the given preferences object.
	 * @param table The table to load the preferences for
	 * @param tableName The name of the table (used as a prefix for the preference keys)
	 * @param preferences The preferences object to load the preferences from
	 */
	public static void loadTableUIPreferences(JTable table, String tableName, ORPreferences preferences) {
		loadTableUIPreferences(table, tableName, preferences, true, true, true);
	}
}