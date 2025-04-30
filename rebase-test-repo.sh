#!/usr/bin/env bash
set -euo pipefail

PLAYGROUND="$HOME/Downloads/chronicle-rebase-test"
REMOTE_BARE="$PLAYGROUND/remote.git"
WORK_CLONE="$PLAYGROUND/work"
CONFIG="$PLAYGROUND/rebase-all-repos.yaml"

echo "🧹 Cleaning up old playground…"
rm -rf "$PLAYGROUND"
mkdir -p "$PLAYGROUND"

echo "⚙️  1) Creating bare ‘remote’ repo at $REMOTE_BARE"
git init --bare "$REMOTE_BARE"

echo "⚙️  2) Cloning remote into working dir at $WORK_CLONE"
git clone "$REMOTE_BARE" "$WORK_CLONE"

cd "$WORK_CLONE"

echo "📦 3) On master: commit game v1.1, v1.2, v1.3"
for i in 1 2 3; do
  echo "game v1.$i" > game.txt
  git add game.txt
  git commit -m "game v1.$i"
done

echo "🚀 4) Push initial master to remote"
git push origin master

echo "🌱 5) Create feature/foo and make two mod commits"
git checkout -b feature/foo
for i in 1 2; do
  echo "mod change $i" >> mod.txt
  git add mod.txt
  git commit -m "mod change $i"
done

echo "⬆️ 6) Push feature/foo to remote"
git push -u origin feature/foo

echo "🏗️ 7) Simulate remote advancing to game v1.4 & v1.5"
# work clone still on feature/foo. Let's use a fresh clone to update remote master:
TMP2="$PLAYGROUND/_tmp2"
git clone "$REMOTE_BARE" "$TMP2"
cd "$TMP2"
for i in 4 5; do
  echo "game v1.$i" > game.txt
  git add game.txt
  git commit -m "game v1.$i"
done
git push origin master
cd "$WORK_CLONE"
rm -rf "$TMP2"

echo "📝 8) Write repos config for rebase-all"
cat > "$CONFIG" <<EOF
repos:
  - $WORK_CLONE
EOF

echo
echo "✅ Playground ready!"
echo "Directories:"
echo "  remote bare : $REMOTE_BARE"
echo "  work clone  : $WORK_CLONE"
echo "Config file  : $CONFIG"
echo
echo "—— To test rebase-all: ——"
echo "clt rebase-all -n feature/foo -b master -c $CONFIG"
echo
echo "This will:"
echo "  • fetch origin/master (now at v1.5)"
echo "  • rebase feature/foo (your 2 mod commits) onto origin/master"
echo
echo "👉 Then inspect with:"
echo "cd $WORK_CLONE"
echo "git log --oneline feature/foo | head -n 7"
