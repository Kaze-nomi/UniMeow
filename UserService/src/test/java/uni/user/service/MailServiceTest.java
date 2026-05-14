package uni.user.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uni.user.exception.MailDeliveryException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

	@Mock
	JavaMailSender mailSender;

	@InjectMocks
	MailService mailService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(mailService, "fromEmail", "noreply@unimeow.ru");
		ReflectionTestUtils.setField(mailService, "frontendUrl", "https://unimeow.ru");
		when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
	}

	@Test
	void send_verification_code_calls_mail_sender() {
		mailService.sendVerificationCode("student@spbu.ru", "iivanov", "123456");

		verify(mailSender, times(1)).send(any(MimeMessage.class));
	}

	@Test
	void send_verification_code_builds_correct_message() throws Exception {
		ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);

		mailService.sendVerificationCode("student@spbu.ru", "iivanov", "654321");

		verify(mailSender).send(captor.capture());
		MimeMessage msg = captor.getValue();

		assertThat(msg.getAllRecipients()).hasSize(1);
		assertThat(msg.getAllRecipients()[0].toString()).contains("student@spbu.ru");
		assertThat(msg.getFrom()[0].toString()).contains("noreply@unimeow.ru");
		assertThat(msg.getSubject()).contains("email");

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		msg.writeTo(output);
		String html = output.toString(StandardCharsets.UTF_8);
		assertThat(html).contains("654321");
		assertThat(html).contains("iivanov");
		assertThat(html).contains("https://unimeow.ru/favicon.png");
	}

	@Test
	void send_verification_code_wraps_exception_in_mail_delivery_exception() {
		doThrow(new RuntimeException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));

		assertThatThrownBy(() -> mailService.sendVerificationCode("student@spbu.ru", "iivanov", "000000"))
				.isInstanceOf(MailDeliveryException.class).hasMessageContaining("Почтовый сервис временно недоступен")
				.hasMessageContaining("Попробуйте ещё раз").hasCauseInstanceOf(RuntimeException.class);
	}
}
