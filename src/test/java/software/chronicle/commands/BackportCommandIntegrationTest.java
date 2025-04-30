package software.chronicle.commands;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BackportCommand} using JGit + JUnit 5.
 *  - Repos are spun up in a @TempDir so nothing touches the real FS.
 *  - We assert both happy‑paths and a selection of negative / edge cases
 *    that could “fail fast” *before* the command even reaches the info logs
 *    in a production run.
 */
class BackportCommandIntegrationTest {

    @TempDir Path tempDir;
    private String originalUserDir;
    private PrintStream originalOut, originalErr;
    private Git git;

    /**
     * Set up fresh environment: working dir and IO streams, clear previous Git.
     */
    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        originalOut = System.out;
        originalErr = System.err;
        git = null;
    }

    /**
     * Tear down: restore working dir, IO streams, and close any open Git.
     */
    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (git != null) git.close();
    }

    /** Initialise a brand‑new repo with a single “Initial commit”. */
    private Git initRepo() throws GitAPIException, IOException {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        Path file = tempDir.resolve("app.txt");
        Files.write(file, List.of("Line 1: v1"));
        git.add().addFilepattern("app.txt").call();
        git.commit().setMessage("Initial commit").call();
        return git;
    }

    private RevCommit commitFile(Git repo, String file, String line, String msg) throws Exception {
        Path p = tempDir.resolve(file);
        Files.write(p, List.of(line), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        repo.add().addFilepattern(file).call();
        return repo.commit().setMessage(msg).call();
    }

    /**
     * Locates the automatically-named backport branch for the given target.
     * Pattern: refs/heads/backport/<target-with-"/"→"-">/<short-hash>
     */
    private Ref findBackportBranch(Git git) throws GitAPIException {
        return git.branchList()
                .call()
                .stream()
                .filter(ref -> ref.getName().startsWith("refs/heads/backport/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No backport branch found; expected one starting with 'backport/'"
                ));
    }

    @Test
    void testSingleCommitBackport() throws Exception {
        // Tests the backporting of a single commit to a target branch.
        git = initRepo();

        // set up base
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        commitFile(git, "app.txt", "Line 2", "release/2.26 base");

        // feature branch with bugfix
        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        RevCommit bugfix = commitFile(git, "app.txt", "Line 3", "Bug fix");

        // run backport
        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps   = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = null;   // single latest
        cmd.run();

        Ref backportRef = findBackportBranch(git);
        ObjectId head = backportRef.getObjectId();
        boolean found = false;
        for (RevCommit c : git.log().add(head).call()) {
            if (c.getShortMessage().equals(bugfix.getShortMessage())) {  // compare message
                found = true;
                break;
            }
        }
        assertTrue(found, "bugfix must appear in the new backport branch (by message)");

    }

    @Test
    void testAutoDependencyResolution() throws Exception {
        // Tests that ancestor commits are automatically resolved and backported in order.
        git = initRepo();

        // base
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        commitFile(git, "app.txt", "Line 2", "release/2.26 base");

        // feature with multiple commits
        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        commitFile(git, "util.txt", "v1", "Commit A: add util.txt");
        commitFile(git, "util.txt", "v2", "Commit B: update util.txt to v2");
        commitFile(git, "app.txt", "Line 3", "Commit C: independent change");
        RevCommit last = commitFile(git, "util.txt", "v3", "Commit D: tweak util.txt to v3");

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch   = "release/2.28";
        cmd.targetBranch   = "release/2.26";
        cmd.commitHashes   = List.of(last.getName());
        cmd.noAutoDeps     = false; // default

        cmd.run();

        Ref backportRef = findBackportBranch(git);
        ObjectId head = backportRef.getObjectId();

        List<String> msgs = new ArrayList<>();
        for (RevCommit c : git.log().add(head).call()) {
            msgs.add(c.getShortMessage());
        }
        Collections.reverse(msgs);

        // The first 6 commits should be:
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
        git = initRepo();
        Path app = tempDir.resolve("app.txt");

        // source that rewrites Line 1
        git.branchCreate().setName("release/2.28").call();
        git.checkout().setName("release/2.28").call();
        Files.writeString(app, "Line 1: v3", StandardOpenOption.TRUNCATE_EXISTING);
        git.add().addFilepattern("app.txt").call();
        RevCommit src = git.commit().setMessage("v3 change").call();

        // target rewriting the same line differently
        git.checkout().setCreateBranch(true).setName("release/2.26").call();
        Files.writeString(app, "Line 1: v2", StandardOpenOption.TRUNCATE_EXISTING);
        git.add().addFilepattern("app.txt").call();
        git.commit().setMessage("v2 change").call();

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps   = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(src.getName());

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            cmd.run();
        } finally {
            System.setOut(originalOut);
        }
        String out = buf.toString();
        assertTrue(out.contains("git cherry-pick --continue"), "should instruct how to continue");
    }

    @Test
    void testMissingSourceBranch() throws Exception {
        // Tests that an exception is thrown when the source branch does not exist.
        git = initRepo();
        git.branchCreate().setName("release/2.26").call();

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "non-existent";
        cmd.targetBranch = "release/2.26";
        cmd.noAutoDeps   = true;
        cmd.commitHashes = List.of("deadbeef");

        BackportCommand.BackportException ex = assertThrows(BackportCommand.BackportException.class, cmd::run);
        assertTrue(ex.getCause() instanceof RefNotFoundException,
            "Expected cause to be RefNotFoundException");
    }

    @Test
    void testMissingTargetBranch() throws Exception {
        git = initRepo();
        git.branchCreate().setName("release/2.28").call();

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "nope";
        cmd.noAutoDeps   = true;
        cmd.commitHashes = List.of();

        BackportCommand.BackportException ex = assertThrows(BackportCommand.BackportException.class, cmd::run);
        assertTrue(ex.getCause() instanceof RefNotFoundException,
            "Expected cause to be RefNotFoundException");
    }

    @Test
    void testDuplicateCommitDeduplication() throws Exception {
        // Tests that duplicate commit hashes are de-duplicated during backporting.
        git = initRepo();

        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();

        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        RevCommit A = commitFile(git, "util.txt", "v1", "A util");
        RevCommit B = commitFile(git, "util.txt", "v2", "B util");

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps   = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(A.getName(), B.getName(), A.getName());

        cmd.run();

        Ref backportRef = findBackportBranch(git);
        ObjectId head = backportRef.getObjectId();

        List<String> picked = new ArrayList<>();
        for (RevCommit c : git.log().add(head).call()) {
            picked.add(c.getShortMessage());
        }
        long countA = picked.stream().filter(msg -> msg.equals("A util")).count();
        assertEquals(1, countA, "A should be cherry-picked exactly once");
    }

    @Test
    void testComplexHistoryMerge() throws Exception {
        // Tests backporting from a merged branch with complex history.
        git = initRepo();

        // Setup base branch
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        commitFile(git, "base.txt", "base", "Base commit");

        // Create diverging branches
        git.checkout().setCreateBranch(true).setName("featureA").call();
        commitFile(git, "a.txt", "A1", "A1 commit");
        RevCommit a2 = commitFile(git, "a.txt", "A2", "A2 commit");

        git.checkout().setName("release/2.26").call();
        git.checkout().setCreateBranch(true).setName("featureB").call();
        commitFile(git, "b.txt", "B1", "B1 commit");

        // Merge featureA into featureB to create a complex history
        git.merge().include(a2).call();

        // Backport from merged branch
        String mergedBranch = "featureB";
        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = mergedBranch;
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(); // use latest commit
        cmd.run();

        Ref backportRef = findBackportBranch(git);
        assertNotNull(backportRef);
    }

    @Test
    void testMidSequenceConflict() throws Exception {
        // Tests that a conflict in the middle of a sequence of commits is handled correctly.
        git = initRepo();
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        commitFile(git, "common.txt", "v1", "base");

        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        commitFile(git, "common.txt", "A", "Commit A");
        commitFile(git, "common.txt", "B", "Commit B");
        RevCommit C = commitFile(git, "common.txt", "C", "Commit C");
        commitFile(git, "common.txt", "D", "Commit D");

        // Create conflict in target
        git.checkout().setName("release/2.26").call();
        Files.writeString(tempDir.resolve("common.txt"), "conflict line", StandardOpenOption.TRUNCATE_EXISTING);
        git.add().addFilepattern("common.txt").call();
        git.commit().setMessage("conflict").call();

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(C.getName());
        cmd.run();

        Ref backportRef = findBackportBranch(git);
        assertNotNull(backportRef);
        assertEquals("CHERRY_PICKING", git.getRepository().getRepositoryState().name());
    }

    @Test
    void testEmptyCommitBackport() throws Exception {
        // Tests that an empty commit can be backported.
        git = initRepo();
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setCreateBranch(true).setName("release/2.28").call();

        RevCommit empty = git.commit().setMessage("empty").setAllowEmpty(true).call();

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(empty.getName());
        cmd.run();

        Ref backportRef = findBackportBranch(git);
        assertNotNull(backportRef);
    }

    @Test
    void testAlreadyPickedCommit() throws Exception {
        // Tests that a commit already cherry-picked into the target branch is skipped.
        git = initRepo();
        git.branchCreate().setName("release/2.26").call();
        git.checkout().setName("release/2.26").call();
        RevCommit base = commitFile(git, "dupe.txt", "same", "base");

        git.checkout().setCreateBranch(true).setName("release/2.28").call();
        // manually cherry-pick already done
        git.cherryPick().include(base).call();

        BackportCommand cmd = new BackportCommand();
        cmd.noAutoDeps = true;
        cmd.sourceBranch = "release/2.28";
        cmd.targetBranch = "release/2.26";
        cmd.commitHashes = List.of(base.getName());
        cmd.run(); // Should skip or no-op, not fail
    }

    @Test
    void testUntrackedFilePreventsBackport() throws Exception {
        // Tests that an untracked file prevents backporting.
        git = initRepo();
        Files.writeString(tempDir.resolve("junk.txt"), "temp");

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "main";
        cmd.targetBranch = "main";
        cmd.noAutoDeps = true;
        cmd.commitHashes = List.of();

        Exception ex = assertThrows(IllegalStateException.class, cmd::run);
        assertTrue(ex.getMessage().contains("not clean"));
    }

    @Test
    void testUnsafeRepoStateFails() throws Exception {
        // Tests that the command fails if the repository is in an unsafe state.
        git = initRepo();
        Path cherryPath = tempDir.resolve(".git").resolve("CHERRY_PICK_HEAD");
        Files.writeString(cherryPath, "deadbeef");

        BackportCommand cmd = new BackportCommand();
        cmd.sourceBranch = "main";
        cmd.targetBranch = "main";
        cmd.noAutoDeps = true;
        cmd.commitHashes = List.of();

        Exception ex = assertThrows(IllegalStateException.class, cmd::run);
        assertTrue(ex.getMessage().contains("unsafe state"));
    }
}
