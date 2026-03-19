module com.botwithus.bot.scripts.xapi {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires imgui.binding;
    requires com.google.gson;

    opens com.botwithus.bot.scripts.xapi to com.google.gson;

    provides com.botwithus.bot.api.BotScript
        with com.botwithus.bot.scripts.xapi.XapiScript;
}
