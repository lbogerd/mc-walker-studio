# reference/

Read-only source checkouts of the mods we build on, pinned to the versions in `mod/gradle.properties`.
Use them to look up how Create's kinetics or Sable's sub-levels actually work. Don't edit them, and never
commit them (they're git-ignored).

```sh
npm run reference            # clone/update the default set (create, sable)
npm run reference -- --all   # include opt-in entries
```

What's cloned and at which ref is listed in `repos.txt`. When you bump a dependency in
`mod/gradle.properties`, bump its ref there too and re-run the sync.

**Create: Aeronautics / Simulated** is opt-in. Its code is MIT-licensed, but the maintainers ask that LLM
tools not be used to read or work with that codebase (its `AGENTS.md`). Agents: don't sync or read it unless
the user explicitly asks.
