package com.signalcrm.signalleads.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Klient mot Google Gemini API (generativelanguage.googleapis.com) - samme oppsett som SignalCRMs
 * GeminiApiClient (én API-nøkkel, egne tidsavbrudd), men ber om JSON-svar og returnerer
 * tokenforbruket, som SignalCRM trenger for å telle mot kundens tak.
 *
 * NB: Gemini API garanterer ikke behandling i EU (det krever Vertex AI med regionbundet
 * tjenestekonto) - samme bevisste valg som i SignalCRM. Det som sendes er offentlige
 * bedriftsdata og nettsidetekst, ikke kundedata fra CRM-et.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    @Value("${app.ai.enabled:false}")
    private boolean aiEnabled;
    @Value("${app.gemini.api-key:}")
    private String apiKey;
    @Value("${app.gemini.api-url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String apiUrl;
    @Value("${app.gemini.model:gemini-2.5-flash}")
    private String defaultModel;
    @Value("${app.gemini.max-tokens:4000}")
    private int maxTokens;
    @Value("${app.gemini.connect-timeout-ms:10000}")
    private int connectTimeoutMs;
    @Value("${app.gemini.read-timeout-ms:120000}")
    private int readTimeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeoutMs);
        f.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(f);
    }

    public boolean isConfigured() {
        return aiEnabled && apiKey != null && !apiKey.isBlank();
    }

    /** Ett kall som ber om et rent JSON-svar. {@code model} null/tom = standardmodellen. */
    public LlmResponse completeJson(String prompt, String model) {
        if (!isConfigured()) throw new IllegalStateException("KI er ikke konfigurert");
        String m = modelName(model);
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
        ObjectNode gen = body.putObject("generationConfig");
        gen.put("maxOutputTokens", maxTokens);
        gen.put("responseMimeType", "application/json");
        gen.put("temperature", 0.2);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-goog-api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = apiUrl + "/" + m + ":generateContent";
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(url, new HttpEntity<>(body.toString(), headers), String.class);
        } catch (HttpStatusCodeException e) {
            String message = e.getResponseBodyAsString();
            try { message = objectMapper.readTree(message).path("error").path("message").asText(message); }
            catch (Exception ignored) { /* ikke JSON - bruk rå tekst */ }
            log.error("Gemini svarte HTTP {} for modell '{}': {}", e.getStatusCode(), m, message);
            throw new IllegalStateException("Gemini-feil: " + message);
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            StringBuilder text = new StringBuilder();
            for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
            JsonNode usage = root.path("usageMetadata");
            return new LlmResponse(text.toString(), usage.path("promptTokenCount").asLong(0),
                    usage.path("candidatesTokenCount").asLong(0), m);
        } catch (Exception e) {
            throw new IllegalStateException("Uleselig svar fra Gemini: " + e.getMessage());
        }
    }

    /** Uten "models/"-prefiks - apiUrl slutter allerede på /models (se SignalCRMs GeminiApiClient#modellnavn). */
    private String modelName(String model) {
        String m = (model == null || model.isBlank()) ? defaultModel : model.trim();
        while (m.startsWith("models/")) m = m.substring("models/".length());
        // Modellnavnet havner i URL-stien - tillat bare vanlige tegn.
        if (!m.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Ugyldig modellnavn");
        return m;
    }
}
