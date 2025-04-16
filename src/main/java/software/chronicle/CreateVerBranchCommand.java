package software.chronicle;

import picocli.CommandLine;

@CommandLine.Command(
        name = "create-version-branch",
        aliases = {"cvb"},
        mixinStandardHelpOptions = true,
        description = "Creates a new version branch from the specified source branch."
)

public class CreateVerBranchCommand implements Runnable {

    @Override
    public void run() {

    }
}
