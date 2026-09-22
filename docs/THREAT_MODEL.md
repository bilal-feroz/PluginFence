# PluginFence threat model

PluginFence is a **runtime policy-enforcement and behavioural-monitoring proof of concept** for
IntelliJ plugins. This document states what it defends against, what it deliberately does not, and
why. Please read it before relying on PluginFence for anything beyond visibility.

## What we are defending

The developer machine that runs IntelliJ IDEA, and specifically the assets a plugin running inside
the IDE's JVM can reach with the IDE's own privileges:

- credential stores (`~/.ssh`, `~/.aws`, `~/.kube`, `~/.docker`, `~/.gnupg`, `.npmrc`, `.pypirc`, `.git-credentials`, gcloud/azure configs, `.netrc`)
- project secrets (`.env*`, private keys and keystores, `credentials.*`, `secrets.*`)
- secret-bearing environment variables (`*_TOKEN`, `*_API_KEY`, `*_SECRET`, `*_PASSWORD`, `AWS_*`, `OPENAI_API_KEY`, ...)
- the network (exfiltration channels) and the ability to spawn processes

JetBrains' own documentation describes the baseline:
[plugins "run as part of the IDE and have the same access rights as the IDE itself"](https://plugins.jetbrains.com/docs/marketplace/understanding-plugin-security.html)
and are neither permission-restricted nor sandboxed. The June 2026
[Marketplace security update](https://blog.jetbrains.com/platform/2026/06/marketplace-ecosystem-security-update-malicious-ai-plugins/)
(15 plugins harvesting AI-provider API keys and posting them over plaintext HTTP to a C2 server)
is the concrete instance of the threat.

## Adversary

A **third-party plugin** written in Java or Kotlin and installed by the developer, which:

1. is malicious from the start, **or**
2. becomes malicious in an update (compromised publisher account, sold plugin, supply-chain injection), **or**
3. is merely careless (reads credentials it does not need, talks to servers it does not disclose).

We assume the adversary controls the plugin's bytecode but does **not** control the IDE
installation, its VM options, the operating system, or the developer's user account beyond what
running inside the JVM gives it.

## What PluginFence provides

| Guarantee | How |
| --- | --- |
| Sensitive operations performed through the **instrumented JVM/Kotlin/IntelliJ APIs** from **third-party plugin bytecode** are observed, attributed to the plugin, evaluated against per-plugin policy and, when the policy says so, **prevented before they execute**. | Call-site rewriting at class-load time; hooks run before the original operation; blocked operations throw `SecurityException` (or return `null` for environment reads). The list of instrumented APIs is exhaustive and auditable: [`HookRules.java`](../agent/src/main/java/com/pluginfence/agent/HookRules.java). |
| Attribution is **evidence-based**. | The identity comes from the IntelliJ `PluginClassLoader` that defined the calling class and its plugin descriptor. Unknown loaders are reported as *Unknown third-party plugin*. Package names are never used to infer trust. |
| Secrets are **never** captured by PluginFence itself. | Hooks decide before reading; environment values are never touched; URLs and command lines are redacted; nothing is uploaded; persistence is local and bounded. |
| The IDE keeps working if PluginFence is missing or broken. | Without the agent the plugin runs in "agent not active" mode; without the plugin the agent allows and buffers; provider exceptions fail open; transformer failures leave classes unmodified. |
| Behavioural drift is **visible**. | Deterministic per-version baselines; drift = capabilities/hosts/processes/sensitive categories present in the new version and absent in the previous one. |

## What PluginFence does NOT provide

This is a proof of concept, not a sandbox. It does not claim - and cannot deliver - complete
containment. Known bypasses and gaps:

| Gap | Why | Mitigation status |
| --- | --- | --- |
| **JNI / native code** | Native code bypasses the JVM's I/O APIs entirely. | Out of scope. Needs OS-level controls (eBPF, Endpoint Security, ETW). |
| **`sun.misc.Unsafe`, JDK internals, `MethodHandle` / reflection on `FileSystemProvider`, `FileChannel.open`, NIO memory-mapped files, `java.net.DatagramSocket`, raw `SocketImpl`s, `ProcessHandle`, …** | Only the APIs in the rule table are rewritten. A determined author can reach the same functionality through un-instrumented paths. | Partial: the table covers the common Java/Kotlin/IntelliJ APIs; extending it is a one-line rule plus a hook. Not a fundamental fix. |
| **Delegation to platform code** | A plugin can ask an IntelliJ service (which is not instrumented) to do I/O on its behalf. Several such paths are covered as pre-checks (`VirtualFile`, `VfsUtil`, `FileUtil`, `GeneralCommandLine`, `OSProcessHandler`, `ExecUtil`, `HttpRequests`, `EnvironmentUtil`), but the platform surface is large. | Partial. Real coverage needs platform cooperation. |
| **Agent tampering** | Code in the same JVM can, in principle, use `Instrumentation` or reflection to remove the transformer, clear `GuardBridge`'s provider, or flip the enforcement flag. | Not defended. The agent is a proof of concept; hardening would need the platform to own the enforcement point. |
| **Class-loader tricks** | Loading attack code through a non-plugin class loader (e.g. `URLClassLoader` created by the plugin, or bytes defined via `Lookup.defineClass`) yields "Unknown third-party plugin" attribution and, depending on the loader, may not be instrumented at all. | Attribution is honest (unknown), but enforcement can be bypassed. Instrumenting every loader is possible but was ruled out for IDE stability in this MVP. |
| **Exploits against the IDE or the JVM** | A plugin that exploits a platform vulnerability is outside the model. | Out of scope. |
| **Bundled / JetBrains-vendor plugins** | Treated as part of the platform (monitored, not enforced by default) to keep the IDE usable. A compromised bundled plugin is therefore not blocked unless the user sets explicit permissions for it. | Configurable per plugin. |
| **Timing** | A malicious plugin that acts before the PluginFence control plane registers is only recorded (bounded buffer, replayed at registration), not blocked. | Registration happens at app-frame creation; the window is small but real. |
| **Dynamic plugin loading** | Classes loaded later are instrumented normally; classes already loaded when the agent is attached via `agentmain` are not retransformed in this MVP. | `-javaagent` at startup is the supported mode. |
| **Policy evasion through ASK** | ASK prevents the operation and asks the user; a user who clicks *Always Allow* on a malicious request has consented. | Notifications show the exact target and risk factors; that is the best a permission model can do. |

## The AI analyst

The analyst adds a model to a tool whose job is protecting secrets, so its boundaries are strict:

| Concern | Position |
| --- | --- |
| **Does enforcement depend on it?** | No. Interception, attribution, policy, blocking, baselines, drift and correlation are deterministic and run whether the analyst is off, misconfigured, slow or wrong. The analyst only produces recommendations; a user click applies them. |
| **What leaves the machine?** | Only when the analyst is enabled and only what PluginFence already records: plugin ids/names/versions, plugin manifests, redacted paths, host names, executable names, verdicts, rule ids, risk factors and the deterministic risk model. File contents, environment values and request bodies are never captured by PluginFence and therefore cannot be sent. With a local endpoint (Ollama, LM Studio) nothing leaves at all. |
| **Where is the key?** | In the IDE credential store (`PasswordSafe`), never in a settings file. `GROQ_API_KEY` / `OPENAI_API_KEY` from the environment are accepted as a fallback. It is sent only as a bearer token to the endpoint the user configured. Scripted runs deliberately pass it by environment inheritance rather than as a `-D` JVM option, because IntelliJ writes its full JVM options into `idea.log`. |
| **Prompt injection** | Everything the model reads through tools - paths, hostnames, command lines, plugin names and descriptions - is produced by the plugin under investigation, which may be hostile. Mitigations: every tool is read-only; the model has no side-effecting capability at all; the system prompt tells it to treat tool output as untrusted data; recommendations are constrained to the enum vocabulary (six capabilities × ALLOW/ASK/BLOCK, host/executable/directory targets) and parsed strictly; nothing is applied without the user clicking Apply and seeing what will change. The worst case is a misleading recommendation, which the evidence shown beside it lets the user check. |
| **Availability** | Requests run on a bounded background pool with connect/request timeouts; the intercepted thread never waits on a model. Failures degrade to a "failed" card with Retry. |
| **Can the model cause enforcement to fail open?** | No. It has no path into `PolicyEngine`, `GuardBridge` or the enforcement flag. |

The analyst is the one place PluginFence trades a little privacy (metadata to a model of the
user's choosing) for a lot of explainability. That trade is opt-in, visible in Settings, and
reversible with one checkbox.

## Design principles that follow from this

1. **Evidence over prediction.** PluginFence never decides based on what a plugin might do; it
   decides on what it is doing right now, and records it.
2. **Fail open for the IDE, fail closed for the operation.** PluginFence's own bugs must never
   crash IntelliJ; but once a policy says BLOCK, the operation must not run.
3. **Do not become a secret leak.** No values, no telemetry, redaction everywhere, bounded local
   storage.
4. **Be honest in the UI.** *Unknown third-party plugin* instead of a guess; *Agent not active*
   instead of pretending; risk factors listed instead of a magic number.

## Toward production-grade isolation

Everything above says the same thing: a plugin inside the IDE's JVM cannot be perfectly contained
by another plugin inside the same JVM. A production version would need one or more of:

- an enforcement point owned by the platform (a permission model in the plugin loader / platform
  services rather than bytecode rewriting from the outside),
- process isolation for plugins (the direction JetBrains' Agent Client Protocol points at),
- OS-level monitoring of the IDE process (eBPF on Linux, Endpoint Security on macOS, ETW on Windows)
  to catch native and out-of-process activity.

PluginFence demonstrates the missing layer - runtime evidence, attribution, permissions,
baselines and drift - in a form that works today, and it is explicit about the boundary of what
that form can guarantee.
