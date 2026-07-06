package io.jenkins.plugins.queuemonitor;

import hudson.tasks.Mailer;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jenkins.model.JenkinsLocationConfiguration;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the sustained-increasing-queue-depth alert email via the Mailer plugin's
 * configured SMTP session (Manage Jenkins → System → E-mail Notification).
 */
public final class QueueTrendNotifier {

    private static final Logger LOG = Logger.getLogger(QueueTrendNotifier.class.getName());

    private QueueTrendNotifier() {}

    /**
     * @return true only if the message was handed off to the SMTP transport successfully,
     *         so the caller can gate cooldown tracking on an actual send.
     */
    public static boolean send(GlobalConfig cfg, int fromDepth, int toDepth, int sustainedSamples) {
        List<String> recipients = cfg.getTrendNotificationRecipientList();
        if (recipients.isEmpty()) {
            LOG.warning("[QueueMonitor] Trend alert triggered but no recipients configured; skipping email.");
            return false;
        }

        try {
            Session session = Mailer.descriptor().createSession();
            MimeMessage message = new MimeMessage(session);

            String from = JenkinsLocationConfiguration.get().getAdminAddress();
            if (from != null && !from.isBlank()) {
                message.setFrom(new InternetAddress(from));
            }

            InternetAddress[] to = new InternetAddress[recipients.size()];
            for (int i = 0; i < recipients.size(); i++) {
                to[i] = new InternetAddress(recipients.get(i));
            }
            message.setRecipients(Message.RecipientType.TO, to);
            message.setSubject("[Queue Monitor] Sustained increasing queue depth detected");
            message.setText(buildBody(cfg, fromDepth, toDepth, sustainedSamples));

            Transport.send(message);
            LOG.info(String.format(
                "[QueueMonitor] Trend alert email sent to %d recipient(s): depth %d -> %d over %d consecutive samples",
                recipients.size(), fromDepth, toDepth, sustainedSamples));
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[QueueMonitor] Failed to send trend alert email", e);
            return false;
        }
    }

    private static String buildBody(GlobalConfig cfg, int fromDepth, int toDepth, int sustainedSamples) {
        StringBuilder body = new StringBuilder();
        body.append("The build queue depth has increased for ").append(sustainedSamples)
            .append(" consecutive samples: ").append(fromDepth).append(" -> ").append(toDepth).append(".\n\n");

        String url = JenkinsLocationConfiguration.get().getUrl();
        if (url != null && !url.isBlank()) {
            body.append("Dashboard: ").append(url).append("queue-monitor/\n\n");
        }

        body.append("You will not receive another alert for at least ")
            .append(cfg.getTrendNotificationCooldownSeconds())
            .append(" seconds, and only if the increasing trend continues.\n");
        return body.toString();
    }
}
