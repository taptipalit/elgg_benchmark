package web20_benchmark;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.sun.faban.driver.BenchmarkDefinition;
import com.sun.faban.driver.BenchmarkDriver;
import com.sun.faban.driver.BenchmarkOperation;
import com.sun.faban.driver.CycleType;
import com.sun.faban.driver.DriverContext;
import com.sun.faban.driver.FlatMix;
import com.sun.faban.driver.HttpTransport;
import com.sun.faban.driver.NegativeExponential;
import com.sun.faban.driver.Timing;

@BenchmarkDefinition(name = "Elgg benchmark", version = "0.1")

@BenchmarkDriver (
		name = "Elgg_Driver",
		percentiles = { "90", "95th", "99.9th%"}
		)

/*
 * We have 4 types of operations, each equally likely (for now)
 */
@FlatMix (mix = {25, 25, 25, 25})

@NegativeExponential (
		cycleDeviation = 2,
		cycleMean = 500, //0.5 seconds
		cycleType = CycleType.CYCLETIME) // cycle time or think time - count from the start of prev operation or end

public class Web20Driver {

	private DriverContext context;
	
	private String hostUrl;
	
	/*
	 * We can think of the users waiting at any of the pages as a "state" the user is in. 
	 * An operation can be performed only by the users which are in a "valid" state to perform that operation.
	 * For example, we can update River feed only for the logged in users. 
	 */
	
	/**
	 * The userPasswordList consists of all the available users and their passwords. When new users are created
	 * they are added to this.
	 * 
	 */
	private List<UserPasswordPair> userPasswordList;
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
	
	public Web20Driver() throws MalformedURLException {
		
		context = DriverContext.getContext();
		homeClientList = new ArrayList<Web20Client>();
		loggedInClientList = new ArrayList<Web20Client>();
		activityClientList = new ArrayList<Web20Client>();
		
		// #TODO Read from config
		userPasswordList = new ArrayList<UserPasswordPair>();
		userPasswordList.add(new UserPasswordPair("tpalit", "password1234"));
		userPasswordList.add(new UserPasswordPair("yoshen", "password1234"));
		
		// #TODO Read from config
		hostUrl = "http://10.22.17.101";
		
		userPasswordIndex = -1;
	}
	
	private UserPasswordPair selectNextUsernamePassword() {
		if (++userPasswordIndex < userPasswordList.size()) {
			return userPasswordList.get(userPasswordIndex);
		} else {
			return null;
		}
	}

	private Web20Client selectRandomHomePageClient() {
		Random random = new Random();
		int randomIndex = random.nextInt(homeClientList.size());
		return homeClientList.get(randomIndex);
	}
	
	private Web20Client selectRandomActivityPageClient() {
		Random random = new Random();
		int randomIndex = random.nextInt(activityClientList.size());
		return activityClientList.get(randomIndex);
	}
	
	private Web20Client selectRandomLoggedInClient() {
		Random random = new Random();
		int randomIndex = random.nextInt(loggedInClientList.size());
		return loggedInClientList.get(randomIndex);
	}
	
	
	@BenchmarkOperation (
			name = "UpdateActivity",
			max90th = 2.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.AUTO
			)
	public void updateActivity() throws Exception {
		Web20Client client = selectRandomActivityPageClient();
		String postString = "options%5Bcount%5D=false&options%5Bpagination%5D=false&options%5Boffset%5D=0&options%5Blimit%5D=20&count=2"; // #TODO - What on earth is this?
		StringBuilder sb = client.getHttp().fetchURL(hostUrl+RIVER_UPDATE_URL, postString);
		System.out.println(sb);
	}
	
