package net.sf.openrocket.file.openrocket.importt;

import java.util.HashMap;

import net.sf.openrocket.logging.WarningSet;
import net.sf.openrocket.file.simplesax.ElementHandler;
import net.sf.openrocket.file.simplesax.PlainTextHandler;
import net.sf.openrocket.util.Config;

import org.xml.sax.SAXException;

public class ConfigHandler extends EntryHandler {
	private final Config config = new Config();
	
	@Override
	public ElementHandler openElement(String element, HashMap<String, String> attributes, WarningSet warnings) throws SAXException {
		if (element.equals("entry") && "list".equals(attributes.get("type"))) {
			listHandler = new ConfigHandler();
			return listHandler;
		} else {
			return PlainTextHandler.INSTANCE;
		}
	}
	
	@Override
	public void closeElement(String element, HashMap<String, String> attributes, String content, WarningSet warnings) throws SAXException {
		if (element.equals("entry")) {
			String key = attributes.get("key");
			Object value = EntryHelper.getValueFromEntry(ConfigHandler.this, attributes, content);
			if (value != null) {
				if (key != null) {
					config.put(key, value);
				} else {
					list.add(value);
				}
			}
		} else {
			super.closeElement(element, attributes, content, warnings);
		}
	}
	
	public Config getConfig() {
		return config;
	}
	
}
