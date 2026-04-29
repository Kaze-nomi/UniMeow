package uni.gateway.security;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class GraphQlAuthInterceptor implements WebGraphQlInterceptor {

	@Override
	public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
		String userId = request.getHeaders().getFirst("X-User-Id");

		if (userId != null) {
			request.configureExecutionInput(
					(input, builder) -> builder.graphQLContext(ctx -> ctx.put("userId", userId)).build());
		}

		return chain.next(request);
	}
}
