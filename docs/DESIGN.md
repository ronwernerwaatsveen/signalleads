# signalleads – AI-basert lead generator for SignalCRM

Status per 2026-11-03: første versjon er ferdig kodet i både signalleads og SignalCRM. Den er kjørt lokalt
mot Brønnøysund uten KI. Den er ikke satt i drift, og ingenting er committet.

## 1. Formål

signalleads finner og vurderer bedrifter som passer en kundeprofil, og forklarer hvorfor. Den har to
oppgaver:

1. **Analyse av én bedrift.** Dette er «Score mot kundeprofil» på en lead eller mulighet i SignalCRM.
   Den erstatter den gamle Gemini-scoringen (`CustomerProfileScoreService`).
2. **Prospektering.** «Finn nye leads» finner N nye bedrifter som ligner kundeprofilen, og erstatter
   Avansert leadsøk.

Tjenesten er laget for alle SignalCRM-kunder, ikke bare Input Process.

## 2. Vedtatte prinsipper

| Prinsipp | Konsekvens |
|---|---|
| **Tilstandsløs per kunde.** Ingen hardkodet firmaprofil, produkter eller vekter. | Hvert kall har med selger, produkter, kundeprofil, vekter, fasit (egne kunder og tapte salg), eksklusjoner og budsjett. Alt dette bygges av `LeadGeneratorRequestBuilder` i SignalCRM. |
| **Alle kall går fra SignalCRM.** | signalleads lytter bare på 127.0.0.1, med delt API-nøkkel. Ingen brukere og ingen innlogging. |
| **SignalCRM eier dataene.** | Resultater lagres bare i SignalCRM (`st_lead_analyses`, `st_prospect_runs`). signalleads holder jobber i minnet i maks 24 t. Personnavn (offentlige roller) caches aldri i signalleads. |
| **Bevis per påstand.** | Hver faktor og hvert KI-funn peker på en kilde (E1, E2, …) med URL og utdrag. Et kjøpssignal uten et ordrett sitat som finnes i kilden blir forkastet. |
| **Ukjent er ikke det samme som dårlig.** | En faktor kan ha status POSITIVE, NEUTRAL, NEGATIVE, UNKNOWN eller NOT_APPLICABLE. UNKNOWN teller som 0 og trekker ned *datadekningen*, så brukeren ser at scoren er usikker. |
| **Ingen lokal reserve.** | Er generatoren nede i modus GENERATOR, får brukeren en feilmelding og ikke en dårligere score. |
| **NextBestAction holdes utenfor.** | Den tas inn senere. |

## 3. Arkitektur

```
Nettleser ── SignalCRM (9091) ──X-Api-Key, X-Tenant-Ref──▶ signalleads (127.0.0.1:9092)
              │  MySQL: st_lead_analyses,                    │  Brønnøysund (enhet, regnskap, roller)
              │  st_prospect_runs, Company/Opportunity       │  Bedriftens nettside (robots.txt respekteres)
              │                                              │  Gemini API (valgfritt)
```

**signalleads** er en Spring Boot 3.2-app på Java 17. Den har ingen database. Pakker:

| Pakke | Innhold |
|---|---|
| `api` | Kontrakten: forespørsler (`AnalyzeRequest`, `ProspectRequest`) og svar (`JobView`, `CandidateResult`, `FactorResult`, `Evidence`, `AiAnalysis` …). |
| `config` | `ApiKeyFilter` (fail closed, konstant-tid-sammenligning), `TtlCache`, `UsageTracker`. |
| `brreg` | `BrregClient` (enhet, søk, regnskap, roller) og `NaceResolver` (bransjetekst til næringskoder, tabeller portert fra SignalCRM, i tillegg til SSB Klass). |
| `web` | `WebsiteFetcher` (forside og relevante undersider, maks 6000 tegn per side), `UrlGuard` (SSRF-vern) og `Robots`. |
| `ai` | `GeminiClient` (JSON-svar, tokentelling) og `LeadAnalyzer` (prompt med nummererte kilder og validering av svaret). |
| `scoring` | `ScoringEngine`, deterministisk og enhetstestet. |
| `job` | `JobService` (asynkrone jobber og trakten), `CompanyResearcher` (berik → analyser → scor) og `JobController`. |

## 4. API (signalleads)

Alle kall unntatt `/health` krever `X-Api-Key`. GET og DELETE krever i tillegg `X-Tenant-Ref`, som må være lik
`tenantRef` i jobben. Ellers svarer tjenesten 404, slik at én kunde aldri ser en annen kundes jobb.

