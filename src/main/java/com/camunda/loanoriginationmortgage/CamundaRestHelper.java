package com.camunda.loanoriginationmortgage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin REST wrapper around the Camunda Zeebe REST API.
 * Uses RestTemplate (Java HttpURLConnection) instead of the SDK's Apache HttpClient 5
 * to avoid MalformedResponseException on application/json error responses.
 */
@Service
public class CamundaRestHelper {

    private static final Logger log = LoggerFactory.getLogger(CamundaRestHelper.class);

    private static final String TOKEN_URL        = "https://login.cloud.camunda.io/oauth/token";
    private static final String AUDIENCE         = "zeebe.camunda.io";
    public  static final String TASKLIST_AUDIENCE = "tasklist.camunda.io";

    @Value("${camunda.client.rest-address}")
    private String restAddress;

    @Value("${camunda.client.auth.client-id}")
    private String clientId;

    @Value("${camunda.client.auth.client-secret}")
    private String clientSecret;

    final RestTemplate http = new RestTemplate();
    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient javaHttp = HttpClient.newHttpClient();

    private final Map<String, String>  tokensByAudience  = new HashMap<>();
    private final Map<String, Instant> expiriesByAudience = new HashMap<>();

    String token() { return tokenForAudience(AUDIENCE); }

    synchronized String tokenForAudience(String audience) {
        var expiry = expiriesByAudience.get(audience);
        if (expiry != null && Instant.now().isBefore(expiry)) {
            return tokensByAudience.get(audience);
        }
        String body = "grant_type=client_credentials"
                + "&client_id="     + URLEncoder.encode(clientId,     StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&audience="      + URLEncoder.encode(audience,     StandardCharsets.UTF_8);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.postForObject(TOKEN_URL, new HttpEntity<>(body, headers), Map.class);
        String token = (String) resp.get("access_token");
        Instant exp  = Instant.now().plusSeconds(((Number) resp.get("expires_in")).longValue() - 60);
        tokensByAudience.put(audience, token);
        expiriesByAudience.put(audience, exp);
        return token;
    }

    /**
     * Tasklist base URL derived from the Zeebe REST URL.
     * Camunda 8 SaaS REST URLs all share the pattern
     *   https://{region}.{service}.camunda.io/{cluster}
     * so converting Zeebe → Tasklist is just swapping the service subdomain.
     */
    String tasklistAddress() {
        String addr = restAddress.endsWith("/")
                ? restAddress.substring(0, restAddress.length() - 1) : restAddress;
        return addr.replace(".zeebe.camunda.io/", ".tasklist.camunda.io/");
    }

    public Map<String, Object> post(String path, String jsonBody) {
        String base = restAddress.endsWith("/")
                ? restAddress.substring(0, restAddress.length() - 1) : restAddress;
        String url = base + "/v2" + path;

        var headers = new HttpHeaders();
        headers.setBearerAuth(token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> resp = http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(bodyBytes, headers), String.class);
        String body = resp.getBody();
        log.info("[rest] POST {} -> status={} body={}", path, resp.getStatusCode().value(),
                body == null ? "<null>" : (body.length() > 500 ? body.substring(0, 500) + "..." : body));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(body, Map.class);
            return parsed;
        } catch (Exception e) {
            log.warn("[rest] parse failed for {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    /**
     * POST to the Tasklist v1 REST API ({tasklist-host}{path}) using the
     * tasklist-audience token. Returns the raw response body as a parsed JSON tree
     * (List for arrays, Map for objects).
     */
    public Object tasklistPost(String path, String jsonBody) {
        return tasklistRequest("POST", path, jsonBody);
    }

    /** PATCH to the Tasklist v1 REST API. Used for /v1/tasks/{id}/complete. */
    public Object tasklistPatch(String path, String jsonBody) {
        return tasklistRequest("PATCH", path, jsonBody);
    }

    private Object tasklistRequest(String method, String path, String jsonBody) {
        String url = tasklistAddress() + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + tokenForAudience(TASKLIST_AUDIENCE))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = javaHttp.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            }
            String b = resp.body();
            if (b == null || b.isBlank()) return Map.of();
            return mapper.readValue(b, Object.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> post(String path, Object bodyObj) {
        try {
            return post(path, mapper.writeValueAsString(bodyObj));
        } catch (Exception e) {
            log.warn("[rest] POST {} failed: {}", path, e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> deployResource(byte[] bytes, String filename) {
        String base = restAddress.endsWith("/")
                ? restAddress.substring(0, restAddress.length() - 1) : restAddress;
        String url = base + "/v2/deployments";

        var fileResource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("resources", fileResource);

        var headers = new HttpHeaders();
        headers.setBearerAuth(token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(resp.getBody(), Map.class);
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
