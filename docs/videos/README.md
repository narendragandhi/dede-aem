# Demo videos

Three terminal videos (1280×720, H.264) and an overview that joins them. They are rendered, not
screen-recorded: `tools/render_terminal.py` draws each frame and pipes it to ffmpeg, so they can
be regenerated after any change, identically.

**Everything shown is real output** from the commands shown, run on Adobe's WKND sample and
aem-core-wcm-components. Trimmed output is marked `...`; long package paths are shortened with `…`.

| Video | Length | Shows |
|---|---|---|
| `01-what-actually-runs` | ~41 s | static scan → generated OTel config → traces + request.log joined |
| `02-real-sites` | ~30 s | 0 → 32 of 35 requests attributed once the authored content is added |
| `03-gate-and-ask` | ~29 s | `--fail-on CRITICAL` failing a build; MCP tool calls |
| `dede-overview` | ~100 s | all three |

## Regenerate

```bash
./record-demo.sh /path/to/aem-guides-wknd /path/to/aem-core-wcm-components   # re-captures the output
python3 tools/render_terminal.py scenes/01-what-actually-runs.py out/01-what-actually-runs.mp4
```

`record-demo.sh` prints the output each scene quotes; if a number changes, update the scene.
The trace and request log come from a local AEM SDK run with the OpenTelemetry agent (see the
[Runtime guide](../runtime-guide.md)); they are not in the repository.

Needs Python 3 with Pillow, ffmpeg with libx264, and the macOS system fonts Menlo and SF.

## Voice-over scripts

Optional narration, timed to the videos. Record over the MP4 in any editor.

### 1. Which findings actually matter? (~41 s)

> **0:00** Every AEM migration starts with a list of findings. The question is which ones touch
> code that actually runs.
> **0:05** dede scans the project: code, OSGi, Sling Models, HTL and content, as one graph. WKND
> gets one finding -- a Sling Model using the JCR API directly.
> **0:15** The graph then decides what to instrument -- classes with findings, AEM entry points,
> and classes nothing references -- and writes an OpenTelemetry agent config.
> **0:22** After AEM runs with that agent, dede joins the traces and AEM's own request log back
> onto the graph. The finding now carries 964 real calls. HelloWorldModel was instrumented and
> never called. And 35 requests are mapped to the models that rendered them.
> **0:36** We checked this against the traces themselves: every page matched.

### 2. Works on real sites (~30 s)

> **0:00** On a real site, pages are authored in AEM, not committed to Git.
> **0:05** Without them, dede can't tell which components a URL renders -- zero of 35 requests.
> **0:09** Give it the site's content -- a Package Manager export, or a live instance -- and 32 of
> 35 are attributed, the same as with the content in the repository.
> **0:20** It follows how Sling resolves: templates, experience fragments, supertypes, vanity URLs.

### 3. Gate the build, ask the graph (~29 s)

> **0:00** The same findings gate your build. Here Core Components fails on its two CRITICAL
> findings, with exit code 1.
> **0:12** And with `--mcp`, the whole scan -- findings, graph and runtime data -- is available
> to Claude Code or any MCP client. An agent asks about BylineImpl and gets its type, its
> dependencies and the nine requests it served.
