package uni.gateway.security;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final OAuth2SuccessHandler successHandler;

	@Value("${app.security.allowed-origins:http://localhost:*,null}")
	private List<String> allowedOrigins;

	@Bean
	public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
		return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.authorizeExchange(auth -> auth.anyExchange().permitAll())
				.oauth2Login(oauth2 -> oauth2.authenticationSuccessHandler(successHandler)).build();
	}

	@Bean
	public WebSessionIdResolver webSessionIdResolver(@Value("${app.security.secure-cookie:false}") boolean secure) {
		CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
		resolver.addCookieInitializer(cookie -> cookie.path("/").httpOnly(true).secure(secure).sameSite("Lax"));
		return resolver;
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOriginPatterns(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
