package workload.driver;

public class ChatPair {

	private Web20Client client1;
	private Web20Client client2;
	
	private String guid;
	private String chatUrl;
	
	public Web20Client getClient1() {
		return client1;
	}
	public void setClient1(Web20Client client1) {
		this.client1 = client1;
	}
	public Web20Client getClient2() {
		return client2;
	}
	public void setClient2(Web20Client client2) {
		this.client2 = client2;
	}
	public String getGuid() {
		return guid;
	}
	public void setGuid(String guid) {
		this.guid = guid;
	}
	public String getChatUrl() {
		return chatUrl;
	}
	public void setChatUrl(String chatUrl) {
		this.chatUrl = chatUrl;
	}
	
	
}
