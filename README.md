# PLUGINFENCE

**A runtime firewall for IntelliJ plugins.**

IntelliJ plugins currently run with powerful access to the developer's machine.

PLUGINFENCE adds a runtime security layer that watches what plugins actually do, blocks dangerous
access, and detects behavioral changes after updates.

```text
Plugin
  ↓
PLUGINFENCE
  ↓
Policy Engine
  ↓
ALLOW / BLOCK / ALERT
```

[![CI](https://github.com/bilal-feroz/Plugin-Fence/actions/workflows/ci.yml/badge.svg)](https://github.com/bilal-feroz/Plugin-Fence/actions/workflows/ci.yml)
![IntelliJ 2026.2](https://img.shields.io/badge/IntelliJ%20Platform-2026.2-000000?logo=intellijidea)
![Java 25](https://img.shields.io/badge/JVM%20agent-java.lang.instrument-blue)
![License MIT](https://img.shields.io/badge/license-MIT-green)

---

## The problem

Your browser asks before a website uses your camera. Your phone asks before an app reads your
location. Your IDE plugins ask for nothing - and they run on the machine where you keep source
code, SSH keys, cloud credentials, `.env` files and production access.

JetBrains describes the current model plainly in
[Understanding plugin security](https://plugins.jetbrains.com/docs/marketplace/understanding-plugin-security.html):

> Plugins in JetBrains IDEs run as part of the IDE and have the same access rights as the IDE
> itself. [...] The IDE does not restrict plugins using fine-grained permissions, nor does it
> isolate (sandbox) them.

This is not theoretical. In June 2026 JetBrains published a
[Marketplace ecosystem security update](https://blog.jetbrains.com/platform/2026/06/marketplace-ecosystem-security-update-malicious-ai-plugins/)
after removing 15 third-party plugins that posed as AI utilities and silently transmitted
developers' AI-provider API keys (OpenAI, DeepSeek, SiliconFlow) to a command-and-control server
over plaintext HTTP. JetBrains noted that its Plugin Verifier "was architected as a compatibility
and API-usage checker rather than a dedicated data-flow or anti-malware scanner", and that the
Verified Vendor badge "does not serve as a 100% technical guarantee of a plugin's absolute safety".

Today a developer cannot answer simple questions:

- Why does this syntax-highlighting plugin need my SSH keys?
- Which plugin just connected to that IP address?
- Did this plugin execute a shell command?
- Did version 2.4 gain behaviour that version 2.3 never had?
- Can I *deny* a plugin access to my credentials?

Static scanning asks *"does this plugin look dangerous?"*
PLUGINFENCE asks **"what did this plugin actually attempt to do?"** - and answers it while it happens.

## What it does

| Capability | What PluginFence does |
| --- | --- |
| **Runtime interception** | A JVM agent rewrites sensitive call sites (`Files.*`, `FileInputStream`, `kotlin.io`, `System.getenv`, `ProcessBuilder`, `Runtime.exec`, `Socket.connect`, `URL.openConnection`, `HttpClient.send`, IntelliJ `VirtualFile` / `GeneralCommandLine` / `HttpRequests` ...) in **third-party plugin bytecode only**. 122 rules, listed in [`HookRules.java`](agent/src/main/java/com/pluginfence/agent/HookRules.java). |
| **Attribution** | Every event is tied to the plugin whose class loader defined the calling class - ID, name, version, vendor - read from the real plugin descriptor. When attribution is impossible it says *Unknown third-party plugin*, never a guess. |
| **Enforcement** | Per-plugin **ALLOW / ASK / BLOCK** for six capabilities: project files, files outside the project, sensitive files, environment secrets, network, system commands. Blocked operations never execute. |
| **Sensitive resource protection** | `~/.ssh`, `~/.aws`, `~/.kube`, `~/.docker`, `.npmrc`, `.pypirc`, `.git-credentials`, gcloud/azure/gnupg, `.env*`, `*.pem`, `*.key`, keystores, `credentials.*`, `secrets.*`; secret-looking environment variables (`*_TOKEN`, `*_API_KEY`, `*_SECRET`, `*_PASSWORD`, `AWS_*`, `OPENAI_API_KEY`, ...). Values are never read or shown - only names. |
| **Behavior baselines** | A deterministic profile per plugin **version**: capabilities used, hosts contacted, executables launched, sensitive-resource categories touched. No ML. |
| **Behavior drift** | When a new version appears, PluginFence shows exactly what it does that the previous version never did, with a risk rating and a *HIGH-RISK BEHAVIOR CHANGE* banner. |
| **Correlation** | Sequences matter: *sensitive file read → new outbound connection within 10 s* becomes a **POTENTIAL SECRET EXFILTRATION** incident and the network leg is blocked outright. |
| **Native UI** | A JetBrains-style tool window with Overview, Activity, Permissions and Drift tabs, plus notifications with **Allow Once / Always Allow / Keep Blocking**. |
| **Privacy** | Everything stays on the machine. No telemetry, no uploads, no secret values in logs, redacted URLs and command lines. |

## Screenshots

> Replace with real captures from `./gradlew runFenceIde` - see [docs/screenshots/README.md](docs/screenshots/README.md).

| Overview - incident attack chain | Drift - what changed after the update |
| --- | --- |
| ![Overview](docs/screenshots/overview.png) | ![Drift](docs/screenshots/drift.png) |

| Activity - firewall-style log | Permissions - per-plugin matrix |
| --- | --- |
| ![Activity](docs/screenshots/activity.png) | ![Permissions](docs/screenshots/permissions.png) |

## Why not just use AI?

An AI model can inspect code and *suggest* that an operation looks suspicious.
PLUGINFENCE observes the operation while it actually occurs and can *prevent* it.

The difference is prediction versus enforcement.

```text
AI:
"This plugin may access credentials."

PLUGINFENCE:
"Plugin com.example.demo-helper 1.1.0 attempted to read
~/.ssh/id_rsa at 10:41:03 via java.nio.file.Files.readString.

The operation was blocked."
```

No LLM is involved anywhere in PluginFence. Detection, attribution, policy, blocking, baselines,
drift and correlation are all deterministic and run locally.

## How it works

```text
┌─────────────────────────────────────────────────────────────┐
│ IntelliJ IDEA                                               │
│                                                             │
│  third-party plugin        PluginFence plugin (Kotlin)      │
│  ┌──────────────────┐      ┌─────────────────────────────┐  │
│  │ Files.readString │      │ policy engine   baselines   │  │
│  │ Socket.connect   │      │ correlation     drift       │  │
│  │ ProcessBuilder   │      │ persistence     tool window │  │
│  └────────┬─────────┘      └──────────────▲──────────────┘  │
│           │ rewritten by the agent        │ DecisionProvider │
│           ▼                               │                  │
│  ┌────────────────────────────────────────┴──────────────┐  │
│  │ com.pluginfence.bootstrap (boot class path)           │  │
│  │ GuardHooks → GuardBridge → decide() / record()        │  │
│  └────────────────────────────────────────▲──────────────┘  │
│                                           │ Boot-Class-Path  │
│  ┌────────────────────────────────────────┴──────────────┐  │
│  │ plugin-fence-agent.jar  (-javaagent, ASM rewriting)   │  │
│  │ PluginClassLoader → plugin identity → token           │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

1. **The agent** (`-javaagent:plugin-fence-agent.jar`) registers a `ClassFileTransformer`. For every
   class defined by an IntelliJ `PluginClassLoader` of a non-bundled plugin, it rewrites matching
   invocations so they go through `GuardHooks` - e.g. `Files.readString(path)` becomes
   `GuardHooks.Files_readString(path, token, "com.example.Caller")`. The token identifies the
   plugin (resolved once per class loader from its descriptor). Platform, JDK and bundled
   JetBrains plugin classes are never touched.
2. **The bootstrap bridge** is a tiny dependency-free jar appended to the boot class path, so a
   single `GuardBridge` is visible from every class loader in the JVM. Hooks build a redacted
   `SecurityRequest`, ask the registered `DecisionProvider`, record the event, and either run the
   original operation or throw `SecurityException` (blocked environment reads simply return `null`).
   A thread-local re-entrancy guard keeps PluginFence from intercepting itself. If no provider is
   registered, operations are allowed and buffered - PluginFence never bricks the IDE.
3. **The IntelliJ plugin** registers the provider at startup, evaluates policy synchronously
   (in-memory, microseconds), and processes events on a background thread: baselines, drift,
   correlation, notifications, bounded persistence and UI updates.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Policy precedence

```text
 1. trusted platform plugin (PluginFence itself, bundled or JetBrains plugins) → allow, monitor
 2. one-time grant ("Allow Once")
 3. explicit user BLOCK for the capability
 4. explicit user ALLOW for the capability
 5. "Always Allow" approval of the specific target (host, executable, directory, variable)
 6. sensitive credential file                          default BLOCK
 7. secret environment variable                        default BLOCK
 8. network / process within 10 s of sensitive access  BLOCK (correlated exfiltration)
 9. process execution                                  default ASK
10. project file                                       default ALLOW
11. file outside open projects                         default ASK   (IDE-managed dirs: allow)
12. network: loopback allowed, everything else         default ASK
13. fallback                                           allow + monitor
```

### Risk scoring (deterministic)

```text
Credential store (ssh, aws, kube, docker, git, gcloud, npm, pypi, netrc, gpg, azure)  +60
Other sensitive file (.env, *.pem, *.key, keystores, credentials.*, secrets.*)         +50
Secret environment variable                                                             +45
Process execution                                                                       +25
  + shell / interpreter / downloader executable                                         +15
Network: new destination                                                                +20
  + raw IP destination                                                                  +20
  + plaintext protocol                                                                  +20
File outside open projects                                                              +15
New behaviour after update (not seen in the previous version)                           +20
Sensitive access → network / process within 10 s                                        +50

0-19 INFO · 20-39 LOW · 40-59 MEDIUM · 60-79 HIGH · 80-100 CRITICAL
```

### ASK semantics

ASK never freezes the IDE waiting for a dialog. The operation is prevented immediately, the
plugin receives a `SecurityException`, and a notification offers **Allow Once**, **Always Allow**
and **Keep Blocking**. The developer retries the plugin action afterwards.

## Requirements

- JDK 17+ to run Gradle (the build auto-provisions JDK 25, which IntelliJ 2026.2 requires)
- ~2 GB of disk for the IntelliJ IDEA 2026.2.3 distribution the build downloads
- Windows, macOS or Linux

## Build

```bash
./gradlew test               # unit tests: bootstrap, agent (in-process rewriting), plugin logic
./gradlew :agent:agentTest   # integration tests in a forked JVM started with the real -javaagent
./gradlew build              # everything, incl. plugin configuration verification
./gradlew buildPlugin        # intellij-plugin/build/distributions/intellij-plugin-<version>.zip
./gradlew assembleAgent      # agent/build/dist/plugin-fence-agent.jar + plugin-fence-bootstrap.jar
./gradlew buildDemoPlugins   # build/demo/demo-helper-1.0.0.zip and demo-helper-1.1.0.zip
```

Windows: use `gradlew.bat` with the same tasks (or `scripts\*.ps1`).

## Run

```bash
./gradlew runFenceIde            # sandbox IDE + agent + PluginFence + Demo Helper 1.0.0 (benign)
./gradlew runFenceIdeUpdated     # same sandbox + Demo Helper 1.1.0 (suspicious) → drift + incidents
```

Both tasks share one sandbox (`intellij-plugin/build/idea-sandbox/IU-2026.2.3/`), so policies and
baselines recorded in the first run are still there in the second. The repository itself is opened
as the project. Use `-PpluginfenceDebug=true` for verbose agent diagnostics in the console.

### Installing into your own IDE

1. Build: `./gradlew buildPlugin assembleAgent`.
2. Install `intellij-plugin/build/distributions/intellij-plugin-*.zip` via
   *Settings → Plugins → ⚙ → Install Plugin from Disk*.
3. Copy `agent/build/dist/plugin-fence-agent.jar` **and** `plugin-fence-bootstrap.jar` to a
   permanent directory (they must stay side by side).
4. *Help → Edit Custom VM Options*, add `-javaagent:/path/to/plugin-fence-agent.jar`, restart.

The Overview tab shows *Protection ACTIVE* when the agent is attached; otherwise it shows exactly
these setup steps.

## Demo walkthrough (2-3 minutes)

The scripted version of this walkthrough runs unattended: `scripts/smoke-test.sh` (or `.ps1`).
The narrated version is in [docs/DEMO.md](docs/DEMO.md).

1. `./gradlew runFenceIde` - Overview shows **Protection ACTIVE**, plugin classes instrumented.
2. In the *Demo Helper* tool window click **Normal Behavior**: reads a project file, calls a
   loopback service. Activity shows two ALLOWED rows; Drift shows *BASELINE ESTABLISHED* for 1.0.0.
3. Close the IDE, `./gradlew runFenceIdeUpdated` - Demo Helper is now **1.1.0** (a real, different
   build of the same plugin ID with extra actions).
4. **Attempt Secret Read** → `demo-fixtures/home/.ssh/id_rsa` (fake) is **BLOCKED**, risk CRITICAL
   (credential store + new behaviour after update). Notification appears.
5. **Attempt Network Exfiltration** → `198.51.100.42:8080` prevented (ASK, new raw-IP destination).
6. **Attempt Process Execution** → `java -version` prevented (ASK). Click **Allow Once** in the
   notification, click the button again → ALLOWED, recorded.
7. **Run Attack Sequence** → secret read, then a connection 0.6 s later: the network leg is
   **BLOCKED by the correlation rule** and Overview shows *Potential secret exfiltration* with the
   attack chain and risk 100 / CRITICAL.
8. Open **Drift**: `1.0.0 → 1.1.0`, *HIGH-RISK BEHAVIOR CHANGE*, NEW: sensitive file access (ssh),
   network 198.51.100.42, process java.
9. Open **Permissions**: change any capability for Demo Helper; it applies immediately and persists.

Everything the demo touches is fake: see [demo-fixtures/README.md](demo-fixtures/README.md).

## Threat model and limitations

PluginFence is a **runtime policy-enforcement and behavioural-monitoring proof of concept**, not a
mathematically isolated sandbox. It rewrites a documented list of JVM call sites in third-party
plugin bytecode. It does **not** protect against:

- JNI / native code, `sun.misc.Unsafe`, reflection into JDK internals, or `MethodHandle` tricks
  that avoid the rewritten call sites
- code that tampers with the agent, the bootstrap bridge, or the IDE's VM options
- I/O APIs that are not in the rule table (e.g. memory-mapped files, `java.nio.channels.FileChannel.open`)
- exploitation of the IDE platform itself, or anything below the JVM (kernel, other processes)
- a plugin that delegates dangerous work to *platform* code paths that are not instrumented

Trust is inferred only from class-loader evidence, never from package names. Bundled and
JetBrains-vendor plugins are treated as part of the platform (monitored, not enforced by default) -
you can change that per plugin. Production-strength isolation would need cooperation from the
platform or process-level sandboxing. Full details: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Technology

| Component | Stack |
| --- | --- |
| Target platform | IntelliJ IDEA 2026.2.3 (build 262), JetBrains Runtime 25 |
| Build | Gradle 9.7.1 (Kotlin DSL), IntelliJ Platform Gradle Plugin 2.19.0, Kotlin 2.4.20, foojay toolchains (auto-provisions JDK 25) |
| Agent | Java 17 bytecode, `java.lang.instrument`, ASM 9.10.1 (shaded), no other dependencies |
| Bootstrap | Pure Java, zero dependencies, ~40 KB |
| Plugin | Kotlin, IntelliJ Platform UI (JB components, `PersistentStateComponent`, message bus, notifications) |
| Tests | JUnit 5; agent tests run in a forked JVM with the real `-javaagent` and verify rewritten bytecode with ASM's `CheckClassAdapter` |
| CI | GitHub Actions: tests, agent integration tests, build, plugin configuration verification |

## Repository layout

```text
bootstrap/          boot-class-path bridge: GuardBridge, GuardHooks, SecurityRequest, redaction
agent/              -javaagent: PluginClassLoader attribution, HookRules table, ASM call-site rewriter
intellij-plugin/    PluginFence plugin: policy, baselines, drift, correlation, persistence, UI
demo-plugin/        Demo Helper 1.0.0 (benign) + the "update" source set used by 1.1.0
demo-plugin-update/ Demo Helper 1.1.0 build (same plugin ID, adds the suspicious actions)
demo-fixtures/      fake ~/.ssh/id_rsa etc. used by the demo
scripts/            run-demo, smoke-test (unattended end-to-end), sandbox state checker
docs/               ARCHITECTURE.md, THREAT_MODEL.md, DEMO.md, screenshots/
```

## Future work

- Configuration UI for sensitive-path and secret-variable rules (the classifier is data-driven today)
- Export incidents as JSON; searchable timeline across sessions
- Organisation-wide signed policy packs
- OS-level companions (eBPF / Endpoint Security / ETW) for native and out-of-process activity
- Marketplace metadata correlation (vendor, release cadence, permission drift across releases)
- Optional local explanation of incidents - never required for detection

## License

MIT - see [LICENSE](LICENSE).
