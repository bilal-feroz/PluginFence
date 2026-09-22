# PluginFence demo script (2-3 minutes)

Everything in this demo is safe: the "secret" is `demo-fixtures/home/.ssh/id_rsa`, a text file
containing `FAKE_PLUGINFENCE_DEMO_PRIVATE_KEY`; the "exfiltration" target is `198.51.100.42`, an
RFC 5737 documentation address that is not routable; the "process" is the IDE's own
`java -version`. See [demo-fixtures/README.md](../demo-fixtures/README.md).

## Before the demo

```bash
./gradlew build buildDemoPlugins        # once; downloads IntelliJ IDEA 2026.2.3 and JDK 25
./gradlew :intellij-plugin:cleanSandbox # optional: start from an empty sandbox (no baselines)
```

Have two terminals ready. The sandbox IDE opens this repository as its project.

If you want a fully unattended dry run first: `scripts/smoke-test.sh` (or `.ps1`) performs the
whole flow below without clicks and asserts on the persisted result. If a local Ollama is running
it also exercises the AI analyst against it and checks that every analysis completed.

**AI analyst setup (do this before the demo):** *Settings → Tools → PluginFence* → enable, paste
the OpenAI key from the hackathon vault (it lands in the IDE credential store, not in a file), keep
model `gpt-4.1-mini`, click *Test Connection*. For an offline demo point the endpoint at
`http://localhost:11434/v1` with an Ollama model instead. Settings live in the sandbox, so do this
once in the Act 1 IDE and they carry over to Act 2. Optionally tick *Automatically analyse CRITICAL
incidents* so the analyst starts on its own the moment the attack sequence fires.

## Act 1 - a normal plugin (Demo Helper 1.0.0)

```bash
./gradlew runFenceIde
```

1. **Open the PluginFence tool window** (bottom tool bar, shield icon, or *Tools → PluginFence →
   Show PluginFence*).
   - Overview: **Protection ACTIVE**, "N plugin classes instrumented, M call sites guarded,
     122 interception rules".
   - Permissions: *Demo Helper 1.0.0* is listed with the default matrix
     (Project files ALLOW, Outside project ASK, Sensitive files BLOCK, Environment secrets BLOCK,
     Network ASK, System commands ASK).
2. **Open the Demo Helper tool window** (right tool bar). It has one button: **Normal Behavior**.
3. Click **Normal Behavior**. The plugin reads a project file and calls its own loopback HTTP
   service. Demo Helper's log says `OK`.
   - Activity: two rows, *File read* and *Network*, both **ALLOWED**, risk Info.
   - Drift: **BASELINE ESTABLISHED** - Demo Helper 1.0.0: Project files ✓, Network ✓, host 127.0.0.1.

> Say: "This is what a well-behaved plugin looks like. PluginFence has now learned what version
> 1.0.0 does."

4. Close the IDE (File → Exit). Baselines, policies and history are persisted in the sandbox.

## Act 2 - the update (Demo Helper 1.1.0)

```bash
./gradlew runFenceIdeUpdated
```

This installs **Demo Helper 1.1.0** into the same sandbox - a genuinely different build of the
same plugin ID with four extra actions. From PluginFence's point of view this is a plugin update.
(Untested alternative without Gradle: *Settings → Plugins → ⚙ → Install Plugin from Disk →
`build/demo/demo-helper-1.1.0.zip`*; the IDE may require a restart. The Gradle path above is the
one the smoke test exercises.)

5. Open the Demo Helper tool window: it now shows **1.1.0** and five buttons.
6. Click **Attempt Secret Read**.
   - Demo Helper's log: `DENIED: PluginFence blocked file read of …/demo-fixtures/home/.ssh/id_rsa`.
   - A notification: *PluginFence blocked Demo Helper* - the plugin attempted to access `…/.ssh/id_rsa`.
   - Activity: *File read* `…/.ssh/id_rsa` **BLOCKED**, **Critical 80** - risk factors
     *Credential store: SSH keys +60* and *New behaviour after update (1.0.0 → 1.1.0) +20*.
   - Overview: incident *Sensitive credential access blocked*.

> Say: "The plugin called `Files.readString`. The call never reached the file system. PluginFence
> knows which plugin, which version, which API, and that 1.0.0 never did this."

7. Click **Attempt Network Exfiltration**.
   - `DENIED` in the plugin log; Activity: *Network* `198.51.100.42:8080` prevented - either
     **BLOCKED** by the correlation rule (if you clicked within 10 s of step 6) or **ASK** as a
     new raw-IP destination, with a notification offering *Allow Once / Always Allow / Keep Blocking*.
