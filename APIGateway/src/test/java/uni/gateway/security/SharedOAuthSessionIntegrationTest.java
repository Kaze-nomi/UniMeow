package uni.gateway.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import uni.gateway.ApiGatewayApplication;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.RefreshSessionResponse;
import uni.grpc.user.UserResponse;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Run against an isolated real Redis: TEST_REDIS_PORT=16381. No real OAuth provider or user DB. */
@Tag("integration")
class SharedOAuthSessionIntegrationTest {

	private static final String USER = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

	@Test
	void oauth_started_on_a_completes_on_b_and_refresh_logout_work_across_replicas() throws Exception {
		HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		provider.createContext("/token", exchange -> {
			byte[] body = "{\"access_token\":\"fake-provider-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		provider.createContext("/userinfo", exchange -> {
			byte[] body = "{\"email\":\"student@example.test\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		provider.start();
		String namespace = "test:gateway:" + UUID.randomUUID();
		try (var a = gateway(provider.getAddress().getPort(), namespace);
				var b = gateway(provider.getAddress().getPort(), namespace)) {
			HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
			var start = client.send(HttpRequest.newBuilder(URI.create(base(a) + "/oauth2/authorization/google")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(start.statusCode()).isEqualTo(302);
			String session = start.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("SESSION="))
					.findFirst().orElseThrow().split(";", 2)[0];
			String state = UriComponentsBuilder.fromUriString(start.headers().firstValue("location").orElseThrow())
					.build().getQueryParams().getFirst("state");
			var callback = client.send(HttpRequest.newBuilder(URI.create(base(b) + "/login/oauth2/code/google?code=valid&state=" + state))
					.header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(callback.statusCode()).isEqualTo(302);
			assertThat(callback.headers().firstValue("location")).contains("http://localhost:5173/");
			String access = cookie(callback, "ACCESS_TOKEN");
			String refresh = cookie(callback, "REFRESH_TOKEN");
			assertThat(a.getBean(JwtUtil.class).extractUserId(access.split("=", 2)[1])).isEqualTo(USER);
			var refreshed = client.send(HttpRequest.newBuilder(URI.create(base(a) + "/api/auth/refresh"))
					.header("Cookie", refresh).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(refreshed.statusCode()).isEqualTo(200);
			assertThat(cookie(refreshed, "REFRESH_TOKEN")).isEqualTo("REFRESH_TOKEN=rotated-token");
			var logout = client.send(HttpRequest.newBuilder(URI.create(base(b) + "/api/auth/logout"))
					.header("Cookie", "REFRESH_TOKEN=rotated-token; " + session).POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(logout.statusCode()).isEqualTo(200);
			assertThat(logout.headers().allValues("set-cookie")).anyMatch(c -> c.startsWith("ACCESS_TOKEN=") && c.contains("Max-Age=0"));
			var replay = client.send(HttpRequest.newBuilder(URI.create(base(a) + "/login/oauth2/code/google?code=valid&state=" + state))
					.header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(replay.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("ACCESS_TOKEN="));
		} finally {
			provider.stop(0);
		}
	}

	private static String cookie(HttpResponse<?> response, String name) {
		return response.headers().allValues("set-cookie").stream().filter(c -> c.startsWith(name + "="))
				.findFirst().orElseThrow().split(";", 2)[0];
	}

	private static String base(ConfigurableApplicationContext context) {
		return "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
	}

	private static ConfigurableApplicationContext gateway(int providerPort, String namespace) {
		SpringApplication application = new SpringApplication(ApiGatewayApplication.class, Stubs.class);
		application.setAdditionalProfiles("test");
		var properties = new HashMap<String, Object>();
		properties.put("server.port", "0");
		properties.put("spring.autoconfigure.exclude", "net.devh.boot.grpc.client.autoconfigure.GrpcClientMetricAutoConfiguration");
		properties.put("spring.data.redis.host", "127.0.0.1");
		properties.put("spring.data.redis.port", System.getenv("TEST_REDIS_PORT"));
		properties.put("spring.session.redis.namespace", namespace);
		properties.put("spring.security.oauth2.client.provider.google.authorization-uri", "http://127.0.0.1:" + providerPort + "/authorize");
		properties.put("spring.security.oauth2.client.provider.google.token-uri", "http://127.0.0.1:" + providerPort + "/token");
		properties.put("spring.security.oauth2.client.provider.google.user-info-uri", "http://127.0.0.1:" + providerPort + "/userinfo");
		application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
				new org.springframework.core.env.MapPropertySource("integration", properties)));
		return application.run();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Stubs {
		@Bean
		@Primary
		UserGrpcClient oauthUserGrpcClient() {
			var client = mock(UserGrpcClient.class);
			when(client.createOrGetUser(any())).thenReturn(Mono.just(UserResponse.newBuilder().setId(USER).build()));
			when(client.createSession(anyString(), anyString())).thenReturn(Mono.empty());
			when(client.refreshSession(anyString())).thenReturn(Mono.just(RefreshSessionResponse.newBuilder()
					.setUserId(USER).setNewRefreshToken("rotated-token").build()));
			when(client.revokeRefreshToken(anyString())).thenReturn(Mono.just(true));
			return client;
		}
	}
}
