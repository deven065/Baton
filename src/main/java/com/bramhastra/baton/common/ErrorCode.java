package com.bramhastra.baton.common;

import org.springframework.http.HttpStatus;

/** Error codes from LLD L9. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    ATTESTATION_REQUIRED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
    SHARE_INACTIVE(HttpStatus.FORBIDDEN),
    /** Missing OR not visible. Never reveal that a hidden resource exists. */
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CLAIM_STALE(HttpStatus.CONFLICT),
    APPROVAL_BLOCKED(HttpStatus.CONFLICT),
    INVALID_TRANSITION(HttpStatus.CONFLICT),
    RUN_IN_PROGRESS(HttpStatus.CONFLICT),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    /** Workspace sign-up: the name is in use. */
    WORKSPACE_TAKEN(HttpStatus.CONFLICT),
    /** Admin: an email, group or project name is already used in this workspace. */
    ALREADY_EXISTS(HttpStatus.CONFLICT),
    /** Admin: a change that would lock the workspace out (last admin, own account) or orphan data (group in use). */
    NOT_ALLOWED(HttpStatus.CONFLICT),
    /** Workspace sign-up: Baton could not connect to the company's database. */
    DATABASE_UNREACHABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** Workspace sign-up: the database is reachable but not usable (not empty, no pgvector, too old, private host). */
    DATABASE_UNSUITABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