| Metode | Sti | Beskrivelse |
|---|---|---|
| GET | `/api/v1/health` | Åpen. |
| POST | `/api/v1/jobs/analyze` | Analyse av én bedrift → 202 med jobb-id. |
| POST | `/api/v1/jobs/prospect` | Finn `count` (1–500) nye bedrifter → 202. |
| GET | `/api/v1/jobs/{id}` | Status, framdrift, resultater, forbruk og advarsler. |
| DELETE | `/api/v1/jobs/{id}` | Avbryt. Resultatene så langt beholdes. |

Jobbstatusene er QUEUED, RUNNING, DONE, FAILED og CANCELLED. Ugyldig forespørsel gir 400. Full kø eller
manglende konfigurasjon gir 503.

### Forespørsel (felles felter)

- `tenantRef`: SignalCRMs `coid`.
- `criteriaVersion`: SHA-256 (16 hex) av profil, vekter og produkter. Samme kriterier gir samme versjon,
  så resultater kan sammenlignes over tid.
- `seller`: selgerens navn, språk og produkter (maks 10, beskrivelse maks 1500 tegn).
- `profile`: ansatte/omsetning min–maks, bransjer (tekst) eller næringskoder, steder, sertifiseringer,
  roller og nøkkelord for nettsiden.
- `scoring`: `weights` (manglende nøkkel betyr vekt 0, manglende map betyr like vekter), `thresholdA`, `thresholdB`.
- `references`: `customerOrgNumbers` (fasit for «ligner eksisterende kunder», krever minst 3) og `lostOrgNumbers`.
- `budget`: `maxAiCalls`, `maxWebsites`, `maxCandidates`, `maxPagesPerSite`.
- Bare for analyse: `company` (orgnr, navn, nettside …) og `context` (f.eks. teksten i en Doffin-kunngjøring).
- Bare for prospektering: `excludeOrgNumbers` (alle bedrifter kunden allerede har i CRM-et) og `count`.

## 5. Scoring

Det er åtte faktorer. Hver gir 0–100 og vektes:

| Nøkkel | Standardvekt | Kilde | Regel |
|---|---|---|---|
| `industry` | 20 | E1 Brreg | Næringskoden er innenfor profilens bransjer (100) eller utenfor (0). |
| `size` | 15 | E1 Brreg | Antall ansatte er innenfor intervallet (100), ellers avtar scoren med avstanden. |
| `revenue` | 10 | E2 Regnskap | Som `size`. |
| `financialHealth` | 5 | E2 Regnskap | Negativ egenkapital gir 15, underskudd 50, ellers 100. Konkurs eller avvikling gjør bedriften diskvalifisert. |
| `growth` | 10 | E2 Regnskap | Omsetningsendring siste to år, trinnvis 10–100. |
| `lookalike` | 15 | Fasit | Likhet i næringskode (100/85/70/55/0) og størrelse mot egne kunder. Krever minst 3 referanser. |
| `needsFit` | 20 | KI | Hvor godt produktene treffer virksomheten. Krever gyldige kilde-id-er. |
| `buyingSignals` | 25 | KI | Kjøpssignaler med ordrett sitat fra kilden. |

- **Score** = Σ(vekt × delscore) / Σ vekt over faktorer som ikke er NOT_APPLICABLE.
- **Datadekning** = Σ vekt for kjente faktorer / Σ vekt for faktorer som gjelder.
- **Prioritet:** A hvis score ≥ thresholdA (75), B hvis score ≥ thresholdB (50), ellers C. A krever i
  tillegg datadekning ≥ 0,5, ellers blir det B.
- Er KI slått av for hele tjenesten, blir `needsFit` og `buyingSignals` NOT_APPLICABLE og påvirker verken
  score eller dekning.

### Regnskap

Brreg returnerer både SELSKAP- og KONSERN-regnskap for morselskaper. Tjenesten bruker selskapsregnskapet,
ett per år. Dette ble rettet etter at testen mot Equinor viste «2025-2025».

## 6. KI-analyse

- Kildene nummereres: E1 (Brreg), E2 (Regnskap), E3+ (nettsider og kontekst). Modellen får bare disse og
  må referere til id-ene.
