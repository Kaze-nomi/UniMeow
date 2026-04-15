package uni.gateway.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.UserGrpcClient;
import uni.gateway.security.CookieUtil;
import uni.gateway.security.JwtUtil;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserGrpcClient userGrpcClient;
    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;

    @PostMapping("/refresh")
    public Mono<ResponseEntity<Void>> refresh(ServerWebExchange exchange) {
        
        HttpCookie refreshCookie = exchange.getRequest().getCookies().getFirst("REFRESH_TOKEN");

        if (refreshCookie == null || refreshCookie.getValue().isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        String oldRefreshToken = refreshCookie.getValue();

        return userGrpcClient.refreshSession(oldRefreshToken)
                .flatMap(response -> {
                    String newAccessToken = jwtUtil.generateToken(response.getUserId());
                    String newRefreshToken = response.getNewRefreshToken();

                    exchange.getResponse().addCookie(cookieUtil.createAccessTokenCookie(newAccessToken));
                    exchange.getResponse().addCookie(cookieUtil.createRefreshTokenCookie(newRefreshToken));

                    return Mono.just(ResponseEntity.ok().<Void>build());
                })
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        HttpCookie refreshCookie = exchange.getRequest().getCookies().getFirst("REFRESH_TOKEN");

        Mono<Boolean> revokeMono = (refreshCookie != null && !refreshCookie.getValue().isBlank())
                ? userGrpcClient.revokeRefreshToken(refreshCookie.getValue())
                : Mono.just(false);

        return revokeMono
                .onErrorReturn(false)
                .doOnNext(result -> {
                    exchange.getResponse().addCookie(cookieUtil.revokeCookie("ACCESS_TOKEN"));
                    exchange.getResponse().addCookie(cookieUtil.revokeCookie("REFRESH_TOKEN"));
                })
                .thenReturn(ResponseEntity.ok().<Void>build());
    }
}