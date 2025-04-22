package software.chronicle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
public class CreateVerBranchCommand implements Runnable {

    @Option(names = {"-n", "--branch-name"},
            required = true,
            description = "Name of the branch to create (e.g. release/v1.2.0).")
    private String branchName;

    @Option(names = {"-B", "--base-branch"},
            defaultValue = "main",
            description = "Local base branch to create from (defaults to main).")
    private String baseBranch;

    @Option(names = {"-c", "--config-file"},
            defaultValue = "repos.yaml",
            description = "Path to repos config file (YAML format).")
    private File configFile;

    private static final Logger logger = Logger.getLogger(CreateVerBranchCommand.class.getName());

    @Override
    public void run() {
        // 1) Load configuration via SnakeYAML
        if (!configFile.exists()) {
            logger.severe("Config file not found: " + configFile.getAbsolutePath());
            return;
        }

        List<String> repos;
        Yaml yaml = new Yaml();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Map<String, Object> cfg = yaml.load(fis);
            Object list = cfg.get("repositories");
            if (!(list instanceof List)) {
                logger.severe("Config file must contain a top-level 'repositories' list.");
                return;
            }
            // unchecked cast, but config file should ensure type
            //noinspection unchecked
            repos = (List<String>) list;
        } catch (IOException e) {
            logger.severe("Failed to read config file: " + e.getMessage());
            return;
        }

        // 2) Iterate each repo
        for (String repoPath : repos) {
            File repoDir = new File(repoPath);
            if (!repoDir.isDirectory()) {
                logger.severe("Not a directory: " + repoPath);
                continue;
            }

            logger.info("Processing repo: " + repoPath);
            try (Git git = Git.open(repoDir)) {
                // Checkout local base branch
                try {
                    git.checkout()
                        .setName(baseBranch)
                        .call();
                } catch (GitAPIException e) {
                    logger.severe("  Failed to checkout base branch '" + baseBranch + "': " + e.getMessage());
                    continue;
                }

                // Create & checkout new branch from local base branch
                try {
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(branchName)
                        .setStartPoint(baseBranch)
                        .call();
                    logger.info("  Created and checked out branch '" + branchName + "'");
                } catch (RefAlreadyExistsException ex) {
                    logger.warning("  Branch already exists: " + branchName);
                } catch (GitAPIException e) {
                    logger.severe("  Failed to create branch '" + branchName + "': " + e.getMessage());
                }

            } catch (IOException e) {
                logger.severe("  Unable to open repository at " + repoPath + ": " + e.getMessage());
            }
        }
    }
}
