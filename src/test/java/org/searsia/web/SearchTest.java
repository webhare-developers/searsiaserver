package org.searsia.web;

import java.io.IOException;

import javax.ws.rs.core.Response;
import javax.xml.xpath.XPathExpressionException;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.varia.NullAppender;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.searsia.SearsiaOptions;
import org.searsia.index.SearchResultIndex;
import org.searsia.index.ResourceIndex;
import org.searsia.web.Search;
import org.searsia.engine.Resource;

public class SearchTest {
    
    private static boolean letsLog = false;

    private static final Logger LOGGER = Logger.getLogger("org.searsia");
    private static final String PATH  = "target/index-test";
    private static final String INDEX = "test2";
    private static SearchResultIndex index;
    private static ResourceIndex engines;
	private static SearsiaOptions options;


    private static Resource wiki() throws XPathExpressionException, JSONException {
    	return new Resource(new JSONObject("{\"apitemplate\":\"http://searsia.org/searsia/wiki/wiki{q}.json\", \"id\":\"wiki\"}"));
    }
 
    private static Resource wrong() throws XPathExpressionException, JSONException {
    	return new Resource(new JSONObject("{\"apitemplate\":\"http://reallyreallydoesnotexist.com/wrong?q={q}\", \"id\":\"wrong\"}"));
    }
    
    private static Resource ok() throws XPathExpressionException, JSONException {
        return new Resource(new JSONObject("{\"apitemplate\":\"http://searsia.org/searsia/wiki/wikifull1{q}.json\", \"id\":\"wikifull1\"}"));
    }
    
    private static Resource okDeleted() throws XPathExpressionException, JSONException {
        return new Resource(new JSONObject("{\"deleted\":true, \"id\":\"wikifull1\"}"));
    }
    
    private static Resource me() throws XPathExpressionException, JSONException {
    	return new Resource(new JSONObject("{\"apitemplate\":\"http://me.org?q={q}\", \"id\":\"wiki\"}"));
    }

    @BeforeClass
    public static void setUp() throws Exception {
        Appender appender = null;
    	LOGGER.removeAllAppenders();
    	if (letsLog) {
    	    appender = new ConsoleAppender(new PatternLayout("%m%n"), ConsoleAppender.SYSTEM_ERR);
    	} else {
    	    appender = new NullAppender();  // thou shall not log
    	}
    	LOGGER.addAppender(appender);
        LOGGER.setLevel(Level.ALL);
    	index = new SearchResultIndex(PATH, INDEX, 10);
    	engines = new ResourceIndex(PATH, INDEX);
    	options = new SearsiaOptions();
    	engines.putMother(wiki());
    	engines.put(wrong());
    	engines.put(ok());
    	engines.putMyself(me());
    }

    @AfterClass
    public static void lastThing() throws IOException {
    	index.close();    	
    }
   
    @Test // returns 'my' resource description
	public void test() throws IOException {
		Search search = new Search(index, engines, options);
		Response response = search.query("wiki.json", "", null, null);
		int status = response.getStatus();
		String entity = (String) response.getEntity();
		JSONObject json = new JSONObject(entity);
		JSONObject resource  = (JSONObject) json.get("resource");
        Assert.assertEquals(200, status);
		Assert.assertEquals("wiki", resource.get("id"));
	}
    
    @Test // returns local search results for 'searsia'
	public void testQuery() throws IOException {
		Search search = new Search(index, engines, options);
		Response response = search.query("wiki.json", "searsia search for noobs", null, null);
		int status = response.getStatus();
		String entity = (String) response.getEntity();
		JSONObject json = new JSONObject(entity);
		JSONArray hits  = json.getJSONArray("hits");
		String url = "";
		for (int i = 0; i < hits.length(); i += 1) {
			JSONObject hit = (JSONObject) hits.get(i);
			if (hit.has("url")) {
			    url = hit.getString("url");
			    break;
			}
		}
		Assert.assertEquals(200, status);
		Assert.assertTrue(hits.length() == 3);
		Assert.assertEquals("http://searsia.org", url);
		Assert.assertNotNull(json.get("resource"));		
	}
    
    @Test // returns local resource 'wrong' 
	public void testResource() throws IOException, XPathExpressionException, JSONException {
		Search search = new Search(index, engines, options);
		Response response = search.query("wrong.json", "", null, null);
		int status = response.getStatus();
		String entity = (String) response.getEntity();
		JSONObject json = new JSONObject(entity);
		JSONObject resource  = (JSONObject) json.get("resource");
		Assert.assertEquals(200, status);
		Assert.assertTrue(json.has("health"));
		Assert.assertEquals(wrong().getAPITemplate(), resource.get("apitemplate"));
	}
    
    @Test // returns local resource 'wrong' without apitemplate and health 
	public void testResourceNoSharing() throws IOException, XPathExpressionException, JSONException {
        String[] args = {"-d", "-n", "-m=http://searsia.org/searsia/wiki/wiki{q}.json"};
    	SearsiaOptions newOptions = new SearsiaOptions(args);
		Search search = new Search(index, engines, newOptions);
		Response response = search.query("wrong.json", "", null, null);
		String entity = (String) response.getEntity();
		JSONObject json = new JSONObject(entity);
		JSONObject resource  = (JSONObject) json.get("resource");
		Assert.assertFalse(json.has("health"));
		Assert.assertFalse(resource.has("apitemplate"));
	}
    
    @Test // returns resource 'wikididyoumean' (from mother)
	public void testResourceUnknown() throws IOException {
		Search search = new Search(index, engines, options);
		Response response = search.query("wikididyoumean.json", "", null, null);
		int status = response.getStatus();
		String entity = (String) response.getEntity();
		JSONObject json = new JSONObject(entity);
		JSONObject resource  = (JSONObject) json.get("resource");
		Assert.assertEquals(200, status);
		Assert.assertEquals("Did you mean:", resource.get("name"));
	}
    
    @Test // returns results for the engine 'wrong' (which does not exist)
	public void testError() throws IOException {
		Search search = new Search(index, engines, options);
		Response response = search.query("wrong.json", "testquery", null, null);
		int status = response.getStatus();
		Assert.assertEquals(503, status);
	}

    @Test // returns results for the engine 'wikifull1'
    public void testOk() throws IOException, XPathExpressionException, JSONException {
        Search search = new Search(index, engines, options);
        Response response = search.query("wikifull1.json", "informat", null, null);
        int status = response.getStatus();
        String entity = (String) response.getEntity();
        JSONObject json = new JSONObject(entity);
        Assert.assertEquals(200, status);
        Assert.assertNotNull(json.get("hits"));
        Assert.assertNotNull(json.get("resource"));
        LOGGER.trace("Query result: " + json);
        
        response = search.query("wikifull1.json", "informat", null, null);
        status = response.getStatus();
        entity = (String) response.getEntity();
        json = new JSONObject(entity);
        Assert.assertEquals(200, status);
        Assert.assertNotNull(json.get("hits"));
        Assert.assertNotNull(json.get("resource"));
        LOGGER.trace("Cache result: " + json);
        
        engines.put(okDeleted());
        response = search.query("wikifull1.json", "informat", null, null);
        status = response.getStatus();
        entity = (String) response.getEntity();
        json = new JSONObject(entity);
        Assert.assertEquals(410, status);
        LOGGER.trace("No result: " + json);        
    }

}
