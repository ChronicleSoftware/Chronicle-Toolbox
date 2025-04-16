package software.chronicle;

import picocli.CommandLine;

@CommandLine.Command(
        name = "create-version-branch",
        aliases = {"cvb"},
        mixinStandardHelpOptions = true,
        description = "Creates a new version branch from the specified source branch."
)

public class CreateVerBranchCommand implements Runnable {

    @CommandLine.Option(names = {"-s", "--source"}, description = "Source branch to create the version branch from.", required = true)
    String sourceBranch;

    @Override
    public void run() {

    }
}
