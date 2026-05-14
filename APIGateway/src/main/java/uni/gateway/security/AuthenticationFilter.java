package uni.gateway.security;

import lombok.RequiredArgsConstructor;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthenticationFilter implements WebFilter, Ordered {

	private final JwtUtil jwtUtil;

	// Публичные эндпоинты (GraphQL сам проверяет авторизацию на уровне резолверов)
	private final List<String> openApiEndpoints = List.of("/oauth2/", "/login/", "/graphql", "/actuator/",
			"/api/auth/refresh", "/api/auth/logout");

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		String path = request.getURI().getPath();

		HttpCookie accessCookie = request.getCookies().getFirst("ACCESS_TOKEN");
		boolean hasValidToken = accessCookie != null && jwtUtil.isValid(accessCookie.getValue());

		if (openApiEndpoints.stream().anyMatch(path::startsWith)) {
			if (hasValidToken) {
				String userId = jwtUtil.extractUserId(accessCookie.getValue());
				ServerHttpRequest mutated = request.mutate().header("X-User-Id", userId).build();
				return chain.filter(exchange.mutate().request(mutated).build());
			}
			return chain.filter(exchange);
		}

		if (!hasValidToken) {
			return onError(exchange, HttpStatus.UNAUTHORIZED);
		}

		String userId = jwtUtil.extractUserId(accessCookie.getValue());

		ServerHttpRequest mutatedRequest = exchange.getRequest().mutate().header("X-User-Id", userId).build();

		return chain.filter(exchange.mutate().request(mutatedRequest).build())
				.contextWrite(ctx -> ctx.put("X-User-Id", userId));
	}

	private Mono<Void> onError(ServerWebExchange exchange, HttpStatus httpStatus) {
		String path = exchange.getRequest().getURI().getPath();
		boolean isGraphQl = "/graphql".equals(path);

		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

		String body;
		if (isGraphQl) {
			exchange.getResponse().setStatusCode(httpStatus);
			body = """
					{"errors":[{"message":"Authentication required","extensions":{"code":"UNAUTHORIZED","httpStatus":401}}]}
					"""
					.trim();
		} else {
			exchange.getResponse().setStatusCode(httpStatus);
			body = String.format("{\"error\": \"%s\", \"status\": %d}", httpStatus.getReasonPhrase(),
					httpStatus.value());
		}

		DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
		return exchange.getResponse().writeWith(Mono.just(buffer));
	}

	@Override
	public int getOrder() {
		return -1;
	}
}
