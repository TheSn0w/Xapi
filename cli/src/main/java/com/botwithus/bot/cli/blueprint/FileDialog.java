package com.botwithus.bot.cli.blueprint;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure ImGui file dialog for opening and saving blueprint JSON files.
 * Browses the blueprints directory and shows {@code .blueprint.json} files.
 */
public class FileDialog {

    private static final String FILE_SUFFIX = ".blueprint.json";

    public enum Mode { NONE, OPEN, SAVE }

    public record Result(Path path) {}

    private Mode mode = Mode.NONE;
    private Mode lastMode = Mode.NONE;
    private Path baseDir;
    private Path currentDir;
    private final ImString filenameBuffer = new ImString(256);
    private List<Entry> entries = List.of();
    private int selectedIndex = -1;
    private String errorMessage;

    private record Entry(String name, Path path, boolean isDirectory) {}

    public FileDialog(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.currentDir = this.baseDir;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.lastMode = mode;
        this.selectedIndex = -1;
        this.errorMessage = null;
        if (mode == Mode.SAVE) {
            filenameBuffer.set("untitled");
        } else {
            filenameBuffer.set("");
        }
        refreshEntries();
    }

    public Mode getLastMode() {
        return lastMode;
    }

    /**
     * Renders the dialog if active. Returns a Result when a file is confirmed, null otherwise.
     */
    public Result render() {
        if (mode == Mode.NONE) return null;

        String title = mode == Mode.OPEN ? "Open Blueprint" : "Save Blueprint";
        Result result = null;

        // Size the dialog as a fraction of the viewport
        float vpW = ImGui.getIO().getDisplaySizeX();
        float vpH = ImGui.getIO().getDisplaySizeY();
        ImGui.setNextWindowSize(vpW * 0.5f, vpH * 0.6f, ImGuiCond.Appearing);
        ImGui.setNextWindowPos(vpW * 0.25f, vpH * 0.2f, ImGuiCond.Appearing);

        if (ImGui.begin(title, ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking)) {
            // Current directory label
            ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, currentDir.toString());
            ImGui.separator();

            // File list
            float listHeight = ImGui.getContentRegionAvailY()
                    - ImGui.getFrameHeightWithSpacing() * (mode == Mode.SAVE ? 3 : 2)
                    - ImGui.getTextLineHeightWithSpacing();

            ImGui.beginChild("file_list", 0, listHeight, true);

            // Parent directory entry
            if (!currentDir.equals(baseDir)) {
                if (ImGui.selectable(".. (parent)", false)) {
                    currentDir = currentDir.getParent();
                    if (currentDir == null) currentDir = baseDir;
                    refreshEntries();
                    selectedIndex = -1;
                }
            }

            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                String label = entry.isDirectory ? "[DIR] " + entry.name : entry.name;
                boolean selected = (i == selectedIndex);

                if (ImGui.selectable(label, selected)) {
                    if (entry.isDirectory) {
                        currentDir = entry.path;
                        refreshEntries();
                        selectedIndex = -1;
                    } else {
                        selectedIndex = i;
                        if (mode == Mode.SAVE) {
                            // Strip suffix for the filename field
                            String base = entry.name;
                            if (base.endsWith(FILE_SUFFIX)) {
                                base = base.substring(0, base.length() - FILE_SUFFIX.length());
                            }
                            filenameBuffer.set(base);
                        }
                    }
                }

                // Double-click to confirm open
                if (mode == Mode.OPEN && !entry.isDirectory
                        && ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                    result = new Result(entry.path);
                }
            }

            if (entries.isEmpty()) {
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "No blueprint files found");
            }

            ImGui.endChild();

            // Filename input (for save mode)
            if (mode == Mode.SAVE) {
                ImGui.pushItemWidth(ImGui.getContentRegionAvailX());
                ImGui.inputText("##filename", filenameBuffer);
                ImGui.popItemWidth();
            }

            // Error message
            if (errorMessage != null) {
                ImGui.textColored(1f, 0.3f, 0.3f, 1f, errorMessage);
            }

            // Buttons
            String confirmLabel = mode == Mode.OPEN ? "Open" : "Save";
            if (ImGui.button(confirmLabel)) {
                result = confirmAction();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                mode = Mode.NONE;
            }
        }
        ImGui.end();

        if (result != null) {
            mode = Mode.NONE;
        }
        return result;
    }

    private Result confirmAction() {
        if (mode == Mode.OPEN) {
            if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                Entry entry = entries.get(selectedIndex);
                if (!entry.isDirectory) {
                    return new Result(entry.path);
                }
            }
            errorMessage = "Select a file to open";
            return null;
        } else {
            // Save
            String name = filenameBuffer.get().trim();
            if (name.isEmpty()) {
                errorMessage = "Enter a filename";
                return null;
            }
            if (!name.endsWith(FILE_SUFFIX)) {
                name = name + FILE_SUFFIX;
            }
            Path target = currentDir.resolve(name);
            // Ensure parent directory exists
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                errorMessage = "Cannot create directory: " + e.getMessage();
                return null;
            }
            return new Result(target);
        }
    }

    private void refreshEntries() {
        entries = new ArrayList<>();
        if (!Files.isDirectory(currentDir)) {
            try {
                Files.createDirectories(currentDir);
            } catch (IOException e) {
                return;
            }
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    entries.add(new Entry(p.getFileName().toString(), p, true));
                } else if (p.getFileName().toString().endsWith(FILE_SUFFIX)) {
                    entries.add(new Entry(p.getFileName().toString(), p, false));
                }
            }
        } catch (IOException e) {
            errorMessage = "Error reading directory: " + e.getMessage();
        }

        // Sort: directories first, then files, alphabetically
        entries.sort(Comparator
                .<Entry, Boolean>comparing(e -> !e.isDirectory)
                .thenComparing(e -> e.name.toLowerCase()));
    }
}
