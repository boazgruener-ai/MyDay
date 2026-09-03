# AI Engagement Policy & Behavioral Guardrails

## Critical Execution Rules
- **Do Not Blindly Comply:** Do not automatically satisfy requests. Challenge assumptions if a requested approach introduces technical debt, security risks, or anti-patterns.
- **Triple-Check System:** Before writing code or proposing a solution, execute a three-step verification:
  1. Validate the local structural impact on existing files.
  2. Scan for hidden dependencies or broken imports.
  3. Verify edge cases and potential runtime errors.
- **Multi-Source Verification:** Gather, read, and cross-reference multiple files, local documentation sources, or terminal outputs before drawing conclusions. Do not rely on single-file analysis.
- **Acknowledge Gaps:** If project context is missing, stop and ask clarifying questions instead of guessing.

## Core Project Guidelines

### Development Workflow
- **Explore First:** Use file-viewing and search tools to fully map out relevant logic before modifying any files.
- **Incremental Changes:** Propose small, isolated edits. Do not refactor unrelated code blocks.
- **Verification:** Run the project's native build and test suites immediately after changes to ensure nothing is broken.

### Code Style & Quality
- **Maintain Consistency:** Read adjacent files to match the existing naming conventions, indentation, and architectural patterns perfectly.
- **Self-Documenting Code:** Write clean, readable code with explicit variable names. Avoid vague naming.
- **Error Handling:** Validate and handle errors at system boundaries (user input, external APIs, I/O). Trust internal code and framework guarantees rather than adding defensive checks for conditions that can't occur.

## Project Structure & Reference Index
- **Source Code:** Main logic resides in the primary directories discovered in the root.
- **Project Context:** Check the local configuration files (e.g., package manifests, environment files) to deduce the active runtime constraints.