- Svaret valideres i `LeadAnalyzer.validate`:
  - Et kjøpssignal krever både `evidenceId` og et sitat som finnes ordrett (normalisert) i kilden. Ellers
    forkastes det og telles i `rejectedClaims`.
  - Behov uten kilde blir *hypoteser* og merkes slik i UI-et.
  - `needsFit` uten gyldige kilde-id-er blir UNKNOWN.
- Modellnavnet valideres mot `[A-Za-z0-9._-]+`. Tokenforbruket telles per jobb.
- Gemini API er ikke garantert EU-lokalisert. Det er samme valg som SignalCRM allerede har gjort. Bare
  offentlige data (Brreg og bedriftens egen nettside) og kundens produkttekster sendes til modellen.

## 7. Prospekteringstrakt

1. **Kandidater fra Brønnøysund.** Søket filtrerer server-side på næringskode, antall ansatte, kommune og
   konkurs=false. Ekskluderte orgnr og egne kunder tas bort. Antallet begrenses av `maxCandidates`.
2. **Forhåndsscore** på registerdata for alle kandidatene. Dette er gratis.
3. **Regnskap og nettside** for de beste (`maxWebsites`).
4. **KI-analyse** for de beste av dem (`maxAiCalls`).
5. **Endelig score og rangering.** Offentlige roller (daglig leder, styreleder) hentes bare for de
   `count` som returneres.

`app.jobs.concurrent` (2) styrer hvor mange jobber som kjører samtidig, og `workers-per-job` (4) styrer
parallelle oppslag per jobb.

## 8. Sikkerhet

- Tjenesten binder bare til 127.0.0.1 og eksponeres ikke i nginx.
- API-nøkkelen er påkrevd. Uten nøkkel avvises alle kall (fail closed).
- Jobber er isolert per kunde med `X-Tenant-Ref`.
- **SSRF-vern** (`UrlGuard`):
  - bare offentlige IP-er og bare port 80/443;
  - hver redirect sjekkes på nytt, maks 3;
  - sidestørrelsen er begrenset.
  - *Kjent restrisiko:* DNS kan endre seg mellom sjekk og oppkobling (TOCTOU).
- robots.txt respekteres, og User-Agent identifiserer tjenesten.
- Cachen (`TtlCache`) brukes bare for offentlige data: enhet, regnskap og nettsider. Roller med personnavn
  caches ikke.

## 9. SignalCRM-integrasjon

### Nye og endrede klasser

| Klasse | Rolle |
|---|---|
| `LeadGeneratorClient` | HTTP-klient: submit, get, cancel og `awaitJob` (poller med 700 ms → 3 s). |
| `LeadGeneratorRequestBuilder` | Bygger forespørsler fra Organization, ProductDescription, CustomerProfile, egne kunder (`customerSince`) og tapte deals (stadietype `TYPE_LOST`). Kjører i en kort lesetransaksjon. |
| `CustomerProfileScoreService` | Omskrevet: modusvalg, historikk, skyggekjøring og mapping fra generatorens svar til DTO-en. |
| `LegacyProfileScorer` | Den gamle Gemini- og regelscoringen, flyttet uendret. Slettes når alle kunder er over. |
| `CustomerProfileScoreDto` | Utvidet med `engine`, `priority`, `coverage`, `criteriaVersion`, `jobId`, `warnings` og `analysis` (hele CandidateResult). |
| `LeadAnalysis` / `st_lead_analyses` | Historikk: én rad per kjøring, også skyggekjøringer. |
| `ProspectRun` / `st_prospect_runs` | Prospekteringskjøringer. Resultatene kopieres hit når jobben er ferdig. |
| `ProspectingService` / `ProspectingController` | «Finn nye leads». |
| `Organization.leadgenMode` | LEGACY, SHADOW eller GENERATOR per kunde. |

Tabellene og kolonnen opprettes av `ddl-auto=update`.

### Moduser (overgang per kunde)

| Modus | Brukeren ser | I bakgrunnen |
|---|---|---|
| LEGACY | Gammel score. | – |
| SHADOW | Gammel score. | Generatoren kjører og lagres med `shadow=true`. Loggen viser gammel og ny score side om side. |
| GENERATOR | Generatorens score med faktorer, kilder og salgsvinkel. Avansert leadsøk skjules. | – |

Modusen settes direkte i databasen. Det finnes ingen UI for dette ennå:

```sql
UPDATE st_organizations SET leadgen_mode = 'SHADOW' WHERE coid = '<coid>';
```

Firmaer uten verdi bruker `app.leadgen.default-mode`, som er LEGACY som standard.

