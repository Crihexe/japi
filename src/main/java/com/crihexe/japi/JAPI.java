package com.crihexe.japi;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.json.JSONException;
import org.json.JSONObject;

import com.crihexe.japi.annotations.AuthKey;
import com.crihexe.japi.annotations.BodyParam;
import com.crihexe.japi.annotations.Endpoint;
import com.crihexe.japi.annotations.Header;
import com.crihexe.japi.annotations.Method;
import com.crihexe.japi.annotations.Method.Auth;
import com.crihexe.japi.annotations.Method.Methods;
import com.crihexe.japi.annotations.Nullable;
import com.crihexe.japi.annotations.PathParam;
import com.crihexe.japi.annotations.QueryParam;
import com.crihexe.japi.exception.JAPIException;
import com.crihexe.japi.jackson.BooleanDeserializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;

public class JAPI {
	
	private static CloseableHttpClient httpclient = HttpClients.createDefault();
	
	private ObjectMapper mapper = new ObjectMapper().registerModule(new SimpleModule().addDeserializer(Boolean.class, new BooleanDeserializer())).registerModule(new JodaModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	
	private final String URL;
	
	private String defaultAuthKey;
	
	public JAPI(String URL) {
		this(URL, "");
	}
	
	public JAPI(String URL, String defaultAuthKey) {
		this.URL = URL;
		this.defaultAuthKey = defaultAuthKey;
	}
	
	public void setDefaultAuthKey(String authkey) {
		this.defaultAuthKey = authkey;
	}
	
	public String send(Object request) throws NullPointerException, JAPIException, JSONException, IllegalArgumentException, IllegalAccessException {
		Class<?> c = validateRequest(request);
		
		String endpoint = c.getAnnotation(Endpoint.class).value();
		JSONObject body = new JSONObject();
		String uniqueBody = "";
		byte[] rawData = null;
		ArrayList<com.crihexe.japi.http.Header> headers = new ArrayList<com.crihexe.japi.http.Header>();
		
		boolean authAdded = false;
		boolean uniqueFound = false;
		boolean queryParamFound = false;
		boolean rawDataDetected = false;
		for(Field f : c.getFields()) {
			for(Annotation a : f.getAnnotations()) {
				if(a.annotationType().equals(BodyParam.class)) {
					if(f.getAnnotation(BodyParam.class).keepValue()) {
						if(uniqueFound) throw new JAPIException("There are multiple BodyParam marked as unique");
						uniqueFound = true;
						if(f.getType().equals(byte[].class)) {
							rawData = (byte[]) f.get(request);
							rawDataDetected = true;
						} else uniqueBody = f.get(request).toString();
					} else body.put(f.getName(), f.get(request));
				}
				if(a.annotationType().equals(PathParam.class)) {
					endpoint = endpoint.replace("{" + f.getName() + "}", (String)f.get(request));
				}
				if(a.annotationType().equals(Header.class)) {
					Object value = f.get(request);
					if(value == null) {
						if(!f.isAnnotationPresent(Nullable.class))
							throw new JAPIException("Null param not market as @Nullable");
					} else {
						headers.add(new com.crihexe.japi.http.Header(f.getName(), value));
					}
				}
				if(a.annotationType().equals(QueryParam.class)) {
					String paramName = f.getAnnotation(QueryParam.class).name();
					endpoint += (queryParamFound ? "&" : "?") + (paramName.equals("") ? f.getName() : paramName) + "=" + f.get(request);
					queryParamFound = true;
				}
				if(a.annotationType().equals(AuthKey.class)) {
					Auth auth = c.getAnnotation(Method.class).auth();
					if(auth != Auth.none) {
						String authkey = (String) f.get(request);
						if(authkey == null) 
							if(f.getAnnotation(AuthKey.class).auto()) {
								if(defaultAuthKey == null) throw new JAPIException("The default token is not set! Cannot set the auto token.");
								authkey = defaultAuthKey;
							} else throw new JAPIException("The specified authkey is null! AuthKey.auto is set to false. Set the authkey or change AuthKey.auto to true to try with the default token!");

						headers.add(new com.crihexe.japi.http.Header("Authorization", auth.name + " " + authkey));
						authAdded = true;
					}
				}
			}
		}
		
		if(!authAdded)
			if(c.getAnnotation(Method.class).auth() != Auth.none) throw new JAPIException("This request require an authkey!");
		
		Methods method = c.getAnnotation(Method.class).method();
		com.crihexe.japi.http.Header[] headersArr = headers.toArray(new com.crihexe.japi.http.Header[headers.size()]);
		
		if(method == Methods.GET) {
			return get(URL + endpoint, headersArr);
		} else if(method == Methods.POST) {
			if(rawDataDetected) post(URL + endpoint, rawData, headersArr);
			return post(URL + endpoint, uniqueFound ? uniqueBody : body.toString(), headersArr);
		} else if(method == Methods.PUT) {
			if(rawDataDetected) put(URL + endpoint, rawData, headersArr);
			return put(URL + endpoint, uniqueFound ? uniqueBody : body.toString(), headersArr);
		}
		
		throw new JAPIException("Invalid method for this request");
	}
	
	public <T> T send(Object request, Class<T> c) throws NullPointerException, JSONException, IllegalArgumentException, IllegalAccessException, JAPIException, JsonMappingException, JsonProcessingException {
		String response = send(request);
		return mapper.readValue(response, c);
	}
	
	public <T> T send(Object request, TypeReference<T> valueTypeRef) throws NullPointerException, JSONException, IllegalArgumentException, IllegalAccessException, JAPIException, JsonMappingException, JsonProcessingException {
		String response = send(request);
		return mapper.readValue(response, valueTypeRef);
	}
	
	private String encodeValue(String value) {
	    try {
			return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	    return value;
	}
	
	private String get(String url, com.crihexe.japi.http.Header...headers) {
		HttpGet http = new HttpGet(url);
		
		for(com.crihexe.japi.http.Header header : headers)
			http.addHeader(header.key, header.value);
		
		return execute(http);
	}
	
	private String post(String url, String body, com.crihexe.japi.http.Header...headers) {
		HttpPost http = new HttpPost(url);
		StringEntity requestEntity = new StringEntity(
			    body,
			    ContentType.APPLICATION_JSON);
		
		http.setEntity(requestEntity);
		
		for(com.crihexe.japi.http.Header header : headers)
			http.addHeader(header.key, header.value);
		
		return execute(http);
	}
	
	private String post(String url, byte[] rawData, com.crihexe.japi.http.Header...headers) {
		HttpPost http = new HttpPost(url);
		ByteArrayEntity requestEntity = new ByteArrayEntity(rawData, ContentType.DEFAULT_BINARY);
		
		http.setEntity(requestEntity);
		
		for(com.crihexe.japi.http.Header header : headers)
			http.addHeader(header.key, header.value);
		
		return execute(http);
	}
	
	private String put(String url, String body, com.crihexe.japi.http.Header...headers) {
		HttpPut http = new HttpPut(url);
		StringEntity requestEntity = new StringEntity(
			    body,
			    ContentType.APPLICATION_JSON);
				
		http.setEntity(requestEntity);
		
		for(com.crihexe.japi.http.Header header : headers)
			http.addHeader(header.key, header.value);
		
		return execute(http);
	}
	
	private String put(String url, byte[] rawData, com.crihexe.japi.http.Header...headers) {
		HttpPut http = new HttpPut(url);
		ByteArrayEntity requestEntity = new ByteArrayEntity(rawData, ContentType.DEFAULT_BINARY);
				
		http.setEntity(requestEntity);
		
		for(com.crihexe.japi.http.Header header : headers)
			http.addHeader(header.key, header.value);
		
		return execute(http);
	}
	
	private String execute(ClassicHttpRequest http) {
		try {
			StringBuilder headers = new StringBuilder();
			headers.append("{");
			for(org.apache.hc.core5.http.Header h : http.getHeaders()) {
				headers.append(h.getName() + ": " + h.getValue() + ", ");
			}
			headers.append("}");
			System.out.println(http.getUri() + headers.toString());
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
		try {
			CloseableHttpResponse response = httpclient.execute(http);
			String content = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			response.close();
			return content;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return "";
	}
	
	private Class<?> validateRequest(Object request) throws NullPointerException, JAPIException {
		if(request == null) throw new NullPointerException("Request cannot be null");
		Class<?> c = request.getClass();
		if(!c.isAnnotationPresent(Method.class) || !c.isAnnotationPresent(Endpoint.class)) throw new JAPIException("A valid request should have both Method and Endpoint annotations in order to work!");
		return c;
	}
	
}
