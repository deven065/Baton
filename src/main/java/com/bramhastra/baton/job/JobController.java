package com.bramhastra.baton.job;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository jobs;
    private final SseHub sse;
    private final CurrentUserProvider currentUser;
    private final AccessPolicy access;

    public JobController(JobRepository jobs, SseHub sse, CurrentUserProvider currentUser, AccessPolicy access) {
        this.jobs = jobs;
        this.sse = sse;
        this.currentUser = currentUser;
        this.access = access;
    }

    @GetMapping("/{jobId}")
    public JobView get(@PathVariable UUID jobId) {
        Job job = visibleJob(jobId);
        return new JobView(job.id(), job.kind(), job.status(), jobs.progressOf(job), job.attempts(), job.lastError());
    }

    /** Live progress. Sends the current state first, then updates, then a final "done" event. */
    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID jobId) {
        Job job = visibleJob(jobId);
        return sse.subscribe(job.id(), jobs.progressOf(job), job.status().isFinished(), job.status().name());
    }

    private Job visibleJob(UUID jobId) {
        CurrentUser me = currentUser.get();
        Job job = jobs.findById(jobId).orElseThrow(ApiException::notFound);
        if (job.projectId() == null) {
            if (!me.id().equals(job.createdBy())) {
                throw ApiException.notFound();
            }
        } else {
            access.requireMember(me, job.projectId());
        }
        return job;
    }

    public record JobView(UUID id, JobKind kind, Job.JobStatus status, JobProgress progress, int attempts,
                          String lastError) {
    }
}
