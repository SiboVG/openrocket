package info.openrocket.swing.gui.figure3d;

import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.swing.gui.figure3d.ui.GLScenePanel;
import info.openrocket.swing.gui.figure3d.ui.HUDPanel;
import info.openrocket.swing.gui.figureelements.RocketInfo;
import org.lwjgl.opengl.GL;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;

/**
 * Main application entry point for the OpenRocket 3D visualization engine.
 * Sets up Swing window with OpenGL rendering and HUD overlay.
 */
public class Main {
	/**
	 * Application entry point that initializes OpenRocket and starts the 3D engine.
	 * @param args command line arguments (unused)
	 */
	public static void main(String[] args) {
		info.openrocket.core.startup.OpenRocketCore.initialize();

		SwingUtilities.invokeLater(() -> {
			JFrame frame = new JFrame("LWJGL3 Engine in Swing");
			frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			frame.setPreferredSize(new Dimension(1280, 720)); // Set preferred size on the frame

			// Create a test rocket
			Rocket rocket = DemoFactory.createTestRocket();
			RocketInfo rocketInfo = new RocketInfo(rocket.getSelectedConfiguration());

			// Create the HUD panel, but DO NOT add it to any container.
			// It will be held by the GLScenePanel and used only for painting.
			HUDPanel hudPanel = new HUDPanel(rocket, rocketInfo);

			// Create the 3D canvas and add it directly to the frame.
			GLScenePanel canvas = new GLScenePanel(rocket, hudPanel);
			frame.add(canvas);

			frame.pack();
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);

			// Modify the render loop to call our new render method and remove the HUD repaint.
			Runnable renderLoop = new Runnable() {
				@Override
				public void run() {
					if (!canvas.isValid()) {
						GL.setCapabilities(null);
						return;
					}
					canvas.render();
					SwingUtilities.invokeLater(this);
				}
			};
			SwingUtilities.invokeLater(renderLoop);
		});
	}

	// The forwardMouseEvents method can be completely removed.
}