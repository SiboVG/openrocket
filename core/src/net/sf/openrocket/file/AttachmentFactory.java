package net.sf.openrocket.file;

import net.sf.openrocket.document.Attachment;

public interface AttachmentFactory {
	
	Attachment getAttachment(String name);
	
}
