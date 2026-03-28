module com.xapi.debugger {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires imgui.binding;
    requires com.google.gson;
    requires javafx.graphics;

    opens com.xapi.debugger to com.google.gson;

    provides com.botwithus.bot.api.BotScript
        with com.xapi.debugger.XapiScript,
             com.xapi.debugger.TestScript,
             com.xapi.debugger.WoodBoxTestScript;
}
