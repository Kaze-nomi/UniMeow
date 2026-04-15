package uni.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uni.user.exception.MailDeliveryException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    JavaMailSender mailSender;

    @InjectMocks
    MailService mailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mailService, "fromEmail", "noreply@unimiow.ru");
    }

    @Test
    void send_verification_code_calls_mail_sender() {
        mailService.sendVerificationCode("student@spbu.ru", "iivanov", "123456");

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_verification_code_builds_correct_message() {
        ArgumentCaptor<SimpleMailMessage> captor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);

        mailService.sendVerificationCode("student@spbu.ru", "iivanov", "654321");

        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();

        assertThat(msg.getTo()).containsExactly("student@spbu.ru");
        assertThat(msg.getFrom()).isEqualTo("noreply@unimiow.ru");
        assertThat(msg.getSubject()).contains("UniMeow");
        assertThat(msg.getText()).contains("654321");
        assertThat(msg.getText()).contains("iivanov");
    }

    @Test
    void send_verification_code_wraps_exception_in_mail_delivery_exception() {
        doThrow(new RuntimeException("SMTP unavailable"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() ->
                mailService.sendVerificationCode("student@spbu.ru", "iivanov", "000000"))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("unavailable")
                .hasCauseInstanceOf(RuntimeException.class);
    }
}