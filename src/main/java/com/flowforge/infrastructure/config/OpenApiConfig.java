package com.flowforge.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata: bearer authentication plus the tenant / idempotency headers used across the API. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flowForgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FlowForge API")
                        .version("v1")
                        .description("Enterprise Workflow & Automation Platform. Send X-Tenant-ID on auth calls "
                                + "and X-Idempotency-Key on state-changing POSTs."))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
