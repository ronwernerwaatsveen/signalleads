package com.signalcrm.signalleads.job;

import com.signalcrm.signalleads.api.AnalyzeRequest;
import com.signalcrm.signalleads.api.Budget;
import com.signalcrm.signalleads.api.CandidateResult;
import com.signalcrm.signalleads.api.CompanyInput;
import com.signalcrm.signalleads.api.JobView;
import com.signalcrm.signalleads.api.ProspectRequest;
import com.signalcrm.signalleads.api.ReferenceSet;
import com.signalcrm.signalleads.api.ScoringConfig;
import com.signalcrm.signalleads.api.SellerContext;
import com.signalcrm.signalleads.api.TargetProfile;
import com.signalcrm.signalleads.brreg.BrregClient;
import com.signalcrm.signalleads.brreg.Enhet;
import com.signalcrm.signalleads.brreg.NaceResolver;
import com.signalcrm.signalleads.brreg.SearchPage;
import com.signalcrm.signalleads.config.UsageTracker;
import com.signalcrm.signalleads.scoring.ReferenceProfile;
import com.signalcrm.signalleads.scoring.ScoringEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Kjører jobbene i bakgrunnen og holder dem i minnet til SignalCRM har hentet resultatet.
 *
 * Prospektering er en trakt, så de dyre stegene bare brukes på de mest lovende:
 *  1. Kandidater fra Brønnøysund (server-side filter på bransje, størrelse, kommune) - gratis.
 *  2. Forhåndsscore på registerdata for alle - gratis.
 *  3. Regnskap og nettside for de beste (budget.maxWebsites).
 *  4. KI-analyse for de beste av dem (budget.maxAiCalls).
 *  5. Endelig score og rangering; offentlige roller hentes bare for dem som returneres.
 *
 * Ingen tilstand overlever en omstart: SignalCRM ser da at jobben ikke finnes og sender den på nytt.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_REFERENCES = 200;
    private static final int DEFAULT_PAGES_PER_SITE = 4;

    private final BrregClient brreg;
    private final NaceResolver nace;
    private final CompanyResearcher researcher;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final int workersPerJob;
    private final Duration keepFinished;
    private final int maxStored;

    public JobService(BrregClient brreg, NaceResolver nace, CompanyResearcher researcher,
                      @Value("${app.jobs.concurrent:2}") int concurrent,
                      @Value("${app.jobs.workers-per-job:4}") int workersPerJob,
                      @Value("${app.jobs.keep-finished-hours:24}") int keepFinishedHours,
                      @Value("${app.jobs.max-stored:500}") int maxStored) {
        this.brreg = brreg;
        this.nace = nace;
        this.researcher = researcher;
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrent));
        this.workersPerJob = Math.max(1, workersPerJob);
        this.keepFinished = Duration.ofHours(keepFinishedHours);
        this.maxStored = maxStored;
    }

    public JobView submitAnalyze(AnalyzeRequest req) {
        Job job = newJob("ANALYZE", req.tenantRef(), req.criteriaVersion());
        job.future = executor.submit(() -> run(job, () -> runAnalyze(job, req)));
        return job.view();
    }

    public JobView submitProspect(ProspectRequest req) {
        Job job = newJob("PROSPECT", req.tenantRef(), req.criteriaVersion());
        job.future = executor.submit(() -> run(job, () -> runProspect(job, req)));
        return job.view();
    }

    /** Henting krever samme tenantRef som jobben ble sendt med - ellers "finnes den ikke". */
    public Optional<JobView> get(String id, String tenantRef) {
        Job job = jobs.get(id);
        if (job == null || tenantRef == null || !job.tenantRef.equals(tenantRef)) return Optional.empty();
        return Optional.of(job.view());
    }

    public Optional<JobView> cancel(String id, String tenantRef) {
        Job job = jobs.get(id);
        if (job == null || tenantRef == null || !job.tenantRef.equals(tenantRef)) return Optional.empty();
        job.cancelled = true;
        Future<?> f = job.future;
        if (f != null && "QUEUED".equals(job.status)) f.cancel(false);
        if (!job.finished() && "QUEUED".equals(job.status)) finish(job, "CANCELLED", null);
        return Optional.of(job.view());
    }

    private Job newJob(String type, String tenantRef, String criteriaVersion) {
        if (jobs.size() >= maxStored) cleanup();
        if (jobs.size() >= maxStored) throw new IllegalStateException("For mange jobber i minnet - prøv igjen senere");
        Job job = new Job(UUID.randomUUID().toString(), type, tenantRef, criteriaVersion);
        jobs.put(job.id, job);
        return job;
    }

    private void run(Job job, Runnable body) {
        if (job.cancelled) return;
        job.status = "RUNNING";
        try {
            body.run();
            finish(job, job.cancelled ? "CANCELLED" : "DONE", null);
        } catch (JobCancelledException e) {
            finish(job, "CANCELLED", null);
        } catch (Exception e) {
            log.error("Jobb {} ({}) feilet", job.id, job.type, e);
            finish(job, "FAILED", e.getMessage());
        }
    }

    private void finish(Job job, String status, String error) {
        job.error = error;
        job.status = status;
        job.finishedAt = Instant.now();
        job.progressText = switch (status) {
            case "DONE" -> "Ferdig";
            case "CANCELLED" -> "Avbrutt";
            default -> "Feilet";
        };
    }

    // ── Enkeltanalyse ───────────────────────────────────────────────

    private void runAnalyze(Job job, AnalyzeRequest req) {
        ResearchContext ctx = context(job, req.seller(), req.profile(), req.scoring(), req.references(),
                req.budget(), req.aiModel());
        CompanyInput in = req.company();
        job.progress(20, "Henter registerdata");
        Candidate c;
        String orgNr = BrregClient.normalize(in.orgNumber());
        Optional<Enhet> enhet = orgNr != null ? brreg.fetchEnhet(orgNr, job.usage) : Optional.empty();
        if (enhet.isPresent()) {
            c = Candidate.of(enhet.get());
            if (in.website() != null && !in.website().isBlank()) c.website = in.website();
        } else {
            if (orgNr != null) job.warnings.add("Fant ikke org.nr. " + orgNr + " i Enhetsregisteret.");
            c = new Candidate();
            c.orgNumber = orgNr;
            c.name = in.name();
            c.website = in.website();
            c.naceDescription = in.industry();
            c.city = in.municipality();
        }
        if (req.context() != null) c.context = req.context();
        checkCancelled(job);
        job.progress(40, "Henter regnskap og nettside");
        researcher.enrich(c, ctx);
        checkCancelled(job);
        int maxAi = budget(req.budget(), Budget::maxAiCalls, 1);
        if (maxAi > 0) {
            job.progress(60, "Analyserer med KI");
            researcher.analyze(c, ctx);
        } else {
            c.aiNote = "KI-budsjettet for jobben er 0.";
        }
        if (c.orgNumber != null) c.roles = brreg.fetchKeyRoles(c.orgNumber, job.usage);
        researcher.score(c, ctx);
        job.results = List.of(researcher.toResult(c));
    }

    // ── Prospektering ───────────────────────────────────────────────

    private void runProspect(Job job, ProspectRequest req) {
        ResearchContext ctx = context(job, req.seller(), req.profile(), req.scoring(), req.references(),
                req.budget(), req.aiModel());
        int count = req.count();
        int maxCandidates = Math.min(2000, budget(req.budget(), Budget::maxCandidates, Math.max(200, count * 5)));
        int maxWebsites = Math.min(500, budget(req.budget(), Budget::maxWebsites, count * 2));
        int maxAi = Math.min(500, budget(req.budget(), Budget::maxAiCalls, count));

        Set<String> exclude = new HashSet<>();
        addAll(exclude, req.excludeOrgNumbers());
        if (req.references() != null) {
            addAll(exclude, req.references().customerOrgNumbers());
            addAll(exclude, req.references().lostOrgNumbers());
        }

        job.progress(10, "Søker i Enhetsregisteret");
        List<Candidate> pool = findCandidates(job, req.profile(), ctx.industry(), exclude, maxCandidates);
        if (pool.isEmpty()) {
            job.warnings.add("Ingen bedrifter i Enhetsregisteret passet søkekriteriene.");
            return;
        }

        job.progress(25, "Forhåndsscorer " + pool.size() + " bedrifter");
        pool.forEach(c -> researcher.score(c, ctx));
        sortByScore(pool);

        List<Candidate> enrich = pool.subList(0, Math.min(maxWebsites, pool.size()));
        AtomicInteger done = new AtomicInteger();
        parallel(job, enrich, c -> {
            researcher.enrich(c, ctx);
            researcher.score(c, ctx);
            job.progress(25 + 35 * done.incrementAndGet() / enrich.size(),
                    "Henter regnskap og nettsider (" + done.get() + "/" + enrich.size() + ")");
        });
        sortByScore(pool);

        List<Candidate> withAi = researcher.aiAvailable() ? pool.subList(0, Math.min(maxAi, pool.size())) : List.of();
        if (!researcher.aiAvailable()) job.warnings.add("KI er ikke slått på - behov og kjøpssignaler er ikke vurdert.");
        done.set(0);
        parallel(job, withAi, c -> {
            researcher.enrich(c, ctx); // i tilfelle KI-budsjettet er større enn nettsidebudsjettet
            researcher.analyze(c, ctx);
            researcher.score(c, ctx);
            job.progress(60 + 35 * done.incrementAndGet() / withAi.size(),
                    "Analyserer med KI (" + done.get() + "/" + withAi.size() + ")");
        });
        sortByScore(pool);

        List<Candidate> top = pool.subList(0, Math.min(count, pool.size()));
        job.progress(96, "Henter offentlige roller");
        parallel(job, top, c -> c.roles = brreg.fetchKeyRoles(c.orgNumber, job.usage));
        List<CandidateResult> results = new ArrayList<>();
        for (Candidate c : top) results.add(researcher.toResult(c));
        job.results = results;
    }

    private List<Candidate> findCandidates(Job job, TargetProfile p, ScoringEngine.IndustryCriteria industry,
                                           Set<String> exclude, int max) {
        List<String> municipalities = new ArrayList<>();
        if (p.locations() != null) {
            for (String loc : p.locations()) {
                if (loc == null || loc.isBlank()) continue;
                String nr = brreg.municipalityNumber(loc);
                if (nr != null) municipalities.add(nr); else job.warnings.add("Ukjent sted: " + loc);
            }
            if (municipalities.isEmpty() && !p.locations().isEmpty()) return List.of();
        }
        if (municipalities.isEmpty()) municipalities.add(null);
        // Bare fritekstbransjer (ingen kode): Brreg kan ikke filtrere, så vi filtrerer lokalt på en større pool.
        boolean localTextFilter = industry.prefixes().isEmpty() && !industry.texts().isEmpty();
        int pageLimit = localTextFilter ? 20 : Integer.MAX_VALUE;

        Map<String, Candidate> out = new LinkedHashMap<>();
        for (String kommune : municipalities) {
            int pagesRead = 0;
            for (int page = 0; out.size() < max && pagesRead < pageLimit; page++, pagesRead++) {
                checkCancelled(job);
                SearchPage sp = brreg.search(industry.prefixes(), p.minEmployees(), p.maxEmployees(), kommune,
                        page, PAGE_SIZE, job.usage);
                for (Enhet e : sp.enheter()) {
                    if (out.size() >= max) break;
                    if (exclude.contains(e.orgNumber()) || e.inactive()) continue;
                    if (localTextFilter && !textMatch(e.naceDescription(), industry.texts())) continue;
                    out.putIfAbsent(e.orgNumber(), Candidate.of(e));
                }
                if (sp.enheter().isEmpty() || page + 1 >= sp.totalPages()) break;
            }
        }
        return new ArrayList<>(out.values());
    }

    // ── Felles ──────────────────────────────────────────────────────

    private ResearchContext context(Job job, SellerContext seller, TargetProfile profile, ScoringConfig scoring,
                                    ReferenceSet refs, Budget budget, String aiModel) {
        ScoringEngine.IndustryCriteria industry = new ScoringEngine.IndustryCriteria(
                nace.resolveAll(profile.industries(), profile.naceCodes()), nace.unresolved(profile.industries()));
        List<String> keywords = new ArrayList<>();
        if (profile.websiteKeywords() != null) keywords.addAll(profile.websiteKeywords());
        if (profile.certifications() != null) {
            for (String c : profile.certifications()) {
                if (c == null || c.isBlank()) continue;
                String l = c.trim().toLowerCase(Locale.ROOT);
                keywords.add(l);
                keywords.add(l.replace(' ', '-'));
            }
        }
        return new ResearchContext(seller, profile, scoring, industry, references(job, refs), keywords,
                budget(budget, Budget::maxPagesPerSite, DEFAULT_PAGES_PER_SITE), aiModel, job.usage);
    }

    private ReferenceProfile references(Job job, ReferenceSet refs) {
        if (refs == null || refs.customerOrgNumbers() == null || refs.customerOrgNumbers().isEmpty()) {
            return ReferenceProfile.empty();
        }
        List<ReferenceProfile.Ref> out = new ArrayList<>();
        for (String orgNr : refs.customerOrgNumbers().stream().limit(MAX_REFERENCES).toList()) {
            brreg.fetchEnhet(orgNr, job.usage).ifPresent(e ->
                    out.add(new ReferenceProfile.Ref(e.orgNumber(), e.naceCode(), e.employees())));
        }
        return new ReferenceProfile(out);
    }

    /** Kjører et steg parallelt for flere bedrifter, med jobbens egen trådpool. */
    private void parallel(Job job, List<Candidate> items, Consumer<Candidate> step) {
        if (items.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(workersPerJob, items.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Candidate c : items) {
                futures.add(pool.submit(() -> {
                    if (job.cancelled) return;
                    try {
                        step.accept(c);
                    } catch (Exception e) {
                        log.warn("Steg feilet for {}: {}", c.name, e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobCancelledException();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
        checkCancelled(job);
    }

    private static void sortByScore(List<Candidate> list) {
        list.sort(Comparator.comparingInt(Candidate::currentScore).reversed()
                .thenComparing(c -> c.score == null ? 0 : -c.score.coverage()));
    }

    private static boolean textMatch(String desc, List<String> texts) {
        if (desc == null) return false;
        String d = desc.toLowerCase(Locale.ROOT);
        for (String t : texts) if (d.contains(t.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void addAll(Set<String> set, List<String> orgNumbers) {
        if (orgNumbers == null) return;
        for (String o : orgNumbers) {
            String n = BrregClient.normalize(o);
            if (n != null) set.add(n);
        }
    }

    private static int budget(Budget b, java.util.function.Function<Budget, Integer> f, int dflt) {
        Integer v = b == null ? null : f.apply(b);
        return v == null ? dflt : Math.max(0, v);
    }

    private static void checkCancelled(Job job) {
        if (job.cancelled || Thread.currentThread().isInterrupted()) throw new JobCancelledException();
    }

    /** Rydder ferdige jobber som er hentet for lenge siden. */
    @Scheduled(fixedDelay = 600_000)
    void cleanup() {
        Instant limit = Instant.now().minus(keepFinished);
        jobs.values().removeIf(j -> j.finished() && j.finishedAt.isBefore(limit));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
