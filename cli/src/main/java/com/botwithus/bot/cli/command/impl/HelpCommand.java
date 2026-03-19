package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.TableFormatter;

public class HelpCommand implements Command {

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "help"; }
    @Override public String description() { return "Show available commands"; }
    @Override public String usage() { return "help [command]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String target = parsed.arg(0);
        if (target != null) {
            Command cmd = registry.resolve(target);
            if (cmd == null) {
                ctx.out().println("Unknown command: " + target);
                return;
            }
            ctx.out().println("  " + cmd.name() + " - " + cmd.description());
            ctx.out().println("  Usage: " + cmd.usage());
            if (!cmd.aliases().isEmpty()) {
                ctx.out().println("  Aliases: " + String.join(", ", cmd.aliases()));
            }
            return;
        }

        TableFormatter table = new TableFormatter()
                .headers("Command", "Description");
        for (Command cmd : registry.all()) {
            table.row(cmd.name(), cmd.description());
        }
        ctx.out().print(table.build());
    }
}
