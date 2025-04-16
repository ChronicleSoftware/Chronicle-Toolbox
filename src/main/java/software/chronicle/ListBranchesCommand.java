package software.chronicle;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
@Singleton
public class ListBranchesCommand implements Runnable {

    @CommandLine.Option(names = "--filter", description = "Filter branches by name (e.g., release/)")
    String filter;

    @Override
    public void run() {
        try {
            Repository repo = new FileRepositoryBuilder()
                    .readEnvironment()
                    .findGitDir(new File(System.getProperty("user.dir")))
                    .build();

            Git git = new Git(repo);

            List<String> branches = git.branchList().call().stream()
                    .map(ref -> ref.getName().replace("refs/heads/", ""))
                    .filter(name -> filter == null || name.startsWith(filter))
                    .toList();

            System.out.println("Available branches:");
            for (int i = 0; i < branches.size(); i++) {
                System.out.printf("  %d. %s%n", i + 1, branches.get(i));
            }

        } catch (IOException | GitAPIException e) {
            System.out.println("❌ Failed to list branches:");
            e.printStackTrace();
        }
    }
}
