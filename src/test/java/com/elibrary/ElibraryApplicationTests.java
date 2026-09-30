package com.elibrary;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ElibraryApplicationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoadsAndFlywayMigratesTheSeededCatalogue() {
        Integer books = jdbc.queryForObject("select count(*) from books", Integer.class);
        assertThat(books).isEqualTo(15);
    }

    @Test
    void seedContainsBothAvailableAndFullyBorrowedItems() {
        Integer unavailable = jdbc.queryForObject(
                "select count(*) from books where available_copies = 0", Integer.class);
        assertThat(unavailable).isEqualTo(2);
    }
}
