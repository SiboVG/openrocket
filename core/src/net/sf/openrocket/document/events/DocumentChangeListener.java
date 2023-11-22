package net.sf.openrocket.document.events;

public interface DocumentChangeListener {

	void documentChanged(DocumentChangeEvent event);

	default void documentSaving(DocumentChangeEvent event) {
		// Do nothing
	}
	
}
