/*
 * Copyright 2016-2017 Searsia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.searsia.web;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import org.json.JSONObject;
import org.searsia.SearchResult;
import org.searsia.index.SearchResultIndex;
import org.searsia.index.ResourceIndex;
import org.searsia.engine.Resource;
import org.searsia.engine.SearchException;

/**
 * Generates json response for HTTP GET search.
 * 
 * @author Dolf Trieschnigg and Djoerd Hiemstra
 */
@Path("{resourceid}")
public class Search {

	private final static org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger(Search.class);
    private final static DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private final static String startTime = dateFormat.format(new Date());
	
	private ResourceIndex engines;
    private SearchResultIndex index;
    private long nrOfQueriesOk = 0;
    private long nrOfQueriesError = 0;


	public Search(SearchResultIndex index, ResourceIndex engines) throws IOException {
		this.engines  = engines;
    	this.index = index;
	}
		
	@OPTIONS
	public Response options() {
	    return Response.status(Response.Status.NO_CONTENT)
				.header("Access-Control-Allow-Origin", "*")
				.header("Access-Control-Allow-Methods", "GET")
        		.build();
	}

	@GET
	@Produces(SearchResult.SEARSIA_MIME_ENCODING)
	public Response query(@PathParam("resourceid") String resourceid, @QueryParam("q") String query) {
	    if (!resourceid.endsWith(".json")) {
	        return  SearsiaApplication.responseError(404, "Not found: " + resourceid);
	    }
        resourceid = resourceid.replaceAll("\\.json$", "");
		Resource me, engine, mother;
		SearchResult result;
		JSONObject json;
		me = engines.getMyself();
		mother = engines.getMother();
		if (!resourceid.equals(me.getId())) {
			engine = engines.get(resourceid);
			if (engine == null || engine.getLastUpdatedSecondsAgo() > 7200) {  // unknown or really old? ask your mother
				if (mother != null) {     // TODO: option for 7200 and similar value (3600) in Main
				    try {
    				    engine  = mother.searchResource(resourceid);
				    } catch (SearchException e) {
				    	String message = "Resource not found: " + resourceid;
				    	LOGGER.warn(message);
					    return SearsiaApplication.responseError(404, message);
				    }
				}
				if (engine == null) {
					String message = "Unknown resource identifier: " + resourceid;
			    	LOGGER.warn(message);
    				return SearsiaApplication.responseError(404, message);
				}
    		    engines.put(engine);
 			}
			if (query != null && query.trim().length() > 0) {
			    result = index.cacheSearch(query, engine.getId());
			    if (result != null) {
			        result.removeResourceQuery();
			        json = result.toJson();
			        json.put("resource", engine.toJson());
			        LOGGER.info("Cache " + resourceid + ": " + query);
			        return SearsiaApplication.responseOk(json);
			    } else {
			        try {
                        result = engine.search(query);
                        result.removeResourceQuery();     // only trust your mother
                        json = result.toJson();                         // first json for response, so
                        result.addQueryResourceDate(engine.getId()); // response will not have query + resource
                        index.offer(result);  //  maybe do this AFTER the http response is sent:  https://jersey.java.net/documentation/latest/async.html (11.1.1)
                        json.put("resource", engine.toJson());
                        LOGGER.info("Query " + resourceid + ": " + query);
                        return SearsiaApplication.responseOk(json);
                    } catch (Exception e) {
                        String message = "Resource " + resourceid + " unavailable: " + e.getMessage();
                        LOGGER.warn(message);
                        return SearsiaApplication.responseError(503, message);
                    }
			    }
			} else {
				json = new JSONObject().put("resource", engine.toJson());
				json.put("health", engine.toJsonHealth());
		        LOGGER.info("Resource " + resourceid + ".");
				return SearsiaApplication.responseOk(json);
			}
		} else {
			JSONObject healthJson = null;
		    if (query != null && query.trim().length() > 0) {
		    	try {
			        result = index.search(query);
			    } catch (Exception e) {
			    	String message = "Service unavailable: " + e.getMessage();
			    	LOGGER.warn(message);
                    this.nrOfQueriesError += 1;
				    return SearsiaApplication.responseError(503, message);				
			    }
                this.nrOfQueriesOk += 1;
		    	if (result.getHits().isEmpty() && mother != null) {  // empty? ask mother!
				    try {
    				    result  = mother.search(query);
    				    index.offer(result);  // really trust mother
				    } catch (SearchException e) {
				    	LOGGER.warn("Mother not available");
				    } catch (Exception e) {
				        LOGGER.warn(e);
				    }
		    	} else {  // own results? Do resource ranking.
			        result.scoreResourceSelection(query, engines);
		    	}
			} else {
				result = new SearchResult();
		        result.scoreResourceSelection(query, engines);
		        healthJson = engines.toJsonHealth();
		        healthJson.put("requestsok", this.nrOfQueriesOk);
                healthJson.put("requestserr", this.nrOfQueriesError);
                healthJson.put("upsince", startTime);
			}
		    json = result.toJson();
		    json.put("resource", me.toJson());
		    if (healthJson != null) {
                json.put("health", healthJson);
		    }
		    LOGGER.info("Local " + resourceid + ": " + query); // TODO query can be null
			return SearsiaApplication.responseOk(json);
		}
	}
	
}
