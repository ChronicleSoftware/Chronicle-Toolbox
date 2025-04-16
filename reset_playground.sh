#!/bin/bash
# This script resets the git-playground environment

# Define your playground location
PLAYGROUND=~/Downloads/git-playground

# Remove the old playground folder (if it exists), then recreate it.
rm -rf "$PLAYGROUND"
mkdir "$PLAYGROUND"
cd "$PLAYGROUND" || exit

# Initialize a new Git repository.
git init

# Create an initial file and commit.
echo "Line 1: Initial content" > app.txt
echo "Line 2: More content" >> app.txt
git add app.txt
git commit -m "Initial commit"

# Create source branch: release/2.28
git checkout -b release/2.28
echo "Line 3: Change specific to release/2.28" >> app.txt
git commit -am "Commit on release/2.28"

# Go back to master/main.
git checkout master

# Create target branch: release/2.26
git checkout -b release/2.26
echo "Line 3: Change specific to release/2.26" >> app.txt
git commit -am "Commit on release/2.26"

echo "Playground reset complete!"
