package software.chronicle;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CreateVerBranchCommandIntegrationTest {

    @TempDir Path tempDir;

    /**
     * Initializes a fresh git repository with a single commit on the default branch (master).
     */
    private Git initRepo(Path repoDir) throws Exception {
        Git git = Git.init().setDirectory(repoDir.toFile()).call();
        Path file = repoDir.resolve("README.md");
        Files.writeString(file, "# Repo", StandardOpenOption.CREATE);
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial").call();
        return git;
    }

    @Test
    void testCreateVersionBranchDefaultBase() throws Exception {
        // Setup two repos
        Path repo1 = tempDir.resolve("repo1"); Files.createDirectory(repo1);
        Path repo2 = tempDir.resolve("repo2"); Files.createDirectory(repo2);

        Git git1 = initRepo(repo1);
        Git git2 = initRepo(repo2);

        // Write config file
        Path config = tempDir.resolve("repos.yaml");
        String yaml = String.join("\n",
            "repos:",
            "  - " + repo1.toAbsolutePath(),
            "  - " + repo2.toAbsolutePath()
        );
        Files.writeString(config, yaml, StandardOpenOption.CREATE);

        // Run command without --base-branch
        CreateVerBranchCommand cmd = new CreateVerBranchCommand();
        cmd.branchName = "release/v1.0.0";
        cmd.baseBranch = null;  // default
        cmd.configFile = config.toFile();
        cmd.run();

        // Verify branch in each repo
        for (Path repoDir : List.of(repo1, repo2)) {
            try (Git git = Git.open(repoDir.toFile())) {
                List<String> branches = git.branchList()
                                           .call()
                                           .stream()
                                           .map(Ref::getName)
                                           .map(name -> name.replace("refs/heads/", ""))
                                           .collect(Collectors.toList());
                assertTrue(branches.contains("release/v1.0.0"),
                           "Expected release/v1.0.0 in " + repoDir);
            }
        }
    }

    @Test
    void testCreateVersionBranchWithExplicitBase() throws Exception {
        // Setup repo
        Path repo = tempDir.resolve("repoX"); Files.createDirectory(repo);
        Git git = initRepo(repo);

        // Create and checkout 'develop'
        git.branchCreate().setName("develop").call();
        git.checkout().setName("develop").call();
        // Make a commit on develop
        Path file = repo.resolve("dev.txt");
        Files.writeString(file, "dev work", StandardOpenOption.CREATE);
        git.add().addFilepattern("dev.txt").call();
        git.commit().setMessage("dev commit").call();
        Object developHead = git.getRepository().resolve("refs/heads/develop").getName();

        // Write config with an invalid path included
        Path config = tempDir.resolve("repos.yaml");
        String yaml = String.join("\n",
            "repos:",
            "  - " + repo.toAbsolutePath(),
            "  - /non/existent/path"
        );
        Files.writeString(config, yaml, StandardOpenOption.CREATE);

        // Run command with --base-branch develop
        CreateVerBranchCommand cmd = new CreateVerBranchCommand();
        cmd.branchName = "release/v2.0.0";
        cmd.baseBranch = "develop";
        cmd.configFile = config.toFile();
        cmd.run();

        // Verify new branch exists and points to develop's HEAD
        try (Git git2 = Git.open(repo.toFile())) {
            Ref newRef = git2.getRepository().findRef("release/v2.0.0");
            assertNotNull(newRef, "Branch release/v2.0.0 should exist");
            assertEquals(developHead, newRef.getObjectId().getName(),
                         "New branch should point at develop head");
        }
    }

    @Test
    void testCreateVersionBranchInvalidPathSkipped() throws Exception {
        // Setup one valid and one invalid repo
        Path validRepo = tempDir.resolve("good"); Files.createDirectory(validRepo);
        Git git = initRepo(validRepo);

        // Write config with invalid path
        Path config = tempDir.resolve("repos.yaml");
        String yaml = String.join("\n",
            "repos:",
            "  - /nowhere123",
            "  - " + validRepo.toAbsolutePath()
        );
        Files.writeString(config, yaml, StandardOpenOption.CREATE);

        // Run command
        CreateVerBranchCommand cmd = new CreateVerBranchCommand();
        cmd.branchName = "release/v3.0.0";
        cmd.baseBranch = null;
        cmd.configFile = config.toFile();
        cmd.run();

        // Only the valid repo should have the new branch
        try (Git git2 = Git.open(validRepo.toFile())) {
            List<String> branches = git2.branchList()
                                        .call()
                                        .stream()
                                        .map(Ref::getName)
                                        .map(n -> n.replace("refs/heads/", ""))
                                        .toList();
            assertTrue(branches.contains("release/v3.0.0"));
        }
    }
}
