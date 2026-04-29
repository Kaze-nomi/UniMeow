package uni.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.entity.EmailVerificationCode;
import uni.user.entity.UniversityDomain;
import uni.user.entity.User;
import uni.user.exception.EmailAlreadyUsedException;
import uni.user.exception.UnknownDomainException;
import uni.user.exception.UserNotFoundException;
import uni.user.repository.EmailVerificationCodeRepository;
import uni.user.repository.UniversityDomainRepository;
import uni.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VerificationService {

	private static final Random random = new Random();

	private static final int MAX_ATTEMPTS = 3;
	private static final int CODE_EXPIRY_MIN = 3;

	private final EmailVerificationCodeRepository verifyRepository;
	private final UniversityDomainRepository domainRepository;
	private final UserRepository userRepository;
	private final MailService mailService;

	@Transactional
	public void sendCode(UUID userId, String universityEmail) {
		String domain = extractDomain(universityEmail);

		domainRepository.findByDomain(domain).orElseThrow(
				() -> new UnknownDomainException("Domain '" + domain + "' is not a registered university domain"));

		userRepository.findAll().stream()
				.filter(u -> universityEmail.equals(u.getEmailUniversity()) && !u.getId().equals(userId)).findAny()
				.ifPresent(u -> {
					throw new EmailAlreadyUsedException("Email already linked to another account");
				});

		User user = userRepository.findById(userId)
				.orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

		String code = String.format("%06d", random.nextInt(1_000_000));

		verifyRepository.save(EmailVerificationCode.builder().userId(userId).email(universityEmail).code(code)
				.attempts(0).expiresAt(LocalDateTime.now().plusMinutes(CODE_EXPIRY_MIN)).build());

		mailService.sendVerificationCode(universityEmail, user.getUsername(), code);
	}

	@Transactional
	public String verify(UUID userId, String inputCode) {
		Optional<EmailVerificationCode> opt = verifyRepository.findById(userId);

		if (opt.isEmpty()) {
			return "EXPIRED";
		}

		EmailVerificationCode record = opt.get();

		if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
			verifyRepository.delete(record);
			return "EXPIRED";
		}

		if (record.getAttempts() >= MAX_ATTEMPTS) {
			return "ATTEMPTS_EXCEEDED";
		}

		if (!record.getCode().equals(inputCode)) {
			record.setAttempts(record.getAttempts() + 1);
			verifyRepository.save(record);
			return "INVALID";
		}

		String domain = extractDomain(record.getEmail());
		UniversityDomain universityDomain = domainRepository.findByDomain(domain)
				.orElseThrow(() -> new UnknownDomainException("Domain disappeared: " + domain));

		User user = userRepository.findById(userId)
				.orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

		user.setEmailUniversity(record.getEmail());
		user.setUniversity(universityDomain.getUniversity());

		switch (universityDomain.getRole()) {
			case STUDENT -> user.setStudentVerified(true);
			case EMPLOYEE -> user.setEmployeeVerified(true);
		}

		userRepository.save(user);
		verifyRepository.delete(record);
		return "SUCCESS";
	}

	private String extractDomain(String email) {
		int atIndex = email.indexOf('@');
		if (atIndex < 0 || atIndex == email.length() - 1) {
			throw new IllegalArgumentException("Invalid email format: " + email);
		}
		return email.substring(atIndex + 1).toLowerCase();
	}

}
