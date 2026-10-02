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

/**
 * The librarian ledger over the real filter chain.
 *
 * <p>These assertions cannot live in the standalone {@code MockMvc} web test: that setup
 * wires the controller without Spring Security, so a request from a member would reach the
 * handler and pass. Only a full context exercises the authorisation rule that makes this
 * endpoint safe.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibrarianLedgerIT {

    // A distinct member/book pair per test. The datasource is a *named* in-memory H2
    // (jdbc:h2:mem:elibrary with DB_CLOSE_DELAY=-1), so one database is shared by every
    // integration test in the JVM and loans survive across classes. Reusing a pair another
    // test borrows would hit "no two active loans of the same book per member" and fail
    // here for a reason that has nothing to do with this endpoint. These four journals are
    // touched by no other test.
    private static final String ACM = "11111111-1111-1111-1111-111111111112";
    private static final String IEEE_SOFTWARE = "11111111-1111-1111-1111-111111111113";
    private static final String ACM_TSE = "11111111-1111-1111-1111-111111111114";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void borrow(String member, String bookId) throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + bookId + "\"}")
                        .with(httpBasic(member, "password")))
                .andExpect(status().isCreated());
    }

    @Test
    void aMemberIsForbiddenAndGetsTheSameErrorShapeAsEveryOtherFailure() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loans").with(httpBasic("alice", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/loans"));
    }

    @Test
    void anAnonymousCallerIsUnauthenticatedRatherThanForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theLibrarianSeesWhoHoldsEachLoanAcrossMembers() throws Exception {
        borrow("bob", IEEE_SOFTWARE);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/loans")
                        .param("bookId", IEEE_SOFTWARE)
                        .with(httpBasic("librarian", "password")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode items = json(result).get("items");
        assertThat(items).isNotEmpty();
        JsonNode loan = items.get(0);
        assertThat(loan.get("memberId").asText()).isEqualTo("bob");
        assertThat(loan.get("book").get("title").asText()).isEqualTo("IEEE Software");
        assertThat(loan.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(loan.get("dueOn").asText()).isNotBlank();
        assertThat(loan.get("overdue").isBoolean()).isTrue();
    }

    @Test
    void filtersCombineToNarrowTheLedger() throws Exception {
        borrow("carol", ACM_TSE);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/loans")
                        .param("memberId", "carol")
                        .param("bookId", ACM_TSE)
                        .param("status", "active")
                        .with(httpBasic("librarian", "password")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(json(result).get("items")).allSatisfy(loan ->
                assertThat(loan.get("memberId").asText()).isEqualTo("carol"));
    }

    @Test
    void theMemberEndpointStillOnlyEverShowsTheCallersOwnLoans() throws Exception {
        borrow("bob", ACM);

        MvcResult result = mockMvc.perform(get("/api/v1/loans")
                        .param("status", "all")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(json(result).get("items"))
                .as("adding the librarian view must not widen the member view")
                .allSatisfy(loan -> assertThat(loan.has("memberId")).isFalse());
    }
}
