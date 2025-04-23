package software.chronicle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

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

    @Option(names = {"-n", "--branch-name"},
            required = true,
            description = "Name of the branch to create (e.g. release/v1.2.0).")
    private String branchName;

    @Option(names = {"-B", "--base-branch"},
            description = "Local base branch to create from (defaults to each repo's current branch).")
    private String baseBranch;

    @Option(names = {"-c", "--config-file"},
            defaultValue = "repos.yaml",
            description = "Path to repos config file (YAML format).")
    private File configFile;

    private static final Logger logger = Logger.getLogger(CreateVerBranchCommand.class.getName());

    @Override
    public void run() {
        List<String> repos = GitUtils.loadReposFromFile(configFile);
        if (repos.isEmpty()) {
            logger.severe("No repositories to process.");
            return;
        }

        for (String path : repos) {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                logger.severe("Not a directory: " + path);
                continue;
            }

            logger.info("Processing repo: " + path);
            try (Git git = GitUtils.openRepository(dir)) {
                String startPoint;
                if (baseBranch == null || baseBranch.isBlank()) {
                    startPoint = git.getRepository().getBranch(); // use current HEAD branch
                    logger.info("  Using current branch as base: " + startPoint);
                } else {
                    startPoint = baseBranch;
                    GitUtils.checkoutBranch(git, startPoint);
                }

                GitUtils.createBranch(git, branchName, startPoint);

            } catch (IOException | GitAPIException e) {
                logger.severe("Error processing " + path + ": " + e.getMessage());
            }
        }
    }
}
