# PluginFence architecture

PluginFence is two cooperating pieces plus a tiny shared bridge:

| Piece | Module | Loaded by | Responsibility |
| --- | --- | --- | --- |
| **JVM agent** | `agent/` | system class path (`-javaagent`) | intercept, attribute, rewrite bytecode |
| **Bootstrap bridge** | `bootstrap/` | boot class path (`Boot-Class-Path`) | the one shared rendezvous point: hooks, requests, decisions |
| **IntelliJ plugin** | `intellij-plugin/` | IntelliJ `PluginClassLoader` | policy, baselines, drift, correlation, persistence, UI |

```mermaid
flowchart LR
    subgraph JVM["IntelliJ IDEA JVM"]
        direction TB
        P["Third-party plugin bytecode<br/>(rewritten at class-load time)"]
        H["GuardHooks / GuardBridge<br/><i>boot class path</i>"]
        E["FenceEngine (DecisionProvider)<br/>PolicyEngine · BaselineEngine<br/>CorrelationEngine · FenceState"]
        UI["PluginFence tool window<br/>Overview · Activity · Permissions · Drift"]
        A["plugin-fence-agent.jar<br/>FenceTransformer · CallSiteRewriter<br/>PluginLoaderRegistry"]
        P -- "GuardHooks.Files_readString(path, token, caller)" --> H
        H -- "decide(SecurityRequest)" --> E
        E -- "SecurityDecision" --> H
        H -- "record(request, decision)" --> E
        E -- "message bus (batched)" --> UI
        A -. "rewrites classes defined by<br/>PluginClassLoader" .-> P
        A -. "registers PluginIdentity → token" .-> H
    end
    OS["Files · Network · Processes · Environment"]
    H -- "only if allowed" --> OS
```

## 1. JVM agent (`com.pluginfence.agent`)

**Entry point:** `PluginFenceAgent.premain` (also `agentmain`). The manifest declares
`Premain-Class`, `Can-Retransform-Classes` and `Boot-Class-Path: plugin-fence-bootstrap.jar`,
which the JVM resolves next to the agent jar and appends to the boot class path *before*
`premain` runs. `premain` verifies that `com.pluginfence.bootstrap.GuardBridge` is defined by the
boot loader (falling back to `Instrumentation.appendToBootstrapClassLoaderSearch` if the sibling
jar was found but not applied) and only then references any bootstrap type - a broken install
degrades to "agent not active" rather than a startup failure.

**Selection (`PluginLoaderRegistry`).** The transformer is called for every class the JVM
defines. A class is a candidate only if its defining loader is an IntelliJ
`com.intellij.ide.plugins.cl.PluginClassLoader`. The loader's `getPluginId()` and
`getPluginDescriptor()` (name, version, vendor, `isBundled`) are read reflectively - the agent has
no compile-time dependency on IntelliJ - **once per loader**, cached in a weak map, and turned into
a `PluginIdentity` registered with `GuardBridge`, which returns an integer **token**.
PluginFence's own plugin and bundled plugins are skipped (`pluginfence.agent.instrumentBundled=true`
overrides the latter). Nothing is inferred from package names; trust comes from class-loader
evidence only. For tests, `pluginfence.agent.instrument.packages` forces instrumentation of given
packages with a synthetic identity.

**Rewriting (`HookRules`, `CallSiteRewriter`, `FenceTransformer`).** Only *invocation
instructions* inside candidate classes are touched; the platform, the JDK and the Kotlin stdlib
are never modified. Two shapes exist:

