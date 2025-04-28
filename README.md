---

# Chronicle Toolbox

A CLI tool to streamline Git workflows across multiple Chronicle repositories.  
Supports backporting commits, creating version branches, and listing branches — all from the terminal.

---

## Features

- **Backport**: Cherry-pick commits from one branch into another, with optional automatic dependency resolution.
- **Create Version Branch**: Create a new branch across multiple repositories, configured via a YAML file.
- **List Branches**: List all local Git branches in the current repository so you know what clt can see.

- There are also some testing scripts that create repos in /Downloads/ to mess with
---

## Requirements

- Java 17+
- Maven ([https://maven.apache.org/](https://maven.apache.org/))
- Git (installed locally)

---

## Install
```bash
git clone git@github.com:ChronicleSoftware/Chronicle-Toolbox.git
cd Chronicle-Toolbox
mvnw clean package
```

You can then run `clt` to see the available commands.

---
## CLI Commands

### Backport (`backport` / `bp`)

```bash
clt backport -s <source-branch> -t <target-branch> [-c <commit1,commit2,...>] [-n <new-branch-name>] [--no-auto-deps]
```

- **Backports** one or more commits from a source to a target branch.
- Supports automatic dependency resolution (unless `--no-auto-deps` is set).

#### Options:
- `-s`, `--source` — required source branch or commit
- `-t`, `--target` — required target branch
- `-c`, `--commit` — comma-separated list of commits to backport
- `-n`, `--name` — name of the backport branch (optional)
- `--no-auto-deps` — disables automatic commit dependency resolution

---

## Backport Command Flow

1. **Loads Repository**: Locate the `.git` directory.
2. **Resolves Commits**: If `-c` is used, parse commits; otherwise use latest.
3. **Resolves Dependencies** (if enabled): Build a topological commit order.
4. **Checkouts Target**: Switch to the target branch.
5. **Creates New Branch**: From the target.
6. **Cherry-picks Commits**: Apply each commit in order, handle conflicts.
7. **Finishes**: You’ll be prompted to `git push` manually.

---

### Create Version Branch (`create-version-branch` / `cvb`)

```bash
clt create-version-branch -n <branch-name> [-B <base-branch>] [-c <config-file>]
```

- Creates a new branch in each repo listed in a `repos.yaml` config file.
- Works entirely locally — no remote interaction.

#### Options:
- `-n`, `--branch-name` — name of the new branch (required)
- `-B`, `--base-branch` — local branch to create from (defaults to current branch in each repo)
- `-c`, `--config-file` — path to a YAML file listing repositories (default: `./repos.yaml`)

---

### `repos.yaml` Structure

Your YAML file must contain a top-level `repos:` key with a list of repository paths:

```yaml
repos:
  - C:/Users/you/dev/repo1      # ✅ Absolute (Windows)
  - /home/you/projects/repo2    # ✅ Absolute (Unix)
  - ../relative/path/to/repo3   # ✅ Relative to current directory
```

> **We recommend using absolute paths** to avoid issues with relative path resolution, especially in CI environments.

#### Location:

- **Default**: `./repos.yaml`
- **Custom**: Use the `-c` option:
  ```bash
  clt cvb -n release/v1.2.0 -c ./config/repos.yaml
  ```

#### Examples:

```bash
# Create new branch across repos using default repos.yaml
clt cvb -n release/v1.0.0

# Use develop as base branch
clt cvb -n release/v1.1.0 -B develop

# Use a custom config file
clt cvb -n release/v1.2.0 -c /absolute/path/to/repos.yaml
```

---

### List Branches (`list`)

```bash
clt list
```

- Lists all local branches in the current Git repository.

---

## Windows Tips

- Avoid newline issues with:
  ```bash
  git config --global core.autocrlf input
  ```
- For best results, use Git Bash or WSL when running commands.
