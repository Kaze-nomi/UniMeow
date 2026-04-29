package uni.gateway.dto.admin;

public record UniversityProposalDto(String id, String authorId, String name, String shortName, String subdomain,
		String studentDomain, String employeeDomain, String city, String description, String status, String createdAt,
		String reviewedBy, String reviewedAt, String iconUrl) {
}
