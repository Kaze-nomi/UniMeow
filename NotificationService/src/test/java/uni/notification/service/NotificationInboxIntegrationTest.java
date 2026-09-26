package uni.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import uni.notification.repository.NotificationRepository;
import uni.notification.repository.ProcessedEventRepository;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Uses a fresh schema in an isolated test PostgreSQL; no production DB/DB
 * drops.
 */
@SpringJUnitConfig(NotificationInboxIntegrationTest.Config.class)
@Tag("integration")
class NotificationInboxIntegrationTest {

	@Autowired
	NotificationEventService service;
	@Autowired
	NotificationRepository notifications;
	@Autowired
	ProcessedEventRepository inbox;
	@Autowired
	PlatformTransactionManager transactions;

	@Configuration
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
	static class Config {
		@Bean
		DataSource dataSource() throws Exception {
			String url = System.getenv("INTEGRATION_POSTGRES_URL");
			String user = System.getenv().getOrDefault("INTEGRATION_POSTGRES_USER", "postgres");
			String password = System.getenv("INTEGRATION_POSTGRES_PASSWORD");
			String schema = "notification_test_" + UUID.randomUUID().toString().replace("-", "");
			try (var connection = DriverManager.getConnection(url, user, password);
					var statement = connection.createStatement()) {
				statement.execute("CREATE SCHEMA " + schema);
			}
			var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
					user, password);
			Flyway.configure().dataSource(ds).defaultSchema(schema).load().migrate();
			return ds;
		}

		@Bean
		LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
			var factory = new LocalContainerEntityManagerFactoryBean();
			factory.setDataSource(dataSource);
			factory.setPackagesToScan("uni.notification.entity");
			factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
			factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.physical_naming_strategy",
					"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
			return factory;
		}

		@Bean
		PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
			return new JpaTransactionManager(factory);
		}

		@Bean
		NotificationEventService notificationEventService(NotificationRepository notifications,
				ProcessedEventRepository inbox) {
			return new NotificationEventService(notifications, inbox, new ObjectMapper());
		}
	}

	private String event(String id, UUID recipient, String actor) {
		return """
				{"eventId":"%s","eventType":"POST_LIKED","payload":{"postId":"test-post","authorId":"%s","actorId":"%s"}}
				"""
				.formatted(id, recipient, actor);
	}

	@Test
	void concurrentSameEventClaimsOnceWithoutUniqueViolationOrDuplicateEffect() throws Exception {
		UUID recipient = UUID.randomUUID();
		String id = UUID.randomUUID().toString();
		String raw = event(id, recipient, UUID.randomUUID().toString());
		runConcurrently(() -> service.processRaw(raw), () -> service.processRaw(raw));
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
		assertThat(inbox.existsByEventId(id)).isTrue();
		service.processRaw(raw); // commit succeeded but the Kafka ack could have been lost
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
	}

	@Test
	void differentEventIdsInSameBusinessGroupMergeUnderConcurrentFirstInsert() throws Exception {
		UUID recipient = UUID.randomUUID();
		String actor = UUID.randomUUID().toString();
		String first = event(UUID.randomUUID().toString(), recipient, actor);
		String second = event(UUID.randomUUID().toString(), recipient, actor);
		runConcurrently(() -> service.processRaw(first), () -> service.processRaw(second));
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
	}

	@Test
	void failedEffectRollsBackClaimAndRetryCanFinish() {
		UUID recipient = UUID.randomUUID();
		String id = UUID.randomUUID().toString();
		assertThatThrownBy(() -> service.processRaw(event(id, recipient, "invalid-uuid")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(inbox.existsByEventId(id)).isFalse();
		service.processRaw(event(id, recipient, UUID.randomUUID().toString()));
		assertThat(inbox.existsByEventId(id)).isTrue();
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
	}

	@Test
	void latePostEventCannotRecreateNoticeForDeletedRecipientOrActor() {
		UUID recipient = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		service.processRaw(event(UUID.randomUUID().toString(), recipient, actor.toString()));
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
		service.processRaw(deletion(recipient));
		service.processRaw(event(UUID.randomUUID().toString(), recipient, actor.toString()));
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isZero();
		UUID anotherRecipient = UUID.randomUUID();
		service.processRaw(deletion(actor));
		service.processRaw(event(UUID.randomUUID().toString(), anotherRecipient, actor.toString()));
		assertThat(notifications.countByUserIdAndIsReadFalse(anotherRecipient)).isZero();
	}

	@Test
	void concurrentRemovalAndOppositeParticipantNoticesCannotDeadlockOrResurrect() throws Exception {
		for (int iteration = 0; iteration < 10; iteration++) {
			UUID first = UUID.randomUUID();
			UUID second = UUID.randomUUID();
			String toFirst = event(UUID.randomUUID().toString(), first, second.toString());
			String toSecond = event(UUID.randomUUID().toString(), second, first.toString());
			runConcurrently(() -> service.processRaw(toFirst), () -> service.processRaw(toSecond));
			runConcurrently(() -> service.processRaw(deletion(first)),
					() -> service.processRaw(event(UUID.randomUUID().toString(), first, second.toString())));
			assertThat(notifications.countByUserIdAndIsReadFalse(first)).isZero();
			assertThat(notifications.countByUserIdAndIsReadFalse(second)).isZero();
		}
	}

	@Test
	void rolledBackDeletionDoesNotLeaveATombstoneOrLoseExistingNotices() {
		UUID recipient = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		service.processRaw(event(UUID.randomUUID().toString(), recipient, actor.toString()));
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			service.processRaw(deletion(recipient));
			status.setRollbackOnly();
		});
		assertThat(notifications.isUserDeleted(recipient)).isFalse();
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
		service.processRaw(event(UUID.randomUUID().toString(), recipient, UUID.randomUUID().toString()));
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(2);
	}

	private String deletion(UUID user) {
		return "{\"eventId\":\"" + UUID.randomUUID()
				+ "\",\"eventType\":\"USER_DELETED\",\"payload\":{\"userId\":\"" + user + "\"}}";
	}

	@Test
	void waitingClaimTakesOverAfterOtherTransactionRollsBack() throws Exception {
		UUID recipient = UUID.randomUUID();
		String id = UUID.randomUUID().toString();
		String raw = event(id, recipient, UUID.randomUUID().toString());
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch competing = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
				assertThat(inbox.claim(id)).isEqualTo(1);
				claimed.countDown();
				await(competing);
				status.setRollbackOnly();
				return null;
			}));
			assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
			var second = executor.submit(() -> {
				competing.countDown();
				service.processRaw(raw);
			});
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);
		}
		assertThat(notifications.countByUserIdAndIsReadFalse(recipient)).isEqualTo(1);
		assertThat(inbox.existsByEventId(id)).isTrue();
	}

	private void runConcurrently(Runnable first, Runnable second) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> {
				await(start);
				first.run();
			});
			var b = executor.submit(() -> {
				await(start);
				second.run();
			});
			start.countDown();
			a.get(20, TimeUnit.SECONDS);
			b.get(20, TimeUnit.SECONDS);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS))
				throw new IllegalStateException("Timed out waiting for competing transaction");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
