# Demo fixtures

Fake files used by the **Demo Helper** plugin so the PluginFence demo never touches real
credentials.

| Path | Purpose |
| --- | --- |
| `home/.ssh/id_rsa` | Fake SSH private key (`FAKE_PLUGINFENCE_DEMO_PRIVATE_KEY`) |
| `home/.aws/credentials` | Fake AWS credentials file |
| `project/notes.txt` | Ordinary project file for the benign "Normal Behaviour" action |

The demo plugin locates this directory through the `pluginfence.demo.fixtures` system property
(set automatically by `./gradlew runFenceIde`). Without it, the plugin extracts bundled copies of
these fixtures into the IDE's system directory.
