package software.chronicle;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;
import software.chronicle.commands.BackportCommand;
import software.chronicle.commands.CreateVerBranchCommand;
import software.chronicle.commands.FeatureBranchCommand;
import software.chronicle.commands.ListBranchesCommand;

@TopCommand
@CommandLine.Command(name = "clt", subcommands = {
        BackportCommand.class,
        CreateVerBranchCommand.class,
        FeatureBranchCommand.class,
        ListBranchesCommand.class
})
public class ChronicleToolboxCommand {}
