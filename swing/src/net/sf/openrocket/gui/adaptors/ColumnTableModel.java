package net.sf.openrocket.gui.adaptors;

import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import net.sf.openrocket.startup.Application;

@SuppressWarnings("serial")
public abstract class ColumnTableModel extends AbstractTableModel {
	private final Column[] columns;
	
	public ColumnTableModel(Column... columns) {
		this.columns = columns;
	}
	
	public void setColumnWidths(TableColumnModel model) {
		for (int i = 0; i < columns.length; i++) {
			if (!columns[i].isVisible()) {
				continue; // Skip invisible columns
			}
			if (columns[i].getExactWidth() > 0) {
				TableColumn col = model.getColumn(i);
				int w = columns[i].getExactWidth();
				col.setResizable(false);
				col.setMinWidth(w);
				col.setMaxWidth(w);
				col.setPreferredWidth(w);
			} else {
				model.getColumn(i).setPreferredWidth(columns[i].getDefaultWidth());
			}
		}
	}

	public Column getColumn(int i) {
		return columns[i];
	}
	
	@Override
	public int getColumnCount() {
		int visibleCount = 0;
		for (Column column : columns) {
			if (column.isVisible()) {
				visibleCount++;
			}
		}
		return visibleCount;
	}
	
	@Override
	public String getColumnName(int col) {
		return columns[col].toString();
	}
	
	@Override
	public Class<?> getColumnClass(int col) {
		return columns[col].getColumnClass();
	}
	
	@Override
	public Object getValueAt(int row, int col) {
		if ((row < 0) || (row >= getRowCount()) ||
				(col < 0) || (col >= columns.length)) {
			Application.getExceptionHandler().handleErrorCondition("Error:  Requested illegal column/row, col=" + col + " row=" + row);
			return null;
		}
		return columns[col].getValueAt(row);
	}

	@Override
	public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
		columns[columnIndex].setValueAt(rowIndex, aValue);
	}

	public void setColumnVisible(int columnIndex, boolean visible) {
		if (columnIndex < 0 || columnIndex >= columns.length) {
			throw new IllegalArgumentException("Column index out of range");
		}
		Column column = columns[columnIndex];
		if (column.isVisible() != visible) {
			column.setVisible(visible);
			// Notify listeners that the model has changed
			fireTableStructureChanged();
		}
	}

	public void moveColumn(int fromIndex, int toIndex) {
		if (fromIndex < 0 || fromIndex >= columns.length || toIndex < 0 || toIndex >= columns.length) {
			throw new IllegalArgumentException("Column index out of range");
		}
		// Ensure we're moving visible columns as intended or adjust logic for actual requirements
		Column columnToMove = columns[fromIndex];
		if (fromIndex < toIndex) {
			System.arraycopy(columns, fromIndex + 1, columns, fromIndex, toIndex - fromIndex);
		} else {
			System.arraycopy(columns, toIndex, columns, toIndex + 1, fromIndex - toIndex);
		}
		columns[toIndex] = columnToMove;

		// Notify listeners that the model has changed
		fireTableStructureChanged();
	}
	
}
