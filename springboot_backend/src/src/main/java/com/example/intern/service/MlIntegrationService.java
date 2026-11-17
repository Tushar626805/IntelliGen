package com.example.intern.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Talks to the Flask/Ollama service. Failures become AiServiceException with a teacher-friendly message. */
@Service
public class MlIntegrationService {

    private static final Pattern ERROR_FIELD = Pattern.compile("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");

    @Value("${ai.service.url:http://localhost:5000}")
    private String baseUrl;

    /** Optional shared secret; must match IGEN_API_KEY on the Flask side. */
    @Value("${ai.service.key:}")
    private String apiKey;

    private final RestTemplate longClient;   // synchronous generation can take minutes on a local model
    private final RestTemplate quickClient;  // job start / poll / cancel

    public MlIntegrationService() {
        SimpleClientHttpRequestFactory slow = new SimpleClientHttpRequestFactory();
        slow.setConnectTimeout(10000);
        slow.setReadTimeout(600000);
        this.longClient = new RestTemplate(slow);

        SimpleClientHttpRequestFactory fast = new SimpleClientHttpRequestFactory();
        fast.setConnectTimeout(5000);
        fast.setReadTimeout(20000);
        this.quickClient = new RestTemplate(fast);
    }

    // ---------------------------------------------------------------- public API
    public String generateQuestionsFromText(String text, int num) {
        return generateQuestionsFromText(text, num, "medium");
    }

    /** Synchronous generation (kept for the original upload endpoints). Returns Flask's JSON reply. */
    public String generateQuestionsFromText(String text, int num, String difficulty) {
        return call(longClient, HttpMethod.POST, "/generate_mcq", requestBody(text, num, difficulty));
    }

    /** Starts a background job; returns the Flask job id. */
    public String startJob(String text, int num, String difficulty) {
        String reply = call(quickClient, HttpMethod.POST, "/jobs", requestBody(text, num, difficulty));
        String id = extractId(reply);
        if (id == null) {
            throw new AiServiceException(502, "The AI service gave an unexpected reply. Please try again.");
        }
        return id;
    }

    public String getJob(String flaskJobId) {
        return call(quickClient, HttpMethod.GET, "/jobs/" + flaskJobId, null);
    }

    public void cancelJob(String flaskJobId) {
        call(quickClient, HttpMethod.DELETE, "/jobs/" + flaskJobId, null);
    }

    // ---------------------------------------------------------------- internals
    private static Map<String, Object> requestBody(String text, int num, String difficulty) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("num_questions", num);
        body.put("difficulty", difficulty);
        return body;
    }

    private String call(RestTemplate client, HttpMethod method, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("X-IGEN-KEY", apiKey);
        }
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = client.exchange(baseUrl + path, method, request, String.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw mapFailure(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new AiServiceException(503, "The AI service is not reachable. Start it with \"python app.py\" in the "
                    + "FlaskProject folder, and make sure Ollama is running.");
        } catch (Exception e) {
            throw new AiServiceException(502, "The AI service failed unexpectedly. Please try again.");
        }
    }

    /** Package-private for tests: turns a Flask error status/body into a safe exception. */
    static AiServiceException mapFailure(int status, String body) {
        String message = extractError(body);
        if (status == 400) {
            return new AiServiceException(400, message != null ? message : "The AI service rejected the request.");
        }
        if (status == 404) {
            return new AiServiceException(404, message != null ? message : "Job not found or expired.");
        }
        if (status == 401) {
            return new AiServiceException(502, "The AI service refused the request. Check that ai.service.key matches IGEN_API_KEY.");
        }
        return new AiServiceException(502, message != null ? message : "The AI service failed. Please try again.");
    }

    static String extractError(String body) {
        if (body == null) return null;
        Matcher m = ERROR_FIELD.matcher(body);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
    }

    private static final Pattern DONE_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"done\"");
    private static final Pattern GENERATED_FIELD = Pattern.compile("\"generated\"\\s*:\\s*(\\d+)");

    /** True when a Flask job status reply says the job is finished successfully. */
    public static boolean isDone(String json) {
        return json != null && DONE_FIELD.matcher(json).find();
    }

    /** How many questions a finished job produced (0 if unknown). */
    public static int generatedCount(String json) {
        if (json == null) return 0;
        Matcher m = GENERATED_FIELD.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String extractId(String json) {
        if (json == null) return null;
        Matcher m = ID_FIELD.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
