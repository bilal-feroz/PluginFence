# PluginFence - presentation script

A spoken script for a 3-minute judged demo. **Bold** = say it. `[brackets]` = do it.
Everything here is true of the build in this repository; nothing is aspirational.

Target: 3:00. If you are running long, cut Act 1 to a single sentence and skip the Permissions tab
at the end - the attack chain, the AI analyst and the Drift tab are the parts that win.

---

## 0:00 - Cold open (20s)

`[PluginFence tool window open on Overview, agent active, no incidents yet]`

> **Your browser asks before a website uses your camera.**
> **Your phone asks before an app reads your location.**
> **Your IDE plugins ask for nothing.**

> **They run with the same access as the IDE itself - on the machine where you keep your source
> code, your SSH keys, your cloud credentials and your production access. JetBrains says so in
> their own docs: the IDE does not restrict plugins with permissions, and does not sandbox them.**

> **In June, fifteen Marketplace plugins were caught using exactly that. They posed as AI
> assistants and shipped developers' API keys to a server over plain HTTP.**

> **We built the missing permission layer. It's called PluginFence.**

---

## 0:20 - Act 1: a normal plugin (25s)

`[Demo Helper tool window -> click "Normal Behavior"]`

> **This is an ordinary third-party plugin. It reads a project file, calls its own backend.**

`[PluginFence -> Activity: two ALLOWED rows]`

> **PluginFence saw both, attributed them to the plugin by name and version, and allowed them -
> that's what the policy says to do.**

`[Drift tab -> BASELINE ESTABLISHED]`

> **And it has now learned what version 1.0.0 does. Remember that.**

---

## 0:45 - Act 2: the update (60s)

`[Already running the 1.1.0 IDE. Demo Helper now shows five buttons]`

> **Same plugin. Same plugin ID. One version later. Now watch.**

`[Click "Run Attack Sequence"]`

> **It just tried to read a private SSH key - and then, six tenths of a second later, open a
> socket to a server it had never contacted before.**

`[Overview -> the incident is already there]`

> **PluginFence blocked both. Not warned - blocked. The read never reached the file system, the
> socket never connected.**

`[Point at the attack chain]`

> **And it didn't just log two events. It correlated them. Credential access, followed by a brand
> new destination, inside ten seconds - that's the exfiltration pattern, and that's why this is
> rated one hundred out of a hundred, critical.**

> **This is the difference between prediction and enforcement. A scanner tells you a plugin
> *might* be dangerous. This tells you what it *did*, names the plugin, the version and the exact
> API - and stops it while it happens.**

---

## 1:45 - The AI analyst (50s)

`[Click "Analyse with AI" under the chain]`

> **Now - the brief asked for an AI solution, and here's where we think AI actually belongs.**

`[Tool calls appear one by one - let them land, don't talk over them]`

> **It's not a prompt wrapped around a button. It's an agent, and it's deciding what evidence it
> needs: the incident, the plugin's behaviour baseline, what changed in the update - and the
> plugin's own manifest, straight out of its jar.**

`[Verdict banner appears]`

> **There it is. It read the plugin's own description, compared it to what the plugin actually
> did, and concluded: a plugin that calls itself a helper has no business in dot-ssh.**

`[Point at the proposed policy rows]`

> **And it doesn't just explain. It proposes the exact permissions this plugin should have -
> Network: ask becomes block.**

`[Click "Apply" -> Permissions tab]`

> **One click. Applied and persisted.**

> **But notice what the model did *not* do. It didn't block anything. PluginFence did that in
> microseconds, deterministically, before the model was even called. The AI explains and proposes.
> You decide. PluginFence enforces.**

---

## 2:35 - Drift (15s)

`[Drift tab]`

> **Last thing. This is the question nobody can answer today: what does this update do that the
> last version never did?**

> **Three new capabilities. Sensitive file access. A raw IP address. Process execution. None of
> which existed in 1.0.0. That's how you catch a plugin that went bad in an update, instead of one
> that was born bad.**

---

## 2:50 - Close (15s)

> **PluginFence is a JVM agent that rewrites a hundred and twenty-two sensitive call sites inside
> third-party plugin bytecode only, a deterministic policy engine, and an AI analyst on top.
> Real tests, CI, a threat model that's honest about what it can't stop.**

> **Don't guess what a plugin might do. Watch what it does - and stop it.**

---

# Q&A prep

Short, confident answers. Say "we didn't do that" when you didn't.

**"Is this a real sandbox?"**
> No, and we say so in the threat model. It's runtime policy enforcement over a documented list of
> call sites. JNI, Unsafe, and APIs outside our table can bypass it. Real isolation needs help from
> the platform - that's the honest answer.

**"Why not just use the Plugin Verifier / static scanning?"**
> JetBrains said it themselves after the June incident: the Verifier was built as a compatibility
> checker, not a malware scanner. And static analysis can't see what a plugin does at runtime after
> an update. We watch the actual call.

**"What does the AI actually add? Could you drop it?"**
> The findings come from our deterministic engine either way - and if the model is down, it still
> produces the verdict from our own rules. What the model adds is the reasoning a human would do:
> reading the plugin's manifest, comparing claim against behaviour, and turning it into a policy
> you can apply in one click.

**"Did you use Koog?"**
> No. Koog pulls Ktor and kotlinx into the IDE process next to the platform's own copies, and a
> classloader clash during a demo wasn't worth it. The loop is about 150 lines and does the same
> pattern - tool calls, structured output. Koog is the natural next step.

**"What happens on a rate limit?"**
> It falls through: backup model, then our own deterministic rules. We tested it against a dead
> endpoint - full verdict in 52 milliseconds. There's no error state.

**"What gets sent to the model?"**
> Only what we already record: plugin IDs, versions, manifests, redacted paths, host names,
> verdicts. We never capture file contents or environment values in the first place, so they can't
> leak. Point it at a local Ollama and nothing leaves the machine at all.

**"Does it slow the IDE down?"**
> Policy is an in-memory lookup on the calling thread - microseconds. Everything else - baselines,
> correlation, UI - is on a background thread. We only rewrite third-party plugin classes, never
> the JDK or the platform.

**"How do you know which plugin did it?"**
> The class loader. IntelliJ gives every plugin its own, and we read the plugin descriptor from it.
> If we can't prove which plugin it was, we say "unknown third-party plugin" - we never guess.

---

# If something goes wrong

| Problem | Do this |
| --- | --- |
| AI shows "PluginFence rules - no model involved" | Don't apologise. **"That's the fallback - the model is unreachable, and it still produced the verdict, because the evidence was always local."** |
| Drift tab shows nothing | Sandbox wasn't clean. Say **"let me show you the incident instead"** and stay on Overview. |
| Analysis takes >15s | Talk over it: explain that every tool call is read-only and the model can't change anything. |
| Anything red appears | **"That's the sandbox IDE, not the plugin"** - then move on. Don't debug on stage. |