| Shape | Bytecode change | Used for |
| --- | --- | --- |
| **REPLACE** | `invokestatic/virtual Owner.m(desc)` → push `token`, `ldc callerClass`, `invokestatic GuardHooks.Owner_m(desc + I + String)`. Receiver of instance calls becomes the first parameter. The hook performs the original operation. | JDK APIs whose types are visible from the boot class path (`Files.*`, `Runtime.exec`, `ProcessBuilder.start`, `Socket.connect`, `URL.openConnection`, `System.getenv`, …) and `kotlin.io` functions, whose originals are invoked through the plugin's class loader (`Delegates`). |
| **PRECHECK** | `dup` (or `swap; dup; …; swap`, or `dup2`) the relevant argument, push `token`, caller and API name, `invokestatic GuardHooks.check*(Object, …)`; the original instruction stays. | Constructors (`new FileInputStream(File)`), receiver calls (`file.delete()`), and IntelliJ / `java.net.http` APIs whose types the boot class path cannot see (`VirtualFile.contentsToByteArray`, `GeneralCommandLine.createProcess`, `HttpRequests.request`, `HttpClient.send`). The hook reads what it needs reflectively via public exported supertypes. |

Both shapes are stack-neutral, add no branches or locals, and therefore keep existing
`StackMapTable` frames valid; `ClassWriter.COMPUTE_MAXS` recomputes the max stack without loading
any class (`COMPUTE_FRAMES` is deliberately avoided inside a class-load hook). ASM is shaded to
`com.pluginfence.agent.shaded.asm` so it can never clash with the IDE's own ASM. A thread-local
guard prevents re-entrant transformation while the registry's reflection loads classes. Any
failure leaves the class untouched and is counted in the diagnostics.

## 2. Bootstrap bridge (`com.pluginfence.bootstrap`)

Dependency-free Java 17 classes (~40 KB):

- `GuardHooks` - the static entry points named in the rule table. Each builds a redacted
  `SecurityRequest` (path normalised, URL user-info and credential-looking query parameters
  masked, command-line arguments sanitised, environment values never read), calls
  `GuardBridge.decide`, then `GuardBridge.record`, and either runs the original operation or
  throws `SecurityException` - the exception type the JDK documents for security-manager denials
  of the same APIs. Blocked `System.getenv(name)` returns `null` and enumerated environments are a
  filtered view (`GuardedEnvironment`), so plugins degrade gracefully instead of crashing.
- **Re-entrancy guard**: a thread-local depth counter. While a check runs, nested hooks pass
  through; the original operation always executes *outside* the guard so plugin callbacks (Kotlin
  lambdas in `forEachLine`, for example) stay protected. `GuardHooks.withGuard` lets the control
  plane persist and log without ever intercepting itself.
- `GuardBridge` - the process-wide singleton: provider registration, identity tokens,
  enforcement kill-switch, counters, agent diagnostics, and a bounded (512) buffer of events
  recorded before the control plane registered. With no provider every operation is allowed
  (`MONITOR` verdict) - PluginFence never bricks the IDE.
- `SecurityRequest`, `SecurityDecision`, `PluginIdentity`, `OperationType`, `Verdict`,
  `RiskLevel`, `DecisionProvider` - the contract.
- `Redactor`, `Targets`, `Delegates` - sanitisation, reflective target description, and
  reflective invocation of originals that live outside the boot class path.

Why the boot class path? IntelliJ's plugin class loaders are self-first and isolated from each
other; the only loader every class can reach is the bootstrap loader. That gives exactly one
`GuardBridge` per JVM, with one class identity. It also imposes a rule the code follows
carefully: bootstrap code may only reference `java.base` types (e.g. `java.net.http` is *not*
visible there, which is why `HttpClient.send` uses the pre-check shape).

## 3. IntelliJ plugin (`com.pluginfence`)

`FenceEngine` is an application-level service and the `DecisionProvider`. The plugin uses only
public platform API (the Plugin Verifier reports no internal, experimental or deprecated usages);
in particular the list of governed plugins comes from the identities the agent registered while
resolving plugin class loaders, not from the (internal) plugin registry API. The plugin compiles
against `bootstrap` with `compileOnly` and must **not** bundle it (a second copy inside the plugin
loader would break class identity). The domain model (`com.pluginfence.model`) has no bootstrap
dependency at all, and `AgentBridge` is the single class that touches bootstrap types; it is only
instantiated when `AgentProbe` finds `GuardBridge` on the boot loader. So the plugin, its UI and
its history work in "agent not active" mode too.

### Decision path (synchronous, on the intercepted thread)

`PolicyEngine.evaluate` does in-memory lookups only:

