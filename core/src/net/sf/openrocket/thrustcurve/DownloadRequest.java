package net.sf.openrocket.thrustcurve;

import java.util.ArrayList;

class DownloadRequest {
	
	private final ArrayList<Integer> motorIds = new ArrayList<>();
	
	private String format = null;
	
	public void add(Integer motorId) {
		this.motorIds.add(motorId);
	}
	
	public void setFormat(String format) {
		this.format = format;
	}
	
	@Override
	public String toString() {
		StringBuilder w = new StringBuilder();
		
		w.append("<?xml version=\"1.0\" encoding=\"ascii\"?>\n");
		w.append("<download-request\n");
		w.append(" xmlns=\"https://www.thrustcurve.org/2008/DownloadRequest\"\n");
		w.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
		w.append(" xsi:schemaLocation=\"https://www.thrustcurve.org/2008/DownloadRequesthttps:///www.thrustcurve.org/2008/download-request.xsd\">\n");
		
		if (format != null) {
			w.append("  <format>").append(format).append("</format>\n");
		}
		
		w.append("  <motor-ids>\n");
		for (Integer i : motorIds) {
			w.append("      <id>").append(i).append("</id>\n");
		}
		w.append("  </motor-ids>\n");
		w.append("</download-request>\n");
		return w.toString();
	}
	
	
}
