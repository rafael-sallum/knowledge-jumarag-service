package com.jumatech.jumarag;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class JumaRagApplication {

    public static void main(String[] args) {
        String runtime = runtime(args);
        SpringApplication application = new SpringApplication(JumaRagApplication.class);
        if ("migrate".equals(runtime)) {
            application.setWebApplicationType(WebApplicationType.NONE);
            try (ConfigurableApplicationContext ignored = application.run(args)) {
                // Flyway runs during context initialization; closing makes this a one-shot process.
            }
            return;
        }
        application.run(args);
    }

    private static String runtime(String[] args) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith("--app.runtime="))
                .map(argument -> argument.substring("--app.runtime=".length()))
                .findFirst()
                .orElseGet(() -> System.getenv().getOrDefault("APP_RUNTIME", "api"));
    }
}
