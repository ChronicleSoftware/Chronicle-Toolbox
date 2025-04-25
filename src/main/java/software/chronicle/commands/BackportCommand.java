package software.chronicle.commands;

import org.eclipse.jgit.api.errors.MultipleParentsNotAllowedException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;
import software.chronicle.utils.GitUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * BackportCommand automates cherry-picking commits (and their dependencies) from a source branch
 * into a target branch as a backport.
 *
 * <p>Features:
 * - Automatic dependency detection via attempt-and-fallback dry-run cherry-picks.
 * - Optional manual mode where specified commits are cherry-picked without dependency resolution.
 * - Local-only Git operations: no remote push is performed.
 */
@Command(
    name        = "backport",
    aliases     = {"bp"},
    mixinStandardHelpOptions = true,
    description = "Backports commits to a target branch, resolving dependencies."
)
@Singleton
public class BackportCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(BackportCommand.class);

    @Option(names = {"-s", "--source"}, description = "Source branch, tag, or commit", required = true)
    String sourceBranch;

    @Option(names = {"-t", "--target"}, description = "Target branch", required = true)
    String targetBranch;

    @Option(names = {"-n", "--name"}, description = "Name of the new backport branch")
    String backportBranchName;

    @Option(names = {"-c", "--commit"}, split = ",",
            description = "Comma-separated commit hashes to backport")
    List<String> commitHashes;

    @CommandLine.Option(names = "--no-auto-deps", description = "Disable automatic dependency detection and use only specified commits.")
    boolean noAutoDeps = false;

    @Override
    public void run() {
        LOGGER.info("Starting the backport process.");
        try (Git git = new Git(GitUtils.loadCurrentRepository())) {
            Repository repo = git.getRepository();
            LOGGER.info("Repository loaded successfully.");

            // 1) Validate clean workspace and state
            GitUtils.ensureCleanState(git);

            // 2) Resolve revisions
            ObjectId srcId = GitUtils.resolveRevString(repo, sourceBranch);
            GitUtils.resolveRevString(repo, targetBranch);

            // 3) Determine commits to backport
            List<String> toBackport = decideCommits(repo, srcId);
            LinkedHashSet<String> unique = new LinkedHashSet<>(toBackport);
            toBackport = new ArrayList<>(unique);
            LOGGER.infof("Commits to backport: %s", toBackport);

            // 4) Creates a name for the new backport branch if not provided
            if (backportBranchName == null || backportBranchName.isBlank()) {
                String shortHash = toBackport.isEmpty() ? "unknown" : toBackport.get(toBackport.size()-1).substring(0,7);
                backportBranchName = String.format("backport/%s/%s", targetBranch.replace('/', '-'), shortHash);
                LOGGER.infof("Generated backport branch name: %s", backportBranchName);
            }

            // 5) Checkout and create the backport branch from the target branch
            GitUtils.checkoutBranch(git, targetBranch);
            GitUtils.createBranch(git, backportBranchName, targetBranch);

            // 6) Cherry-pick in order
            for (String hash : toBackport) {
                LOGGER.infof("Cherry-picking commit: %s", hash);
                try {
                    CherryPickResult result = git.cherryPick()
                            .include(repo.resolve(hash))
                            .call();
                    switch (result.getStatus()) {
                        case OK:
                            LOGGER.infof("Picked %s", hash);
                            break;
                        case CONFLICTING:
                            LOGGER.errorf("Conflict in %s on branch '%s'", hash, backportBranchName);
                            handleConflicts(git);
                            return;
                        default:
                            LOGGER.errorf("Unexpected status %s for %s", result.getStatus(), hash);
                            throw new BackportException("Unexpected status: " + result.getStatus());
                    }
                } catch (MultipleParentsNotAllowedException e) {
                    // merge commit detected
                    if (noAutoDeps) {
                        throw new BackportException("Merge commits are not supported: " + hash);
                    } else {
                        LOGGER.warnf("Skipping merge commit: %s", hash);
                    }
                }
            }
            LOGGER.infof("Backport complete on %s. Please push manually.", backportBranchName);

        } catch (IOException | GitAPIException e) {
            throw new BackportException("Backport failed", e);
        }
    }

    /**
     * Determines the list of commits to backport based on the provided options.
     *
     * @param repo  The Git repository.
     * @param srcId The ObjectId of the source branch or commit.
     * @return A list of commit hashes to backport.
     * @throws IOException If an error occurs while resolving dependencies.
     */
    private List<String> decideCommits(Repository repo, ObjectId srcId) throws IOException {
        if (!noAutoDeps && commitHashes != null && !commitHashes.isEmpty()) {
            return resolveDependencies(repo, commitHashes);
        }
        if (commitHashes == null || commitHashes.isEmpty()) {
            String latest = srcId.getName();
            return noAutoDeps
                ? List.of(latest)
                : resolveDependencies(repo, List.of(latest));
        }
        return new ArrayList<>(commitHashes);
    }

    /**
     * Resolves dependencies for the given list of commit hashes.
     *
     * @param repo   The Git repository.
     * @param hashes The list of commit hashes to resolve dependencies for.
     * @return A list of commit hashes in dependency order.
     * @throws IOException If an error occurs while walking the commit graph.
     */
    private List<String> resolveDependencies(Repository repo, List<String> hashes) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            List<RevCommit> ancestry = new ArrayList<>();
            for (String hash : hashes) {
                walk.reset();
                ObjectId commit = repo.resolve(hash);
                walk.markStart(walk.parseCommit(commit));
                ObjectId base = repo.resolve(targetBranch);
                walk.markUninteresting(walk.parseCommit(base));
                for (RevCommit c : walk) ancestry.add(c);
            }
            Collections.reverse(ancestry); // Ensure commits are in the correct order
            List<String> list = new ArrayList<>();
            for (RevCommit c : ancestry) list.add(c.getName());
            return list;
        }
    }

    /**
     * Handles conflicts that occur during a cherry-pick operation.
     *
     * @param git The Git instance.
     * @throws GitAPIException If an error occurs while retrieving the status.
     */
    private void handleConflicts(Git git) throws GitAPIException {
        Status status = git.status().call();
        status.getConflicting().forEach(f -> LOGGER.error("Conflict: " + f));
        System.out.println("Resolve conflicts and run: git cherry-pick --continue");
    }

    /**
     * Custom exception for backport-related errors.
     */
    public static class BackportException extends RuntimeException {
        public BackportException(String message) { super(message); }
        public BackportException(String message, Throwable cause) { super(message, cause); }
    }
}
