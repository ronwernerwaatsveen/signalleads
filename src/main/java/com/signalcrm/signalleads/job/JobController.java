package com.signalcrm.signalleads.job;

import com.signalcrm.signalleads.api.AnalyzeRequest;
import com.signalcrm.signalleads.api.JobView;
import com.signalcrm.signalleads.api.ProspectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API for SignalCRM. Alle kall er asynkrone: POST starter en jobb og svarer straks med id (202),
 * GET henter status og - når status er DONE - resultatet. X-Tenant-Ref må være den samme som
 * tenantRef i jobben, så en jobb aldri kan hentes på vegne av en annen kunde.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @PostMapping("/analyze")
    public ResponseEntity<JobView> analyze(@Valid @RequestBody AnalyzeRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.submitAnalyze(req));
    }

    @PostMapping("/prospect")
    public ResponseEntity<JobView> prospect(@Valid @RequestBody ProspectRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.submitProspect(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobView> get(@PathVariable String id, @RequestHeader("X-Tenant-Ref") String tenantRef) {
        return service.get(id, tenantRef).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<JobView> cancel(@PathVariable String id, @RequestHeader("X-Tenant-Ref") String tenantRef) {
        return service.cancel(id, tenantRef).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).findFirst().orElse("Ugyldig forespørsel");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> busy(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}
