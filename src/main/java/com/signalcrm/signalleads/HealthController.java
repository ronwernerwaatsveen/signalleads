package com.signalcrm.signalleads;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @Value("${app.ai.enabled:false}")
    private boolean aiEnabled;

    /** Åpen (uten nøkkel) for overvåking - avslører ingenting utover om tjenesten lever. */
    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "aiEnabled", aiEnabled);
    }
}
