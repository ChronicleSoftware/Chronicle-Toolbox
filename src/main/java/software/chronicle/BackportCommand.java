package software.chronicle;

import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import picocli.CommandLine;
import jakarta.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Starting it all looks like this  java -jar target/quarkus-app/quarkus-run.jar <name of subcommand> <options>
// here is the name of the subcommand
@CommandLine.Command(
        name = "backport",
        aliases = {"bp"},
        mixinStandardHelpOptions = true,
        description = "Backports bugfix commits to a specified branch, accepting multiple commits."
)
@Singleton
public class BackportCommand implements Runnable {

    // The source branch from which the commits originate (e.g., 'develop' or 'release/2.26')
    @CommandLine.Option(names = "-source", description = "The source branch", required = true)
    String sourceBranch;

    // The target branch into which the commits will be applied (e.g., 'release/2.24')
    @CommandLine.Option(names = "-target", description = "The target branch", required = true)
    String targetBranch;

    // The name for the new backport branch (if not supplied, a default will be auto-generated)
    @CommandLine.Option(names = "-name", description = "Name of the new backport branch")
    String backportBranchName;

    // Comma-separated list of commit hashes to backport.
    // If not supplied, the tool will default to the latest commit from the source branch.
    @CommandLine.Option(names = "-commit", description = "Comma-separated commit hash(es) to backport", split = ",")
    List<String> commitHashes;

    @Override
    public void run() {
        try {
            // Initialize Git repository from current directory
            Repository repo = initialiseRepository();
            Git git = new Git(repo);

            // If no commit hashes are provided, default to the latest commit from the source branch.
            if (commitHashes == null || commitHashes.isEmpty()) {
                String resolvedHash = resolveCommitHash(repo, sourceBranch);
                commitHashes = new ArrayList<>();
                commitHashes.add(resolvedHash);
            }

            // If no backport branch name is provided, create a default name.
            if (backportBranchName == null || backportBranchName.isEmpty()) {
                // For example, default to "feature/backport_release_2_24"
                backportBranchName = "feature/backport_" + targetBranch.replace("/", "_");
            }

            System.out.printf("Backporting commits %s from %s to %s on branch '%s'%n", commitHashes, sourceBranch, targetBranch, backportBranchName);

            // Checkout the target branch (assumes that branch is up-to-date locally)
            checkoutBranch(git, targetBranch);

            // Create a new branch from the target branch for backporting.
            createBackportBranch(git, backportBranchName);

            // Process each commit in order (oldest commit first).
            for (String hash : commitHashes) {
                System.out.printf("Cherry-picking commit %s%n", hash);
                CherryPickResult result = git.cherryPick().include(repo.resolve(hash)).call();
                if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING) {
                    handleConflicts(git);
                    // If a conflict occurs, exit early.
                    return;
                } else if (result.getStatus() == CherryPickResult.CherryPickStatus.OK) {
                    System.out.printf("Commit %s cherry-picked successfully%n", hash);
                } else {
                    System.out.println("Backport finished with status: " + result.getStatus());
                }
            }

            // All commits have been applied. Now push the new branch to the remote.
            System.out.println("All commits cherry-picked. Pushing backport branch to remote...");
            pushBranch(git, backportBranchName);
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    // Initializes the Repository by using the working directory and searching upward for .git.
    private Repository initialiseRepository() throws IOException {
        return new FileRepositoryBuilder()
                .setWorkTree(new File(System.getProperty("user.dir")))
                .readEnvironment()
                .findGitDir(new File(System.getProperty("user.dir")))
                .setMustExist(true)
                .build();
    }

    // Resolves the commit hash from a branch (returns the latest commit on the specified branch).
    private String resolveCommitHash(Repository repo, String branch) throws IOException {
        ObjectId commitId = repo.resolve("refs/heads/" + branch);
        if (commitId == null) {
            throw new IllegalArgumentException("Source branch not found: " + branch);
        }
        return commitId.getName();
    }

    // Checks out an existing branch.
    private void checkoutBranch(Git git, String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
    }

    // Creates a new branch for backporting (from the current branch, which is the target branch).
    private void createBackportBranch(Git git, String branchName) throws GitAPIException {
        git.checkout().setCreateBranch(true).setName(branchName).call();
    }

    // Handles the cherry-pick operation and its result.
    private void pushBranch(Git git, String branchName) throws GitAPIException {
        // Push the newly created branch to the remote named "origin"
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/" + branchName + ":refs/heads/" + branchName))
                .call();
        System.out.println("Push successful for branch: " + branchName);
    }

    // Displays conflicts when they occur and instructs the user to resolve them manually.
    private void handleConflicts(Git git) throws GitAPIException {
        Status status = git.status().call();
        Set<String> conflictingFiles = status.getConflicting();
        if (!conflictingFiles.isEmpty()) {
            System.out.println("❌ Conflicts detected in:");
            for (String file : conflictingFiles) {
                System.out.println("   🔥 " + file);
            }
        }
        System.out.println("\n🔧 Merge conflict during cherry-pick.");
        System.out.println("Please resolve the conflict manually, then run:");
        System.out.println("    git add <file>");
        System.out.println("    git cherry-pick --continue");
    }
}
