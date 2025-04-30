package software.chronicle.commands;

import org.eclipse.jgit.api.RebaseResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jboss.logging.Logger;
import software.chronicle.utils.GitUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Command(
    name        = "rebase-all",
    aliases = {"ra"},
    description = "Fetches and rebases <branch> across all repos in your repos.yaml."
)
public class RebaseAllCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(RebaseAllCommand.class);

    @Option(names = {"-n","--branch"},
            description = "Local branch to rebase (e.g. feature/foo).",
            required = true)
    String branch;

    @Option(names = {"-b","--base-branch"},
            defaultValue = "master",
            description = "Base branch to pull in before replaying commits (default: master).")
    String baseBranch;

    @Option(names = {"-c","--config-file"},
            description = "YAML file listing repositories (default: ./repos.yaml)",
            defaultValue = "rebase-all-repos.yaml")
    File configFile;

    @Option(names = {"-p","--push"},
            description = "After a successful rebase, push the rebased branch upstream.")
    boolean push;

    @Override
    public void run() {
        List<String> repos = GitUtils.loadReposFromFile(configFile);
        if (repos.isEmpty()) {
            LOGGER.error("No repositories to process (check repos.yaml).");
            return;
        }

        for (String path : repos) {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                LOGGER.errorf("Not a directory, skipping: %s", path);
                continue;
            }

            LOGGER.infof("→ Processing repo: %s", path);
            try (Git git = GitUtils.openRepository(dir)) {
                // 1) Clean state
                GitUtils.ensureCleanState(git);

                // 2) Checkout the branch to rebase
                GitUtils.checkoutBranch(git, branch);

                // 3) Fetch the base branch from origin
                LOGGER.infof("  Fetching origin/%s", baseBranch);
                git.fetch().setRemote("origin").call();

                // 4) Rebase onto origin/<base-branch>
                LOGGER.infof("  Rebasing %s onto origin/%s", branch, baseBranch);
                RebaseResult result = git.rebase()
                    .setUpstream("origin/" + baseBranch)
                    .call();
                if (result.getStatus().isSuccessful()) {
                    LOGGER.info("  Rebase successful.");
                    // 5) Optionally push
                    if (push) {
                        git.push().setRemote("origin").add(branch).call();
                        LOGGER.infof("  Pushed rebased branch to origin/%s", branch);
                    }
                } else {
                    LOGGER.errorf("  Rebase failed: %s", result.getStatus());
                }

            } catch (IOException | GitAPIException e) {
                LOGGER.errorf("  Error in %s: %s", path, e.getMessage());
            } catch (IllegalStateException e) {
                LOGGER.errorf("  Unsafe workspace in %s: %s", path, e.getMessage());
            }
        }
    }
}
