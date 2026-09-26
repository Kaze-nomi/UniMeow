package uni.post;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * An isolated entry point: no component scan, HTTP/gRPC, consumers, publishers
 * or scheduler.
 */
public final class DatabaseMigration {
	private DatabaseMigration() {
	}

	public static void run(String[] args) {
		SpringApplication application = new SpringApplication(MigrationConfiguration.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setAdditionalProfiles("migrate");
		application.addInitializers(context -> context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("migrationSafety", Map.of("spring.flyway.enabled", "true",
						"spring.flyway.clean-disabled", "true", "spring.main.web-application-type", "none"))));
		try (var context = application.run(args)) {
			// Startup runs Flyway. Requiring the bean prevents a silent success if
			// configuration disables it.
			context.getBean(Flyway.class).validate();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("migrate")
	@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
	static class MigrationConfiguration {
	}
}
