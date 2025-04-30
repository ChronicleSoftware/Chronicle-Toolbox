package software.chronicle;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;
import software.chronicle.commands.*;

@TopCommand
@CommandLine.Command(name = "clt", subcommands = {
        BackportCommand.class,
        CreateVerBranchCommand.class,
        FeatureBranchCommand.class,
        ListBranchesCommand.class,
        RebaseAllCommand.class
})
public class ChronicleToolboxCommand {}
