package uni.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import uni.user.exception.MailDeliveryException;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	private final JavaMailSender mailSender;

	// TODO: Сделать нормальный домен
	@Value("${spring.mail.username}")
	private String fromEmail;

	public void sendVerificationCode(String toEmail, String username, String code) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromEmail);
		message.setTo(toEmail);
		message.setSubject("Подтверждение университетского email — UniMeow");
		message.setText("Привет, " + username + "!\n\n" + "Твой код подтверждения: " + code + "\n\n"
				+ "Код действителен 3 минуты.\n"
				+ "Если ты не запрашивал подтверждение — просто проигнорируй это письмо.");

		try {
			mailSender.send(message);
		} catch (Exception e) {
			throw new MailDeliveryException("Mail service is temporarily unavailable", e);
		}
	}
}
