package software.chronicle;

import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import picocli.CommandLine;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * BackportCommand is a command-line tool that automates the process of cherry-picking
 * commits from a source branch into a target branch, effectively backporting bugfixes.
 *
 * <p>The command accepts a source branch, target branch, commit hashes to backport,
 * and optionally a backport branch name. If no commit hashes are provided, the tool
 * will default to the latest commit on the source branch. The tool then creates a new
 * branch from the target branch and cherry-picks the commit(s).
 *
 * <p><b>Note:</b> This tool only performs local Git operations. It does <i>not</i> push the new
 * branch to any remote repository. That is left to the user.
 *
 * <p>This tool uses JGit for interacting with Git repositories and Picocli for command-line parsing.
 */
@CommandLine.Command(
        name = "backport",
        aliases = {"bp"},
        mixinStandardHelpOptions = true,
        description = "Backports bugfix commits to a specified branch, accepting multiple commits."
)
@Singleton
public class BackportCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(BackportCommand.class);

    /**
     * The source branch from which the commits originate (e.g., 'develop' or 'release/2.26').
     */
    @CommandLine.Option(names = {"-s", "-source"}, description = "The source branch", required = true)
    String sourceBranch;

    /**
     * The target branch into which the commits will be applied (e.g., 'release/2.24').
     */
    @CommandLine.Option(names = {"-t", "-target"}, description = "The target branch", required = true)
    String targetBranch;

    /**
     * The name for the new backport branch (if not supplied, a default will be auto-generated).
     */
    @CommandLine.Option(names = {"-n", "-name"}, description = "Name of the new backport branch")
    String backportBranchName;

    /**
     * Comma-separated list of commit hashes to backport.
     * If not supplied, the tool will default to the latest commit from the source branch.
     */
    @CommandLine.Option(names = {"-c", "-commit"}, description = "Comma-separated commit hash(es) to backport", split = ",")
    List<String> commitHashes;

    /**
     * Executes the backporting process by initializing the repository, checking out the appropriate
     * branches, cherry-picking commits, and finally pushing the new backport branch to the remote.
     */
    @Override
    public void run() {
        LOGGER.info("Starting the backport process.");
        try {
            // Loads the Git repository from the current directory
            Repository repo = loadCurrentRepository();
            Git git = new Git(repo);
            LOGGER.info("Repository loaded successfully.");

            // Determine which commits to backport. If no commit hashes are provided, use the latest commit.
            if (commitHashes == null || commitHashes.isEmpty()) {
                String resolvedHash = resolveCommitHash(repo, sourceBranch);
                commitHashes = new ArrayList<>();
                commitHashes.add(resolvedHash);
                LOGGER.infof("No commit hashes provided. Defaulting to latest commit: %s", resolvedHash);
            } else {
                LOGGER.infof("Commit hashes provided: %s", commitHashes.toString());
            }

            // Generate a backport branch name if not provided
            if (backportBranchName == null || backportBranchName.isEmpty()) {
                backportBranchName = "feature/backport_" + targetBranch.replace("/", "_");
                LOGGER.infof("No backport branch name provided. Defaulting to: %s", backportBranchName);
            } else {
                LOGGER.infof("Using provided backport branch name: %s", backportBranchName);
            }

            LOGGER.infof("Backporting commits %s from source branch '%s' to target branch '%s' on new branch '%s'.",
                    commitHashes, sourceBranch, targetBranch, backportBranchName);

            // Checkout the target branch (assumes that branch is up-to-date locally)
            checkoutBranch(git, targetBranch);
            LOGGER.infof("Checked out target branch: %s", targetBranch);

            // Create a new branch from the target branch for backporting.
            createBackportBranch(git, backportBranchName);
            LOGGER.infof("Created new backport branch: %s", backportBranchName);

            // Process each commit (oldest commit first)
            for (String hash : commitHashes) {
                LOGGER.infof("Cherry-picking commit: %s", hash);
                CherryPickResult result = git.cherryPick().include(repo.resolve(hash)).call();
                if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING) {
                    LOGGER.errorf("Conflict detected while cherry-picking commit: %s", hash);
                    handleConflicts(git);
                    // Exit early upon encountering a conflict
                    return;
                } else if (result.getStatus() == CherryPickResult.CherryPickStatus.OK) {
                    LOGGER.infof("Successfully cherry-picked commit: %s", hash);
                } else {
                    LOGGER.warnf("Cherry-pick finished with status: %s", result.getStatus());
                }
            }

            // The backport has been applied locally.
            LOGGER.infof("Backport process complete. The branch '%s' has been created locally.", backportBranchName);
            LOGGER.info("Please push your new branch manually using your preferred Git workflow, e.g.:");
            LOGGER.infof("    git push <remote> %s", backportBranchName);

        } catch (IOException | GitAPIException e) {
            LOGGER.error("An error occurred during the backport process.", e);
            throw new RuntimeException("Backport process failed.", e);
        }
    }

    /**
     * Initializes the Git repository by locating the .git directory starting from the current working directory.
     *
     * @return the initialized Repository
     * @throws IOException if the repository cannot be found or read
     */
    private Repository loadCurrentRepository() throws IOException {
        LOGGER.debug("Initializing repository using FileRepositoryBuilder.");
        return new FileRepositoryBuilder()
                .setWorkTree(new File(System.getProperty("user.dir")))
                .readEnvironment()
                .findGitDir(new File(System.getProperty("user.dir")))
                .setMustExist(true)
                .build();
    }

    /**
     * Resolves and returns the latest commit hash from a given branch.
     *
     * @param repo   the Git Repository
     * @param branch the branch name to resolve
     * @return the latest commit hash as a String
     * @throws IOException              if an error occurs during resolution
     * @throws IllegalArgumentException if the branch does not exist
     */
    private String resolveCommitHash(Repository repo, String branch) throws IOException {
        LOGGER.debugf("Resolving latest commit hash for branch: %s", branch);
        ObjectId commitId = repo.resolve("refs/heads/" + branch);
        if (commitId == null) {
            String errorMsg = "Source branch not found: " + branch;
            LOGGER.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        LOGGER.debugf("Resolved commit hash: %s", commitId.getName());
        return commitId.getName();
    }

    /**
     * Checks out an existing branch.
     *
     * @param git        the Git object
     * @param branchName the name of the branch to check out
     * @throws GitAPIException if the checkout operation fails
     */
    private void checkoutBranch(Git git, String branchName) throws GitAPIException {
        LOGGER.debugf("Checking out branch: %s", branchName);
        git.checkout().setName(branchName).call();
    }

    /**
     * Creates a new branch for backporting from the currently checked-out branch.
     *
     * @param git        the Git object
     * @param branchName the new backport branch name to create
     * @throws GitAPIException if branch creation fails
     */
    private void createBackportBranch(Git git, String branchName) throws GitAPIException {
        LOGGER.debugf("Creating new backport branch: %s", branchName);
        git.checkout().setCreateBranch(true).setName(branchName).call();
    }

    /**
     * Handles conflicts that occur during the cherry-pick process.
     * Logs the conflicting files and instructs the user on how to resolve the conflicts manually.
     *
     * @param git the Git object
     * @throws GitAPIException if retrieving the repository status fails
     */
    private void handleConflicts(Git git) throws GitAPIException {
        LOGGER.debug("Handling conflicts encountered during cherry-pick.");
        Status status = git.status().call();
        Set<String> conflictingFiles = status.getConflicting();
        if (!conflictingFiles.isEmpty()) {
            LOGGER.error("❌ Conflicts detected in the following files:");
            for (String file : conflictingFiles) {
                LOGGER.errorf("   🔥 %s", file);
            }
        } else {
            LOGGER.warn("A conflict occurred, but no conflicting files were reported.");
        }
        // Provide manual conflict resolution instructions
        System.out.println("\n🔧 Merge conflict during cherry-pick.");
        System.out.println("Please resolve the conflict manually, then run:");
        System.out.println("    git add <file>");
        System.out.println("    git cherry-pick --continue");
    }
}
