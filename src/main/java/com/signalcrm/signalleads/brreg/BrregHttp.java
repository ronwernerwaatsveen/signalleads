package com.signalcrm.signalleads.brreg;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Felles HTTP-klient mot Brønnøysund og SSB, med tidsavbrudd. Uten dem venter RestTemplate for
 * alltid, og en hengende forbindelse holder en jobbtråd til omstart (samme lærdom som i SignalCRM).
 */
@Component
public class BrregHttp {

    private final RestTemplate restTemplate;

    public BrregHttp(@Value("${app.brreg.connect-timeout-ms:5000}") int connectTimeoutMs,
                     @Value("${app.brreg.read-timeout-ms:20000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeoutMs);
        f.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(f);
    }

    public RestTemplate restTemplate() {
        return restTemplate;
    }
}
