package com.campuscoin.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger configuration.
 *
 * <p>Declares the Bearer scheme so the Swagger UI "Authorize" button can attach a real token to
 * protected calls, which is what makes manual verification of UC-02 and UC-05 possible straight
 * from the browser.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI campusCoinOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Campus Coin API")
                        .version("v1")
                        .description("""
                                REST API for Campus Coin, a student personal finance and expense
                                tracking application.

                                The relational schema is defined by db/merged/campuscoin_full.sql and
                                is the single source of truth; this service never creates or alters
                                it. Business rules that the database already enforces - ownership,
                                account status, reset-token lifecycle, budget thresholds - are not
                                duplicated here.

                                Authentication uses a signed JWT sent as
                                `Authorization: Bearer <token>`. Obtain one from
                                POST /api/v1/auth/login (students) or
                                POST /api/v1/admin/auth/login (administrators), then use the
                                Authorize button above.
                                """))
                .addServersItem(new Server().url("/").description("Current host"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token issued by the sign-in endpoints.")))
                // Applied globally so every operation shows the lock icon; the public endpoints
                // override it with @SecurityRequirements in their controllers.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
