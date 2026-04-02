module com.botwithus.bot.scripts.eliteclue {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires imgui.binding;
    requires com.google.gson;
    requires javafx.controls;
    requires javafx.graphics;

    opens com.botwithus.bot.scripts.eliteclue to javafx.graphics;
    opens com.botwithus.bot.scripts.eliteclue.scan to javafx.graphics;

    provides com.botwithus.bot.api.BotScript
        with com.botwithus.bot.scripts.eliteclue.EliteClueScript;
}
