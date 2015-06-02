package web20_benchmark;

import com.sun.faban.driver.HttpTransport;

public class Web20Client {
	
	private String elggToken;
	private String elggTs;
	private String username="tpalit";
	private String password="password12345";
	private HttpTransport http;
	
	public String getElggToken() {
		return elggToken;
	}
	public void setElggToken(String elggToken) {
		this.elggToken = elggToken;
	}
	public String getElggTs() {
		return elggTs;
	}
	public void setElggTs(String elggTs) {
		this.elggTs = elggTs;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public String getPassword() {
		return password;
	}
	public void setPassword(String password) {
		this.password = password;
	}
	public HttpTransport getHttp() {
		return http;
	}
	public void setHttp(HttpTransport http) {
		this.http = http;
	}

	
}
