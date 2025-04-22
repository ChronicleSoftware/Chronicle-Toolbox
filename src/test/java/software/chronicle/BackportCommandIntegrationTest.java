package software.chronicle;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BackportCommand} using JGit + JUnit 5.
 *  - Repos are spun up in a @TempDir so nothing touches the real FS.
 *  - We assert both happy‑paths and a selection of negative / edge cases
 *    that could “fail fast” *before* the command even reaches the info logs
 *    in a production run.
 */
class BackportCommandIntegrationTest {

    @TempDir
    Path tempDir;
    private String originalUserDir;

    /*───────────────────────────  Helpers  ───────────────────────────*/

    @BeforeEach
    void hijackWorkingDir() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void resetWorkingDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    /** Initialise a brand‑new repo with a single “Initial commit”. */
    private Git initRepo() throws GitAPIException, IOException {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        Path file = tempDir.resolve("app.txt");
        Files.write(file, List.of("Line 1: v1"));
        git.add().addFilepattern("app.txt").call();
        git.commit().setMessage("Initial commit").call();
        return git;
    }

    private RevCommit commitFile(Git git, String file, String line, String msg) throws Exception {
        Path p = tempDir.resolve(file);
        Files.write(p, List.of(line), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        git.add().addFilepattern(file).call();
        return git.commit().setMessage(msg).call();
    }

    /*────────────────────────  Positive paths  ───────────────────────*/

    @Test
    void testSingleCommitBackport() throws Exception {
        // Tests the backporting of a single commit to a target branch.
        Git git = initRepo();
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        commitFile(git, "app.txt", "Line 2", "release/2.26 base");

        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        RevCommit bugfix = commitFile(git, "app.txt", "Line 3", "Bug fix");

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps   = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = null;
        cmd.run();

        ObjectId head = git.getRepository().resolve("refs/heads/feature/backport_release_2.26");
        assertNotNull(head);
        boolean present = false;
        for (RevCommit c : git.log().add(head).call()) {
            if (c.getName().equals(bugfix.getName())) { present = true; break; }
        }
        assertTrue(present, "bugfix must be in backport branch");
    }

    @Test
    void testAutoDependencyResolution() throws Exception {
        // Tests that ancestor commits are automatically resolved and backported in order.
        Git git = initRepo();
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        commitFile(git, "app.txt", "Line 2", "release/2.26 base");

        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        commitFile(git, "util.txt", "v1", "Commit A: add util.txt");
        commitFile(git, "util.txt", "v2", "Commit B: update util.txt to v2");
        commitFile(git, "app.txt", "Line 3", "Commit C: independent change");
        RevCommit D = commitFile(git, "util.txt", "v3", "Commit D: tweak util.txt to v3");

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(D.getName()); // let auto‑deps pull the rest
        cmd.run();

        ObjectId head = git.getRepository().resolve("refs/heads/feature/backport_release_2.26");
        assertNotNull(head);

        List<String> msgs = new ArrayList<>();
        for (RevCommit c : git.log().add(head).call()) msgs.add(c.getShortMessage());
        Collections.reverse(msgs);

        assertEquals(List.of(
                "Initial commit",
                "release/2.26 base",
                "Commit A: add util.txt",
                "Commit B: update util.txt to v2",
                "Commit C: independent change",
                "Commit D: tweak util.txt to v3"
        ), msgs.subList(0, 6));
    }

    @Test
    void testConflictHandling() throws Exception {
        // Tests that the user is prompted to resolve conflicts during a cherry-pick.
        Git git = initRepo();
        Path app = tempDir.resolve("app.txt");

        // source
        git.branchCreate().setName("release/2.28").call();
        git.checkout().setName("release/2.28").call();
        Files.writeString(app, "Line 1: v3", StandardOpenOption.TRUNCATE_EXISTING);
        git.add().addFilepattern("app.txt").call();
        RevCommit srcCommit = git.commit().setMessage("v3 change").call();

        // target
        git.checkout().setCreateBranch(true).setName("release/2.26").call();
        Files.writeString(app, "Line 1: v2", StandardOpenOption.TRUNCATE_EXISTING);
        git.add().addFilepattern("app.txt").call();
        git.commit().setMessage("v2 change").call();

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps   = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(srcCommit.getName());

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(buf));
        try { cmd.run(); } finally { System.setOut(orig); }

        String out = buf.toString();
        assertTrue(out.contains("git cherry-pick --continue"), "should instruct how to continue");
    }

    @Test
    void testMissingSourceBranch() throws Exception {
        // Tests that an exception is thrown when the source branch does not exist.
        Git git = initRepo();
        git.branchCreate().setName("release/2.26").call();

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "non-existent";
        cmd.targetBranch = "release/2.26";
        cmd.noAutoDeps   = true;
        cmd.commitHashes = List.of("deadbeef");

        assertThrows(IllegalArgumentException.class, cmd::run, "invalid source branch should throw");
    }

    @Test
    void testMissingTargetBranch() throws Exception {
        // Tests that an exception is thrown when the target branch does not exist.
        Git git = initRepo();
        git.branchCreate().setName("release/2.28").call();

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "nope";
        cmd.noAutoDeps   = true;
        cmd.commitHashes = List.of();

        Exception ex = assertThrows(RuntimeException.class, cmd::run);
        assertInstanceOf(RefNotFoundException.class, ex.getCause(), "Expected cause to be RefNotFoundException");
    }

    @Test
    void testDuplicateCommitDeduplication() throws Exception {
        // Tests that duplicate commit hashes are de-duplicated during backporting.
        Git git = initRepo();
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();

        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        RevCommit A = commitFile(git, "util.txt", "v1", "A util");
        RevCommit B = commitFile(git, "util.txt", "v2", "B util");

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps   = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(A.getName(), B.getName(), A.getName()); // duplicate A
        cmd.run();

        ObjectId head = git.getRepository().resolve("refs/heads/feature/backport_release_2.26");
        List<String> picked = new ArrayList<>();
        for (RevCommit c : git.log().add(head).call()) picked.add(c.getShortMessage());
        assertEquals(1, picked.stream().filter(s -> s.equals("A util")).count(), "A should be cherry‑picked once");
    }
}
