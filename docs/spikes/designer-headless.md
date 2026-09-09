# Spike: Designer headless on macOS (2026-09-09)

**Question.** The PDT analysis ([pdt-analysis.md](pdt-analysis.md)) recommends
driving Designer's headless application for package operations. Does it launch
on this Mac (Designer 4.10.1, `/Applications/Designer`)?

**Run.**

```
/Applications/Designer/Designer.app/Contents/MacOS/Designer -nosplash \
  -application com.novell.idm.rcp.DesignerHeadless -nl en -data <scratch workspace> \
  -command listContents -L P -p /Applications/Designer/packages/eclipse/plugins -l <dir>/headless.log
```

**Result.** Exit 0; the log ends with `#OPERATION_SUCCESS`; 480 base packages
listed as `<n>. <SHORT> , <version>, <supported drivers;>` in 20.5 s
(`Total time to run command listContents --> 20.554(in Sec.)`). The first
attempt exited 13 because `-l` must name a **file**, not a directory
(`HeadlessLogger` opens it with `FileOutputStream`; the stack is in the
workspace's `.metadata/.log`). Eclipse logs hundreds of
`Could not resolve module … Another singleton bundle selected` warnings while
installing the catalog jars as bundles — every package version in the
directory is offered and OSGi keeps one per symbolic name; harmless for
listing, but a command builder that needs a *specific* version must pass
`-v` and `SHORT_version` in `-b` rather than rely on which singleton won.

**Consequence.** Route A is feasible here: the runner (`-application …
DesignerHeadless`, scratch `-data`, `-l` file, parse for
`#OPERATION_SUCCESS` / `#OPERATION_FAILED`) is a process launch plus a log
parser. Next spike: `deployDriver -f <out.xml> -u <proj> -d -b NOVLEDIRBASE_<ver>
-F <answers> -g` (generate the prompt template), then the same without `-g` to
produce an export that `driver.add --from-export` consumes.
