package info.openrocket.swing.gui.figure3d.flight;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Scrolls a row of controls horizontally when the window is too narrow for it. It grows by the
 * scroll bar's height while the bar shows, so the bar never covers the controls.
 */
@SuppressWarnings("serial")
final class HorizontalScrollPane extends JScrollPane {
	private boolean scrollBarShown;

	HorizontalScrollPane(Row row) {
		super(row, VERTICAL_SCROLLBAR_NEVER, HORIZONTAL_SCROLLBAR_AS_NEEDED);
		setBorder(BorderFactory.createEmptyBorder());
		setViewportBorder(null);
		getHorizontalScrollBar().setUnitIncrement(16);
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent event) {
				// The bar appearing or disappearing changes the height the layout must reserve.
				if (needsScrollBar() != scrollBarShown) {
					scrollBarShown = needsScrollBar();
					revalidate();
				}
			}
		});
	}

	private boolean needsScrollBar() {
		Insets insets = getInsets();
		int available = getWidth() - insets.left - insets.right;
		return available > 0 && getViewport().getView().getPreferredSize().width > available;
	}

	@Override
	public Dimension getPreferredSize() {
		Dimension size = super.getPreferredSize();
		if (needsScrollBar()) {
			size.height += getHorizontalScrollBar().getPreferredSize().height;
		}
		return size;
	}

	/**
	 * A {@link BorderLayout} row that fills the available width while its controls fit, keeping
	 * the east part at the edge, and keeps its preferred width (so it scrolls) once they no
	 * longer do.
	 */
	static final class Row extends JPanel implements Scrollable {
		Row() {
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return Math.max(16, visibleRect.width - 32);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return getParent() == null || getParent().getWidth() >= getPreferredSize().width;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return true;
		}
	}
}
