package com.pluginfence.classify

import java.util.Locale

/**
 * Classifies file paths into sensitive-resource categories (SSH keys, cloud credentials,
 * `.env` files, private keys, ...). Rules are data, so they can be persisted and edited.
 *
 * Matching is purely lexical: separators are normalized, `~` is expanded, matching is
 * case-insensitive, and no filesystem access happens (the classifier runs on the intercepted
 * thread).
 */
class SensitivePathClassifier(
    rules: List<SensitiveRule> = SensitiveRule.defaults(),
    userHome: String = System.getProperty("user.home") ?: "",
) {
    data class Match(val category: String, val label: String, val credentialStore: Boolean, val ruleId: String)

    private val rules = rules.toList()
    private val home = normalize(userHome).trimEnd('/')

    fun classify(rawPath: String): Match? {
        if (rawPath.isBlank()) return null
        val path = expandHome(normalize(rawPath))
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val fileName = segments.last()
        for (rule in rules) {
            val hit = when (rule.kind) {
                SensitiveRule.Kind.DIRECTORY_SEGMENT -> segments.any { it == rule.pattern }
                SensitiveRule.Kind.PATH_SUFFIX -> path.endsWith("/" + rule.pattern) || path == rule.pattern
                SensitiveRule.Kind.PATH_CONTAINS -> path.contains("/" + rule.pattern + "/") || path.endsWith("/" + rule.pattern)
                SensitiveRule.Kind.FILE_NAME -> fileName == rule.pattern
                SensitiveRule.Kind.FILE_NAME_PREFIX -> fileName.startsWith(rule.pattern) && fileName.length > rule.pattern.length
                SensitiveRule.Kind.EXTENSION -> fileName.endsWith(rule.pattern) && fileName.length > rule.pattern.length
            }
            if (hit) return Match(rule.category, rule.label, rule.credentialStore, rule.id)
        }
        return null
    }

    /** Shortens a path for display, e.g. `~/.ssh/id_rsa`. */
    fun displayPath(rawPath: String): String {
        val path = normalize(rawPath)
        if (home.isNotEmpty() && path.lowercase(Locale.ROOT).startsWith(home.lowercase(Locale.ROOT))) {
            return "~" + path.substring(home.length)
        }
        return path
    }

    private fun expandHome(path: String): String =
        if (path.startsWith("~/") && home.isNotEmpty()) home.lowercase(Locale.ROOT) + path.substring(1) else path

    companion object {
        fun normalize(path: String): String =
            path.replace('\\', '/').replace(Regex("/+"), "/").trim().lowercase(Locale.ROOT)
    }
}

data class SensitiveRule(
    val id: String,
    val kind: Kind,
    val pattern: String,
    val category: String,
    val label: String,
    /** True for credential stores (SSH/AWS/kube/...) - weighted higher than generic secrets. */
    val credentialStore: Boolean,
) {
    enum class Kind { DIRECTORY_SEGMENT, PATH_SUFFIX, PATH_CONTAINS, FILE_NAME, FILE_NAME_PREFIX, EXTENSION }

    companion object {
        fun defaults(): List<SensitiveRule> = listOf(
            SensitiveRule("ssh", Kind.DIRECTORY_SEGMENT, ".ssh", "ssh", "SSH keys", true),
            SensitiveRule("aws-credentials", Kind.PATH_SUFFIX, ".aws/credentials", "aws", "AWS credentials", true),
            SensitiveRule("aws-config", Kind.PATH_SUFFIX, ".aws/config", "aws", "AWS config", true),
            SensitiveRule("kube", Kind.PATH_SUFFIX, ".kube/config", "kubernetes", "Kubernetes config", true),
            SensitiveRule("docker", Kind.PATH_SUFFIX, ".docker/config.json", "docker", "Docker credentials", true),
            SensitiveRule("npmrc", Kind.FILE_NAME, ".npmrc", "npm", "npm credentials", true),
            SensitiveRule("pypirc", Kind.FILE_NAME, ".pypirc", "pypi", "PyPI credentials", true),
            SensitiveRule("git-credentials", Kind.FILE_NAME, ".git-credentials", "git", "Git credentials", true),
            SensitiveRule("gcloud", Kind.PATH_CONTAINS, ".config/gcloud", "gcloud", "Google Cloud credentials", true),
            SensitiveRule("azure", Kind.DIRECTORY_SEGMENT, ".azure", "azure", "Azure credentials", true),
            SensitiveRule("netrc", Kind.FILE_NAME, ".netrc", "netrc", "netrc credentials", true),
            SensitiveRule("netrc-win", Kind.FILE_NAME, "_netrc", "netrc", "netrc credentials", true),
            SensitiveRule("gnupg", Kind.DIRECTORY_SEGMENT, ".gnupg", "gpg", "GPG keys", true),
            SensitiveRule("dotenv", Kind.FILE_NAME, ".env", "dotenv", "Environment file (.env)", false),
            SensitiveRule("dotenv-variant", Kind.FILE_NAME_PREFIX, ".env.", "dotenv", "Environment file (.env.*)", false),
            SensitiveRule("pem", Kind.EXTENSION, ".pem", "private-key", "Private key (.pem)", false),
            SensitiveRule("key", Kind.EXTENSION, ".key", "private-key", "Private key (.key)", false),
            SensitiveRule("p12", Kind.EXTENSION, ".p12", "private-key", "Keystore (.p12)", false),
            SensitiveRule("pfx", Kind.EXTENSION, ".pfx", "private-key", "Keystore (.pfx)", false),
            SensitiveRule("jks", Kind.EXTENSION, ".jks", "private-key", "Keystore (.jks)", false),
            SensitiveRule("keystore", Kind.EXTENSION, ".keystore", "private-key", "Keystore", false),
            SensitiveRule("ppk", Kind.EXTENSION, ".ppk", "private-key", "PuTTY key (.ppk)", false),
            SensitiveRule("credentials-file", Kind.FILE_NAME_PREFIX, "credentials.", "credentials-file", "Credentials file", false),
            SensitiveRule("secrets-file", Kind.FILE_NAME_PREFIX, "secrets.", "credentials-file", "Secrets file", false),
            SensitiveRule("secret-file", Kind.FILE_NAME_PREFIX, "secret.", "credentials-file", "Secret file", false),
        )
    }
}
