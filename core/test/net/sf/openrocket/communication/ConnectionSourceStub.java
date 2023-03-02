package net.sf.openrocket.communication;

import java.net.HttpURLConnection;

public class ConnectionSourceStub implements ConnectionSource {
	
	private final HttpURLConnection connection;

	public ConnectionSourceStub(HttpURLConnection connection) {
		this.connection = connection;
	}
	
	@Override
	public HttpURLConnection getConnection(String url) {
		if (connection instanceof HttpURLConnectionMock) {
			((HttpURLConnectionMock)connection).setTrueUrl(url);
		}
		return connection;
	}

}
