package software.chronicle;

import picocli.CommandLine;

public class ChronicleToolboxMain {
    public static void main(String[] args) {
        // This is the main entry point for the application
        // It will run the command line interface
        // The command line interface is defined in the ChronicleToolboxCommand class
        CommandLine cl = new CommandLine(new ChronicleToolboxCommand());
        cl.execute(args);
    }
}
