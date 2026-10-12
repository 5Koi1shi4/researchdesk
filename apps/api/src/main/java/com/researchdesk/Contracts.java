package com.researchdesk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Canonical DTO names and JSON fields shared with contracts/openapi.json. */
public final class Contracts {
    private Contracts() {}

    public enum TaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum RunState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
    public enum RunKind { ANSWER, REPORT }
    public enum Availability { AVAILABLE, DELETED }
    public enum EventType {
        @JsonProperty("started") STARTED,
        @JsonProperty("delta") DELTA,
        @JsonProperty("citation") CITATION,
        @JsonProperty("completed") COMPLETED,
        @JsonProperty("failed") FAILED,
        @JsonProperty("cancelled") CANCELLED
    }
    public record ApiError(String code, String message, String requestId) {}
    public record Citation(String chunkId, String documentVersionId, String title,
                           Integer page, Integer paragraph, String excerpt, Availability availability) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationRequest(RunKind kind, String projectId, String conversationId,
                                    List<String> documentVersionIds, String prompt) {}
    public record RunEvent(String runId, long seq, EventType type, Map<String, Object> payload) {}
    public record InternalTask(String taskId, String attemptId, long fence, String projectId,
                               String documentVersionId, String objectKey, String parserVersion) {}
    public record StartedPayload(RunKind kind) {}
    public record DeltaPayload(String text) {}
    public record CitationPayload(Citation citation) {}
    public record CompletedPayload(RunState state) {}
    public record FailedPayload(ApiError error) {}
    public record CancelledPayload(RunState state) {}
}
