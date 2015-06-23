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

import org.json.JSONObject;

import com.sun.faban.driver.BenchmarkDefinition;
import com.sun.faban.driver.BenchmarkDriver;
import com.sun.faban.driver.BenchmarkOperation;
import com.sun.faban.driver.CustomMetrics;
import com.sun.faban.driver.CycleType;
import com.sun.faban.driver.DriverContext;
import com.sun.faban.driver.FlatMix;
import com.sun.faban.driver.HttpTransport;
import com.sun.faban.driver.NegativeExponential;
import com.sun.faban.driver.Timing;


@BenchmarkDefinition(name = "Elgg benchmark", version = "0.1")

@BenchmarkDriver (
		name = "ElggDriver", /* Should be the same as the name attribute of driverConfig in run.xml */
		threadPerScale = 1
		)

/*
 * We have 3 types of operations, each equally likely (for now)
 */
@FlatMix (mix = {5, 10, 30, 40, 10, 5},
			operations = {"AccessHomepage", "DoLogin", "PostSelfWall", "UpdateActivity", "AddFriend", "Register"},
			deviation = 5)

@NegativeExponential (
		cycleDeviation = 2,
		cycleMean = 1000, //1 seconds
		cycleType = CycleType.THINKTIME) // cycle time or think time - count from the start of prev operation or end

/**
 * The main driver class.
 * 
 * @author Tapti Palit
 *
 */
public class Web20Driver {

	private DriverContext context;
	private Logger logger;
	private FileHandler fileTxt;

	private SimpleFormatter formatterTxt;
	  
	private ElggDriverMetrics elggMetrics;
	
	private String hostUrl;
	
	/*
	 * We can think of the users waiting at any of the pages as a "state" the user is in. 
	 * An operation can be performed only by the users which are in a "valid" state to perform that operation.
	 * For example, we can update River feed only for the logged in users. 
	 */
	
	/**
	 * The userPasswordList consists of all the available users and their passwords. This is read from the run.xml file.
	 * 
	 * DONOT use this directly. This is not synchronized among the agent threads. Use myUserPasswordList
	 */
	private List<UserPasswordPair> userPasswordList;
	private List<UserPasswordPair> myUserPasswordList;
	
	private int userPasswordIndex;
	
	/**
	 * The list of clients who are at the home page.
	 * Visiting the homepage causes a cookie to be associated with the client.
	 */
	private List<Web20Client> homeClientList;
	
	/**
	 * The list of clients who are logged in. This could be in any of the pages of the site.
	 */
	private List<Web20Client> loggedInClientList;
	
	/**
	 * The list of clients who are in the Activity page.
	 */
	private List<Web20Client> activityClientList;
	
	/* Constants : URL*/
	private final String ROOT_URL = "/";
	private final String[] ROOT_URLS= new String[] {
			"/vendors/requirejs/require-2.1.10.min.js",
			"/vendors/jquery/jquery-1.11.0.min.js",
			"/vendors/jquery/jquery-migrate-1.2.1.min.js",
			"/vendors/jquery/jquery-ui-1.10.4.min.js",
			"/_graphics/favicon-16.png",
			"/_graphics/favicon-32.png",
			"/_graphics/icons/user/defaultsmall.gif",
			"/_graphics/icons/user/defaulttiny.gif",
			"/_graphics/header_shadow.png",
			"/_graphics/elgg_sprites.png",
			"/_graphics/sidebar_background.gif",
			"/_graphics/button_graduation.png",
			"/_graphics/favicon-128.png"
	};
	private final String LOGIN_URL = "/action/login";
	private final String[] LOGIN_URLS = new String[] {
			"/js/lib/ui.river.js",
			"/_graphics/icons/user/defaulttopbar.gif",
			"/mod/riverautoupdate/_graphics/loading.gif",
			"/_graphics/toptoolbar_background.gif",
			"/mod/reportedcontent/graphics/icon_reportthis.gif"
	};
	private final String ACTIVITY_URL = "/activity";
	private final String[] ACTIVITY_URLS = new String[] {
			"/mod/hypeWall/vendors/fonts/font-awesome.css",
			"/mod/hypeWall/vendors/fonts/open-sans.css"
	};
	
