package uni.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NotificationServiceApplication {
	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("migrate")) {
			DatabaseMigration.run(java.util.Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		SpringApplication.run(NotificationServiceApplication.class, args);
	}
}