1. classify the target - `SensitivePathClassifier` (data-driven rules: directory segments, path
   suffixes, file names, extensions; separators normalised, `~` expanded, case-insensitive, no
   filesystem access), `SecretEnvClassifier`, `PathScope` (open project roots vs IDE-managed
   directories), `NetworkClassifier` (loopback, raw IP, plaintext), `ProcessClassifier`;
2. add context - `BaselineEngine.previousVersionProfile` (new behaviour after update) and
   `CorrelationEngine.recentSensitiveAccess` (sensitive access within the 10 s window);
3. sum deterministic `RiskFactor`s (clamped 0-100) and apply the precedence documented in the
   README (trusted → grant → user BLOCK → user ALLOW → approval → sensitive → secret env →
   correlation → process → project → outside → network → fallback).

`FenceEngine.record` (also synchronous) builds the immutable `FenceEvent`, notes sensitive
accesses in the correlation window immediately (so the next decision on any thread sees them),
and enqueues the event.

### Background path (single bounded executor)

Batches of events are appended to the bounded in-memory history, fed to `BaselineEngine.observe`
(profile per plugin+version; drift computed against the most recently seen other version),
`CorrelationEngine.observe` (incidents with the full attack chain), `FenceNotifier` (aggregated
IntelliJ notifications with Allow Once / Always Allow / Keep Blocking), persisted through
`FenceState` / `FenceHistory` (`PersistentStateComponent`, roaming disabled, bounded), and
published on the application message bus (`FenceListener`). The tool window coalesces
notifications with a 200 ms alarm and re-reads snapshots on the EDT - no UI work per event.

### Persistence

| File | Content |
| --- | --- |
| `pluginfence.xml` | settings, per-plugin policies (overrides + always-allowed targets), behaviour profiles, drift reports |
| `pluginfence-history.xml` | last 500 events and 100 incidents |

Both live in the IDE config directory; nothing leaves the machine.

### UI

`FenceToolWindowFactory` hosts four tabs built from JetBrains UI components (`JBTable`,
`JBList`, `OnePixelSplitter`, `SimpleToolWindowPanel`, `JBColor`, `AllIcons`):
**Overview** (status, stat cards, incidents with the attack-chain view), **Activity** (filterable
event table with details and permission actions), **Permissions** (per-plugin ALLOW/ASK/BLOCK
matrix, always-allowed targets), **Drift** (version comparison table with NEW / UNCHANGED /
REMOVED and a high-risk banner).

## 4. Demo plugin

`demo-plugin/` builds **Demo Helper 1.0.0** (one benign action). `demo-plugin-update/` compiles the
same sources plus `src/update/` and its own `plugin.xml` into **Demo Helper 1.1.0** - a genuinely
different build of the same plugin ID. `runFenceIde` / `runFenceIdeUpdated` install one or the
other into a shared sandbox, so the second run is a real plugin update from PluginFence's point of
view. A `pluginfence.demo.autorun` property lets scripts drive the actions unattended
(`scripts/smoke-test.*`), and `scripts/check-sandbox-state.py` asserts on the persisted result.

## 5. Sequence: the attack chain

```mermaid
sequenceDiagram
    participant D as Demo Helper 1.1.0
    participant H as GuardHooks (boot)
    participant E as FenceEngine
    participant U as Tool window / notifications
    D->>H: Files_readString(~/.ssh/id_rsa, token, caller)
    H->>E: decide(FILE_READ …/.ssh/id_rsa)
    E-->>H: BLOCK  sensitive.default  risk 80
    H->>E: record(…)
    E-)E: correlation window += sensitive access
    H-->>D: SecurityException (contents never read)
    D->>H: Socket_connect(198.51.100.42:8080, token, caller) [0.6 s later]
    H->>E: decide(NETWORK_CONNECT 198.51.100.42:8080)
    E-->>H: BLOCK  correlation.exfiltration  risk 100
    H-->>D: SecurityException (socket never connects)
    E-)U: incident POTENTIAL_SECRET_EXFILTRATION, drift 1.0.0 → 1.1.0
```