	private final String RIVER_UPDATE_URL = "/activity/proc/updateriver";
	private final String WALL_URL = "/action/wall/status";
	
	private final String REGISTER_PAGE_URL = "/register";
	
	private final String DO_REGISTER_URL = "/action/register";
	private final String DO_ADD_FRIEND = "/action/friends/add";
	
	public Web20Driver() throws SecurityException, IOException {
		
		context = DriverContext.getContext();
		userPasswordList = new ArrayList<UserPasswordPair>();
		myUserPasswordList = new ArrayList<UserPasswordPair>();
		
		logger = context.getLogger();
		logger.setLevel(Level.INFO);
	    fileTxt = new FileHandler("Logging.txt");
	    formatterTxt = new SimpleFormatter();
	    fileTxt.setFormatter(formatterTxt);
	    logger.addHandler(fileTxt);

	    BufferedReader bw = new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("users.txt")));
	    String line;
	    while ((line = bw.readLine()) != null) {
	    	String tokens[] = line.split(" ");
	    	UserPasswordPair pair = new UserPasswordPair(tokens[0], tokens[1]);
	    	userPasswordList.add(pair);
	    }
		bw.close();
		// Partition the userPasswordList by the number of threads. 
		int partitionSize = (context.getScale() > 0? userPasswordList.size() / context.getScale() : userPasswordList.size()) ;
		int startIndex = ( context.getScale() > 0 ? (context.getThreadId() % context.getScale()) * partitionSize: 0  );
		int endIndex = startIndex + partitionSize;
		for (int i = startIndex; i < endIndex && i < userPasswordList.size(); i++) {
			myUserPasswordList.add(userPasswordList.get(i));
		}
		
		StringBuffer logBuffer = new StringBuffer();
		logBuffer.append("Thread id = "+context.getThreadId()+" has start and end index as "+startIndex + " "+endIndex);
		logBuffer.append("Thread will work with the following users: \n");
		for (UserPasswordPair u: myUserPasswordList) {
			logBuffer.append(u.getUserName()+"\n");
		}
		logger.fine(logBuffer.toString());
		
		elggMetrics = new ElggDriverMetrics();
		context.attachMetrics(elggMetrics);
		
		homeClientList = new ArrayList<Web20Client>();
		loggedInClientList = new ArrayList<Web20Client>();
		activityClientList = new ArrayList<Web20Client>();
				
		// #TODO Read from config
		hostUrl = "http://10.22.17.101";
		
		userPasswordIndex = -1;
	}
	
	private UserPasswordPair selectNextUsernamePassword() {
		if (++userPasswordIndex < myUserPasswordList.size()) {
			return myUserPasswordList.get(userPasswordIndex);
		} else {
			return null;
		}
	}

	private synchronized Web20Client selectRandomHomePageClient() {
		if (homeClientList.size() > 0) {
			Random random = new Random();
			int randomIndex = random.nextInt(homeClientList.size());
			Web20Client client = homeClientList.get(randomIndex);
			logger.fine("Thread Id : " +context.getThreadId() + " removing from home page : "+ client.getUsername());
			homeClientList.remove(client);
			return client;
		} else {
			return null;
		}
	}
	
	private synchronized Web20Client selectRandomActivityPageClient() {
		if (activityClientList.size() > 0) {
			Random random = new Random();
			int randomIndex = random.nextInt(activityClientList.size());
			return activityClientList.get(randomIndex);
		} else {
			return null;
		}
	}
	
	private synchronized Web20Client selectRandomLoggedInClient() {
		if (loggedInClientList.size() > 0) {
			Random random = new Random();
			int randomIndex = random.nextInt(loggedInClientList.size());
			return loggedInClientList.get(randomIndex);
		} else {
			return null;
		}
	}
	
	private void updateElggTokenAndTs(Web20Client client, StringBuilder sb) {
		
		// Get the Json
		int startIndex = sb.indexOf("var elgg = ");
		int endIndex = sb.indexOf(";", startIndex);
		String elggJson = sb.substring(startIndex+"var elgg = ".length(), endIndex);
		//System.out.println(elggJson);
		
		JSONObject elgg = new JSONObject(elggJson);
		JSONObject securityToken = elgg.getJSONObject("security").getJSONObject("token");
		String elggToken = securityToken.getString("__elgg_token");
		Long elggTs = securityToken.getLong("__elgg_ts");

		//System.out.println("Elgg Token = "+elggToken);
        //System.out.println("Elgg Ts = "+elggTs);
        client.setElggToken(elggToken);
        client.setElggTs(elggTs.toString());
        
        if (!elgg.getJSONObject("session").isNull("user")) {
        	JSONObject userSession = elgg.getJSONObject("session").getJSONObject("user");
    		Integer elggGuid = userSession.getInt("guid");
    		//var elgg = {"config":{"lastcache":1433352491,"viewtype":"default","simplecache_enabled":1},"security":{"token":{"__elgg_ts":1434062648,"__elgg_token":"5e435f7859b03068395b986c5b257334"}},"session":{"user":{"guid":274,"type":"user","subtype":"","owner_guid":274,"container_guid":0,"site_guid":1,"time_created":"2015-06-10T15:41:22-04:00","time_updated":"2015-06-10T15:41:22-04:00","url":"http:\/\/10.22.17.101\/profile\/UVuopgYrGM","name":"UVuopgYrGM","username":"UVuopgYrGM","language":"en","admin":false}},"page_owner":{"guid":274,"type":"user","subtype":"","owner_guid":274,"container_guid":0,"site_guid":1,"time_created":"2015-06-10T15:41:22-04:00","time_updated":"2015-06-10T15:41:22-04:00","url":"http:\/\/10.22.17.101\/profile\/UVuopgYrGM","name":"UVuopgYrGM","username":"UVuopgYrGM","language":"en"}};
            // System.out.println("Guid = "+elggGuid);

            client.setGuid(elggGuid.toString());
        }
	}
	
	@BenchmarkOperation (
			name = "UpdateActivity",
			max90th = 10.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.MANUAL
			)
	public void updateActivity() throws Exception {
		boolean success = false;
		context.recordTime();
		Web20Client client = selectRandomActivityPageClient();
		if (null != client) {
			String postString = "options%5Bcount%5D=false&options%5Bpagination%5D=false&options%5Boffset%5D=0&options%5Blimit%5D=20&count=2"; // #TODO - What on earth is this?
			StringBuilder sb = client.getHttp().fetchURL(hostUrl+RIVER_UPDATE_URL, postString);
			success = true;
		}
		context.recordTime();
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptUpdateActivityCnt++;
		}

	}
	
	@BenchmarkOperation (
			name = "AccessHomepage",
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

		UserPasswordPair userPwdPair = selectNextUsernamePassword();
		if (null != userPwdPair) {
			Web20Client client = new Web20Client();
			
			client.setUsername(userPwdPair.getUserName());
			client.setPassword(userPwdPair.getPassword());
			
			HttpTransport http = HttpTransport.newInstance();
			http.addTextType("application/xhtml+xml");
			http.addTextType("application/xml");
			http.addTextType("q=0.9,*/*");
			http.addTextType("q=0.8");
			http.setFollowRedirects(true);
			
			client.setHttp(http);
			
	        StringBuilder sb = http.fetchURL(hostUrl+ROOT_URL);
	        
	        updateElggTokenAndTs(client, sb);
	        for (String url: ROOT_URLS) {
	        	http.readURL(hostUrl+url);
	            //System.out.println(sb.indexOf("__elgg_token"));
	        }
	        success = true;

	        homeClientList.add(client);
		}
        context.recordTime();
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptHomePageCnt++;
		}

	}
	
	@BenchmarkOperation (
			name = "DoLogin",
			max90th = 10.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.MANUAL
			)
	public void doLogin() throws Exception {
		boolean success = false;
		context.recordTime();
		Web20Client client = selectRandomHomePageClient();
			if (null != client) {
			/* 
			 * To do the login, 
			 * To login, we need four parameters in the POST query 
			 * 1. Elgg token
			 * 2. Elgg timestamp
			 * 3. user name
			 * 4. password
			 * 
			 */
			String postRequest="__elgg_token="+client.getElggToken()+"&__elgg_ts="+client.getElggTs()+"&username="+client.getUsername()+"&password="+client.getPassword();
			//System.out.println("post request = "+postRequest);
	        for (String url: LOGIN_URLS) {
	        	client.getHttp().readURL(hostUrl+url);
	        }
			Map<String, String> headers = new HashMap<String, String>();
			headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			headers.put("Accept-Language", "en-US,en;q=0.5");
			headers.put("Accept-Encoding", "gzip, deflate");
			headers.put("Referer", hostUrl+"/");
			headers.put("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");
	
			StringBuilder sb = client.getHttp().fetchURL(hostUrl+LOGIN_URL, postRequest, headers);
			//System.out.println(sb.toString());
			logger.fine(sb.toString());
			updateElggTokenAndTs(client, sb);
			logger.fine(""+client.getHttp().getResponseCode());
			loggedInClientList.add(client);
	        activityClientList.add(client);
			success = true;
		}
		context.recordTime();
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptLoginCnt++;
		}
		
	}

	/**
	 * Add friend
	 * @throws Exception
	 */
	@BenchmarkOperation (
			name = "AddFriend",
			max90th = 10.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.MANUAL
			)
	public void addFriend() throws Exception {
		boolean success = false;
		context.recordTime();
		Web20Client friender = selectRandomLoggedInClient();
		if (null != friender) {
			int friendeeGuid = new Random().nextInt(userPasswordList.size());
			String postString = "friend="+friendeeGuid+"&__elgg_ts"+friender.getElggTs()+"&__elgg_token"+friender.getElggToken();
			friender.getHttp().fetchURL(hostUrl+DO_ADD_FRIEND+"?"+postString, postString);
			success = true;
		}
		context.recordTime();
		if (success) {
			if (context.isTxSteadyState()) {
				elggMetrics.attemptAddFriendsCnt++;
			}
		}

	}
	
	/**
	 * Post something on the Wall (actually on the Wire but from the Wall!).
	 * @throws Exception
	 */
	@BenchmarkOperation (
			name = "PostSelfWall",
			max90th = 10.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.MANUAL
			)
	public void postSelfWall() throws Exception {
		boolean success = false;
		
		context.recordTime();		
		Web20Client client = selectRandomActivityPageClient();
		if (null == client) {
			client = selectRandomLoggedInClient();
			if (null == client) {
				context.recordTime();
				return;
			}
			client.getHttp().fetchURL(hostUrl+ACTIVITY_URL);
			for (String url: ACTIVITY_URLS) {
				client.getHttp().fetchURL(hostUrl+url);
			}
		}
		String status = "Hello world! "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
		String postRequest = "__elgg_token="+client.getElggToken()+"&__elgg_ts="+client.getElggTs()+"&status="+status+"&address=&access_id=2&origin=wall&container_guid="+client.getGuid();

		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		headers.put("Accept-Language", "en-US,en;q=0.5");
		headers.put("Accept-Encoding", "gzip, deflate");
		headers.put("Referer", hostUrl+"/activity");
		headers.put("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");

		StringBuilder sb = client.getHttp().fetchURL(hostUrl+WALL_URL, postRequest, headers);
		updateElggTokenAndTs(client, sb);
		success = true;
		context.recordTime();
		
		if (context.isTxSteadyState()) {
			if (success)
				elggMetrics.attemptPostWallCnt++;
		}

	}
	
	@BenchmarkOperation (
			name = "Register",
			max90th = 10.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.MANUAL
			)
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
		
        StringBuilder sb = http.fetchURL(hostUrl+ROOT_URL);
        
        updateElggTokenAndTs(client, sb);
        for (String url: ROOT_URLS) {
        	http.readURL(hostUrl+url);
            //System.out.println(sb.indexOf("__elgg_token"));
        }
        
		context.recordTime();

		// Click on Register link and generate user name and password
		
		client.getHttp().fetchURL(hostUrl+REGISTER_PAGE_URL);
		String userName = RandomStringGenerator.generateRandomString(10, RandomStringGenerator.Mode.ALPHA);
		String password = RandomStringGenerator.generateRandomString(10, RandomStringGenerator.Mode.ALPHA);
		String email = RandomStringGenerator.generateRandomString(7, RandomStringGenerator.Mode.ALPHA)
					+"@"+RandomStringGenerator.generateRandomString(5, RandomStringGenerator.Mode.ALPHA)
					+".co.in";
		client.setUsername(userName);
		client.setPassword(password);
		client.setEmail(email);
		
		String postString = "__elgg_token="+client.getElggToken()+"&__elgg_ts="+client.getElggTs()
							+"&name="+client.getUsername()+"&email="+client.getEmail()
							+"&username="+client.getUsername()+"&password="+client.getPassword()+"&password2="+client.getPassword()
							+"&friend_guid=0+&invitecode=&submit=Register";
		// __elgg_token=0c3a778d2b74a7e7faf63a6ba55d4832&__elgg_ts=1434992983&name=display_name&email=tapti.palit%40gmail.com&username=user_name&password=pass_word&password2=pass_word&friend_guid=0&invitecode=&submit=Register
		
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		headers.put("Accept-Language", "en-US,en;q=0.5");
		headers.put("Accept-Encoding", "gzip, deflate");
		headers.put("Referer", hostUrl+"/register");
		headers.put("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:33.0) Gecko/20100101 Firefox/33.0");

		sb = client.getHttp().fetchURL(hostUrl+DO_REGISTER_URL, postString, headers);
		//System.out.println(sb);
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
		
		@Override
		public void add(CustomMetrics arg0) {
			ElggDriverMetrics e = (ElggDriverMetrics)arg0;
			this.attemptHomePageCnt += e.attemptHomePageCnt;
			this.attemptLoginCnt += e.attemptLoginCnt;
			this.attemptPostWallCnt += e.attemptPostWallCnt;
			this.attemptUpdateActivityCnt += e.attemptUpdateActivityCnt;
			this.attemptAddFriendsCnt += e.attemptAddFriendsCnt;
		}

		@Override
		public Element[] getResults() {
			Element[] el = new Element[5];
			el[0] = new Element();
			el[0].description = "Number of times home page was actually attempted to be accessed.";
			el[0].passed = true;
			el[0].result = ""+this.attemptHomePageCnt;
			el[1] = new Element();
			el[1].description = "Number of times login was actually attempted.";
			el[1].passed = true;
			el[1].result = ""+this.attemptLoginCnt;
			el[2] = new Element();
			el[2].description = "Number of times posting on wall was actually attempted.";
			el[2].passed = true;
			el[2].result = ""+this.attemptPostWallCnt;
			el[3] = new Element();
			el[3].description = "Number of times update activity was actually attempted.";
			el[3].passed = true;
			el[3].result = ""+this.attemptUpdateActivityCnt;
			el[4] = new Element();
			el[4].description = "Number of times add friends was actually attempted.";
			el[4].passed = true;
			el[4].result = ""+this.attemptAddFriendsCnt;
			return el;
		}
		
        public Object clone() {
        	ElggDriverMetrics clone = new ElggDriverMetrics();
        	clone.attemptHomePageCnt = this.attemptHomePageCnt;
        	clone.attemptLoginCnt = this.attemptLoginCnt;
        	clone.attemptPostWallCnt = this.attemptPostWallCnt;
        	clone.attemptUpdateActivityCnt = this.attemptUpdateActivityCnt;
        	clone.attemptAddFriendsCnt = this.attemptAddFriendsCnt;
            return clone;
        }
	}
	
	public static void main (String[] pp) throws Exception {
		Web20Driver driver = new Web20Driver();
		driver.register();
			
	}
	
	
}

class RandomStringGenerator {
	
	public static enum Mode {
	    ALPHA, ALPHANUMERIC, NUMERIC 
	}
	
	public static String generateRandomString(int length, Mode mode) throws Exception {

		StringBuffer buffer = new StringBuffer();
		String characters = "";

		switch(mode){
		
		case ALPHA:
			characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
			break;
		
		case ALPHANUMERIC:
			characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
			break;
	
		case NUMERIC:
			characters = "1234567890";
		    break;
		}
		
		int charactersLength = characters.length();

		for (int i = 0; i < length; i++) {
			double index = Math.random() * charactersLength;
			buffer.append(characters.charAt((int) index));
		}
		return buffer.toString();
	}
}
