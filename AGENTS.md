# Repository Guidelines

## Project Structure & Module Organization

Start with [docs/handoff/README.md](docs/handoff/README.md) for the current scope, status, source baseline, and next task. M0 and M1 are complete; M2-01 is next. `backend/` has no application module; `frontend/` contains the independent Vue Web application and separate historical M0 node presets. Put server code and server-specific tests under `backend/`, client code and client-specific tests under `frontend/`, and shared design notes, API descriptions, and diagrams under `docs/`. Keep assets beside the application that uses them unless they are shared documentation assets. Add a short README in each directory when its first module is introduced.

## Build, Test, and Development Commands

The frontend has its own pinned Bun workspace, build and test configuration; use the exact commands and working directory in [frontend/README.md](frontend/README.md). No backend application commands exist yet. Documentation checks and historical node/validation helpers are separate from application commands; see their READMEs. Before adding an application command to this guide, commit the configuration that implements it and document the working directory. Do not assume conventional commands work at the repository root.

## Coding Style & Naming Conventions

Follow the formatter and lint rules selected for each module when those tools are added. Commit their configuration with the first source files so style is reproducible. Use descriptive names, keep related source and tests together, and use consistent naming within each language rather than mixing conventions. Format Markdown with clear headings and relative links to repository files.

## Testing Guidelines

Frontend client/protocol tests and documentation contract checks are configured; there is no coverage target or backend business test runner yet. Add focused tests with each new behavior and document how to run them in the relevant module README. Name tests after the behavior they verify, and include regression tests when fixing bugs. Avoid claiming a coverage threshold until the project configures one.

## Commits & Pull Requests

The repository has Git history and an independent frontend module. Check `git status` before editing; local source, documentation and fixtures may still be untracked. Use short, imperative subjects that identify the change, such as `Add frontend setup`. In pull requests, explain the purpose, list verification performed, link a relevant issue when one exists, and include screenshots for visible UI changes.

## Agent-Specific Instructions

Use current PRD, DESIGN, CONTRACTS, and TASKS as the working baseline. `docs/archive/` and raw evidence describe historical observations; do not restore their retired providers or treat old pending items as current requirements. `.m0-work/` is ignored local scratch data, not a required build or handoff input. Update the handoff entry and task status when a milestone actually changes. Preserve unrelated work and never copy company credentials into code, documentation, fixtures, or Git.

For UML diagrams, prefer PlantUML and use the installed `plantuml` skill. Store diagram sources in `docs/` so contributors can revise them alongside the surrounding documentation.
