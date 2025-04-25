package software.chronicle.commands;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jboss.logging.Logger;
import picocli.CommandLine;
import jakarta.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.util.List;

@CommandLine.Command(
        name = "list-branches",
        aliases = {"ls"},
        description = "Lists available Git branches, optionally filtered by prefix."
)

/*
  Nice to confirm that clt sees all the same things that git sees.
  Its git counterpart is git branch --list
 */
@Singleton
public class ListBranchesCommand implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ListBranchesCommand.class);

    @CommandLine.Option(names = "--filter", description = "Filter branches by name (e.g., release/)")
    String filter;

    @Override
    public void run() {
        LOGGER.info("Starting the branch listing process.");
        try {
            Repository repo = new FileRepositoryBuilder()
                    .readEnvironment()
                    .findGitDir(new File(System.getProperty("user.dir")))
                    .build();
            LOGGER.info("Repository loaded successfully.");

            Git git = new Git(repo);
            LOGGER.info("Git instance initialized.");

            List<String> branches = git.branchList().call().stream()
                    .map(ref -> ref.getName().replace("refs/heads/", ""))
                    .filter(name -> filter == null || name.startsWith(filter))
                    .toList();
            LOGGER.infof("Branches retrieved successfully. Total branches: %d", branches.size());

            System.out.println("Available branches:");
            for (int i = 0; i < branches.size(); i++) {
                System.out.printf("  %d. %s%n", i + 1, branches.get(i));
            }

        } catch (IOException | GitAPIException e) {
            LOGGER.error("An error occurred while listing branches.", e);
        }
    }
}
