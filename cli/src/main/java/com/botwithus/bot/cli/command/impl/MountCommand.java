package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;

import java.util.List;

public class MountCommand implements Command {

    @Override public String name() { return "mount"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Filter output to a specific connection"; }
    @Override public String usage() { return "mount [<connection>|off|none]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String arg = parsed.arg(0);
        if (arg == null) {
            // Show current mount state
            if (ctx.isMounted()) {
                ctx.out().println("Mounted to: " + ctx.getMountedConnectionName());
            } else {
                ctx.out().println("Not mounted. Use 'mount <connection>' to filter output.");
            }
            return;
        }

        if ("off".equalsIgnoreCase(arg) || "none".equalsIgnoreCase(arg)) {
            ctx.unmount();
            ctx.out().println("Unmounted — showing all output.");
            return;
        }

        // Verify the connection exists
        boolean found = ctx.getConnections().stream()
                .anyMatch(c -> c.getName().equals(arg));
        if (!found) {
            ctx.out().println("Connection not found: " + arg);
            return;
        }

        ctx.mount(arg);
        ctx.out().println("Mounted to '" + arg + "' — only showing output from this connection.");
    }
}
