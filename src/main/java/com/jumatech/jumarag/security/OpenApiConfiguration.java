package com.jumatech.jumarag.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    public static final String ADMIN_SCHEME = "AdminBearer";
    public static final String CLIENT_SCHEME = "ClientApiKey";

    @Bean
    OpenAPI jumaRagOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JumaRAG grounded knowledge API")
                        .version("v1")
                        .description("Snapshot-reproducible evidence retrieval and grounded answers through provider-neutral gateways."))
                .components(new Components()
                        .addSecuritySchemes(
                                ADMIN_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("opaque admin service token"))
                        .addSecuritySchemes(
                                CLIENT_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(ServiceAuthenticationFilter.CLIENT_API_KEY_HEADER)));
    }
}
