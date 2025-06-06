package software.chronicle.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.jboss.logging.Logger;
import software.chronicle.utils.GitUtils;

/**
 * create-version-branch (cvb)
 * Creates a new version branch across multiple repositories using JGit.
 * <p>
 * Assumes all listed repositories are up-to-date locally; performs no remote operations.
 */
@Command(
    name        = "create-version-branch",
    aliases     = {"cvb"},
    mixinStandardHelpOptions = true,
    description = "Creates a new version branch across multiple repositories using JGit."
)
@SuppressWarnings("unused")
public class CreateVerBranchCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(CreateVerBranchCommand.class.getName());

    @Option(names = {"-n", "--new-branch"},
            description = "Name of the branch to create (e.g. release/v1.2.0).",
            required = true)
    String branchName;

    @Option(names = {"-b", "--base-branch"},
            description = "Local base branch to create from (defaults to each repo's current branch).")
    String baseBranch;

    @Option(names = {"-c", "--config-file"},
            description = "Path to repos config file (YAML format).",
            defaultValue = "cvb-repos.yaml")
    File configFile;

    @Option(names = {"--force"},
            description = "Skip clean‐state check and proceed even if there are uncommitted changes.")
    boolean force;

    @Override
    public void run() {
        // 1) Load the paths from config file
        List<String> repos = GitUtils.loadReposFromFile(configFile);
        if (repos.isEmpty()) {
            LOGGER.error("No repositories to process.");
            return;
        }

        for (String path : repos) {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                LOGGER.errorf("Not a directory: %s", path);
                continue;
            }

            // 2) Open the repository
            LOGGER.infof("Processing repo: %s", path);
            try (Git git = GitUtils.openRepository(dir)) {
                try {
                    // 3) Validate clean workspace and state unless forced
                    if (!force) {
                        GitUtils.ensureCleanState(git);
                    } else {
                        LOGGER.warn("Skipping clean‐state check (--force)");
                    }

                } catch (IllegalStateException | GitAPIException e) {
                    LOGGER.errorf("Repository not safe to modify: %s — %s", path, e.getMessage());
                    continue;
                }

                String startPoint;
                // 4) Creates branch from HEAD unless base branch is specified
                if (baseBranch == null || baseBranch.isBlank()) {
                    startPoint = git.getRepository().getBranch(); // use current HEAD branch
                    LOGGER.infof("  Using current branch as base: %s", startPoint);
                } else {
                    startPoint = baseBranch;
                }

                GitUtils.createBranch(git, branchName, startPoint);
            } catch (IOException | GitAPIException e) {
                LOGGER.errorf("Error processing %s: %s", path, e.getMessage());
            }
        }
    }
}
