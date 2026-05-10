package uni.user.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import uni.user.exception.MailDeliveryException;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	private final JavaMailSender mailSender;

	@Value("${spring.mail.username}")
	private String fromEmail;

	@Value("${app.frontend-url:https://unimeow.ru}")
	private String frontendUrl;

	public void sendVerificationCode(String toEmail, String username, String code) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(fromEmail, "UniMeow"));
			helper.setTo(toEmail);
			helper.setSubject("Подтверждение университетского email");

			String html = """
					<!DOCTYPE html>
					<html lang="ru">
					<head>
					  <meta charset="UTF-8"/>
					  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
					  <style>
					    body {
					      margin: 0;
					      padding: 0;
					      background: #f5f5f5;
					    }
					    table {
					      border-collapse: collapse;
					    }
					  </style>
					</head>

					<body style="margin:0;padding:0;background:#f5f5f5;font-family:Arial,Helvetica,sans-serif;">
					  <span style="display:none;max-height:0;overflow:hidden;mso-hide:all;">
					    Подтвердите email — ваш код: %CODE% (действителен 3 мин)
					  </span>

					  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
					         style="width:100%;background:#f5f5f5;margin:0;padding:0;">
					    <tr>
					      <td align="center" style="padding:32px 16px;text-align:center;">

					        <table role="presentation" width="520" cellpadding="0" cellspacing="0" border="0"
					               align="center"
					               style="width:520px;max-width:520px;background:#ffffff;margin:0 auto;border-radius:16px;overflow:hidden;">
					          <tr>
					            <td align="center"
					                style="background:#7c3aed;padding:24px 32px;text-align:center;">
					              <img src="%FRONTEND_URL%/favicon.png"
					                   alt="UniMeow"
					                   width="48"
					                   height="48"
					                   style="display:block;margin:0 auto 8px;border-radius:12px;border:0;outline:none;text-decoration:none;"/>
					              <div style="color:#ffffff;font-size:22px;font-weight:800;letter-spacing:-0.5px;">
					                UniMeow
					              </div>
					            </td>
					          </tr>

					          <tr>
					            <td align="center"
					                style="padding:32px 36px;text-align:center;">
					              <p style="margin:0 0 8px;font-size:16px;color:#111111;line-height:1.4;">
					                Привет, <strong>%USERNAME%</strong>!
					              </p>

					              <p style="margin:0 0 24px;font-size:15px;color:#555555;line-height:1.55;">
					                Введи этот код в UniMeow, чтобы подтвердить свой университетский email.
					              </p>

					              <table role="presentation" cellpadding="0" cellspacing="0" border="0" align="center"
					                     style="margin:0 auto 28px;">
					                <tr>
					                  <td align="center"
					                      style="background:#f3e8ff;color:#5b21b6;font-size:34px;font-weight:800;letter-spacing:8px;padding:16px 32px;border-radius:12px;border:2px solid #d8b4fe;text-align:center;">
					                    %CODE%
					                  </td>
					                </tr>
					              </table>

					              <p style="margin:0;font-size:13px;color:#888888;line-height:1.5;">
					                Код действителен <strong>3 минуты</strong>.<br/>
					                Если ты не запрашивал подтверждение — просто проигнорируй это письмо.
					              </p>
					            </td>
					          </tr>

					          <tr>
					            <td align="center"
					                style="padding:16px 36px 24px;border-top:1px solid #eeeeee;text-align:center;">
					              <span style="font-size:12px;color:#bbbbbb;">
					                © UniMeow — социальная сеть для студентов
					              </span>
					            </td>
					          </tr>
					        </table>

					      </td>
					    </tr>
					  </table>
					</body>
					</html>
					"""
					.replace("%FRONTEND_URL%", frontendUrl).replace("%USERNAME%", username).replace("%CODE%", code);

			helper.setText(html, true);
			mailSender.send(message);
		} catch (Exception e) {
			throw new MailDeliveryException("Почтовый сервис временно недоступен. Попробуйте позже.", e);
		}
	}
}
