package workload.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.xml.xpath.XPathExpressionException;

import org.json.JSONObject;

import workload.driver.RandomStringGenerator.Mode;
import workload.driver.Web20Client.ClientState;

import com.sun.faban.driver.Background;
import com.sun.faban.driver.BenchmarkDefinition;
import com.sun.faban.driver.BenchmarkDriver;
import com.sun.faban.driver.BenchmarkOperation;
import com.sun.faban.driver.CustomMetrics;
import com.sun.faban.driver.CycleType;
import com.sun.faban.driver.DriverContext;
import com.sun.faban.driver.FixedTime;
import com.sun.faban.driver.FlatMix;
import com.sun.faban.driver.HttpTransport;
import com.sun.faban.driver.NegativeExponential;
import com.sun.faban.driver.Timing;

@BenchmarkDefinition(name = "Elgg benchmark", version = "0.1")
@BenchmarkDriver(name = "ElggDriver", 
									/*
									 * Should be the same as the name attribute
									 * of driverConfig in run.xml
									 */
				 threadPerScale = 1)

/**
 * The mix of operations and their proabilities.
 */

@FlatMix(mix = { 5, 5, 15, 20, 20, 20, 10, 5, 5 }, 
		operations = { "AccessHomepage", /* 5 */
						"DoLogin",  /* 5 */
						"PostSelfWall", /* 15 */
						"UpdateActivity", /* 20% */
						"SendChatMessage", /* 20% */
						"ReceiveChatMessage", /* 20% */
						"AddFriend", /* 10 */
						"Register", /* 5 */ 
						"Logout" /* 5 */ },
		deviation = 5)

/*
@Background(operations = 
	{ "UpdateActivity", "ReceiveChatMessage"}, 
	timings = { 
		@FixedTime(cycleTime = 5000, cycleDeviation = 2),
		@FixedTime(cycleTime = 1000, cycleDeviation = 2) }
)
*/

/*
@FlatMix(mix = { 50,50 }, 
operations = { "AccessHomepage",
				"DoLogin"}, 
deviation = 5)
*/
@NegativeExponential(cycleDeviation = 2, 
						cycleMean = 1000, // 1 seconds
						cycleType = CycleType.THINKTIME)
// cycle time or think time - count from the start of prev operation or end
/**
 * The main driver class.
 * 
 * Operations :-
 * 
 * Create new user (X)
 * Login existing user (X)
 * Logout logged in user
 * Activate user
 * Wall post (X)
 * New blog post 
 * Send friend request (X)
 * Send chat message (X)
 * Receive chat message (X)
 * Update live feed (X)
 * Refresh security token
 * 
 * @author Tapti Palit
 *
 */
public class Web20Driver {

	private List<UserPasswordPair> userPasswordList;

	private DriverContext context;
	private Logger logger;
	private FileHandler fileTxt;

	private SimpleFormatter formatterTxt;

	private ElggDriverMetrics elggMetrics;

	private String hostUrl;

	private UserPasswordPair thisUserPasswordPair;

	private Web20Client thisClient;

	
	private Random random; 
	
	/* Constants : URL */
	private final String ROOT_URL = "/";
	private final String[] ROOT_URLS = new String[] {
			"/vendors/requirejs/require-2.1.10.min.js",
			"/vendors/jquery/jquery-1.11.0.min.js",
			"/vendors/jquery/jquery-migrate-1.2.1.min.js",
			"/vendors/jquery/jquery-ui-1.10.4.min.js",
			"/_graphics/favicon-16.png", "/_graphics/favicon-32.png",
			"/_graphics/icons/user/defaultsmall.gif",
			"/_graphics/icons/user/defaulttiny.gif",
			"/_graphics/header_shadow.png", "/_graphics/elgg_sprites.png",
			"/_graphics/sidebar_background.gif",
			"/_graphics/button_graduation.png", "/_graphics/favicon-128.png" };
	private final String LOGIN_URL = "/action/login";
	private final String[] LOGIN_URLS = new String[] { "/js/lib/ui.river.js",
			"/_graphics/icons/user/defaulttopbar.gif",
			"/mod/riverautoupdate/_graphics/loading.gif",
			"/_graphics/toptoolbar_background.gif",
			"/mod/reportedcontent/graphics/icon_reportthis.gif" };
	private final String ACTIVITY_URL = "/activity";
	private final String[] ACTIVITY_URLS = new String[] {
			"/mod/hypeWall/vendors/fonts/font-awesome.css",
			"/mod/hypeWall/vendors/fonts/open-sans.css" };

