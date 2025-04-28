#!/usr/bin/env bash
# multi-commit-test.sh
# Creates a Git repo with a commit chain exhibiting dependencies A→B→D,
# plus an independent commit C, so you can test --auto-deps.

set -euo pipefail

PLAYGROUND=~/Downloads/git-dep-test
echo "📂 Resetting playground at: $PLAYGROUND"
rm -rf "$PLAYGROUND"
mkdir -p "$PLAYGROUND"
cd "$PLAYGROUND"

echo "🆕 Initializing new Git repo"
git init

echo "📝 Initial commit on master"
echo "Line 1: Base content" > app.txt
git add app.txt
git commit -m "Initial commit"

echo "🌿 Creating target branch: release/2.26"
git checkout -b release/2.26
echo "Line 2: Release 2.26 base change" >> app.txt
git commit -am "Release/2.26 base update"

echo "🌱 Creating source branch: release/2.28"
git checkout -b release/2.28

echo "A) Adding a new file util.txt (commit A)"
echo "utility v1" > util.txt
git add util.txt
git commit -m "Add util.txt (commit A)"
A=$(git rev-parse HEAD)

echo "B) Modifying util.txt to v2 (depends on A) (commit B)"
echo "utility v2" >> util.txt
git commit -am "Update util.txt to v2 (commit B)"
B=$(git rev-parse HEAD)

echo "C) Independent change in app.txt (commit C)"
echo "Line 3: Independent change" >> app.txt
git commit -am "Independent app.txt change (commit C)"
C=$(git rev-parse HEAD)

echo "D) Further util.txt tweak (depends on B) (commit D)"
# Use GNU sed in-place replacement
sed -i 's/v2/v3/' util.txt
git commit -am "Tweak util.txt to v3 (commit D)"
D=$(git rev-parse HEAD)

echo "🛑 Done setting up release/2.28"
git checkout release/2.26

cat <<EOF

✅ Playground ready with branches:

  • release/2.26 at: $(git rev-parse --abbrev-ref HEAD)
  • release/2.28 at commit D: ${D:0:7}

Commit hashes:
  A (adds util.txt): ${A:0:7}
  B (updates util.txt): ${B:0:7}
  C (independent): ${C:0:7}
  D (further util change): ${D:0:7}

❓ To test auto-deps for commit B only:
   clt backport -s release/2.28 -t release/2.26 -c ${B}

   → should pull in A then B.

❓ To cherry-pick only C (no deps needed):
   clt backport -s release/2.28 -t release/2.26 -c ${C}

❓ To grab D (should pull in A→B→D):
   clt backport -s release/2.28 -t release/2.26 -c ${D}|

EOF
