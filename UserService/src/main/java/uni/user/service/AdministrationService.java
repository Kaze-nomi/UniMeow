package uni.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.entity.FacultyProposal;
import uni.user.entity.ImprovementSuggestion;
import uni.user.entity.ProgramProposal;
import uni.user.entity.University;
import uni.user.entity.UniversityDomain;
import uni.user.entity.UniversityFaculty;
import uni.user.entity.UniversityProgram;
import uni.user.entity.UniversityProposal;
import uni.user.entity.User;
import uni.user.repository.FacultyProposalRepository;
import uni.user.repository.ImprovementSuggestionRepository;
import uni.user.repository.ProgramProposalRepository;
import uni.user.repository.UniversityDomainRepository;
import uni.user.repository.UniversityFacultyRepository;
import uni.user.repository.UniversityProgramRepository;
import uni.user.repository.UniversityProposalRepository;
import uni.user.repository.UniversityRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdministrationService {

	private final ImprovementSuggestionRepository suggestionRepository;
	private final UniversityProposalRepository universityProposalRepository;
	private final FacultyProposalRepository facultyProposalRepository;
	private final ProgramProposalRepository programProposalRepository;
	private final UniversityRepository universityRepository;
	private final UniversityDomainRepository universityDomainRepository;
	private final UniversityFacultyRepository universityFacultyRepository;
	private final UniversityProgramRepository universityProgramRepository;
	private final UserService userService;

	@Transactional
	public void createSuggestion(UUID authorId, String text) {
		ensureActiveUser(authorId);
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("Suggestion text cannot be empty");
		}
		suggestionRepository.save(ImprovementSuggestion.builder().authorId(authorId).text(text.trim()).status("NEW")
				.createdAt(LocalDateTime.now()).build());
		log.info("Improvement suggestion created by user {}", authorId);
	}

	@Transactional(readOnly = true)
	public List<ImprovementSuggestion> listSuggestions(UUID adminId) {
		ensureAdmin(adminId);
		return suggestionRepository.findAll().stream()
				.sorted(Comparator.comparing(ImprovementSuggestion::getCreatedAt).reversed()).toList();
	}

	@Transactional
	public void deleteSuggestion(UUID adminId, long id) {
		ensureAdmin(adminId);
		if (!suggestionRepository.existsById(id)) {
			throw new IllegalArgumentException("Suggestion not found: " + id);
		}
		suggestionRepository.deleteById(id);
	}

	@Transactional
	public void createUniversityProposal(UUID authorId, String name, String shortName, String subdomain,
			String studentDomain, String employeeDomain, String city, String description, String iconUrl) {
		ensureActiveUser(authorId);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("University name cannot be empty");
		}
		if (shortName == null || shortName.isBlank()) {
			throw new IllegalArgumentException("University short name cannot be empty");
		}
		if (subdomain == null || subdomain.isBlank()) {
			throw new IllegalArgumentException("Subdomain cannot be empty");
		}
		if (!subdomain.trim().matches("[a-z0-9][a-z0-9\\-]{0,48}[a-z0-9]|[a-z0-9]")) {
			throw new IllegalArgumentException("Subdomain must contain only lowercase letters, digits and hyphens");
		}
		if (studentDomain == null || studentDomain.isBlank()) {
			throw new IllegalArgumentException("Student university domain cannot be empty");
		}
		if (employeeDomain == null || employeeDomain.isBlank()) {
			throw new IllegalArgumentException("Employee university domain cannot be empty");
		}
		universityProposalRepository.save(UniversityProposal.builder().authorId(authorId).name(name.trim())
				.shortName(shortName.trim()).subdomain(subdomain.trim().toLowerCase())
				.studentDomain(studentDomain.trim().toLowerCase()).employeeDomain(employeeDomain.trim().toLowerCase())
				.city(blankToNull(city)).description(blankToNull(description)).iconUrl(blankToNull(iconUrl))
				.status("NEW").createdAt(LocalDateTime.now()).build());
		log.info("University proposal '{}' submitted by user {}", name.trim(), authorId);
	}

	@Transactional(readOnly = true)
	public List<UniversityProposal> listUniversityProposals(UUID adminId) {
		ensureAdmin(adminId);
		return universityProposalRepository.findAll().stream()
				.sorted(Comparator.comparing(UniversityProposal::getCreatedAt).reversed()).toList();
	}

	@Transactional
	public void createFacultyProposal(UUID authorId, long universityId, String name, String shortName) {
		ensureActiveUser(authorId);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Faculty name cannot be empty");
		}
		if (shortName == null || shortName.isBlank()) {
			throw new IllegalArgumentException("Faculty short name cannot be empty");
		}
		University university = universityRepository.findById(universityId)
				.orElseThrow(() -> new IllegalArgumentException("University not found: " + universityId));
		facultyProposalRepository.save(FacultyProposal.builder().authorId(authorId).university(university)
				.name(name.trim()).shortName(shortName.trim()).status("NEW").createdAt(LocalDateTime.now()).build());
		log.info("Faculty proposal '{}' submitted for university {} by user {}", shortName.trim(), universityId,
				authorId);
	}

	@Transactional(readOnly = true)
	public List<FacultyProposal> listFacultyProposals(UUID adminId) {
		ensureAdmin(adminId);
		return facultyProposalRepository.findAll().stream()
				.sorted(Comparator.comparing(FacultyProposal::getCreatedAt).reversed()).toList();
	}

	@Transactional
	public void createProgramProposal(UUID authorId, long facultyId, String name, String shortName) {
		ensureActiveUser(authorId);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Program name cannot be empty");
		}
		if (shortName == null || shortName.isBlank()) {
			throw new IllegalArgumentException("Program short name cannot be empty");
		}
		UniversityFaculty faculty = universityFacultyRepository.findById(facultyId)
				.orElseThrow(() -> new IllegalArgumentException("Faculty not found: " + facultyId));
		programProposalRepository.save(ProgramProposal.builder().authorId(authorId).university(faculty.getUniversity())
				.faculty(faculty).facultyName(faculty.getName()).facultyShortName(faculty.getShortName())
				.name(name.trim()).shortName(shortName.trim()).status("NEW").createdAt(LocalDateTime.now()).build());
		log.info("Program proposal '{}' submitted for faculty {} by user {}", shortName.trim(), facultyId, authorId);
	}

	@Transactional(readOnly = true)
	public List<ProgramProposal> listProgramProposals(UUID adminId) {
		ensureAdmin(adminId);
		return programProposalRepository.findAll().stream()
				.sorted(Comparator.comparing(ProgramProposal::getCreatedAt).reversed()).toList();
	}

	@Transactional
	public void reviewFacultyProposal(UUID reviewerId, long proposalId, String status) {
		ensureAdmin(reviewerId);
		if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
			throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
		}
		FacultyProposal proposal = facultyProposalRepository.findById(proposalId)
				.orElseThrow(() -> new IllegalArgumentException("Faculty proposal not found: " + proposalId));
		if ("APPROVED".equals(status) && !"APPROVED".equals(proposal.getStatus())) {
			createFaculty(reviewerId, proposal.getUniversity().getId(), proposal.getName(), proposal.getShortName());
		}
		proposal.setStatus(status);
		proposal.setReviewedBy(reviewerId);
		proposal.setReviewedAt(LocalDateTime.now());
		facultyProposalRepository.save(proposal);
		log.info("Faculty proposal {} {} by admin {}", proposalId, status, reviewerId);
	}

	@Transactional
	public void reviewProgramProposal(UUID reviewerId, long proposalId, String status) {
		ensureAdmin(reviewerId);
		if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
			throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
		}
		ProgramProposal proposal = programProposalRepository.findById(proposalId)
				.orElseThrow(() -> new IllegalArgumentException("Program proposal not found: " + proposalId));
		if ("APPROVED".equals(status) && !"APPROVED".equals(proposal.getStatus())) {
			createProgramForAdmin(reviewerId, proposal.getFaculty().getId(), proposal.getName(),
					proposal.getShortName());
		}
		proposal.setStatus(status);
		proposal.setReviewedBy(reviewerId);
		proposal.setReviewedAt(LocalDateTime.now());
		programProposalRepository.save(proposal);
		log.info("Program proposal {} {} by admin {}", proposalId, status, reviewerId);
	}

	@Transactional
	public void reviewUniversityProposal(UUID reviewerId, long proposalId, String status) {
		ensureAdmin(reviewerId);
		if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
			throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
		}
		UniversityProposal proposal = universityProposalRepository.findById(proposalId)
				.orElseThrow(() -> new IllegalArgumentException("University proposal not found: " + proposalId));
		if ("APPROVED".equals(status) && !"APPROVED".equals(proposal.getStatus())) {
			createUniversityFromProposal(proposal);
		}
		proposal.setStatus(status);
		proposal.setReviewedBy(reviewerId);
		proposal.setReviewedAt(LocalDateTime.now());
		universityProposalRepository.save(proposal);
		log.info("University proposal {} {} by admin {}", proposalId, status, reviewerId);
	}

	@Transactional
	public void createFaculty(UUID adminId, long universityId, String name, String shortName) {
		ensureAdmin(adminId);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Faculty name cannot be empty");
		}
		if (shortName == null || shortName.isBlank()) {
			throw new IllegalArgumentException("Faculty short name cannot be empty");
		}

		University university = universityRepository.findById(universityId)
				.orElseThrow(() -> new IllegalArgumentException("University not found: " + universityId));
		String normalizedShortName = shortName.trim();
		if (universityFacultyRepository.existsByUniversityIdAndShortName(universityId, normalizedShortName)) {
			throw new IllegalArgumentException(
					"Faculty short name already exists in this university: " + normalizedShortName);
		}

		universityFacultyRepository.save(UniversityFaculty.builder().university(university).name(name.trim())
				.shortName(normalizedShortName).build());
		log.info("Faculty '{}' created in university {} by admin {}", normalizedShortName, universityId, adminId);
	}

	@Transactional
	public UniversityProgram createProgram(UUID userId, long facultyId, String name, String shortName) {
		ensureActiveUser(userId);
		return createProgramRecord(facultyId, name, shortName);
	}

	@Transactional
	public UniversityProgram createProgramForAdmin(UUID adminId, long facultyId, String name, String shortName) {
		ensureAdmin(adminId);
		return createProgramRecord(facultyId, name, shortName);
	}

	private UniversityProgram createProgramRecord(long facultyId, String name, String shortName) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Program name cannot be empty");
		}
		if (shortName == null || shortName.isBlank()) {
			throw new IllegalArgumentException("Program short name cannot be empty");
		}
		UniversityFaculty faculty = universityFacultyRepository.findById(facultyId)
				.orElseThrow(() -> new IllegalArgumentException("Faculty not found: " + facultyId));
		String normalizedShortName = shortName.trim();
		return universityProgramRepository.findByFacultyId(facultyId).stream()
				.filter(p -> p.getShortName().equalsIgnoreCase(normalizedShortName)).findFirst()
				.orElseGet(() -> universityProgramRepository
						.save(UniversityProgram.builder().university(faculty.getUniversity()).faculty(faculty)
								.name(name.trim()).shortName(normalizedShortName).build()));
	}

	@Transactional
	public UniversityProgram createProgramForUser(UUID userId, long facultyId, String name, String shortName) {
		return createProgram(userId, facultyId, name, shortName);
	}

	private void ensureAdmin(UUID userId) {
		if (!userService.isAdmin(userId)) {
			throw new SecurityException("Admin privileges required");
		}
	}

	private void ensureActiveUser(UUID userId) {
		User user = userService.getById(userId);
		LocalDateTime bannedUntil = user.getBannedUntil();
		if (user.isBannedPermanent() || (bannedUntil != null && bannedUntil.isAfter(LocalDateTime.now()))) {
			throw new SecurityException("User is banned");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private void createUniversityFromProposal(UniversityProposal proposal) {
		if (universityRepository.findByName(proposal.getName()).isPresent()) {
			throw new IllegalArgumentException("University already exists: " + proposal.getName());
		}
		if (universityRepository.findByShortName(proposal.getShortName()).isPresent()) {
			throw new IllegalArgumentException("University short name already exists: " + proposal.getShortName());
		}
		if (universityRepository.findBySubdomain(proposal.getSubdomain()).isPresent()) {
			throw new IllegalArgumentException("Subdomain already taken: " + proposal.getSubdomain());
		}
		if (universityDomainRepository.findByDomain(proposal.getStudentDomain()).isPresent()) {
			throw new IllegalArgumentException("Student domain already registered: " + proposal.getStudentDomain());
		}
		if (universityDomainRepository.findByDomain(proposal.getEmployeeDomain()).isPresent()) {
			throw new IllegalArgumentException("Employee domain already registered: " + proposal.getEmployeeDomain());
		}

		University university = universityRepository.save(University.builder().name(proposal.getName())
				.shortName(proposal.getShortName()).subdomain(proposal.getSubdomain()).iconUrl(proposal.getIconUrl())
				.createdAt(LocalDateTime.now()).build());

		universityDomainRepository.save(UniversityDomain.builder().domain(proposal.getStudentDomain())
				.role(UniversityDomain.DomainRole.STUDENT).university(university).build());
		universityDomainRepository.save(UniversityDomain.builder().domain(proposal.getEmployeeDomain())
				.role(UniversityDomain.DomainRole.EMPLOYEE).university(university).build());
	}
}
