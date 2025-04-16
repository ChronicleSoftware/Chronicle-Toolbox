#!/bin/bash
# reset-git-playground-clean.sh
# Creates a clean backport scenario with no cherry-pick conflicts

# Define playground location
PLAYGROUND=~/Downloads/git-playground
rm -rf "$PLAYGROUND"
mkdir -p "$PLAYGROUND"
cd "$PLAYGROUND" || exit

# Initialize repo
git init
echo "Line 1: Initial content" > app.txt
git add app.txt
git commit -m "Initial commit"

# Create target branch first (release/2.26)
git checkout -b release/2.26
echo "Line 2: Common content for all branches" >> app.txt
git commit -am "Common update for release/2.26"

# Create source branch from same commit (no divergence yet)
git checkout -b release/2.28
echo "Line 3: Bug fix in release/2.28" >> app.txt
git commit -am "Bug fix on release/2.28"

# Return to target branch for testing
git checkout release/2.26

echo -e "\n✅ Clean backport test environment created at: $PLAYGROUND"