	private final String RIVER_UPDATE_URL = "/activity/proc/updateriver";
	private final String WALL_URL = "/action/wall/status";

	private final String REGISTER_PAGE_URL = "/register";

	private final String DO_REGISTER_URL = "/action/register";
	private final String DO_ADD_FRIEND = "/action/friends/add";
	
	private final String CHAT_CREATE_URL = "/action/elggchat/create";
	private final String CHAT_POST_URL = "/action/elggchat/post_message";
	private final String CHAT_RECV_URL = "/action/elggchat/poll";
	
	private final String LOGOUT_URL = "/action/logout";
	
	public Web20Driver() throws SecurityException, IOException, XPathExpressionException {

		thisClient = new Web20Client();
		thisClient.setClientState(ClientState.LOGGED_OUT);
		thisClient.setChatSessionList(new ArrayList<String>());
		
		context = DriverContext.getContext();
		userPasswordList = new ArrayList<UserPasswordPair>();

		logger = context.getLogger();
		logger.setLevel(Level.FINE);

		fileTxt = new FileHandler("Logging.txt");
		formatterTxt = new SimpleFormatter();
		fileTxt.setFormatter(formatterTxt);
		logger.addHandler(fileTxt);

		BufferedReader bw = new BufferedReader(new InputStreamReader(this
				.getClass().getClassLoader().getResourceAsStream("users.txt")));
		String line;
		while ((line = bw.readLine()) != null) {
			String tokens[] = line.split(" ");
			UserPasswordPair pair = new UserPasswordPair(tokens[0], tokens[1]);
			userPasswordList.add(pair);
		}
		
		bw.close();
		
		thisUserPasswordPair = userPasswordList.get(context.getThreadId());

		elggMetrics = new ElggDriverMetrics();
		context.attachMetrics(elggMetrics);
		
		hostUrl = "http://"+context.getXPathValue("/webbenchmark/serverConfig/host");
		//hostUrl = "http://octeon";
		random = new Random();
	}

	
	private void updateElggTokenAndTs(Web20Client client, StringBuilder sb, boolean updateGUID) {

		// Get the Json
		int startIndex = sb.indexOf("var elgg = ");
		int endIndex = sb.indexOf(";", startIndex);
		String elggJson = sb.substring(startIndex + "var elgg = ".length(),
				endIndex);

		JSONObject elgg = new JSONObject(elggJson);
		JSONObject securityToken = elgg.getJSONObject("security")
				.getJSONObject("token");
		String elggToken = securityToken.getString("__elgg_token");
		Long elggTs = securityToken.getLong("__elgg_ts");

		client.setElggToken(elggToken);
		client.setElggTs(elggTs.toString());

		if (updateGUID) {
			if (!elgg.getJSONObject("session").isNull("user")) {
				JSONObject userSession = elgg.getJSONObject("session")
						.getJSONObject("user");
				Integer elggGuid = userSession.getInt("guid");
					client.setGuid(elggGuid.toString());
			}
		}
	}


