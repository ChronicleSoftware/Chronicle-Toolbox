# 🧰 Chronicle Toolbox

A CLI tool to streamline Git workflows across Chronicle repositories.  
Supports backporting, branch management, and version operations — all from the command line.

---

## 🚀 Features

- 🔄 **Backport** commits from one branch to another
- 🌿 **Create version branches** across repos
- 📋 **List local Git branches**
- 📦 Future-proof for multi-repo workflows and CI integration

---

## 🧪 Requirements

- Java 17+
- [Maven](https://maven.apache.org/)
- Git (locally installed)
- Recommended: Git Bash or WSL for Windows users

---

## 🛠️ Installation

1. Clone this repo:
   ```bash
   git clone https://github.com/your-org/chronicle-toolbox.git
   cd chronicle-toolbox
   ```
2. Build the project:

    ```bash
    ./mvnw clean package
    ```
3. Use the clt command:

    ```bash
    ./clt backport -source release/2.28 -target release/2.26
    ```

You can move or symlink clt to a location in your PATH for global use.

## 📝 CLI Commands
### 🔄 Backport
```bash
clt backport -source <source-branch> -target <target-branch> [-commit <hash>] [-name <branch-name>]
```
Cherry-picks a commit from one branch to another

Auto-generates a new backport branch if no name is provided

### 🌿 Create Version Branch
```bash
clt create-version-branch -branch-name release/2.29 -base-branch main
```
Creates a new branch in multiple repos (YAML-configured)

### 📋 List Branches
```bash
clt list
```
Lists all local branches (optional filtering coming soon)

### ⚠️ Notes for Windows Users
To avoid issues with line endings in shell scripts do run:

```bash
git config --global core.autocrlf input
```
Also recommended: use Git Bash or WSL for best cross-platform compatibility.