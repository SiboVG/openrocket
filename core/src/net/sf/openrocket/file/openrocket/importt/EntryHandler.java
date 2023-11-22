package net.sf.openrocket.file.openrocket.importt;

import net.sf.openrocket.file.simplesax.AbstractElementHandler;
import net.sf.openrocket.util.ArrayList;

import java.util.List;

/**
 * Handler for entries that have a key and type attribute, and a value.
 * For example <entry key="foo" type="string">bar</entry>
 */
public abstract class EntryHandler extends AbstractElementHandler {
	protected EntryHandler listHandler;
	protected final List<Object> list = new ArrayList<>();

	public List<Object> getNestedList() {
		if (listHandler != null) {
			return listHandler.list;
		}
		return null;
	}
}
