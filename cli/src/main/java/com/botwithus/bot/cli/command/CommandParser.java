package com.botwithus.bot.cli.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommandParser {

    private CommandParser() {}

    public static ParsedCommand parse(String input) {
        List<String> tokens = tokenize(input.trim());
        if (tokens.isEmpty()) {
            return new ParsedCommand("", List.of(), Map.of());
        }

        String name = tokens.getFirst().toLowerCase();
        List<String> args = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();

        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--")) {
                String flagPart = token.substring(2);
                int eq = flagPart.indexOf('=');
                if (eq >= 0) {
                    flags.put(flagPart.substring(0, eq), flagPart.substring(eq + 1));
                } else {
                    flags.put(flagPart, "true");
                }
            } else {
                args.add(token);
            }
        }

        return new ParsedCommand(name, List.copyOf(args), Map.copyOf(flags));
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (c == ' ' || c == '\t') {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens;
    }
}
