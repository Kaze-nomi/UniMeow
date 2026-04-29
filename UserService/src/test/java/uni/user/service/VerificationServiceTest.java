package uni.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.user.entity.EmailVerificationCode;
import uni.user.entity.University;
import uni.user.entity.UniversityDomain;
import uni.user.entity.User;
import uni.user.exception.EmailAlreadyUsedException;
import uni.user.exception.UnknownDomainException;
import uni.user.exception.UserNotFoundException;
import uni.user.repository.EmailVerificationCodeRepository;
import uni.user.repository.UniversityDomainRepository;
import uni.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

	@Mock
	EmailVerificationCodeRepository verifyRepository;

	@Mock
	UniversityDomainRepository domainRepository;

	@Mock
	UserRepository userRepository;

	@Mock
	MailService mailService;

	@InjectMocks
	VerificationService verificationService;

	private static final UUID USER_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

	private User buildUser() {
		return User.builder().id(USER_ID).emailGoogle("ivan@gmail.com").username("ivan_petrov").name("Иван")
				.isStudentVerified(false).isEmployeeVerified(false).createdAt(LocalDateTime.now()).build();
	}

	private UniversityDomain studentDomain() {
		return new UniversityDomain(1L, "student.mgu.ru", UniversityDomain.DomainRole.STUDENT, university());
	}

	private UniversityDomain employeeDomain() {
		return new UniversityDomain(2L, "mgu.ru", UniversityDomain.DomainRole.EMPLOYEE, university());
	}

	private University university() {
		return University.builder().id(1L).name("Moscow State University").shortName("MSU")
				.createdAt(LocalDateTime.now()).build();
	}

	@Test
	void send_code_saves_verification_record() {
		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findAll()).thenReturn(Collections.emptyList());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
		when(verifyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		verificationService.sendCode(USER_ID, "ivan@student.mgu.ru");

		verify(verifyRepository).save(any(EmailVerificationCode.class));
	}

	@Test
	void send_code_calls_mail_service() {
		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findAll()).thenReturn(Collections.emptyList());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
		when(verifyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		verificationService.sendCode(USER_ID, "ivan@student.mgu.ru");

		verify(mailService).sendVerificationCode(eq("ivan@student.mgu.ru"), eq("ivan_petrov"), any(String.class));
	}

	@Test
	void send_code_throws_when_domain_is_unknown() {
		when(domainRepository.findByDomain("gmail.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> verificationService.sendCode(USER_ID, "ivan@gmail.com"))
				.isInstanceOf(UnknownDomainException.class).hasMessageContaining("gmail.com");
	}

	@Test
	void send_code_throws_when_email_already_used_by_another_user() {
		UUID otherUserId = UUID.randomUUID();
		User otherUser = User.builder().id(otherUserId).emailGoogle("other@gmail.com")
				.emailUniversity("ivan@student.mgu.ru").name("Other").createdAt(LocalDateTime.now()).build();

		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findAll()).thenReturn(List.of(otherUser));

		assertThatThrownBy(() -> verificationService.sendCode(USER_ID, "ivan@student.mgu.ru"))
				.isInstanceOf(EmailAlreadyUsedException.class);
	}

	@Test
	void send_code_throws_when_user_not_found() {
		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findAll()).thenReturn(Collections.emptyList());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> verificationService.sendCode(USER_ID, "ivan@student.mgu.ru"))
				.isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void verify_returns_success_and_sets_student_verified() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(0).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		User user = buildUser();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));
		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String result = verificationService.verify(USER_ID, "123456");

		assertThat(result).isEqualTo("SUCCESS");
		assertThat(user.isStudentVerified()).isTrue();
		assertThat(user.getUniversity()).isNotNull();
		assertThat(user.getUniversity().getId()).isEqualTo(1L);
	}

	@Test
	void verify_returns_success_and_sets_employee_verified() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@mgu.ru")
				.code("999999").attempts(0).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		User user = buildUser();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));
		when(domainRepository.findByDomain("mgu.ru")).thenReturn(Optional.of(employeeDomain()));
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String result = verificationService.verify(USER_ID, "999999");

		assertThat(result).isEqualTo("SUCCESS");
		assertThat(user.isEmployeeVerified()).isTrue();
		assertThat(user.isStudentVerified()).isFalse();
	}

	@Test
	void verify_returns_expired_when_no_record_found() {
		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.empty());

		String result = verificationService.verify(USER_ID, "123456");

		assertThat(result).isEqualTo("EXPIRED");
	}

	@Test
	void verify_returns_expired_and_deletes_when_code_is_old() {
		EmailVerificationCode expired = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(0).expiresAt(LocalDateTime.now().minusMinutes(1)).build();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(expired));

		String result = verificationService.verify(USER_ID, "123456");

		assertThat(result).isEqualTo("EXPIRED");
		verify(verifyRepository).delete(expired);
	}

	@Test
	void verify_returns_invalid_when_code_is_wrong() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(0).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));

		String result = verificationService.verify(USER_ID, "000000");

		assertThat(result).isEqualTo("INVALID");
	}

	@Test
	void verify_increments_attempts_on_wrong_code() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(1).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));

		verificationService.verify(USER_ID, "wrong");

		assertThat(record.getAttempts()).isEqualTo(2);
		verify(verifyRepository).save(record);
	}

	@Test
	void verify_returns_attempts_exceeded_when_limit_reached() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(3).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));

		String result = verificationService.verify(USER_ID, "999999");

		assertThat(result).isEqualTo("ATTEMPTS_EXCEEDED");
	}

	@Test
	void verify_deletes_code_after_success() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(0).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));
		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		verificationService.verify(USER_ID, "123456");

		verify(verifyRepository).delete(record);
	}

	@Test
	void verify_sets_university_email_on_user_after_success() {
		EmailVerificationCode record = EmailVerificationCode.builder().userId(USER_ID).email("ivan@student.mgu.ru")
				.code("123456").attempts(0).expiresAt(LocalDateTime.now().plusMinutes(10)).build();

		User user = buildUser();

		when(verifyRepository.findById(USER_ID)).thenReturn(Optional.of(record));
		when(domainRepository.findByDomain("student.mgu.ru")).thenReturn(Optional.of(studentDomain()));
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		verificationService.verify(USER_ID, "123456");

		assertThat(user.getEmailUniversity()).isEqualTo("ivan@student.mgu.ru");
	}
}
