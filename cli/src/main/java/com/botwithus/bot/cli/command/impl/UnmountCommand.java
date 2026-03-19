package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;

import java.util.List;

public class UnmountCommand implements Command {

    @Override public String name() { return "unmount"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Stop filtering output to a specific connection"; }
    @Override public String usage() { return "unmount"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        if (!ctx.isMounted()) {
            ctx.out().println("Not currently mounted.");
            return;
        }
        String was = ctx.getMountedConnectionName();
        ctx.unmount();
        ctx.out().println("Unmounted from '" + was + "' — showing all output.");
    }
}
