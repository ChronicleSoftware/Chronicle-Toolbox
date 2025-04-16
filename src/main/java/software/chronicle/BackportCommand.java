package software.chronicle;

import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import picocli.CommandLine;
import jakarta.inject.Singleton;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.Status;

import java.io.File;
import java.io.IOException;
import java.util.Set;

// Starting it all looks like this  java -jar target/quarkus-app/quarkus-run.jar <name of subcommand> <options>
// here is the name of the subcommand
@CommandLine.Command(
        name = "backport",
        aliases = {"bp"},
        mixinStandardHelpOptions = true,
        description = "Backports bugfix commits to a specified branch."
)
@Singleton
public class BackportCommand implements Runnable {
// here are the options
    @CommandLine.Option(names = "-source", description = "The source bugfix branch", required = true)
    String sourceBranch;

    @CommandLine.Option(names = "-target", description = "The target backport branch", required = true)
    String targetBranch;

    @CommandLine.Option(names = "-name", description = "Name of your commit")
    String backportBranchName;

    @CommandLine.Option(names = "-commit", description = "The hash of the commit to backport")
    String commitHash;

    @Override
    public void run() {
        try {
            // Initialize Git repository from current directory
            Repository repo = initialiseRepository();
            Git git = new Git(repo);

            // Ensure we have a commit hash (resolve to latest from source if not given)
            commitHash = resolveCommitHash(repo);

            // If no name is provided, use the commit message for display
            if (backportBranchName == null) {
                backportBranchName = resolveCommitTitle(repo, git, commitHash);
            }
            System.out.printf("Backporting \"%s\" from %s to %s%n", backportBranchName, sourceBranch, targetBranch);

            // Checkout the target branch (do not create a new one!)
            checkoutBranch(git, targetBranch);

            // Perform the cherry-pick operation
            performCherryPick(repo, git);

        } catch (IOException | GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    private Repository initialiseRepository() throws IOException {
        return new FileRepositoryBuilder()
                .setWorkTree(new File(System.getProperty("user.dir")))
                .readEnvironment()
                .findGitDir(new File(System.getProperty("user.dir")))
                .setMustExist(true)
                .build();
    }

    private String resolveCommitHash(Repository repo) throws IOException {
        if (commitHash == null) {
            ObjectId commitId = repo.resolve("refs/heads/" + sourceBranch);
            if (commitId == null) {
                throw new IllegalArgumentException("Source branch not found: " + sourceBranch);
            }
            return commitId.getName();
        }
        return commitHash;
    }

    private String resolveCommitTitle(Repository repo, Git git, String commitHash) throws GitAPIException, IOException {
        RevCommit commit = git.log()
                .add(repo.resolve(commitHash))
                .setMaxCount(1)
                .call()
                .iterator()
                .next();
        return sanitizeForDisplay(commit.getShortMessage());
    }

    private void checkoutBranch(Git git, String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
    }

    private void performCherryPick(Repository repo, Git git) throws IOException, GitAPIException {
        CherryPickResult result = git.cherryPick()
                .include(repo.resolve(commitHash))
                .call();

        if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING) {
            handleConflicts(git);
        } else if (result.getStatus() == CherryPickResult.CherryPickStatus.OK) {
            System.out.println("Backport successful");
            git.push()
                    .setRemote("origin")
// Retrieves creds (eventually via prompt) .setCredentialsProvider(new UsernamePasswordCredentialsProvider("username", "token"))
                    .setRefSpecs(new RefSpec("refs/heads/" + targetBranch + ":refs/heads/" + targetBranch))
                    .call();
            System.out.println("Push to GitHub successful");
        } else {
            System.out.println("Backport finished with status: " + result.getStatus());
        }
    }

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

    private String sanitizeForDisplay(String input) {
        return input.replaceAll("[^a-zA-Z0-9\\s]", "").trim();
    }
}
