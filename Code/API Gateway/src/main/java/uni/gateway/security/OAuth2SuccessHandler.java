package uni.gateway.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.CreateOrGetUserRequest;

import java.net.URI;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements ServerAuthenticationSuccessHandler {

    private final UserGrpcClient userGrpcClient;
    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange exchange, Authentication authentication) {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("given_name");
        String surname = oauth2User.getAttribute("family_name");
        String avatarUrl = oauth2User.getAttribute("picture");

        CreateOrGetUserRequest request = CreateOrGetUserRequest.newBuilder()
                .setEmailGoogle(email != null ? email : "")
                .setName(name != null ? name : "")
                .setSurname(surname != null ? surname : "")
                .setAvatarUrl(avatarUrl != null ? avatarUrl : "")
                .build();

        return userGrpcClient.createOrGetUser(request)
                .flatMap(userResponse -> {
                    String userId = userResponse.getId();
                    String accessToken = jwtUtil.generateToken(userId);
                    String refreshToken = UUID.randomUUID().toString();

                    return userGrpcClient.createSession(userId, refreshToken)
                            .thenReturn(new String[]{accessToken, refreshToken}); 
                })
                .flatMap(tokens -> {
                    ServerHttpResponse response = exchange.getExchange().getResponse();

                    response.addCookie(cookieUtil.createAccessTokenCookie(tokens[0])); 
                    response.addCookie(cookieUtil.createRefreshTokenCookie(tokens[1])); 

                    response.setStatusCode(HttpStatus.FOUND);
                    response.getHeaders().setLocation(URI.create("http://localhost:3000/"));
                    return response.setComplete();
                });
    }

}