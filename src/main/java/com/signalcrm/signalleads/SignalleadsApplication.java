package com.signalcrm.signalleads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Lead Generator for SignalCRM.
 *
 * Tilstandsløs tjeneste: SignalCRM sender alt som trengs (kundeprofil, produkter, vekter, egne
 * kunder som fasit, budsjett) i hvert kall, og lagrer selv resultatet. Tjenesten henter offentlige
 * data (Brønnøysund, bedriftenes nettsider), kjører KI-analyse og regner ut en etterprøvbar score.
 * Den lagrer ingen kundeinnstillinger og ingen persondata - kun jobber i minnet til SignalCRM har
 * hentet dem, og en cache av offentlige bedriftsdata.
 */
@SpringBootApplication
@EnableScheduling
public class SignalleadsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SignalleadsApplication.class, args);
    }
}
