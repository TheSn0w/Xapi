package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptsCommandGroupTest {

    private ScriptsCommand command;
    private CliContext ctx;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        command = new ScriptsCommand();
        output = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(output);
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, ps, ps);
        ctx = spy(new CliContext(logBuffer, logCapture));
    }

    private ParsedCommand parse(String input) {
        return CommandParser.parse(input);
    }

    private String output() {
        return output.toString();
    }

    private Connection mockConnection(String name) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        when(conn.isAlive()).thenReturn(true);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(conn.getRuntime()).thenReturn(runtime);
        return conn;
    }

    @Test
    void startWithGroupNonExistent() {
        command.execute(parse("scripts start \"Test\" --group=nope"), ctx);
        String out = output();
        assertTrue(out.contains("No active connections") || out.contains("not found"));
    }

    @Test
    void startWithGroupNoActiveConnections() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1"); // not connected
        command.execute(parse("scripts start \"Test\" --group=farm"), ctx);
        assertTrue(output().contains("No active connections"));
    }

    @Test
    void startWithGroupCallsStartOnEachConnection() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");
        ctx.getGroup("farm").add("Bot2");

        Connection conn1 = mockConnection("Bot1");
        Connection conn2 = mockConnection("Bot2");

        ScriptRunner runner1 = mock(ScriptRunner.class);
        when(runner1.isRunning()).thenReturn(false);
        when(runner1.getScriptName()).thenReturn("Test Script");
        when(conn1.getRuntime().findRunner("Test Script")).thenReturn(runner1);

        ScriptRunner runner2 = mock(ScriptRunner.class);
        when(runner2.isRunning()).thenReturn(false);
        when(runner2.getScriptName()).thenReturn("Test Script");
        when(conn2.getRuntime().findRunner("Test Script")).thenReturn(runner2);

        doReturn(List.of(conn1, conn2)).when(ctx).getGroupConnections("farm");

        command.execute(parse("scripts start \"Test Script\" --group=farm"), ctx);

        verify(runner1).start();
        verify(runner2).start();
        String out = output();
        assertTrue(out.contains("[Bot1] Started: Test Script"));
        assertTrue(out.contains("[Bot2] Started: Test Script"));
    }

    @Test
    void startWithGroupSkipsAlreadyRunning() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");

        Connection conn1 = mockConnection("Bot1");
        ScriptRunner runner1 = mock(ScriptRunner.class);
        when(runner1.isRunning()).thenReturn(true);
        when(runner1.getScriptName()).thenReturn("Test");
        when(conn1.getRuntime().findRunner("Test")).thenReturn(runner1);

        doReturn(List.of(conn1)).when(ctx).getGroupConnections("farm");

        command.execute(parse("scripts start \"Test\" --group=farm"), ctx);

        verify(runner1, never()).start();
        assertTrue(output().contains("Already running"));
    }

    @Test
    void stopWithGroup() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");

        Connection conn1 = mockConnection("Bot1");
        when(conn1.getRuntime().stopScript("Test")).thenReturn(true);

        doReturn(List.of(conn1)).when(ctx).getGroupConnections("farm");

        command.execute(parse("scripts stop \"Test\" --group=farm"), ctx);

        verify(conn1.getRuntime()).stopScript("Test");
        assertTrue(output().contains("[Bot1] Stopped: Test"));
    }

    @Test
    void startWithGroupMissingScriptName() {
        command.execute(parse("scripts start --group=farm"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void startWithGroupWarnsDisconnected() {
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");
        ctx.getGroup("farm").add("Bot2"); // not connected

        Connection conn1 = mockConnection("Bot1");
        ScriptRunner runner1 = mock(ScriptRunner.class);
        when(runner1.isRunning()).thenReturn(false);
        when(runner1.getScriptName()).thenReturn("Test");
        when(conn1.getRuntime().findRunner("Test")).thenReturn(runner1);

        // Only Bot1 is active
        doReturn(List.of(conn1)).when(ctx).getGroupConnections("farm");

        command.execute(parse("scripts start \"Test\" --group=farm"), ctx);

        String out = output();
        assertTrue(out.contains("[Bot1] Started: Test"));
        assertTrue(out.contains("[Bot2]") && out.contains("disconnected"));
    }

    @Test
    void withoutGroupFlagUsesActiveConnection() {
        // No --group flag, no active connection
        command.execute(parse("scripts start \"Test\""), ctx);
        assertTrue(output().contains("No active connection"));
    }
}
