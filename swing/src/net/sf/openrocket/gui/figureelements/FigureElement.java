package net.sf.openrocket.gui.figureelements;

import java.awt.Graphics2D;
import java.awt.Rectangle;

public interface FigureElement {

	void paint(Graphics2D g2, double scale);
	
	void paint(Graphics2D g2, double scale, Rectangle visible);
	
}
