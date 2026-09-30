package com.elibrary.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LendingFlowIT {

    private static final String DDD = "11111111-1111-1111-1111-111111111101";
    private static final String ACCELERATE = "11111111-1111-1111-1111-111111111110";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int availableCopiesOf(String bookId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/books/{id}", bookId).with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("availableCopies").asInt();
    }

    @Test
    void browseBorrowViewAndReturnAcrossTheWholeStack() throws Exception {
        int copiesBefore = availableCopiesOf(DDD);

        // browse, filtered and paged
        mockMvc.perform(get("/api/v1/books")
                        .param("q", "domain-driven")
                        .param("available", "true")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").exists())
                .andExpect(jsonPath("$.totalElements").exists());

        // borrow
        MvcResult borrowed = mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String loanId = json(borrowed).get("id").asText();
        String location = borrowed.getResponse().getHeader("Location");

        assertThat(location).isEqualTo("/api/v1/loans/" + loanId);
        assertThat(availableCopiesOf(DDD)).isEqualTo(copiesBefore - 1);

        // the Location header points at something retrievable
        mockMvc.perform(get(location).with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(loanId));

        // currently borrowed books
        mockMvc.perform(get("/api/v1/loans").with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + loanId + "')]").exists())
                .andExpect(jsonPath("$.items[0].book.title").value("Domain-Driven Design"));

        // return
        mockMvc.perform(post("/api/v1/loans/{id}/return", loanId).with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnedAt").exists());

        assertThat(availableCopiesOf(DDD)).isEqualTo(copiesBefore);

        // no longer in the active list
        mockMvc.perform(get("/api/v1/loans").with(httpBasic("alice", "password")))
                .andExpect(jsonPath("$.items[?(@.id == '" + loanId + "')]").doesNotExist());

        // but still visible in history
        mockMvc.perform(get("/api/v1/loans").param("status", "returned").with(httpBasic("alice", "password")))
                .andExpect(jsonPath("$.items[?(@.id == '" + loanId + "')]").exists());
    }

    @Test
    void borrowingAFullyBorrowedBookIsRefusedWithoutChangingAnything() throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + ACCELERATE + "\"}")
                        .with(httpBasic("bob", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_COPIES_AVAILABLE"));

        assertThat(availableCopiesOf(ACCELERATE)).isZero();
    }

    @Test
    void borrowingTheSameBookTwiceIsRefusedAndTheFirstLoanSurvives() throws Exception {
        String book = "11111111-1111-1111-1111-111111111103";

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + book + "\"}")
                        .with(httpBasic("carol", "password")))
                .andExpect(status().isCreated());

        int afterFirst = availableCopiesOf(book);

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + book + "\"}")
                        .with(httpBasic("carol", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BORROWED"));

        assertThat(availableCopiesOf(book))
                .as("a refused borrow must not consume a copy")
                .isEqualTo(afterFirst);
    }

    @Test
    void oneMemberCannotSeeOrReturnAnotherMembersLoan() throws Exception {
        MvcResult borrowed = mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"11111111-1111-1111-1111-111111111106\"}")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isCreated())
                .andReturn();
        String aliceLoan = json(borrowed).get("id").asText();

        mockMvc.perform(get("/api/v1/loans/{id}", aliceLoan).with(httpBasic("bob", "password")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/loans/{id}/return", aliceLoan).with(httpBasic("bob", "password")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/loans").with(httpBasic("bob", "password")))
                .andExpect(jsonPath("$.items[?(@.id == '" + aliceLoan + "')]").doesNotExist());
    }

    @Test
    void everyLoanEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/loans")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