	@BenchmarkOperation(name = "AccessHomepage", 
						max90th = 10.0, 
						timing = Timing.MANUAL)
	/**
	 * A new client accesses the home page. The "new client" is selected from a list maintained of possible users and their passwords.
	 * The details of the new client are stored in the 
	 * @throws Exception
	 */
	public void accessHomePage() throws Exception {
		boolean success = false;
		context.recordTime();

		if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
			thisClient.setUsername(thisUserPasswordPair.getUserName());
			thisClient.setPassword(thisUserPasswordPair.getPassword());
	
			HttpTransport http = HttpTransport.newInstance();
			http.addTextType("application/xhtml+xml");
			http.addTextType("application/xml");
			http.addTextType("q=0.9,*/*");
			http.addTextType("q=0.8");
			http.setFollowRedirects(true);
	
			thisClient.setHttp(http);
			thisClient.setClientState(ClientState.AT_HOME_PAGE);
			StringBuilder sb = http.fetchURL(hostUrl + ROOT_URL);
	
			updateElggTokenAndTs(thisClient, sb, false);
			for (String url : ROOT_URLS) {
				http.readURL(hostUrl + url);
			}
			success = true;
	
		}
		context.recordTime();
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptHomePageCnt++;
		}

	}

	@BenchmarkOperation(name = "DoLogin", 
						max90th = 10.0,
						timing = Timing.MANUAL)
	public void doLogin() throws Exception {
		boolean success = false;
		
		context.recordTime();
		if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
			logger.fine("Login of thread: "+context.getThreadId());

			/*
			 * To do the login, To login, we need four parameters in the POST
			 * query 1. Elgg token 2. Elgg timestamp 3. user name 4. password
			 */
			String postRequest = "__elgg_token=" + thisClient.getElggToken()
					+ "&__elgg_ts=" + thisClient.getElggTs() + "&username="
					+ thisClient.getUsername() + "&password="
					+ thisClient.getPassword();

			for (String url : LOGIN_URLS) {
				thisClient.getHttp().readURL(hostUrl + url);
			}
			
			Map<String, String> headers = new HashMap<String, String>();
			headers.put("Accept",
					"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			headers.put("Accept-Language", "en-US,en;q=0.5");
			headers.put("Accept-Encoding", "gzip, deflate");
			headers.put("Referer", hostUrl + "/");
			headers.put("User-Agent",
					"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");

			StringBuilder sb = thisClient.getHttp().fetchURL(hostUrl + LOGIN_URL,
					postRequest, headers);

			updateElggTokenAndTs(thisClient, sb, true);
			thisClient.setClientState(ClientState.LOGGED_IN);
			success = true;
		}
		context.recordTime();
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptLoginCnt++;
		}
	}

	@BenchmarkOperation(name = "UpdateActivity", max90th = 10.0, timing = Timing.MANUAL)
	public void updateActivity() throws Exception {
		boolean success = false;
		context.recordTime();
		if (thisClient.getClientState() == ClientState.LOGGED_IN) {
			String postString = "options%5Bcount%5D=false&options%5Bpagination%5D=false&options%5Boffset%5D=0&options%5Blimit%5D=20&count=2";
			 // Note: the %5B %5D are [ and ] respectively
			StringBuilder sb = thisClient.getHttp().fetchURL(
					hostUrl + RIVER_UPDATE_URL, postString);
			success = true;
		}
		context.recordTime();
		if (context.isTxSteadyState()) {
			if (success) {
				elggMetrics.attemptUpdateActivityCnt++;
			} else {
				if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
					doLogin();
				} else if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
					accessHomePage();
					doLogin();
				}
			}
		}
	}

	/**
	 * Add friend
	 * 
	 * @throws Exception
	 */
	@BenchmarkOperation(name = "AddFriend", 
						max90th = 10.0,
						timing = Timing.MANUAL)
	public void addFriend() throws Exception {
		boolean success = false;
		context.recordTime();
		if (thisClient.getClientState() == ClientState.LOGGED_IN) {
			int friendeeGuid = random.nextInt(userPasswordList.size());
			String postString = "friend=" + friendeeGuid + "&__elgg_ts"
					+ thisClient.getElggTs() + "&__elgg_token"
					+ thisClient.getElggToken();
			thisClient.getHttp().fetchURL(
					hostUrl + DO_ADD_FRIEND + "?" + postString, postString);
			success = true;
		}
		context.recordTime();
		if (success) {
			if (context.isTxSteadyState()) {
				elggMetrics.attemptAddFriendsCnt++;
			}
		} else {
			if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
				doLogin();
			} else if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
				accessHomePage();
				doLogin();
			}
		}

	}

	/**
	 * Receive a chat message.
	 */
	@BenchmarkOperation(name = "ReceiveChatMessage", 
			max90th = 4.0,
			timing = Timing.MANUAL)
	public void receiveChatMessage() throws Exception {
		boolean success = false;
		context.recordTime();
		if (thisClient.getClientState() == ClientState.LOGGED_IN) {
			Map<String, String> headers = new HashMap<String, String>();
			headers.put("Accept",
					"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			headers.put("Accept-Language", "en-US,en;q=0.5");
			headers.put("Accept-Encoding", "gzip, deflate");
			headers.put("Referer", hostUrl + "/activity");
			headers.put("User-Agent",
					"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");
			
			thisClient.getHttp().fetchURL(hostUrl+CHAT_RECV_URL+"?__elgg_ts="+thisClient.getElggTs()+"__elgg_token="+thisClient.getElggToken(), headers);
			success = true;
		}
		context.recordTime();
		
		if (success) {
			if (context.isTxSteadyState()) {
				elggMetrics.attemptRecvChatMessageCnt ++;
			}
		} else {
			if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
				doLogin();
			} else if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
				accessHomePage();
				doLogin();
			}
		}
	}
	/**
	 * Send a chat message
	 * 
	 * @throws Exception
	 */
	@BenchmarkOperation(name = "SendChatMessage", 
						max90th = 4.0,
						timing = Timing.MANUAL)
	public void sendChatMessage() throws Exception {
		boolean success = false;
		StringBuilder sb = null;
		context.recordTime();
		if (thisClient.getClientState() == ClientState.LOGGED_IN) {
			if (random.nextBoolean() || thisClient.getChatSessionList().isEmpty()) {
				startNewChat();
			} else {
				// Continue an existing chat conversation
				String chatGuid = thisClient.getChatSessionList().get(random.nextInt(thisClient.getChatSessionList().size()));
				
					Map<String, String> headers = new HashMap<String, String>();
					headers.put("Accept",
							"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
					headers.put("Accept-Language", "en-US,en;q=0.5");
					headers.put("Accept-Encoding", "gzip, deflate");
					headers.put("Referer", hostUrl + "/activity");
					headers.put("User-Agent",
							"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");
	
					String postString = "chatsession="+chatGuid+"&chatmessage="
							+RandomStringGenerator.generateRandomString(15, Mode.ALPHA);
					sb = thisClient.getHttp().fetchURL(hostUrl+CHAT_POST_URL
														+"?__elgg_token="+thisClient.getElggToken()
														+"&__elgg_ts="+thisClient.getElggTs()
													, postString, headers);
					
			}
			success = true;
		}
		context.recordTime();
		if (success) {
			if (context.isTxSteadyState()) {
				elggMetrics.attemptSendChatMessageCnt ++;
			}
		} else {
			if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
				doLogin();
			} else if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
				accessHomePage();
				doLogin();
			}
		}
	}

	private void startNewChat() throws Exception {
		StringBuilder sb = null;
		String postString = null;
		String chatGuid = null;
		
		// Create a new chat communication between two logged in users
		int chateeGuid = random.nextInt(userPasswordList.size());
		
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Accept",
				"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		headers.put("Accept-Language", "en-US,en;q=0.5");
		headers.put("Accept-Encoding", "gzip, deflate");
		headers.put("Referer", hostUrl + "/activity");
		headers.put("User-Agent",
				"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");
			
		postString = "invite=" + chateeGuid + "&__elgg_ts="
				+ thisClient.getElggTs() + "&__elgg_token="
				+ thisClient.getElggToken();
		sb = thisClient.getHttp().fetchURL(hostUrl + CHAT_CREATE_URL,
				postString, headers);
		assert (thisClient.getHttp().getResponseCode() == 200);
		chatGuid = sb.toString();
		thisClient.getChatSessionList().add(chatGuid);

		headers.put("Referer", hostUrl + "/chat/add");

		// Send a message
		postString = "chatsession=" + chatGuid + "&chatmessage="
				+ RandomStringGenerator.generateRandomString(15, Mode.ALPHA);
		sb = thisClient.getHttp().fetchURL(
				hostUrl + CHAT_POST_URL + "?__elgg_token="
						+ thisClient.getElggToken() + "&__elgg_ts="
						+ thisClient.getElggTs(), postString, headers);
		assert (thisClient.getHttp().getResponseCode() == 200);
		
	}
	/**
	 * Post something on the Wall (actually on the Wire but from the Wall!).
	 * 
	 * @throws Exception
	 */
	@BenchmarkOperation(name = "PostSelfWall", 
						max90th = 10.0,
						timing = Timing.MANUAL)
	public void postSelfWall() throws Exception {
		boolean success = false;

		context.recordTime();
		if (thisClient.getClientState() == ClientState.LOGGED_IN) {
			String status = "Hello world! "
					+ new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
							.format(new Date());
			String postRequest = "__elgg_token=" + thisClient.getElggToken()
					+ "&__elgg_ts=" + thisClient.getElggTs() + "&status=" + status
					+ "&address=&access_id=2&origin=wall&container_guid="
					+ thisClient.getGuid();
	
			Map<String, String> headers = new HashMap<String, String>();
			headers.put("Accept",
					"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			headers.put("Accept-Language", "en-US,en;q=0.5");
			headers.put("Accept-Encoding", "gzip, deflate");
			headers.put("Referer", hostUrl + "/activity");
			headers.put("User-Agent",
					"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");
	
			StringBuilder sb = thisClient.getHttp().fetchURL(hostUrl + WALL_URL,
					postRequest, headers);
			updateElggTokenAndTs(thisClient, sb, false);
			success = true;
		}
		context.recordTime();

		if (context.isTxSteadyState()) {
			if (success) {
				elggMetrics.attemptPostWallCnt++;
			} else {
				if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
					doLogin();
				} else if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
					accessHomePage();
					doLogin();
				}
			}
		}

	}

	/**
	 * Post something on the Wall (actually on the Wire but from the Wall!).
	 * 
	 * @throws Exception
	 */
	@BenchmarkOperation(name = "Logout", 
						max90th = 10.0,
						timing = Timing.MANUAL)
	public void logout() throws Exception {
		boolean success = false;

		context.recordTime();
		if (thisClient.getClientState() == ClientState.LOGGED_IN) {

			Map<String, String> headers = new HashMap<String, String>();
			headers.put("Accept",
					"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			headers.put("Accept-Language", "en-US,en;q=0.5");
			headers.put("Accept-Encoding", "gzip, deflate");
			headers.put("Referer", hostUrl + "/activity");
			headers.put("User-Agent",
					"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");
	
			StringBuilder sb = thisClient.getHttp().fetchURL(hostUrl + LOGOUT_URL, headers);
			
			updateElggTokenAndTs(thisClient, sb, false);
			thisClient.setClientState(ClientState.AT_HOME_PAGE);
			success = true;
		}
		context.recordTime();

		if (context.isTxSteadyState()) {
			if (success) {
				elggMetrics.attemptPostWallCnt++;
			} else {
				if (thisClient.getClientState() == ClientState.AT_HOME_PAGE) {
					doLogin();
				} else if (thisClient.getClientState() == ClientState.LOGGED_OUT) {
					accessHomePage();
					doLogin();
				}
			}
		}

	}

	/**
	 * 
	 * Register a new user.
	 * 
	 */
	@BenchmarkOperation(name = "Register", 
						max90th = 10.0,
						timing = Timing.MANUAL)
	public void register() throws Exception {
		boolean success = false;

		Web20Client client = new Web20Client();

		HttpTransport http = HttpTransport.newInstance();
		http.addTextType("application/xhtml+xml");
		http.addTextType("application/xml");
		http.addTextType("q=0.9,*/*");
		http.addTextType("q=0.8");
		http.setFollowRedirects(true);
		client.setHttp(http);

		// Navigate to the home page

		StringBuilder sb = http.fetchURL(hostUrl + ROOT_URL);

		updateElggTokenAndTs(client, sb, false);
		for (String url : ROOT_URLS) {
			http.readURL(hostUrl + url);
			// System.out.println(sb.indexOf("__elgg_token"));
		}

		context.recordTime();

		// Click on Register link and generate user name and password

		client.getHttp().fetchURL(hostUrl + REGISTER_PAGE_URL);
		String userName = RandomStringGenerator.generateRandomString(10,
				RandomStringGenerator.Mode.ALPHA);
		String password = RandomStringGenerator.generateRandomString(10,
				RandomStringGenerator.Mode.ALPHA);
		String email = RandomStringGenerator.generateRandomString(7,
				RandomStringGenerator.Mode.ALPHA)
				+ "@"
				+ RandomStringGenerator.generateRandomString(5,
						RandomStringGenerator.Mode.ALPHA) + ".co.in";
		client.setUsername(userName);
		client.setPassword(password);
		client.setEmail(email);

		String postString = "__elgg_token=" + client.getElggToken()
				+ "&__elgg_ts=" + client.getElggTs() + "&name="
				+ client.getUsername() + "&email=" + client.getEmail()
				+ "&username=" + client.getUsername() + "&password="
				+ client.getPassword() + "&password2=" + client.getPassword()
				+ "&friend_guid=0+&invitecode=&submit=Register";
		// __elgg_token=0c3a778d2b74a7e7faf63a6ba55d4832&__elgg_ts=1434992983&name=display_name&email=tapti.palit%40gmail.com&username=user_name&password=pass_word&password2=pass_word&friend_guid=0&invitecode=&submit=Register

		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Accept",
				"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		headers.put("Accept-Language", "en-US,en;q=0.5");
		headers.put("Accept-Encoding", "gzip, deflate");
		headers.put("Referer", hostUrl + "/register");
		headers.put("User-Agent",
				"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");

		sb = client.getHttp().fetchURL(hostUrl + DO_REGISTER_URL, postString,
				headers);
		// System.out.println(sb);
		context.recordTime();
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptUpdateActivityCnt++;
		}

	}

	static class ElggDriverMetrics implements CustomMetrics {

		int attemptLoginCnt = 0;
		int attemptHomePageCnt = 0;
		int attemptPostWallCnt = 0;
		int attemptUpdateActivityCnt = 0;
		int attemptAddFriendsCnt = 0;
		int attemptSendChatMessageCnt = 0;
		int attemptRecvChatMessageCnt = 0;
		
		@Override
		public void add(CustomMetrics arg0) {
			ElggDriverMetrics e = (ElggDriverMetrics) arg0;
			this.attemptHomePageCnt += e.attemptHomePageCnt;
			this.attemptLoginCnt += e.attemptLoginCnt;
			this.attemptPostWallCnt += e.attemptPostWallCnt;
			this.attemptUpdateActivityCnt += e.attemptUpdateActivityCnt;
			this.attemptAddFriendsCnt += e.attemptAddFriendsCnt;
			this.attemptSendChatMessageCnt += e.attemptSendChatMessageCnt;
			this.attemptRecvChatMessageCnt += e.attemptRecvChatMessageCnt;
		}

		@Override
		public Element[] getResults() {
			Element[] el = new Element[7];
			el[0] = new Element();
			el[0].description = "Number of times home page was actually attempted to be accessed.";
			el[0].passed = true;
			el[0].result = "" + this.attemptHomePageCnt;
			el[1] = new Element();
			el[1].description = "Number of times login was actually attempted.";
			el[1].passed = true;
			el[1].result = "" + this.attemptLoginCnt;
			el[2] = new Element();
			el[2].description = "Number of times posting on wall was actually attempted.";
			el[2].passed = true;
			el[2].result = "" + this.attemptPostWallCnt;
			el[3] = new Element();
			el[3].description = "Number of times update activity was actually attempted.";
			el[3].passed = true;
			el[3].result = "" + this.attemptUpdateActivityCnt;
			el[4] = new Element();
			el[4].description = "Number of times add friends was actually attempted.";
			el[4].passed = true;
			el[4].result = "" + this.attemptAddFriendsCnt;
			el[5] = new Element();
			el[5].description = "Number of times send message was actually attempted.";
			el[5].passed = true;
			el[5].result = "" + this.attemptSendChatMessageCnt;
			el[6] = new Element();
			el[6].description = "Number of times receive message was actually attempted.";
			el[6].passed = true;
			el[6].result = "" + this.attemptRecvChatMessageCnt;
			return el;
		}

		public Object clone() {
			ElggDriverMetrics clone = new ElggDriverMetrics();
			clone.attemptHomePageCnt = this.attemptHomePageCnt;
			clone.attemptLoginCnt = this.attemptLoginCnt;
			clone.attemptPostWallCnt = this.attemptPostWallCnt;
			clone.attemptUpdateActivityCnt = this.attemptUpdateActivityCnt;
			clone.attemptAddFriendsCnt = this.attemptAddFriendsCnt;
			clone.attemptSendChatMessageCnt = this.attemptSendChatMessageCnt;
			clone.attemptRecvChatMessageCnt = this.attemptRecvChatMessageCnt;
			return clone;
		}
	}

	public static void main(String[] pp) throws Exception {
		Web20Driver driver = new Web20Driver();
		driver.accessHomePage();
		driver.accessHomePage();
		driver.doLogin();
		driver.doLogin();
		driver.sendChatMessage();
	}
	


}

