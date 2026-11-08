package com.bramhastra.baton.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, Object details) {

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message, null);
    }
}
