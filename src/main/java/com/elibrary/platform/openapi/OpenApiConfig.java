package com.elibrary.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lets a reviewer click through the API before reading any code. */
@Configuration
class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    @Bean
    OpenAPI elibraryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-Library Service")
                        .version("0.1.0")
                        .description("""
                                Browse, borrow, and return digital library content.

                                Authenticate with HTTP Basic. Seeded members: alice, bob, carol \
                                (role MEMBER) and librarian (role LIBRARIAN). The password for all \
                                four is `password`.

                                Errors follow RFC 9457. Every business refusal is 409; branch on the \
                                `code` field rather than the status, because codes can grow without \
                                breaking clients.
                                """))
                .components(new Components().addSecuritySchemes(BASIC_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
