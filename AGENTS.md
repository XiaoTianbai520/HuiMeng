# Repository Guidelines

## Project Structure & Module Organization

Start with [docs/handoff/README.md](docs/handoff/README.md) for the current scope, status, source baseline, and next task. M0 and M1 are complete; M2 security foundation and acceptance of the currently connected development scope are complete. M3-01 is in progress: the first safe file-save/revision batch is verified; M3-03 still contains only the minimal M2 dependency. Full M3/M4 and the latest production deployment remain pending. `backend/` contains the Spring module, migrations, private Bun readiness boundary and isolated deployment configuration; `frontend/` contains the independent Vue Web application and separate historical M0 node presets. Put server code and server-specific tests under `backend/`, client code and client-specific tests under `frontend/`, and shared design notes, API descriptions, and diagrams under `docs/`. Keep assets beside the application that uses them unless they are shared documentation assets. Add a short README in each directory when its first module is introduced.

## Build, Test, and Development Commands

Use the exact commands and working directories in [frontend/README.md](frontend/README.md) and [backend/README.md](backend/README.md). The frontend has a pinned Bun workspace; the backend has a pinned Maven Wrapper, formatter and test configuration. Documentation checks and historical node/validation helpers are separate from application commands; see their READMEs. Before adding an application command to this guide, commit the configuration that implements it and document the working directory. Do not assume conventional commands work at the repository root.

## Coding Style & Naming Conventions

Follow the formatter and lint rules selected for each module when those tools are added. Commit their configuration with the first source files so style is reproducible. Use descriptive names, keep related source and tests together, and use consistent naming within each language rather than mixing conventions. Format Markdown with clear headings and relative links to repository files.

## Testing Guidelines

Frontend client/protocol tests, backend security integration tests and documentation contract checks are configured; no coverage target is configured. Add focused tests with each new behavior and document how to run them in the relevant module README. Name tests after the behavior they verify, and include regression tests when fixing bugs. Avoid claiming a coverage threshold until the project configures one.

## Commits & Pull Requests

The repository has Git history and an independent frontend module. Check `git status` before editing; local source, documentation and fixtures may still be untracked. Use short, imperative subjects that identify the change, such as `Add frontend setup`. In pull requests, explain the purpose, list verification performed, link a relevant issue when one exists, and include screenshots for visible UI changes.

## Agent-Specific Instructions

HuiMeng is a project used by a personal team, not a company project. Describe shared accounts, keys, devices, and administration as team-managed. Do not assume corporate IT, company approval processes, or enterprise deployment requirements. Keep the confirmed administrator/member permissions, shared secret management, LAN scope, and deployment isolation unless the user changes those requirements.

The user does not require installing the internal CA into team devices' trust stores. Treat CA installation as optional, not a milestone prerequisite. Keep HTTPS and secure session cookies; record certificate warnings and actual browser acceptance separately rather than claiming trust or browser validation passed.

Use current PRD, DESIGN, CONTRACTS, and TASKS as the working baseline. `docs/archive/` and raw evidence describe historical observations; do not restore their retired providers or treat old pending items as current requirements. `.m0-work/` is ignored local scratch data, not a required build or handoff input. Update the handoff entry and task status when a milestone actually changes. Preserve unrelated work and never copy team or personal credentials into code, documentation, fixtures, or Git.

For UML diagrams, prefer PlantUML and use the installed `plantuml` skill. Store diagram sources in `docs/` so contributors can revise them alongside the surrounding documentation.