### Endepunkter (SignalCRM)

| Metode | Sti | |
|---|---|---|
| POST | `/api/crm/companies/{id}/profile-score` | Som før. Svaret er utvidet. |
| GET | `/api/crm/companies/{id}/profile-score/history` | Nytt. |
| POST | `/api/crm/opportunities/{id}/profile-score` | Som før. |
| GET | `/api/crm/opportunities/{id}/profile-score/history` | Nytt. |
| GET | `/api/crm/prospecting/available` | Om generatoren er satt opp, og kundens modus. |
| GET/POST | `/api/crm/prospecting/runs` | List eller start kjøring. |
| GET/DELETE | `/api/crm/prospecting/runs/{id}` | Status (poller generatoren) eller avbryt. |
| GET | `/api/crm/prospecting/runs/{id}/results` | Resultater med `alreadyInCrm`. |
| POST | `/api/crm/prospecting/runs/{id}/add` | Oppretter valgte bedrifter som leads. Analysen lagres som leadens kundeprofil-score, så den slipper å analyseres på nytt. |

### Transaksjoner

Ingen ventetid på generatoren skjer inne i en transaksjon. Det er samme regel som for alle KI-kall i
SignalCRM (se `GeminiApiClient`). Forespørselen bygges ferdig i en kort lesetransaksjon før kallet.
Skyggekjøringer går i en egen trådpool (maks 2 tråder og 20 i kø). Er køen full, droppes kjøringen.

### UI (index.html)

- `renderProfileScoreResult` viser:
  - prioritet og datadekning;
  - delscorer med kildelenker;
  - kjøpssignaler og hypoteser;
  - salgsvinkel, anbefalte produkter og roller;
  - offentlige roller og kildeliste.
  
  Dette ligger i felles funksjoner (`leadAnalysisDetailHtml`). Bare http(s)-lenker gjøres klikkbare.
- Knappen «Finn nye leads» på Leads-siden vises bare når generatoren er konfigurert. Den åpner en dialog
  med kundeprofil, sted, antall, maks KI-analyser, framdrift, tidligere søk og en resultatliste som kan
  ekspanderes og hakes av.

## 10. Drift

- Filene ligger i `deploy/signalleads.service` og `deploy/signalleads.env.example` (`/etc/signalleads/signalleads.env`, chmod 600).
- SignalCRM trenger `SIGNALLEADS_API_KEY` (samme verdi), og eventuelt `SIGNALLEADS_URL` og `SIGNALLEADS_DEFAULT_MODE`.
- Bygg med `mvn package`, som gir `target/signalleads.jar`.
- Kostnaden styres av budsjettet. Et typisk søk med `count=25` gir maks 25 KI-kall og 50 nettsider.
  Forbruket vises i UI-et.

**Foreslått innføring:**

1. Deploy signalleads med KI.
2. Sett Input Process i SHADOW i 1–2 uker.
3. Sammenlign gammel og ny score i `st_lead_analyses`.
4. Sett modus GENERATOR.
5. Gjør det samme for de andre kundene.
6. Slett `LegacyProfileScorer` og Avansert leadsøk.

## 11. Kjente begrensninger og videre arbeid

- **Nettsider:** Mange enheter i Brreg mangler hjemmeside. Automatisk oppslag av nettside er ikke laget.
- **Næringskodefilter:** Brreg-filteret på næringskode treffer også sekundærkoder, mens scoringen bruker
  hovedkoden. Noen kandidater kan derfor få 0 på bransje.
- **Vekter og terskler:** Disse er SignalCRMs standard (`DEFAULT_WEIGHTS`) og kan ennå ikke redigeres per
  kundeprofil.
- **Omstart:** Jobber overlever ikke en omstart av signalleads. SignalCRM markerer kjøringen som feilet,
  og brukeren starter på nytt.
- **Modus:** Det finnes ingen UI for å sette `leadgenMode`, og ingen rapport som sammenligner skygge og
  gammel score. Foreløpig brukes SQL og logg.
- **Testdekning:** Det finnes enhetstester for ScoringEngine, LeadAnalyzer og UrlGuard, men ingen
  integrasjonstest i SignalCRM. SignalCRM-delen er kompilert, men ikke kjørt.
- **NextBestAction:** Integrasjonen tas senere.
- **UrlGuard:** DNS-TOCTOU kan tettes med en egen DNS-resolver som låser IP-en.
