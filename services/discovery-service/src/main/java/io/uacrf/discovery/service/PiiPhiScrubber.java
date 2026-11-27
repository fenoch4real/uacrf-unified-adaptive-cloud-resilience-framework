package io.uacrf.discovery.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts PII, PHI, and credential material at S03 ingestion boundaries before storage or graph projection
 * to uphold GDPR, HIPAA, and SOC 2 Confidentiality controls.
 */
@Component
public class PiiPhiScrubber {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern GCP_KEY_PATTERN = Pattern.compile("\\bAIza[0-9A-Za-z\\-_]{35}\\b");

    public ScrubResult scrubText(String input) {
        if (input == null || input.isEmpty()) {
            return new ScrubResult(input, 0);
        }
        int count = 0;
        String out = input;

        var mBearer = BEARER_PATTERN.matcher(out);
        if (mBearer.find()) {
            count++;
            out = mBearer.replaceAll("Bearer [REDACTED_SECRET]");
        }
        var mAws = AWS_KEY_PATTERN.matcher(out);
        if (mAws.find()) {
            count++;
            out = mAws.replaceAll("[REDACTED_SECRET]");
        }
        var mGcp = GCP_KEY_PATTERN.matcher(out);
        if (mGcp.find()) {
            count++;
            out = mGcp.replaceAll("[REDACTED_SECRET]");
        }
        var mSsn = SSN_PATTERN.matcher(out);
        if (mSsn.find()) {
            count++;
            out = mSsn.replaceAll("[REDACTED_PII_SSN]");
        }
        var mEmail = EMAIL_PATTERN.matcher(out);
        if (mEmail.find()) {
            count++;
            out = mEmail.replaceAll("[REDACTED_PII_EMAIL]");
        }
        var mPan = PAN_PATTERN.matcher(out);
        if (mPan.find()) {
            count++;
            out = mPan.replaceAll("[REDACTED_PCI_PAN]");
        }
        return new ScrubResult(out, count);
    }

    public Map<String, String> scrubDimensions(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return Map.of();
        }
        Map<String, String> scrubbed = new LinkedHashMap<>();
        dimensions.forEach((k, v) -> scrubbed.put(k, scrubText(v).sanitizedText()));
        return Map.copyOf(scrubbed);
    }

    public record ScrubResult(String sanitizedText, int redactionsApplied) {
    }
}
