package io.uacrf.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dependency-free AWS Signature Version 4 (AWS4-HMAC-SHA256) request signer and credential resolver.
 * Supports explicit credentials, standard AWS_* environment variables, and INI ~/.aws/credentials profiles.
 */
public final class AwsSigV4SignerSupport {

    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4SignerSupport() {
    }

    public record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {
        public boolean isComplete() {
            return accessKeyId != null && !accessKeyId.isBlank()
                    && secretAccessKey != null && !secretAccessKey.isBlank();
        }
    }

    /**
     * Resolves AWS credentials in priority order:
     * explicit parameters -> AWS_ACCESS_KEY_ID/SECRET/SESSION_TOKEN env vars -> shared INI credentials file (~/.aws/credentials).
     */
    public static AwsCredentials resolveCredentials(
            String explicitAccessKey,
            String explicitSecretKey,
            String explicitSessionToken,
            String explicitCredentialsFile,
            String profileName,
            Map<String, String> env,
            String userHome) {
        if (notBlank(explicitAccessKey) && notBlank(explicitSecretKey)) {
            return new AwsCredentials(
                    explicitAccessKey.trim(),
                    explicitSecretKey.trim(),
                    nullToEmpty(explicitSessionToken).trim()
            );
        }
        if (env != null && notBlank(env.get("AWS_ACCESS_KEY_ID")) && notBlank(env.get("AWS_SECRET_ACCESS_KEY"))) {
            return new AwsCredentials(
                    env.get("AWS_ACCESS_KEY_ID").trim(),
                    env.get("AWS_SECRET_ACCESS_KEY").trim(),
                    nullToEmpty(env.get("AWS_SESSION_TOKEN")).trim()
            );
        }
        Path credsPath = resolveCredentialsFilePath(explicitCredentialsFile, env, userHome);
        if (credsPath != null && Files.isRegularFile(credsPath)) {
            String targetProfile = notBlank(profileName)
                    ? profileName.trim()
                    : (env != null && notBlank(env.get("AWS_PROFILE")) ? env.get("AWS_PROFILE").trim() : "default");
            AwsCredentials fromIni = readProfileFromIni(credsPath, targetProfile);
            if (fromIni.isComplete()) {
                return fromIni;
            }
        }
        return new AwsCredentials("", "", "");
    }

    static Path resolveCredentialsFilePath(String explicitFile, Map<String, String> env, String userHome) {
        if (notBlank(explicitFile)) {
            return Path.of(explicitFile.trim());
        }
        if (env != null && notBlank(env.get("AWS_SHARED_CREDENTIALS_FILE"))) {
            return Path.of(env.get("AWS_SHARED_CREDENTIALS_FILE").trim());
        }
        if (notBlank(userHome)) {
            return Path.of(userHome.trim(), ".aws", "credentials");
        }
        return null;
    }

    static AwsCredentials readProfileFromIni(Path file, String profileName) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String activeSection = "";
            String keyId = "";
            String secret = "";
            String session = "";
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    activeSection = line.substring(1, line.length() - 1).trim();
                    if (activeSection.startsWith("profile ")) {
                        activeSection = activeSection.substring("profile ".length()).trim();
                    }
                    continue;
                }
                if (!profileName.equals(activeSection)) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(eq + 1).trim();
                switch (k) {
                    case "aws_access_key_id" -> keyId = v;
                    case "aws_secret_access_key" -> secret = v;
                    case "aws_session_token" -> session = v;
                    default -> {
                    }
                }
            }
            return new AwsCredentials(keyId, secret, session);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read AWS shared credentials file [" + file + "]: " + ex.getMessage(), ex);
        }
    }

    /**
     * Signs an HTTP request using AWS Signature Version 4 and returns all required signature headers.
     */
    public static Map<String, String> signRequest(
            String httpMethod,
            URI uri,
            String awsService,
            String awsRegion,
            Map<String, String> extraHeaders,
            byte[] payloadBytes,
            AwsCredentials creds,
            Instant timestamp) {
        if (creds == null || !creds.isComplete()) {
            throw new IllegalArgumentException("AWS accessKeyId and secretAccessKey are required for SigV4 signing.");
        }
        byte[] body = payloadBytes == null ? new byte[0] : payloadBytes;
        String method = httpMethod.trim().toUpperCase(Locale.ROOT);
        String service = awsService.trim().toLowerCase(Locale.ROOT);
        String region = awsRegion.trim().toLowerCase(Locale.ROOT);

        String amzDate = AMZ_DATE_FMT.format(timestamp);
        String dateStamp = DATE_STAMP_FMT.format(timestamp);
        String payloadHash = sha256Hex(body);
        String hostHeader = uri.getPort() > 0 && uri.getPort() != 443 && uri.getPort() != 80
                ? uri.getHost() + ":" + uri.getPort()
                : uri.getHost();

        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("host", hostHeader.toLowerCase(Locale.ROOT));
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        canonicalHeaders.put("x-amz-date", amzDate);
        if (notBlank(creds.sessionToken())) {
            canonicalHeaders.put("x-amz-security-token", creds.sessionToken().trim());
        }
        if (extraHeaders != null) {
            for (var entry : extraHeaders.entrySet()) {
                if (notBlank(entry.getKey()) && entry.getValue() != null) {
                    canonicalHeaders.put(
                            entry.getKey().trim().toLowerCase(Locale.ROOT),
                            normalizeWhitespace(entry.getValue())
                    );
                }
            }
        }

        StringBuilder canonicalHeaderBlock = new StringBuilder();
        StringBuilder signedHeadersBuilder = new StringBuilder();
        for (var entry : canonicalHeaders.entrySet()) {
            canonicalHeaderBlock.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
            if (signedHeadersBuilder.length() > 0) {
                signedHeadersBuilder.append(';');
            }
            signedHeadersBuilder.append(entry.getKey());
        }
        String signedHeaders = signedHeadersBuilder.toString();

        String canonicalUri = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        String canonicalQuery = canonicalizeQueryString(uri.getRawQuery());

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaderBlock + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = deriveSigningKey(creds.secretAccessKey(), dateStamp, region, service);
        String signature = hex(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authHeader = "AWS4-HMAC-SHA256 Credential=" + creds.accessKeyId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> out = new LinkedHashMap<>();
        out.put("Authorization", authHeader);
        out.put("x-amz-date", amzDate);
        out.put("x-amz-content-sha256", payloadHash);
        if (notBlank(creds.sessionToken())) {
            out.put("x-amz-security-token", creds.sessionToken().trim());
        }
        return out;
    }

    private static String canonicalizeQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String param : rawQuery.split("&")) {
            int eq = param.indexOf('=');
            String k = eq >= 0 ? param.substring(0, eq) : param;
            String v = eq >= 0 ? param.substring(eq + 1) : "";
            pairs.add(new String[]{awsUrlEncode(decodeUrlComponent(k)), awsUrlEncode(decodeUrlComponent(v))});
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    private static String decodeUrlComponent(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String awsUrlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static byte[] deriveSigningKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String normalizeWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
