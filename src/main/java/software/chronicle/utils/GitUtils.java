package software.chronicle.utils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility class for common Git and config operations.
 */
public class GitUtils {
    private static final Logger LOGGER = Logger.getLogger(GitUtils.class.getName());

    /**
     * Load the current Git repository from the working directory.
     * @return Repository instance.
     * @throws IOException if repo cannot be loaded.
     */
    public static Repository loadCurrentRepository() throws IOException {
        LOGGER.info("Loading repository");
        return new FileRepositoryBuilder()
                .setWorkTree(new File(System.getProperty("user.dir")))
                .readEnvironment()
                .findGitDir(new File(System.getProperty("user.dir")))
                .setMustExist(true)
                .build();
    }

    /**
     * Load a list of repository paths from a YAML config file.
     * Supports top-level keys "repos" or "repositories".
     * @param configFile YAML file defining repo paths.
     * @return List of repository directory paths.
     */
    @SuppressWarnings("unchecked")
    public static List<String> loadReposFromFile(File configFile) {
        if (!configFile.exists()) {
            LOGGER.severe("Config file not found: " + configFile.getAbsolutePath());
            return List.of();
        }
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> cfg = yaml.load(fis);
            Object list = cfg.getOrDefault("repos", cfg.get("repositories"));
            if (!(list instanceof List)) {
                LOGGER.severe("Missing or invalid 'repos' list in config file.");
                return List.of();
            }
            return (List<String>) list;
        } catch (IOException e) {
            LOGGER.severe("Failed to parse config file: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Open an existing Git repository.
     * @param repoDir Directory of the repository.
     * @return Git instance.
     * @throws IOException if repo cannot be opened.
     */
    public static Git openRepository(File repoDir) throws IOException {
        return Git.open(repoDir);
    }

    /**
     * Checkout an existing branch.
     * @param git Git instance.
     * @param branchName Name of branch to checkout.
     * @throws GitAPIException if checkout fails.
     * @throws RefNotFoundException if branch does not exist.
     */
    public static void checkoutBranch(Git git, String branchName) throws GitAPIException {
        git.checkout()
            .setName(branchName)
            .call();
        LOGGER.info("Checked out branch: " + branchName);
    }

    /**
     * Create and checkout a new branch from a start point.
     *
     * @param git        Git instance.
     * @param newBranch  Name of branch to create.
     * @param startPoint Starting ref (e.g. "main" or "origin/main").
     * @throws GitAPIException for other errors.
     */
    public static void createBranch(Git git, String newBranch, String startPoint) throws GitAPIException {
        try {
            git.checkout()
                .setCreateBranch(true)
                .setName(newBranch)
                .setStartPoint(startPoint)
                .call();
            LOGGER.info("Created and checked out branch: " + newBranch);
        } catch (RefAlreadyExistsException e) {
            LOGGER.warning("Branch already exists: " + newBranch);
        }
    }

    /**
     * Ensures the working directory is clean (no unstaged/staged changes)
     * and repository is in a SAFE state (not mid-merge/rebase/cherry-pick).
     * @throws GitAPIException if status cannot be read
     * @throws IllegalStateException if the repo is dirty or in an unsafe state
     */
    public static void ensureCleanState(Git git) throws GitAPIException {
        Repository repo = git.getRepository();
        Status status = git.status().call();

        if (!status.isClean()
                || !status.getUncommittedChanges().isEmpty()
                || !status.getUntracked().isEmpty()) {
            throw new IllegalStateException(
                    "Working directory is not clean. " +
                            "Please commit or stash changes before running this command. " +
                            "Uncommitted: " + status.getUncommittedChanges() +
                            ", Untracked: " + status.getUntracked()
            );
        }

        RepositoryState state = repo.getRepositoryState();
        if (state != RepositoryState.SAFE) {
            throw new IllegalStateException(
                    "Repository is in an unsafe state: " + state +
                            ". Please resolve any in-progress merge/rebase/cherry-pick."
            );
        }
    }

    /**
     * Resolves any valid Git revision string (branch, tag, SHA, HEAD, etc.)
     * into an ObjectId. Throws if it cannot be resolved.
     * @param repo       the Repository instance
     * @param revString  a ref name (e.g. "main", "refs/heads/foo"), tag, or SHA
     * @return the ObjectId of the resolved revision
     * @throws IOException if resolution fails
     * @throws RefNotFoundException if no such revision exists
     */
    public static ObjectId resolveRevString(Repository repo, String revString)
            throws IOException, RefNotFoundException {
        ObjectId id = repo.resolve(revString);
        if (id == null) {
            throw new RefNotFoundException("Could not resolve '" + revString + "' to a valid commit.");
        }
        return id;
    }
}
