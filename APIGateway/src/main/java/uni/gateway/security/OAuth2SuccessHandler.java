package uni.gateway.security;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.CreateOrGetUserRequest;

import java.net.URI;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements ServerAuthenticationSuccessHandler {

	private static final String PERMANENT_BAN_PREFIX = "Google account is permanently banned";

	private final UserGrpcClient userGrpcClient;
	private final JwtUtil jwtUtil;
	private final CookieUtil cookieUtil;

	@Value("${app.security.oauth2-success-redirect:http://localhost:5173/}")
	private String oauth2SuccessRedirect;

	@Override
	public Mono<Void> onAuthenticationSuccess(WebFilterExchange exchange, Authentication authentication) {
		OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

		String email = oauth2User.getAttribute("email");
		String name = oauth2User.getAttribute("given_name");
		String surname = oauth2User.getAttribute("family_name");

		CreateOrGetUserRequest request = CreateOrGetUserRequest.newBuilder().setEmailGoogle(email != null ? email : "")
				.setName(name != null ? name : "").setSurname(surname != null ? surname : "").build();

		return userGrpcClient.createOrGetUser(request).flatMap(userResponse -> {
			String userId = userResponse.getId();
			String accessToken = jwtUtil.generateToken(userId);
			String refreshToken = UUID.randomUUID().toString();

			return userGrpcClient.createSession(userId, refreshToken)
					.thenReturn(new String[]{accessToken, refreshToken});
		}).flatMap(tokens -> {
			ServerHttpResponse response = exchange.getExchange().getResponse();

			response.addCookie(cookieUtil.createAccessTokenCookie(tokens[0]));
			response.addCookie(cookieUtil.createRefreshTokenCookie(tokens[1]));

			response.setStatusCode(HttpStatus.FOUND);
			response.getHeaders().setLocation(URI.create(oauth2SuccessRedirect));
			return response.setComplete();
		}).onErrorResume(StatusRuntimeException.class, ex -> {
			String description = ex.getStatus().getDescription();
			if (ex.getStatus().getCode() == Status.Code.PERMISSION_DENIED && isPermanentBan(description)) {
				return redirectToPermanentBan(exchange, extractBanReason(description));
			}
			return Mono.error(ex);
		});
	}

	private Mono<Void> redirectToPermanentBan(WebFilterExchange exchange, String reason) {
		ServerHttpResponse response = exchange.getExchange().getResponse();
		response.addCookie(cookieUtil.revokeCookie("ACCESS_TOKEN"));
		response.addCookie(cookieUtil.revokeCookie("REFRESH_TOKEN"));
		response.setStatusCode(HttpStatus.FOUND);

		UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(frontendOrigin()).path("/login")
				.queryParam("banned", "permanent");
		if (reason != null && !reason.isBlank()) {
			redirect.queryParam("reason", reason);
		}

		response.getHeaders().setLocation(redirect.build().encode().toUri());
		return response.setComplete();
	}

	private String frontendOrigin() {
		URI uri = URI.create(oauth2SuccessRedirect);
		if (uri.getScheme() != null && uri.getAuthority() != null) {
			return uri.getScheme() + "://" + uri.getAuthority();
		}
		return oauth2SuccessRedirect;
	}

	private static boolean isPermanentBan(String description) {
		return description != null && description.startsWith(PERMANENT_BAN_PREFIX);
	}

	private static String extractBanReason(String description) {
		if (description == null || !description.startsWith(PERMANENT_BAN_PREFIX)) {
			return "";
		}
		String reason = description.substring(PERMANENT_BAN_PREFIX.length()).trim();
		return reason.startsWith(":") ? reason.substring(1).trim() : reason;
	}

}
