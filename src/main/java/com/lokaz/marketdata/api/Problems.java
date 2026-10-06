package com.lokaz.marketdata.api;

import java.io.IOException;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Writes RFC 9457 problem details from servlet filters, which run before Spring MVC's exception handling. */
@Component
public class Problems {

    private final JsonMapper json;

    public Problems(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        var body = new LinkedHashMap<String, Object>();
        // No "type" member: RFC 9457 treats an absent type as "about:blank", same as Spring MVC's output.
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
