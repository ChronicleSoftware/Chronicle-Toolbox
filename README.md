# Chronicle Toolbox

A CLI tool to streamline Git workflows across Chronicle repositories. Supports backporting, branch management, and version operations — all from the command line.

## Features

- **Backport**: Cherry-pick commits from one branch to another, with optional automatic dependency resolution.
- **Create Version Branch**: Generate version-specific branches across multiple repositories via a YAML configuration.
- **List Branches**: Enumerate local Git branches (future support for filtering and CI integration).

## Requirements

- Java 17 or higher
- Maven ([https://maven.apache.org/](https://maven.apache.org/))
- Git (installed locally)

## Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-org/chronicle-toolbox.git
   cd chronicle-toolbox
   ```
2. **Build the project**:
   ```bash
   ./mvnw clean package
   ```
3. **Make the `clt` script executable and available in your `PATH`**:
   ```bash
   chmod +x ./clt
   # Move or symlink to a directory in your PATH
   mv ./clt /usr/local/bin/
   # Or create a symlink in ~/bin
   mkdir -p "$HOME/bin"
   ln -s $(pwd)/clt "$HOME/bin/clt"
   ```
4. **Verify installation**:
   ```bash
   clt --help
   ```

## CLI Commands

### Backport
```bash
clt backport -source <source-branch> -target <target-branch> [-commit <hash>[,<hash>...]] [-name <branch-name>] [--no-auto-deps]
```
- **Description**: Automates backporting of one or more commits, with optional automatic detection of dependencies.
- **Options**:
   - `--no-auto-deps`: Disables dependency resolution (only cherry-picks specified commits).
- **Process**:
   1. Load the Git repository from the current directory.
   2. Resolve commit hashes (defaults to the latest if none provided).
   3. Automatically detect commit dependencies (unless `--no-auto-deps` is used).
   4. Checkout the target branch.
   5. Create a new backport branch (auto-generated name if not supplied).
   6. Cherry-pick commits in dependency order.
   7. On success, logs instructions to manually push the new branch.

### Create Version Branch
```bash
clt create-version-branch -branch-name <branch> -base-branch <base>
```
- **Description**: Creates a new version branch across multiple repositories defined in a YAML config.

### List Branches
```bash
clt list
```
- **Description**: Lists all local Git branches.

## Backport Pipeline Overview

1. **Load Repository**: Use JGit’s `FileRepositoryBuilder` to locate the existing `.git` directory.
2. **Resolve Commits**: If no commit hashes are supplied, resolve the latest commit on the source branch.
3. **Analyze Dependencies**: Attempt dry-run cherry-picks to find additional parent commits needed to cleanly apply the desired change.
4. **Checkout Target**: Switch to the target branch.
5. **Branch Creation**: Create a new backport branch from the target branch.
6. **Cherry-Pick Commits**: Apply each commit in order, handling conflicts by pausing and instructing manual resolution.
7. **Manual Push**: After successful cherry-pick, instruct the user to push the new branch:
   ```bash
   git push <remote> <branch-name>
   ```

## Notes for Windows Users

To avoid line-ending issues:
```bash
git config --global core.autocrlf input
```
Use Git Bash or WSL for best compatibility.