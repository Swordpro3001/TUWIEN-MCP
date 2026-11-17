# Contributing

Thanks for helping. Here is what gets merged, and what needs to come with it.

## New tools: open an issue first

Every tool is loaded into the context of every conversation that uses these servers, so each new one costs tokens
for everyone. Describe what students would ask Claude, and which TUWEL or LectureTube data answers it, in an issue
before you write code. Often an existing tool can do it with one more argument.

## Things that are never merged

- **Anything that weakens the login or session handling.** The session file holds a TU Wien SSO cookie that can
  sign in to every TU Wien service. It stays owner-only, it is never logged, and it never leaves the machine.
- **Uploads that happen without an explicit setting.** Audio only goes to a speech API after the user configured
  a key. New features that send data to a third party follow the same rule.
- **Writes to TUWEL or LectureTube.** The servers read. Submitting assignments, posting in forums or changing
  settings is out of scope.
- **Scraping beyond what a logged-in student can see in the browser.**

## Every change

1. Keep the pull request to one change and link the issue it fixes.
2. Add a test for new logic (a parser, a branch, a retry, anything that handles money or credentials).
   Tests must never touch the network or the real session: they run with `lecturetubemcp.home` pointing to
   `build/test-home`.
3. Before you open the pull request:

   ```bash
   ./gradlew test allJars
   claude plugin validate .
   ```

The CI workflow runs the same tests on every pull request and again after the merge.

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org). The release workflow builds the
version number and `CHANGELOG.md` from them, so the type matters:

| Type | Use for | Version bump |
|---|---|---|
| `feat(scope): ...` | a new tool or capability | minor |
| `fix(scope): ...` | a bug fix | patch |
| `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore` | everything else | patch |

Scopes in use: `auth`, `tuwel`, `lecturetube`, `media`, `plugin`, `deps`. A breaking change gets a `!`
(`feat(tuwel)!: ...`) and a `BREAKING CHANGE:` line in the body.

## Code

- Kotlin, in the existing layout: HTTP clients in `api/` and `moodle/`, domain logic in the `*Service` classes,
  MCP tool definitions in `mcp/`. A tool handler parses arguments and calls a service, nothing more.
- stdout is the MCP channel. Log to stderr only, and never print from a library call.
- Tool results are meant for a model: compact JSON or text, no raw API dumps, and an error message that says what
  to do next.
- Comment the why the code cannot show, in one line. No section banners.

## Development

```bash
./gradlew test          # tests
./gradlew allJars       # build/libs/tuwel-mcp.jar and build/libs/lecturetube-mcp.jar
claude --plugin-dir .   # run the plugin with your local build
java -jar build/libs/tuwel-mcp.jar doctor
```

The plugin launcher (`bin/Launcher.java`) prefers `build/libs/` over the release jars, so a local build is
picked up without any extra setup.

Releases are made by the maintainer with the manual *Release* workflow.
