package uni.gateway.dto.admin;

public record UniversityProposalInput(String name, String shortName, String subdomain, String studentDomain,
		String employeeDomain, String city, String description, String iconUrl) {
}
