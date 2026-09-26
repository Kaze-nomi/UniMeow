package uni.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import uni.post.outbox.OutboxEventRepository;
import uni.post.outbox.OutboxService;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.IdempotencyKeyRepository;
import uni.post.repository.PostLikeRepository;
import uni.post.repository.PostRepository;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(UserRemovalIntegrationTest.Config.class)
@Tag("integration")
class UserRemovalIntegrationTest {

	@Autowired
	PostService service;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactions;

	@Configuration
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackages = {"uni.post.repository", "uni.post.outbox"})
	static class Config {
		@Bean
		DataSource dataSource() throws Exception {
			String url = System.getenv("INTEGRATION_POSTGRES_URL");
			String user = System.getenv().getOrDefault("INTEGRATION_POSTGRES_USER", "postgres");
			String password = System.getenv("INTEGRATION_POSTGRES_PASSWORD");
			String schema = "post_consumer_test_" + UUID.randomUUID().toString().replace("-", "");
			try (var connection = DriverManager.getConnection(url, user, password);
					var statement = connection.createStatement()) {
				statement.execute("CREATE SCHEMA " + schema);
			}
			var dataSource = new DriverManagerDataSource(
					url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
			Flyway.configure().dataSource(dataSource).defaultSchema(schema).load().migrate();
			return dataSource;
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
			var factory = new LocalContainerEntityManagerFactoryBean();
			factory.setDataSource(dataSource);
			factory.setPackagesToScan("uni.post.entity", "uni.post.outbox");
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
		OutboxService outboxService(OutboxEventRepository repository) {
			return new OutboxService(repository, new ObjectMapper());
		}

		@Bean
		PostService postService(PostRepository posts, CommentRepository comments, PostLikeRepository likes,
				CommentLikeRepository commentLikes, IdempotencyKeyRepository idempotency, OutboxService outbox) {
			return new PostService(posts, comments, likes, commentLikes, idempotency, outbox,
					mock(MentionResolver.class));
		}
	}

	private record Fixture(UUID user, UUID ownPost, UUID otherPost, UUID ownComment) {
	}

	private Fixture fixture() {
		UUID user = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID ownPost = UUID.randomUUID();
		UUID otherPost = UUID.randomUUID();
		UUID comment = UUID.randomUUID();
		jdbc.update("INSERT INTO posts(id, author_id, content, updated_at) VALUES (?, ?, 'test', CURRENT_TIMESTAMP)",
				ownPost, user);
		jdbc.update(
				"INSERT INTO posts(id, author_id, content, likes_count, comments_count, updated_at) VALUES (?, ?, 'test', 1, 2, CURRENT_TIMESTAMP)",
				otherPost, other);
		jdbc.update("INSERT INTO post_likes(post_id, user_id) VALUES (?, ?)", otherPost, user);
		jdbc.update(
				"INSERT INTO comments(id, post_id, author_id, content, updated_at) VALUES (?, ?, ?, 'test', CURRENT_TIMESTAMP)",
				comment, otherPost, user);
		jdbc.update(
				"INSERT INTO comments(id, post_id, author_id, parent_comment_id, content, updated_at) VALUES (?, ?, ?, ?, 'reply', CURRENT_TIMESTAMP)",
				UUID.randomUUID(), otherPost, other, comment);
		return new Fixture(user, ownPost, otherPost, comment);
	}

	@Test
	void duplicateDeletedAndBannedDeliveriesDoNotRepeatCounterChangesOrOutboxEvents() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				await(start);
				service.deleteAllContentByAuthor(fixture.user());
			});
			var second = executor.submit(() -> {
				await(start);
				service.deleteAllContentByAuthor(fixture.user());
			});
			start.countDown();
			first.get(30, TimeUnit.SECONDS);
			second.get(30, TimeUnit.SECONDS);
		}
		verifyRemoved(fixture);
		service.deleteAllContentByAuthor(fixture.user()); // redelivery after commit, before Kafka ack
		verifyRemoved(fixture);
	}

	@Test
	void rollbackRestoresContentCountsAndOutboxBeforeSuccessfulRetry() {
		Fixture fixture = fixture();
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			service.deleteAllContentByAuthor(fixture.user());
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Long.class, fixture.ownPost()))
				.isEqualTo(1);
		assertThat(
				jdbc.queryForObject("SELECT likes_count FROM posts WHERE id = ?", Integer.class, fixture.otherPost()))
				.isEqualTo(1);
		assertThat(eventCount(fixture)).isZero();
		service.deleteAllContentByAuthor(fixture.user());
		verifyRemoved(fixture);
	}

	private void verifyRemoved(Fixture fixture) {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Long.class, fixture.ownPost()))
				.isZero();
		assertThat(
				jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE post_id = ?", Long.class, fixture.otherPost()))
				.isZero();
		assertThat(
				jdbc.queryForObject("SELECT likes_count FROM posts WHERE id = ?", Integer.class, fixture.otherPost()))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT comments_count FROM posts WHERE id = ?", Integer.class,
				fixture.otherPost())).isZero();
		assertThat(eventCount(fixture)).isEqualTo(2);
	}

	private long eventCount(Fixture fixture) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE payload->>'aggregateId' IN (?, ?)",
				Long.class, fixture.ownPost().toString(), fixture.otherPost().toString());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS))
				throw new IllegalStateException("Test start timed out");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
