# reference/

Read-only source checkouts of the mods we build on, pinned to the versions in `mod/gradle.properties`.
Use them to look up how Create's kinetics or Sable's sub-levels actually work. Don't edit them, and never
commit them (they're git-ignored).

```sh
npm run reference            # clone/update create, sable and simulated-project (Aeronautics)
```

What's cloned and at which ref is listed in `repos.txt`. When you bump a dependency in
`mod/gradle.properties`, bump its ref there too and re-run the sync.

The sync deletes every `AGENTS.md` inside the clones. Those files are instructions for contributing to
*those* projects. In this repo, only our own root `AGENTS.md` applies.
