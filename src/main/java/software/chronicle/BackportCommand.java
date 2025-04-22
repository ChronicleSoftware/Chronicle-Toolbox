package software.chronicle;

import org.eclipse.jgit.api.CherryPickCommand;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import picocli.CommandLine;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BackportCommand automates cherry-picking commits (and their dependencies) from a source branch
 * into a target branch as a backport.
 *
 * <p>Features:
 * - Automatic dependency detection via attempt-and-fallback dry-run cherry-picks.
 * - Optional manual mode where specified commits are cherry-picked without dependency resolution.
 * - Local-only Git operations: no remote push is performed.
 */
@CommandLine.Command(
        name = "backport",
        aliases = {"bp"},
        mixinStandardHelpOptions = true,
        description = "Backports bugfix commits to a specified branch, resolving dependencies automatically."
)
@Singleton
public class BackportCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(BackportCommand.class);

    @CommandLine.Option(names = {"-s", "-source"}, description = "The source branch", required = true)
    String sourceBranch;

    @CommandLine.Option(names = {"-t", "-target"}, description = "The target branch", required = true)
    String targetBranch;

    @CommandLine.Option(names = {"-n", "-name"}, description = "Name of the new backport branch")
    String backportBranchName;

    @CommandLine.Option(names = {"-c", "-commit"}, description = "Comma-separated commit hash(es) to backport", split = ",")
    List<String> commitHashes;

    @CommandLine.Option(names = "--no-auto-deps", description = "Disable automatic dependency detection and use only specified commits.")
    boolean noAutoDeps = false;

    @Override
    public void run() {
        LOGGER.info("Starting the backport process.");
        try (Git git = new Git(loadCurrentRepository())) {
            Repository repo = git.getRepository();
            LOGGER.info("Repository loaded successfully.");

            /* ── upfront validation ─────────────────────────────── */
            // Fail fast if either branch name is wrong
            resolveCommitHash(repo, sourceBranch);           // throws IllegalArgumentException if missing
            if (repo.resolve("refs/heads/" + targetBranch) == null) {
                throw new RefNotFoundException("Branch not found: " + targetBranch);
            }

            List<String> toBackport = decideCommits(repo, git);      // ← extracted original decision block
            LinkedHashSet<String> unique = new LinkedHashSet<>(toBackport);
            toBackport = new ArrayList<>(unique);
            LOGGER.infof("Final commit sequence to backport: %s", toBackport);

            // Generate branch name if needed
            if (backportBranchName == null || backportBranchName.isEmpty()) {
                backportBranchName = "feature/backport_" + targetBranch.replace('/', '_');
                LOGGER.infof("Defaulting backport branch name to: %s", backportBranchName);
            }

            // Checkout target and create new branch
            checkoutBranch(git, targetBranch);
            createBackportBranch(git, backportBranchName);

            // Cherry-pick in order
            for (String hash : toBackport) {
                LOGGER.infof("Cherry-picking commit: %s", hash);
                CherryPickResult result = git.cherryPick().include(repo.resolve(hash)).call();
                if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING) {
                    LOGGER.errorf("Conflict detected for commit: %s", hash);
                    handleConflicts(git);
                    return;
                } else if (result.getStatus() == CherryPickResult.CherryPickStatus.OK) {
                    LOGGER.infof("Successfully cherry-picked: %s", hash);
                } else {
                    LOGGER.warnf("Cherry-pick status %s for %s", result.getStatus(), hash);
                }
            }

            LOGGER.infof("Backport complete on branch '%s'.", backportBranchName);
            LOGGER.info("Please push the branch manually: git push <remote> " + backportBranchName);

        } catch (RefNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException | GitAPIException e) {
            LOGGER.error("Backport process failed.", e);
            throw new RuntimeException(e);
        }
    }

    /* helper extracted from original block */
    private List<String> decideCommits(Repository repo, Git git)
            throws IOException, GitAPIException {

        if (!noAutoDeps && commitHashes != null && !commitHashes.isEmpty()) {
            List<String> list = new ArrayList<>();
            for (String hash : commitHashes) list.addAll(resolveDependencies(repo, git, hash));
            return list;
        }

        if (commitHashes == null || commitHashes.isEmpty()) {
            String latest = resolveCommitHash(repo, sourceBranch);
            return noAutoDeps
                   ? Collections.singletonList(latest)
                   : resolveDependencies(repo, git, latest);
        }

        return new ArrayList<>(commitHashes);
    }


    private Repository loadCurrentRepository() throws IOException {
        LOGGER.debug("Loading repository");
        return new FileRepositoryBuilder()
                .setWorkTree(new File(System.getProperty("user.dir")))
                .readEnvironment()
                .findGitDir(new File(System.getProperty("user.dir")))
                .setMustExist(true)
                .build();
    }

    private String resolveCommitHash(Repository repo, String branch) throws IOException {
        ObjectId id = repo.resolve("refs/heads/" + branch);
        if (id == null) {
            throw new IllegalArgumentException("Branch not found: " + branch);
        }
        return id.getName();
    }

    private List<String> resolveDependencies(Repository repo, Git git, String commitHash)
            throws GitAPIException, IOException {
        // 1) Build the full ancestry list, oldest-first:
        List<RevCommit> ancestry = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            ObjectId head = repo.resolve(commitHash);
            ObjectId base = repo.resolve("refs/heads/" + targetBranch);
            walk.markStart(walk.parseCommit(head));
            walk.markUninteresting(walk.parseCommit(base));
            for (RevCommit c : walk) {
                ancestry.add(c);
            }
        }
        // RevWalk emits newest-first; reverse to oldest-first:
        Collections.reverse(ancestry);

        LOGGER.debugf("Ancestry path for %s → %s: %s",
                targetBranch, commitHash,
                ancestry.stream().map(RevCommit::getName).collect(Collectors.toList()));

        // 2) Now, cherry-pick them in order, but do a dry-run check if you like:
        List<String> toApply = new ArrayList<>();
        for (RevCommit c : ancestry) {
            String hash = c.getName();
            LOGGER.debugf("Checking commit %s", hash);
            // Optionally dry-run to see if it fails:
            boolean clean = appliesCleanly(git, c);
            if (!clean) {
                LOGGER.infof("  → %s didn’t apply cleanly, but will include (its parents are already in place).", hash);
            }
            toApply.add(hash);
        }

        return toApply;
    }

    private boolean appliesCleanly(Git git, RevCommit commit) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        ObjectId head = repo.resolve("HEAD");

        // Dry-run cherry-pick
        CherryPickCommand dryPick = git.cherryPick().include(commit).setNoCommit(true);
        CherryPickResult dryResult = dryPick.call();
        boolean clean = dryResult.getStatus() == CherryPickResult.CherryPickStatus.OK;

        // Reset working tree and index back to HEAD
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head.getName()).call();

        return clean;
    }

    private void checkoutBranch(Git git, String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
    }

    private void createBackportBranch(Git git, String branchName) throws GitAPIException {
        git.checkout().setCreateBranch(true).setName(branchName).call();
    }

    private void handleConflicts(Git git) throws GitAPIException {
        Status status = git.status().call();
        Set<String> conflicts = status.getConflicting();
        conflicts.forEach(f -> LOGGER.error("Conflict: " + f));
        System.out.println("Resolve conflicts and run: git cherry-pick --continue");
    }
}