8. Click **Attempt Process Execution**.
   - *Process* `java.exe` prevented with **ASK**. In the notification click **Allow Once**, then
     click the button again → `OK: exit 0: openjdk version …`, Activity shows **ALLOWED**.

> Say: "ASK never freezes the IDE. The operation is prevented, you decide, the plugin retries."

9. Click **Run Attack Sequence** - the money shot.
   - The plugin tries to read the key, then 0.6 s later opens a connection to a new server.
   - Overview: **Potential secret exfiltration**, **CRITICAL 100**, with the attack chain:
     `1. File read …/.ssh/id_rsa BLOCKED` ↓ `2. Network 198.51.100.42:8080 BLOCKED` ↓
     *Network blocked - Risk 100 / CRITICAL*.
   - A sticky notification announces the incident.

> Say: "Individually these could be explained away. PluginFence correlates them: secret access
> followed by a brand-new destination inside ten seconds is exfiltration, and the network leg was
> blocked outright - not just reported."

9b. Under the attack chain, click **Analyse with AI** (requires the analyst to be configured - see
    *Before the demo*).
    - The card shows the investigation live, one tool call at a time:
      `get_incident(...)`, `get_plugin_profile(...)`, `get_behavior_drift(...)`,
      `get_plugin_manifest(...)`, `get_policy(...)` - each with a one-line summary of what came back.
    - Then the verdict pill (**MALICIOUS** / **SUSPICIOUS** / ...), a headline, a plain-English
      narrative that cites the events, the evidence list, and **Recommended policy** rows such as
      *NETWORK → BLOCK*, *SENSITIVE FILES → BLOCK*.
    - Click **Apply N changes**, then open **Permissions**: the matrix now shows those decisions as
      *custom*. Click **Show investigation** to reveal the full trace.

> Say: "The model did not block anything - PluginFence did that in microseconds, deterministically.
> What the model adds is the investigation: it pulled the plugin's own manifest and can say that a
> plugin describing itself as a helper has no business in ~/.ssh. It explains and proposes; you
> decide; PluginFence enforces."

> If the model is a small local one (Ollama `llama3.2:3b`) it may answer in prose without a
> structured verdict - the card then shows *INCONCLUSIVE* with the model's text. With
> `gpt-4.1-mini` or better you get the full structured result in a few seconds.

10. Open **Drift**.
    - Below the header there is a second analyst entry point, **Review this update with AI**, which
      asks the same agent the update-specific question. Same on **Permissions** (*Generate trust report*).
    - Headline **3 NEW CAPABILITIES**, banner **HIGH-RISK BEHAVIOR CHANGE**,
      `Demo Helper 1.0.0 → 1.1.0 - risk 100/100 CRITICAL`.
    - Table: Project files ✓ ✓ UNCHANGED, Network ✓ ✓ UNCHANGED, 127.0.0.1 ✓ ✓ UNCHANGED,
      **Sensitive files - ✓ NEW**, **ssh - ✓ NEW**, **198.51.100.42 - ✓ NEW**, **System commands - ✓ NEW**,
      **java.exe - ✓ NEW**.

> Say: "This is the question every update should answer: what can this version do that the
> previous one never did?"

11. Open **Permissions**, select Demo Helper, set *Network* to **BLOCK** - it applies immediately,
    is marked *custom*, and persists across restarts. Optionally set *Sensitive files* to ALLOW and
    repeat step 6 to show the explicit user override winning (and the read now being ALLOWED but
    still rated Critical).

## If something looks off

| Symptom | Check |
| --- | --- |
| Overview says *Agent not active* | The IDE was started without `-javaagent`. Use `runFenceIde`, or add the VM option shown in the panel. |
| Demo Helper still shows 1.0.0 in Act 2 | `runFenceIdeUpdated` syncs the sandbox plugins directory; make sure the Act 1 IDE was closed first. |
| No drift after Act 2 | The baseline from Act 1 must exist: check `intellij-plugin/build/idea-sandbox/IU-2026.2.3/config/options/pluginfence.xml` contains a 1.0.0 profile. |
| Nothing recorded | Look for `[PluginFence]` lines in the Gradle console (`-PpluginfenceDebug=true` prints every instrumented class). |

## Reset

```bash
./gradlew :intellij-plugin:cleanSandbox   # wipe sandbox: baselines, policies, history, installed plugins
```

Or in the IDE: *Tools → PluginFence → Clear Activity History / Reset Behavior Baselines*.
