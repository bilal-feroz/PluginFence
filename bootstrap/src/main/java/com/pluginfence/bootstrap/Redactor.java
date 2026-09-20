package com.pluginfence.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credential material from targets before they are recorded.
 * PluginFence protects secrets; it must never become another place secrets leak to.
 */
public final class Redactor {

    public static final String MASK = "***";

    private static final Pattern SENSITIVE_QUERY_PARAM = Pattern.compile(
            "(?i)([?&](?:[\\w.-]*(?:token|key|secret|password|passwd|pwd|auth|signature|sig|credential|session|cookie|bearer)[\\w.-]*)=)[^&#]*");

    private static final Pattern URL_USERINFO = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@?#]*)@");

    private static final Pattern SENSITIVE_FLAG_ASSIGNMENT = Pattern.compile(
            "(?i)^(-{0,2}[\\w.-]*(?:token|key|secret|password|passwd|pwd|auth|credential|apikey|api-key)[\\w.-]*[=:])(.+)$");

    private static final Pattern SENSITIVE_FLAG = Pattern.compile(
            "(?i)^-{1,2}(?:[\\w.-]*(?:token|key|secret|password|passwd|pwd|auth|credential|apikey|api-key)[\\w.-]*|p|u)$");

    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)^(authorization\\s*:\\s*(?:bearer|basic|token)?\\s*)(\\S+)(.*)$");

    private static final Pattern KNOWN_TOKEN_SHAPE = Pattern.compile(
            "^(?:[A-Fa-f0-9]{40,}|(?:sk|pk|ghp|gho|ghu|ghs|ghr|xox[abp]|AKIA|ASIA)[-_]?[A-Za-z0-9_\\-]{12,}"
                    + "|eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{5,})$");

    private static final Pattern TOKEN_CHARS = Pattern.compile("^[A-Za-z0-9_\\-+/=]{20,}$");

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    private Redactor() {
    }

    /** Strips user-info and credential-looking query parameter values from a URL string. */
    public static String redactUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        String result = url;
        Matcher m = URL_USERINFO.matcher(result);
        if (m.find()) {
            result = m.group(1) + MASK + "@" + result.substring(m.end());
        }
        result = SENSITIVE_QUERY_PARAM.matcher(result).replaceAll("$1" + MASK);
        int fragment = result.indexOf('#');
        if (fragment >= 0) {
            result = result.substring(0, fragment);
        }
        return result;
    }

    /** Sanitizes a command line, masking values that look like credentials. */
    public static List<String> sanitizeArguments(List<String> args) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        boolean maskNext = false;
        for (String arg : args) {
            if (arg == null) {
                out.add("");
                continue;
            }
            if (maskNext) {
                out.add(MASK);
                maskNext = false;
                continue;
            }
            Matcher header = AUTH_HEADER.matcher(arg);
            if (header.matches()) {
                out.add(header.group(1) + MASK + header.group(3));
                continue;
            }
            Matcher assign = SENSITIVE_FLAG_ASSIGNMENT.matcher(arg);
            if (assign.matches()) {
                out.add(assign.group(1) + MASK);
                continue;
            }
            Matcher env = ENV_ASSIGNMENT.matcher(arg);
            if (env.matches() && isSecretName(env.group(1))) {
                out.add(env.group(1) + "=" + MASK);
                continue;
            }
            if (SENSITIVE_FLAG.matcher(arg).matches()) {
                out.add(arg);
                maskNext = true;
                continue;
            }
            if (looksLikeSecret(arg)) {
                out.add(MASK);
                continue;
            }
            out.add(arg.startsWith("http://") || arg.startsWith("https://") ? redactUrl(arg) : arg);
        }
        return out;
    }

    /**
     * Heuristic for bare credential-looking arguments: well-known token prefixes, long hex strings,
     * or long mixed-case alphanumerics with high entropy. Ordinary words, paths and hyphenated
     * identifiers do not match.
     */
    static boolean looksLikeSecret(String s) {
        if (s == null) return false;
        if (KNOWN_TOKEN_SHAPE.matcher(s).matches()) return true;
        if (!TOKEN_CHARS.matcher(s).matches()) return false;
        boolean digit = false, upper = false, lower = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) digit = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
        }
        return digit && upper && lower && shannonEntropy(s) >= 3.5;
    }

    private static double shannonEntropy(String s) {
        int[] counts = new int[128];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) counts[c]++;
        }
        double entropy = 0;
        for (int count : counts) {
            if (count == 0) continue;
            double p = (double) count / s.length();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /** Conservative name-only heuristic used to mask {@code NAME=value} arguments. */
    public static boolean isSecretName(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        return n.contains("TOKEN") || n.contains("SECRET") || n.contains("PASSWORD") || n.contains("PASSWD")
                || n.contains("API_KEY") || n.contains("APIKEY") || n.contains("ACCESS_KEY") || n.contains("PRIVATE_KEY")
                || n.contains("CREDENTIAL") || n.endsWith("_KEY") || n.endsWith("_PWD") || n.contains("AUTH");
    }

    /** Joins sanitized arguments for display, truncating very long command lines. */
    public static String joinForDisplay(List<String> sanitized, int maxLength) {
        StringBuilder sb = new StringBuilder();
        for (String s : sanitized) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s.indexOf(' ') >= 0 ? '"' + s + '"' : s);
            if (sb.length() > maxLength) {
                sb.setLength(maxLength);
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }
}
