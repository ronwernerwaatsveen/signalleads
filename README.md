# signalleads

AI-basert lead generator for SignalCRM. Analyserer én bedrift eller finner nye bedrifter som passer en
kundeprofil, med delscorer, datadekning og kilde for hver påstand. Tilstandsløs: alt den trenger (profil,
produkter, vekter, fasit) sendes fra SignalCRM i hvert kall.

Se [docs/DESIGN.md](docs/DESIGN.md) for design, API og drift.

## Kjør lokalt

```bash
mvn package
SIGNALLEADS_API_KEY=lokal-test-nokkel java -jar target/signalleads.jar
```

Med KI: sett også `SIGNALLEADS_AI_ENABLED=true` og `SIGNALLEADS_GEMINI_API_KEY`.
SignalCRM kobles til med `SIGNALLEADS_API_KEY` (samme verdi) - se `app.leadgen.*` der.
