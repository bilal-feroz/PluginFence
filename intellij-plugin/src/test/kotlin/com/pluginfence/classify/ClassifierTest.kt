package com.pluginfence.classify

import com.pluginfence.model.PathRelation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitivePathClassifierTest {

    private val classifier = SensitivePathClassifier(userHome = "C:\\Users\\dev")

    @Test
    fun `ssh keys on windows and unix paths`() {
        assertEquals("ssh", classifier.classify("C:\\Users\\dev\\.ssh\\id_rsa")?.category)
        assertEquals("ssh", classifier.classify("/home/dev/.ssh/id_ed25519")?.category)
        assertEquals("ssh", classifier.classify("~/.ssh/config")?.category)
        assertEquals("ssh", classifier.classify("D:/demo-fixtures/home/.ssh/id_rsa")?.category)
        assertTrue(classifier.classify("/home/dev/.ssh/id_rsa")!!.credentialStore)
    }

    @Test
    fun `cloud and tool credential stores`() {
        assertEquals("aws", classifier.classify("/home/dev/.aws/credentials")?.category)
        assertEquals("aws", classifier.classify("C:\\Users\\dev\\.aws\\config")?.category)
        assertEquals("kubernetes", classifier.classify("/home/dev/.kube/config")?.category)
        assertEquals("docker", classifier.classify("/home/dev/.docker/config.json")?.category)
        assertEquals("npm", classifier.classify("/project/.npmrc")?.category)
        assertEquals("pypi", classifier.classify("/home/dev/.pypirc")?.category)
        assertEquals("git", classifier.classify("/home/dev/.git-credentials")?.category)
        assertEquals("gcloud", classifier.classify("/home/dev/.config/gcloud/credentials.db")?.category)
        assertEquals("gpg", classifier.classify("/home/dev/.gnupg/private-keys-v1.d/x.key")?.category)
    }

    @Test
    fun `project secrets`() {
        assertEquals("dotenv", classifier.classify("/proj/.env")?.category)
        assertEquals("dotenv", classifier.classify("/proj/.env.production")?.category)
        assertEquals("private-key", classifier.classify("/proj/certs/server.pem")?.category)
        assertEquals("private-key", classifier.classify("C:\\proj\\tls\\server.KEY")?.category)
        assertEquals("credentials-file", classifier.classify("/proj/credentials.json")?.category)
        assertEquals("credentials-file", classifier.classify("/proj/secrets.yaml")?.category)
        assertFalse(classifier.classify("/proj/.env")!!.credentialStore)
    }

    @Test
    fun `ordinary files are not sensitive`() {
        assertNull(classifier.classify("/proj/src/Main.kt"))
        assertNull(classifier.classify("C:\\proj\\README.md"))
        assertNull(classifier.classify("/proj/environment.md"))
        assertNull(classifier.classify("/proj/keyboard.txt"))
        assertNull(classifier.classify("/proj/monkey/stuff.txt"))
        assertNull(classifier.classify("/proj/.envelope"))
    }

    @Test
    fun `display path shortens home`() {
        assertEquals("~/.ssh/id_rsa", classifier.displayPath("C:\\Users\\dev\\.ssh\\id_rsa"))
        assertEquals("/other/x", classifier.displayPath("/other/x"))
    }
}

class SecretEnvClassifierTest {
    private val classifier = SecretEnvClassifier()

    @Test
    fun `well known and pattern based names`() {
        listOf("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "AZURE_CLIENT_SECRET",
            "MY_SERVICE_TOKEN", "DB_PASSWORD", "npm_token", "Foo_Api_Key").forEach {
            assertTrue(classifier.isSecret(it), it)
        }
    }

    @Test
    fun `ordinary variables`() {
        listOf("PATH", "HOME", "JAVA_HOME", "LANG", "USER", "TMP", "GRADLE_OPTS", "TERM").forEach {
            assertFalse(classifier.isSecret(it), it)
        }
    }
}

class NetworkAndProcessClassifierTest {
    @Test
    fun `loopback and raw ip detection`() {
        assertTrue(NetworkClassifier.isLoopback("localhost"))
        assertTrue(NetworkClassifier.isLoopback("127.0.0.1"))
        assertTrue(NetworkClassifier.isLoopback("::1"))
        assertFalse(NetworkClassifier.isLoopback("198.51.100.42"))
        assertTrue(NetworkClassifier.isRawIp("198.51.100.42"))
        assertTrue(NetworkClassifier.isRawIp("2001:db8::1"))
        assertFalse(NetworkClassifier.isRawIp("api.github.com"))
        assertTrue(NetworkClassifier.isPlaintext("http"))
        assertFalse(NetworkClassifier.isPlaintext("https"))
        assertEquals("api.github.com", NetworkClassifier.normalizeHost("API.GitHub.com."))
    }

    @Test
    fun `high risk executables`() {
        assertTrue(ProcessClassifier.isHighRisk("powershell.exe"))
        assertTrue(ProcessClassifier.isHighRisk("/bin/bash"))
        assertTrue(ProcessClassifier.isHighRisk("C:\\Windows\\System32\\cmd.exe"))
        assertTrue(ProcessClassifier.isHighRisk("curl"))
        assertFalse(ProcessClassifier.isHighRisk("java"))
        assertFalse(ProcessClassifier.isHighRisk("git"))
    }
}

class PathScopeTest {
    @Test
    fun `relation to project and ide roots`() {
        val scope = PathScope(listOf("C:\\Users\\dev\\proj"), listOf("C:\\Users\\dev\\AppData\\Roaming\\JetBrains\\config"))
        assertEquals(PathRelation.INSIDE_PROJECT, scope.relation("C:\\Users\\dev\\proj\\src\\Main.kt"))
        assertEquals(PathRelation.INSIDE_PROJECT, scope.relation("c:/users/dev/proj/README.md"))
        assertEquals(PathRelation.IDE_INTERNAL, scope.relation("C:\\Users\\dev\\AppData\\Roaming\\JetBrains\\config\\options\\x.xml"))
        assertEquals(PathRelation.OUTSIDE_PROJECT, scope.relation("C:\\Users\\dev\\projects-other\\x"))
        assertEquals(PathRelation.OUTSIDE_PROJECT, scope.relation("/etc/passwd"))
        assertNotNull(scope)
    }
}
