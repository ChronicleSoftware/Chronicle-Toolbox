package software.chronicle.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.jboss.logging.Logger;
import software.chronicle.utils.GitUtils;

import java.io.IOException;

/**
 * feature-branch (fb)
 * Creates a new feature branch named feature/&lt;name&gt; from HEAD or from a specified base branch.
 * Assumes the working tree is clean and no remote operations are performed.
 */
@Command(
    name        = "feature-branch",
    aliases     = {"fb"},
    mixinStandardHelpOptions = true,
    description = "Creates a new feature branch called feature/<name>."
)
@SuppressWarnings("unused")
public class FeatureBranchCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(FeatureBranchCommand.class);

    @Option(names = {"-n", "--new-branch"},
            description = "Name of the feature (the new branch will be feature/<name>).",
            required = true)
    String branchName;

    @Option(names = {"-b", "--base-branch"},
            description = "Local base branch to create from (defaults to current HEAD).")
    String baseBranch;

    @Option(names = "--force",
            description = "Skip clean‐state check and proceed even if there are uncommitted changes.")
    boolean force;

    @Override
    public void run() {
        LOGGER.infof("Starting feature-branch creation: feature/%s", branchName);
        try (Git git = new Git(GitUtils.loadCurrentRepository())) {
            // 1) Ensure clean working directory
            if (!force) {
                GitUtils.ensureCleanState(git);
            } else {
                LOGGER.warn("Skipping clean‐state check (--force)");
            }

            Repository repo = git.getRepository();

            // 2) Determine start point
            String startPoint;
            if (baseBranch != null && !baseBranch.isBlank()) {
                startPoint = baseBranch;
                LOGGER.infof("Checking out base branch: %s", startPoint);
            } else {
                startPoint = repo.getBranch();
                LOGGER.infof("Using current HEAD branch as base: %s", startPoint);
            }

            // 3) Create the new feature branch
            String featureBranch = "feature/" + branchName;
            GitUtils.createBranch(git, featureBranch, startPoint);
            LOGGER.infof("Feature branch '%s' created from '%s'", featureBranch, startPoint);

        } catch (IOException e) {
            LOGGER.errorf("I/O error loading repository: \n%s", e.getMessage());
        } catch (GitAPIException e) {
            LOGGER.errorf("Git operation failed: \n%s", e.getMessage());
        } catch (IllegalStateException e) {
            LOGGER.errorf("Unsafe working directory: \n%s", e.getMessage());
        }
    }
}
