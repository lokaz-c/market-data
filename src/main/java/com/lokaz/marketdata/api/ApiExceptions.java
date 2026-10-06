package com.lokaz.marketdata.api;

/** Exceptions the API turns into problem details (see ApiExceptionHandler). */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 404: unknown ticker, a ticker not visible without a key, or no data in range. */
    public static class NotFound extends RuntimeException {
        public NotFound(String detail) {
            super(detail);
        }
    }

    /** 400 for invalid combinations that single-parameter validation cannot catch (e.g. from after to). */
    public static class BadRequest extends RuntimeException {
        public BadRequest(String detail) {
            super(detail);
        }
    }
}
