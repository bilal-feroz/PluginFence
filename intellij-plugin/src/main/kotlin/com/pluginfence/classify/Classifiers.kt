package com.pluginfence.classify

import com.pluginfence.model.PathRelation
import java.util.Locale

/** Decides whether an environment variable name looks like a credential. Values are never seen. */
class SecretEnvClassifier(
    private val exactNames: Set<String> = DEFAULT_EXACT,
    private val suffixes: List<String> = DEFAULT_SUFFIXES,
    private val prefixes: List<String> = DEFAULT_PREFIXES,
    private val fragments: List<String> = DEFAULT_FRAGMENTS,
) {
    fun isSecret(name: String): Boolean {
        val n = name.trim().uppercase(Locale.ROOT)
        if (n.isEmpty()) return false
        if (n in exactNames) return true
        if (suffixes.any { n.endsWith(it) }) return true
        if (prefixes.any { n.startsWith(it) }) return true
        return fragments.any { n.contains(it) }
    }

    companion object {
        val DEFAULT_EXACT: Set<String> = setOf(
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GITHUB_TOKEN", "GH_TOKEN", "GITLAB_TOKEN",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "NPM_TOKEN", "PYPI_TOKEN", "DOCKER_PASSWORD", "GOOGLE_APPLICATION_CREDENTIALS",
            "DATABASE_URL", "JDBC_DATABASE_PASSWORD", "SLACK_TOKEN", "STRIPE_SECRET_KEY",
            "HF_TOKEN", "HUGGINGFACE_TOKEN", "GEMINI_API_KEY", "MISTRAL_API_KEY", "GROQ_API_KEY",
        )
        val DEFAULT_SUFFIXES: List<String> = listOf(
            "_TOKEN", "_API_KEY", "_APIKEY", "_SECRET", "_SECRET_KEY", "_PASSWORD", "_PASSWD", "_PWD",
            "_ACCESS_KEY", "_PRIVATE_KEY", "_CREDENTIALS", "_CREDENTIAL", "_AUTH", "_AUTH_TOKEN", "_CLIENT_SECRET",
        )
        val DEFAULT_PREFIXES: List<String> = listOf("AZURE_", "AWS_SECRET", "OPENAI_", "ANTHROPIC_")
        val DEFAULT_FRAGMENTS: List<String> = listOf("SECRET", "TOKEN", "PASSWORD", "API_KEY", "APIKEY", "PRIVATE_KEY")
    }
}

/** Lexical helpers for network destinations. No DNS. */
object NetworkClassifier {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isLoopback(host: String): Boolean {
        val h = host.trim().lowercase(Locale.ROOT).trim('[', ']')
        return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || h.startsWith("127.") || h.endsWith(".localhost")
    }

    fun isRawIp(host: String): Boolean {
        val h = host.trim().trim('[', ']')
        return IPV4.matches(h) || (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' } && h.count { it == ':' } >= 2)
    }

    fun isPlaintext(scheme: String?): Boolean {
        val s = scheme?.lowercase(Locale.ROOT) ?: return false
        return s == "http" || s == "ws" || s == "ftp"
    }

    /** Normalizes a host for baseline comparison (lower-case, no brackets, no trailing dot). */
    fun normalizeHost(host: String): String = host.trim().lowercase(Locale.ROOT).trim('[', ']').trimEnd('.')
}

/** Executables whose launch deserves extra scrutiny (shells, interpreters, downloaders, LOLBins). */
object ProcessClassifier {
    private val HIGH_RISK = setOf(
        "bash", "sh", "zsh", "fish", "dash", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe",
        "curl", "curl.exe", "wget", "wget.exe", "python", "python3", "python.exe", "node", "node.exe", "perl", "ruby",
        "osascript", "certutil", "certutil.exe", "bitsadmin", "bitsadmin.exe", "mshta", "mshta.exe", "rundll32",
        "rundll32.exe", "regsvr32", "regsvr32.exe", "scp", "ssh", "ssh.exe", "nc", "ncat", "netcat", "wscript",
        "wscript.exe", "cscript", "cscript.exe", "base64", "openssl",
    )

    fun isHighRisk(executable: String): Boolean {
        val exe = executable.trim().lowercase(Locale.ROOT).substringAfterLast('/').substringAfterLast('\\')
        return exe in HIGH_RISK
    }
}

/**
 * Resolves where a path sits relative to the open projects and the IDE's own directories.
 * Roots are absolute, normalized and lower-cased; comparison is lexical.
 */
class PathScope(projectRoots: Collection<String>, ideRoots: Collection<String>) {
    private val projectRoots = projectRoots.map { SensitivePathClassifier.normalize(it).trimEnd('/') }.filter { it.isNotEmpty() }
    private val ideRoots = ideRoots.map { SensitivePathClassifier.normalize(it).trimEnd('/') }.filter { it.isNotEmpty() }

    fun relation(rawPath: String): PathRelation {
        val path = SensitivePathClassifier.normalize(rawPath)
        if (projectRoots.any { path == it || path.startsWith("$it/") }) return PathRelation.INSIDE_PROJECT
        if (ideRoots.any { path == it || path.startsWith("$it/") }) return PathRelation.IDE_INTERNAL
        return PathRelation.OUTSIDE_PROJECT
    }
}
