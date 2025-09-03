package info.openrocket.swing.gui.figure3d.utils;

/**
 * Utility class for OpenGL debugging and error handling.
 * Provides methods to check for and report OpenGL errors during rendering operations.
 */
public abstract class GLUtils {
	/**
	 * Checks for OpenGL errors and prints them to stderr with a location tag.
	 * @param locationTag a descriptive string indicating where this check is being performed
	 */
	public static void checkGLError(String locationTag) {
		int error;
		while ((error = org.lwjgl.opengl.GL33.glGetError()) != org.lwjgl.opengl.GL33.GL_NO_ERROR) {
			String errorStr = switch (error) {
				case org.lwjgl.opengl.GL33.GL_INVALID_ENUM -> "INVALID_ENUM";
				case org.lwjgl.opengl.GL33.GL_INVALID_VALUE -> "INVALID_VALUE";
				case org.lwjgl.opengl.GL33.GL_INVALID_OPERATION -> "INVALID_OPERATION";
				case org.lwjgl.opengl.GL33.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
				case org.lwjgl.opengl.GL33.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
				case org.lwjgl.opengl.GL33.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
				case org.lwjgl.opengl.GL33.GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
				default -> "UNKNOWN_ERROR";
			};
			System.err.println("OpenGL Error at [" + locationTag + "]: " + errorStr);
		}
	}

}
