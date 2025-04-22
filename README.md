# Chronicle Toolbox

A CLI tool to streamline Git workflows across Chronicle repositories. Supports backporting, version branch creation, and branch listing — all from the command line.

## Features

- **Backport**: Cherry-pick commits from one branch into another, with optional automatic dependency resolution.  
- **Create Version Branch**: Generate version-specific branches across multiple repositories via a YAML configuration.  
- **List Branches**: Enumerate local Git branches.

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
3. **Install the CLI launcher**:
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

### Backport (`backport` / `bp`)
```bash
clt backport -source <source-branch> -target <target-branch> [-commit <hash>[,<hash>...]] [-name <branch-name>] [--no-auto-deps]
```
- **Description**: Automates backporting one or more commits, optionally resolving dependencies automatically.  
- **Options**:  
  - `-s`, `--source` _\<source-branch\>_ (required)  
  - `-t`, `--target` _\<target-branch\>_ (required)  
  - `-c`, `--commit` _\<hash1,hash2,...\>_ (comma-separated list)  
  - `-n`, `--name` _\<backport-branch-name\>_  
  - `--no-auto-deps` (disable automatic dependency resolution)
- **Process**:
   1. Load the Git repository from the current directory.
   2. Resolve commit hashes (defaults to the latest if none provided).
   3. Automatically detect commit dependencies (unless `--no-auto-deps` is used).
   4. Checkout the target branch.
   5. Create a new backport branch (auto-generated name if not supplied).
   6. Cherry-pick commits in dependency order.
   7. On success, logs instructions to manually push the new branch.

### Create Version Branch (`create-version-branch` / `cvb`)
```bash
clt create-version-branch -branch-name <branch> -base-branch <base>
```
- **Description**: Creates a new version branch across multiple repositories defined in a YAML config.
- **Options**:
  - `-n`, `--branch-name` _\<branch\>_ (required)  
  - `-B`, `--base-branch` _\<base\>_ (default: `main`)  
  - `-c`, `--config-file` _\<path\>_ (default: `repos.yaml`)

#### repos.yaml Layout & Location
Place a `repos.yaml` file in your current working directory (or specify via `-c`).

```yaml
# repos.yaml
authors:
  # (optional metadata)
repos:
  - /absolute/or/relative/path/to/repo1
  - /absolute/or/relative/path/to/repo2
  - ../other-project/repo3
```

- **Key**: `repos` must be a top-level list of file system paths.  
- **Default Path**: `./repos.yaml`  
- **Custom Path**: e.g.  
  ```bash
  clt cvb -n release/v1.2.0 -c config/repos.yaml
  ```

#### Usage Examples

```bash
# Basic: default base-branch=main, config=./repos.yaml
clt cvb -n release/v1.0.0

# Custom base branch\clt cvb -n release/v1.1.0 -B develop

# Custom config file
clt cvb -n release/v1.2.0 -c ./config/myrepos.yaml
```

### List Branches (`list`)
```bash
clt list
```
- **Description**: Prints all local Git branches in the current repository.

## Backport Pipeline Overview

1. **Load Repository**: Locate `.git` via JGit’s `FileRepositoryBuilder`.  
2. **Resolve Commits**: Default to latest on source branch if none specified.  
3. **Analyze Dependencies**: Optionally dry-run cherry-picks to determine needed parent commits.  
4. **Checkout Target**: Switch to the target branch.  
5. **Branch Creation**: Create new backport branch.  
6. **Cherry-Pick Commits**: Apply each commit in order; pause on conflicts.  
7. **Manual Push**: After success, run `git push <remote> <branch-name>`.

## Notes for Windows Users

To avoid line-ending issues:
```bash
git config --global core.autocrlf input
```
Use Git Bash or WSL for best compatibility.