package uni.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

/** Spring Session stores the WebSession (including the OAuth request/state) in Redis. */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {

	@Bean
	public WebSessionIdResolver webSessionIdResolver(@Value("${app.security.secure-cookie:false}") boolean secure) {
		CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
		resolver.setCookieName("SESSION");
		// Lax allows the OAuth provider's top-level GET callback to carry the session.
		resolver.addCookieInitializer(cookie -> cookie.path("/").httpOnly(true).secure(secure).sameSite("Lax"));
		return resolver;
	}
}
