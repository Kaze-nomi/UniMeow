package uni.gateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.RefreshSessionResponse;

import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerTest {

	@LocalServerPort
	int port;

	@MockitoBean
	UserGrpcClient userGrpcClient;

	WebTestClient client;

	private static final String USER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
	private static final String OLD_REFRESH = "old-refresh-uuid-12345";
	private static final String NEW_REFRESH = "new-refresh-uuid-67890";

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void refresh_returns_200_when_token_is_valid() {
		RefreshSessionResponse response = RefreshSessionResponse.newBuilder().setUserId(USER_ID)
				.setNewRefreshToken(NEW_REFRESH).build();

		when(userGrpcClient.refreshSession(OLD_REFRESH)).thenReturn(Mono.just(response));

		client.post().uri("/api/auth/refresh").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus().isOk();
	}

	@Test
	void refresh_sets_new_access_token_cookie() {
		RefreshSessionResponse response = RefreshSessionResponse.newBuilder().setUserId(USER_ID)
				.setNewRefreshToken(NEW_REFRESH).build();

		when(userGrpcClient.refreshSession(OLD_REFRESH)).thenReturn(Mono.just(response));

		client.post().uri("/api/auth/refresh").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus().isOk()
				.expectHeader().valueMatches("Set-Cookie", ".*ACCESS_TOKEN=.*");
	}

	@Test
	void refresh_sets_new_refresh_token_cookie() {
		RefreshSessionResponse response = RefreshSessionResponse.newBuilder().setUserId(USER_ID)
				.setNewRefreshToken(NEW_REFRESH).build();

		when(userGrpcClient.refreshSession(OLD_REFRESH)).thenReturn(Mono.just(response));

		client.post().uri("/api/auth/refresh").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus().isOk()
				.expectHeader().values("Set-Cookie", values -> {
					org.assertj.core.api.Assertions.assertThat(values).anyMatch(v -> v.startsWith("REFRESH_TOKEN="));
				});
	}

	@Test
	void refresh_returns_401_when_no_cookie() {
		client.post().uri("/api/auth/refresh").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void refresh_returns_401_when_cookie_is_blank() {
		client.post().uri("/api/auth/refresh").cookie("REFRESH_TOKEN", "").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void refresh_returns_401_when_grpc_throws_unauthenticated() {
		when(userGrpcClient.refreshSession(OLD_REFRESH))
				.thenReturn(Mono.error(io.grpc.Status.UNAUTHENTICATED.asRuntimeException()));

		client.post().uri("/api/auth/refresh").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus()
				.isUnauthorized();
	}

	@Test
	void refresh_cookies_are_http_only() {
		RefreshSessionResponse response = RefreshSessionResponse.newBuilder().setUserId(USER_ID)
				.setNewRefreshToken(NEW_REFRESH).build();

		when(userGrpcClient.refreshSession(OLD_REFRESH)).thenReturn(Mono.just(response));

		client.post().uri("/api/auth/refresh").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus().isOk()
				.expectHeader().values("Set-Cookie", values -> {
					org.assertj.core.api.Assertions.assertThat(values).anyMatch(v -> v.startsWith("REFRESH_TOKEN="));
					org.assertj.core.api.Assertions.assertThat(values).anyMatch(v -> v.contains("HTTPOnly"));
				});
	}

	@Test
	void logout_returns_200_when_cookie_present() {
		when(userGrpcClient.revokeRefreshToken(OLD_REFRESH)).thenReturn(Mono.just(true));

		client.post().uri("/api/auth/logout").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus().isOk();
	}

	@Test
	void logout_clears_cookies_with_max_age_zero() {
		when(userGrpcClient.revokeRefreshToken(OLD_REFRESH)).thenReturn(Mono.just(true));

		client.post().uri("/api/auth/logout").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectHeader()
				.valueMatches("Set-Cookie", ".*Max-Age=0.*");
	}

	@Test
	void logout_returns_200_when_no_cookie() {
		client.post().uri("/api/auth/logout").exchange().expectStatus().isOk();
	}

	@Test
	void logout_returns_200_even_if_grpc_fails() {
		when(userGrpcClient.revokeRefreshToken(OLD_REFRESH)).thenReturn(Mono.error(new RuntimeException("grpc error")));

		client.post().uri("/api/auth/logout").cookie("REFRESH_TOKEN", OLD_REFRESH).exchange().expectStatus().isOk();
	}
}
