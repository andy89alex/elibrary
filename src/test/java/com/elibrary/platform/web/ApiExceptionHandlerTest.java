package com.elibrary.platform.web;

import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.shared.BookId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    /** Throws on demand so the advice can be exercised without the real controllers. */
    @RestController
    static class BoomController {

        @GetMapping("/boom/not-found")
        String notFound() {
            throw new LoanNotFound(LoanId.of("22222222-2222-2222-2222-222222222201"));
        }

        @GetMapping("/boom/conflict")
        String conflict() {
            throw new BookUnavailable(BookId.of("11111111-1111-1111-1111-111111111110"));
        }

        @GetMapping("/boom/bad-id")
        String badId(@RequestParam String id) {
            return BookId.of(id).toString();
        }

        @GetMapping("/boom/unexpected")
        String unexpected() {
            throw new IllegalStateException("a database cable came loose and the password is hunter2");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void notFoundBecomes404WithAStableCodeAndATypeUri() throws Exception {
        mockMvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Loan not found"))
                .andExpect(jsonPath("$.type").value("https://elibrary.example/problems/loan-not-found"))
                .andExpect(jsonPath("$.instance").value("/boom/not-found"));
    }

    @Test
    void businessRefusalsBecome409AndAreDistinguishedByCodeNotByStatus() throws Exception {
        mockMvc.perform(get("/boom/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_COPIES_AVAILABLE"))
                .andExpect(jsonPath("$.type").value("https://elibrary.example/problems/no-copies-available"));
    }

    @Test
    void aMalformedIdentifierBecomes400RatherThan500() throws Exception {
        mockMvc.perform(get("/boom/bad-id").param("id", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not-a-uuid")));
    }

    @Test
    void anUnexpectedFailureBecomes500AndLeaksNothingFromTheMessage() throws Exception {
        mockMvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("hunter2"))));
    }
}
