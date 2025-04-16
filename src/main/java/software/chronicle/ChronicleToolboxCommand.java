package software.chronicle;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(name = "clt", subcommands = {
        BackportCommand.class,
        CreateVerBranchCommand.class,
        ListBranchesCommand.class
})
public class ChronicleToolboxCommand {}
