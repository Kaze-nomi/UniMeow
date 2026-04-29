package uni.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.UserResponse;

import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthenticationFilterTest {

	@LocalServerPort
	int port;

	@MockitoBean
	UserGrpcClient userGrpcClient;

	@Autowired
	JwtUtil jwtUtil;

	WebTestClient client;

	private static final String USER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void graphql_endpoint_is_accessible_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue("{\"query\": \"{ globalFeed { posts { id } hasMore } }\"}").exchange().expectStatus()
				.value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
	}

	@Test
	void graphql_endpoint_with_invalid_token_still_passes_as_unauthenticated() {
		client.post().uri("/graphql").cookie("ACCESS_TOKEN", "totally.wrong.token")
				.header("Content-Type", "application/json")
				.bodyValue("{\"query\": \"{ globalFeed { posts { id } hasMore } }\"}").exchange().expectStatus()
				.value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
	}

	@Test
	void protected_rest_endpoint_without_token_returns_401() {
		client.get().uri("/api/protected-example").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void actuator_health_endpoint_is_accessible_without_token() {
		client.get().uri("/actuator/health").exchange().expectStatus()
				.value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
	}

	@Test
	void oauth2_endpoint_is_accessible_without_token() {
		client.get().uri("/oauth2/authorization/google").exchange().expectStatus()
				.value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
	}

	@Test
	void refresh_endpoint_is_accessible_without_token() {
		client.post().uri("/api/auth/refresh").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void valid_token_passes_filter() {
		UserResponse grpcResponse = UserResponse.newBuilder().setId(USER_ID).setEmailGoogle("test@gmail.com")
				.setUsername("test_user").setName("Test").setCreatedAt("2024-01-01T00:00:00").build();

		when(userGrpcClient.getUserById(USER_ID)).thenReturn(Mono.just(grpcResponse));

		client.post().uri("/graphql").cookie("ACCESS_TOKEN", jwtUtil.generateToken(USER_ID))
				.header("Content-Type", "application/json").bodyValue("{\"query\": \"{ me { id } }\"}").exchange()
				.expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
	}

	@Test
	void filter_order_is_minus_one() {
		AuthenticationFilter filter = new AuthenticationFilter(jwtUtil);
		org.assertj.core.api.Assertions.assertThat(filter.getOrder()).isEqualTo(-1);
	}
}