	@BenchmarkOperation (
			name = "AccessHomepage",
			max90th = 2.0,
			timing = Timing.AUTO)
	/**
	 * A new client accesses the home page. The "new client" is selected from a list maintained of possible users and their passwords.
	 * The details of the new client are stored in the 
	 * @throws Exception
	 */
	public void accessHomePage() throws Exception {
		
		UserPasswordPair userPwdPair = selectNextUsernamePassword();
		if (null != userPwdPair) {
			Web20Client client = new Web20Client();
			
			client.setUsername(userPwdPair.getUserName());
			client.setPassword(userPwdPair.getPassword());
			
			HttpTransport http = HttpTransport.newInstance();
			client.setHttp(http);
			
	        StringBuilder sb = http.fetchURL(hostUrl+ROOT_URL);
	        // Get the token values
	        int elggTokenStartIndex = sb.indexOf("\"__elgg_token\":\"") + "\"__elgg_token\":\"".length();
	        int elggTokenEndIndex = sb.indexOf("\"", elggTokenStartIndex);
	        String elggToken = sb.substring(elggTokenStartIndex, elggTokenEndIndex);
	        System.out.println("Elgg Token = "+elggToken);
	        
	        int elggTsStartIndex = sb.indexOf("\"__elgg_ts\":") + "\"__elgg_ts\":".length();
	        int elggTsEndIndex = sb.indexOf(",", elggTsStartIndex);
	        String elggTs = sb.substring(elggTsStartIndex, elggTsEndIndex);
	        System.out.println("Elgg Ts = "+elggTs);
	        
	        for (String url: ROOT_URLS) {
	        	http.readURL(hostUrl+url);
	            //System.out.println(sb.indexOf("__elgg_token"));
	        }
	        
	        client.setElggToken(elggToken);
	        client.setElggTs(elggTs);
	        homeClientList.add(client);
		}
	}
	
	@BenchmarkOperation (
			name = "DoLogin",
			max90th = 2.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.AUTO
			)
	public void doLogin() throws Exception {
		Web20Client client = selectRandomHomePageClient();
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
		System.out.println("post request = "+postRequest);
		client.getHttp().fetchURL(hostUrl+LOGIN_URL, postRequest);
		// We should get a redirect to the ROOT URL which we should try to GET
		assert (client.getHttp().getResponseCode() == 302);
		String locations[] = client.getHttp().getResponseHeader("Location");
		assert ((hostUrl+ROOT_URL).equals(locations[0]));
		client.getHttp().fetchURL(hostUrl+ROOT_URL);
		assert (client.getHttp().getResponseCode() == 302);
		locations = client.getHttp().getResponseHeader("Location");
		// We should a redirect to the ACTIVITY URL
		assert ((hostUrl+ACTIVITY_URL).equals(locations[0]));
		client.getHttp().fetchURL(hostUrl+ACTIVITY_URL);
		for (String url: ACTIVITY_URLS) {
			client.getHttp().fetchURL(hostUrl+url);
		}
		assert (client.getHttp().getResponseCode() == 200);
		//System.out.println(out);
        for (String url: LOGIN_URLS) {
        	client.getHttp().readURL(hostUrl+url);
        }
        
        loggedInClientList.add(client);
        activityClientList.add(client);
        homeClientList.remove(client);
	}

	/**
	 * Post something on the Wall (actually on the Wire but from the Wall!).
	 * @throws Exception
	 */
	@BenchmarkOperation (
			name = "PostSelfWall",
			max90th = 2.0, // Fail if 90th percentile crosses 2 seconds
			timing = Timing.AUTO
			)
	public void postSelfWall() throws Exception {
		Web20Client client = selectRandomActivityPageClient();
		if (null == client) {
			client = selectRandomLoggedInClient();
			client.getHttp().fetchURL(hostUrl+ACTIVITY_URL);
			for (String url: ACTIVITY_URLS) {
				client.getHttp().fetchURL(hostUrl+url);
			}
		}
		String status = "Test+status+test+status+test+status+test+status";
		String postRequest = "__elgg_token="+client.getElggToken()+"&__elgg_ts="+client.getElggTs()+"&status="+status+"&address=&access_id=2&origin=wall&container_guid=37";

		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Referer", hostUrl+"/activity");
		client.getHttp().fetchURL(hostUrl+WALL_URL, postRequest, headers);
		if (client.getHttp().getResponseCode() == 302) {
			String[] location = client.getHttp().getResponseHeader("Location");
			StringBuilder out = client.getHttp().fetchURL(location[0]);
			System.out.println(out);
		}
	}
	
	public static void main (String[] pp) throws Exception {
		Web20Driver driver = new Web20Driver();
		driver.accessHomePage();
		driver.doLogin();
		driver.updateActivity();
		driver.postSelfWall();
	}
}
