package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.ConnectionGroup;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class GroupCommandTest {

    private GroupCommand command;
    private CliContext ctx;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        command = new GroupCommand();
        output = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(output);
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, ps, ps);
        ctx = new CliContext(logBuffer, logCapture);
    }

    private ParsedCommand parse(String input) {
        return CommandParser.parse(input);
    }

    private String output() {
        return output.toString();
    }

    @Test
    void nameAndAliases() {
        assertEquals("group", command.name());
        assertTrue(command.aliases().contains("g"));
    }

    @Test
    void noSubcommandShowsUsage() {
        command.execute(parse("group"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void createGroup() {
        command.execute(parse("group create farm"), ctx);
        assertNotNull(ctx.getGroup("farm"));
        assertTrue(output().contains("Group 'farm' created."));
    }

    @Test
    void createDuplicateGroup() {
        ctx.createGroup("farm");
        command.execute(parse("group create farm"), ctx);
        assertTrue(output().contains("already exists"));
    }

    @Test
    void createGroupNoName() {
        command.execute(parse("group create"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void deleteGroup() {
        ctx.createGroup("farm");
        command.execute(parse("group delete farm"), ctx);
        assertNull(ctx.getGroup("farm"));
        assertTrue(output().contains("deleted"));
    }

    @Test
    void deleteNonExistent() {
        command.execute(parse("group delete nope"), ctx);
        assertTrue(output().contains("not found"));
    }

    @Test
    void addToGroup() {
        ctx.createGroup("farm");
        command.execute(parse("group add farm BotWithUs"), ctx);
        assertTrue(ctx.getGroup("farm").contains("BotWithUs"));
        assertTrue(output().contains("Added"));
    }

    @Test
    void addDuplicateToGroup() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("BotWithUs");
        command.execute(parse("group add farm BotWithUs"), ctx);
        assertTrue(output().contains("already in group"));
    }

    @Test
    void addToNonExistentGroup() {
        command.execute(parse("group add nope BotWithUs"), ctx);
        assertTrue(output().contains("not found"));
    }

    @Test
    void addMissingArgs() {
        command.execute(parse("group add"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void removeFromGroup() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("BotWithUs");
        command.execute(parse("group remove farm BotWithUs"), ctx);
        assertFalse(ctx.getGroup("farm").contains("BotWithUs"));
        assertTrue(output().contains("Removed"));
    }

    @Test
    void removeNonMember() {
        ctx.createGroup("farm");
        command.execute(parse("group remove farm BotWithUs"), ctx);
        assertTrue(output().contains("not in group"));
    }

    @Test
    void listGroupsEmpty() {
        command.execute(parse("group list"), ctx);
        assertTrue(output().contains("No groups defined"));
    }

    @Test
    void listGroupsWithMembers() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");
        ctx.getGroup("farm").add("Bot2");
        command.execute(parse("group list"), ctx);
        String out = output();
        assertTrue(out.contains("farm"));
        assertTrue(out.contains("Bot1"));
        assertTrue(out.contains("Bot2"));
    }

    @Test
    void listGroupsEmpty_showsEmptyMarker() {
        ctx.createGroup("farm");
        command.execute(parse("group list"), ctx);
        assertTrue(output().contains("(empty)"));
    }

    @Test
    void infoNonExistent() {
        command.execute(parse("group info nope"), ctx);
        assertTrue(output().contains("not found"));
    }

    @Test
    void infoEmptyGroup() {
        ctx.createGroup("farm");
        command.execute(parse("group info farm"), ctx);
        assertTrue(output().contains("no connections"));
    }

    @Test
    void unknownSubcommand() {
        command.execute(parse("group foo"), ctx);
        assertTrue(output().contains("Unknown subcommand"));
    }
}
